package xyz.oleolegka.gachimuchi.timer

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.TimerStore
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.flatten
import xyz.oleolegka.gachimuchi.domain.restProgram
import xyz.oleolegka.gachimuchi.domain.startRun
import xyz.oleolegka.gachimuchi.domain.stepRemainingMs

/**
 * The glue between the pure runner and Android: does a command actually reach the state,
 * get written to disk, and re-arm the alarm.
 *
 * The counting itself is covered on the JVM (domain/RunnerTest); what is checked here is
 * the wiring that a pure test cannot see — that stopping really cancels the alarm, that a
 * new process finds the run on disk, and that a run from a previous boot is thrown away
 * instead of resumed against a clock that no longer exists.
 *
 * Not covered, and worth being plain about: nothing here proves the phone behaves this way.
 * Robolectric's alarm manager, vibrator and notification manager are stand-ins that record
 * calls; whether the real platform delivers the alarm in Doze, and whether the vibration is
 * felt through a pocket, can only be found out on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: TimerController? = null

    private val tabata = WorkoutProgram(
        name = "Tabata",
        prepareSec = 10,
        groups = listOf(
            ProgramGroup(
                name = "Tabata",
                blocks = listOf(ProgramBlock("Work", workSec = 20, restSec = 10, repeats = 3)),
            )
        ),
    )

    private fun newController(): TimerController = TimerController(context).also { controller = it }

    private fun store() = TimerStore(context)

    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    @After
    fun tearDown() {
        controller?.stop()
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // --- the commands ------------------------------------------------------------------

    @Test
    fun `starting a rest puts a live run on the state and an alarm on the clock`() {
        val timer = newController()
        timer.start(restProgram(120), exerciseId = 5)

        val run = timer.run.value
        assertNotNull(run)
        assertEquals("Rest", run!!.programName)
        assertEquals(1, run.steps.size)
        assertEquals(5L, run.exerciseId)
        assertTrue(run.state.running)
        assertEquals(1, alarms().size)
    }

    @Test
    fun `pausing and resuming keeps what was left of the step`() {
        val timer = newController()
        timer.start(restProgram(120))

        timer.pause()
        val paused = timer.run.value!!
        assertFalse(paused.state.running)
        assertTrue(paused.state.pausedLeftMs in 118_000..120_000)
        // a paused run arms no alarm: there is nothing to wake up for
        assertEquals(0, alarms().size)

        timer.resume()
        val resumed = timer.run.value!!
        assertTrue(resumed.state.running)
        assertEquals(1, alarms().size)
    }

    @Test
    fun `plus thirty seconds moves the end of the step and re-arms the alarm`() {
        val timer = newController()
        timer.start(restProgram(120))
        val before = timer.run.value!!.state.stepEndAtMs

        timer.nudge(30)

        assertEquals(before + 30_000, timer.run.value!!.state.stepEndAtMs)
        assertEquals(1, alarms().size)
    }

    @Test
    fun `skipping a program moves to the next step and announces it as a new one`() {
        val timer = newController()
        timer.start(tabata)
        assertEquals(StepKind.PREPARE, timer.run.value!!.steps[0].kind)

        timer.skip()

        val run = timer.run.value!!
        assertEquals(1, run.state.stepIndex)
        assertEquals(StepKind.WORK, run.steps[run.state.stepIndex].kind)
    }

    @Test
    fun `going back from the second step reopens the first`() {
        val timer = newController()
        timer.start(tabata)
        timer.skip()
        assertEquals(1, timer.run.value!!.state.stepIndex)

        timer.previous()

        assertEquals(0, timer.run.value!!.state.stepIndex)
    }

    @Test
    fun `stopping clears the run, the stored copy and the alarm together`() {
        val timer = newController()
        timer.start(restProgram(120))
        assertNotNull(store().loadRun())

        timer.stop()

        assertNull(timer.run.value)
        assertNull(store().loadRun())
        assertEquals(0, alarms().size)
    }

    @Test
    fun `skipping the only step of a rest ends the run rather than leaving it stuck`() {
        val timer = newController()
        timer.start(restProgram(120))

        timer.skip()

        assertNull(timer.run.value)
        assertNull(store().loadRun())
        assertEquals(0, alarms().size)
    }

    // --- surviving the process ----------------------------------------------------------

    @Test
    fun `a run is written to disk as it goes, with the end moment and not a countdown`() {
        val timer = newController()
        timer.start(restProgram(120))

        val stored = store().loadRun()
        assertNotNull(stored)
        assertEquals(timer.run.value!!.state.stepEndAtMs, stored!!.state.stepEndAtMs)
        assertTrue(stored.state.stepEndAtMs > SystemClock.elapsedRealtime())
    }

    @Test
    fun `a new process picks the run back up from disk at the right point`() {
        val steps = tabata.flatten()
        val startedAt = SystemClock.elapsedRealtime()
        store().saveRun(
            RunSnapshot(
                programId = 0,
                programName = "Tabata",
                steps = steps,
                state = startRun(steps, startedAt),
                bootRef = System.currentTimeMillis() - SystemClock.elapsedRealtime(),
            )
        )

        // as if the process had been killed and something rebuilt the controller
        val revived = newController()

        val run = revived.run.value
        assertNotNull(run)
        assertEquals("Tabata", run!!.programName)
        assertTrue(run.state.running)
        assertTrue(stepRemainingMs(run.steps, run.state, startedAt) in 9_000..10_000)
    }

    @Test
    fun `a run saved before a reboot is discarded instead of counting down from nonsense`() {
        val steps = tabata.flatten()
        store().saveRun(
            RunSnapshot(
                programId = 0,
                programName = "Tabata",
                steps = steps,
                state = startRun(steps, SystemClock.elapsedRealtime()),
                // a boot reference from a device that has since restarted
                bootRef = 0,
            )
        )

        val revived = newController()

        assertNull(revived.run.value)
        assertNull(store().loadRun())
    }

    // --- settings ------------------------------------------------------------------------

    @Test
    fun `settings written through the controller are visible to the next process`() {
        val timer = newController()
        timer.updateSettings(TimerSettings(defaultRestSec = 90, vibrate = false))

        assertEquals(90, timer.settings.value.defaultRestSec)
        assertFalse(timer.settings.value.vibrate)
        assertEquals(90, TimerStore(context).settings.value.defaultRestSec)
    }

    @Test
    fun `turning the timer off stops whatever was running`() {
        val timer = newController()
        timer.setEnabled(true)
        timer.start(restProgram(120))
        assertNotNull(timer.run.value)

        timer.setEnabled(false)

        assertFalse(timer.enabled.value)
        assertNull(timer.run.value)
        assertEquals(0, alarms().size)
    }

    @Test
    fun `starting a second run replaces the first rather than stacking two countdowns`() {
        val timer = newController()
        timer.start(restProgram(120))
        timer.start(restProgram(60))

        assertEquals(1, alarms().size)
        assertEquals(60_000, timer.run.value!!.steps.single().durationMs)
    }
}
