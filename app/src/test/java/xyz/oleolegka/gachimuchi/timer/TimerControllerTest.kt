package xyz.oleolegka.gachimuchi.timer

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import android.os.VibratorManager
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
import org.robolectric.shadows.ShadowSystemClock
import xyz.oleolegka.gachimuchi.data.TimerStore
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.RunState
import xyz.oleolegka.gachimuchi.domain.RunSnapshot
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.flatten
import xyz.oleolegka.gachimuchi.domain.restProgram
import xyz.oleolegka.gachimuchi.domain.startRun
import xyz.oleolegka.gachimuchi.domain.stepRemainingMs
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

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
        // the floors live in their own preference file and leak into the next test otherwise
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
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

    // --- a run that finished while the process was dead --------------------------------------

    /** A hangboard session: a lead-in, then two sets of three hangs with a pause between. */
    private val repeaters = WorkoutProgram(
        name = "Repeaters 7:3",
        prepareSec = 15,
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 3)),
                repeats = 2,
                restBetweenRepeatsSec = 180,
            )
        ),
    )

    /**
     * Stores a run that RAN ITSELF OUT [endedAgoMs] ago in this boot, exactly as the process
     * would have left it: the state it was last saved in, plus a boot reference that is still
     * current because the device has not restarted.
     */
    private fun storeRunThatEnded(program: WorkoutProgram, endedAgoMs: Long): RunSnapshot {
        val steps = program.flatten()
        val now = SystemClock.elapsedRealtime()
        val snapshot = RunSnapshot(
            programId = 0,
            programName = program.name,
            steps = steps,
            // last saved on its final step, which has since run out
            state = RunState(stepIndex = steps.lastIndex, running = true, stepEndAtMs = now - endedAgoMs),
            bootRef = System.currentTimeMillis() - now,
            exerciseId = 42,
            origin = RunOrigin.EXERCISE,
        )
        store().saveRun(snapshot)
        return snapshot
    }

    /**
     * The scenario the whole persisted-offer feature exists for, taken one step further than
     * it used to be tested: the process does not merely miss the end of the run, it is GONE
     * when the run ends, and the outcome is built by a fresh controller hours later.
     *
     * Nothing here drives the controller. The run is put on disk and a new controller is
     * constructed, which is what a process rebuilt by the alarm receiver — or by the user
     * opening the app the next morning — actually does.
     */
    @Test
    fun `an outcome materialised from disk is dated when the run ended, not when it was found`() {
        ShadowSystemClock.advanceBy(Duration.ofHours(12))
        val saved = storeRunThatEnded(repeaters, endedAgoMs = 11 * 3600_000L)
        // the wall moment the run's last step ended, as the phone would reconstruct it
        val endedAtWallMs = saved.bootRef + saved.state.stepEndAtMs

        val revived = newController()

        val outcome = revived.outcome.value
        assertNotNull("a session that happened must not be lost to the process dying", outcome)
        assertEquals(listOf(3, 3), outcome!!.sets.map { it.reps })

        /*
         * The moment the LAST STEP ENDED, reconstructed from the boot reference — not the
         * moment this controller was built. The old code read the wall clock here, so this
         * value came back eleven hours late and the date with it: an evening session filed
         * under the following morning, in the one record the app exists to keep.
         *
         * The second of slack is the boot reference being recomputed as the run is picked up:
         * wall minus monotonic wobbles by a few milliseconds between two readings, which is
         * the same jitter [isRunStale] tolerates on a real device. Eleven hours is the error
         * being tested for; a millisecond is not.
         */
        assertTrue(
            "ended at ${outcome.endedAtWallMs}, expected about $endedAtWallMs",
            kotlin.math.abs(outcome.endedAtWallMs - endedAtWallMs) < 1_000,
        )
        assertEquals(
            Instant.ofEpochMilli(endedAtWallMs).atZone(ZoneId.systemDefault()).toLocalDate().toString(),
            outcome.opDate,
        )
        // and the offer knows it is stale, which is what makes it say when the run ended
        assertFalse(outcome.isFresh(System.currentTimeMillis()))
    }

    @Test
    fun `a run the device restarted out from under keeps the sets it had already done`() {
        val steps = repeaters.flatten()
        // 15 s lead-in, then two sets of three hangs: 1,3,5 and 7,9,11
        assertEquals(12, steps.size)

        val stepStartedAt = 600_000L
        val endedWallMs = System.currentTimeMillis() - 30 * 60_000L
        store().saveRun(
            RunSnapshot(
                programId = 0,
                programName = repeaters.name,
                steps = steps,
                // standing on the second hang of set 2 when the battery went
                state = RunState(
                    stepIndex = 9, running = true, stepEndAtMs = stepStartedAt + steps[9].durationMs,
                ),
                // wall minus monotonic, from the boot that has since ended
                bootRef = endedWallMs - stepStartedAt,
                exerciseId = 42,
                origin = RunOrigin.EXERCISE,
            )
        )

        val revived = newController()

        // the run itself is still discarded: its end moments are readings of a clock that no
        // longer exists, and resuming would count down from an arbitrary number
        assertNull(revived.run.value)
        assertNull(store().loadRun())

        /*
         * But the sets are not readings of anything. Three hangs and one more happened, and
         * they used to be thrown away in silence along with the snapshot — a phone that ran
         * out of battery mid-session lost the session and never said so.
         */
        val outcome = revived.outcome.value
        assertNotNull("a reboot must not swallow the part of the session that happened", outcome)
        assertEquals(listOf(3, 1), outcome!!.sets.map { it.reps })
        assertTrue(outcome.interrupted)
        // dated from the start of the step it was standing on: the last moment the run is
        // KNOWN to have been alive, rather than where that step would have ended
        assertEquals(endedWallMs, outcome.endedAtWallMs)
        // and it is quiet about it
        assertFalse(vibrated())
    }

    @Test
    fun `finding a finished run on launch does not gong the room`() {
        ShadowSystemClock.advanceBy(Duration.ofHours(12))
        storeRunThatEnded(repeaters, endedAgoMs = 4 * 3600_000L)

        val revived = newController()

        /*
         * The signals go out on the alarm stream and ignore the ringer switch (see
         * Signals.kt) — deliberately, because that is the only way to be heard at the gym.
         * The cost of getting this wrong is therefore not a stray beep: it is a full-volume
         * gong and a long vibration, in a quiet room, for a workout that ended before dinner,
         * every time the app is opened. The offer below is the right way to raise a run that
         * ended hours ago.
         */
        assertFalse("a run that ended four hours ago is not news", vibrated())
        assertNotNull(revived.outcome.value)
    }

    @Test
    fun `a boundary the alarm delivers on time still signals`() {
        ShadowSystemClock.advanceBy(Duration.ofHours(12))
        // the backstop alarm fires AT the boundary, and the process it wakes may be brand new:
        // this is the same restore path as the test above and it must behave the opposite way
        storeRunThatEnded(repeaters, endedAgoMs = 200)

        newController()

        assertTrue("the backstop alarm is the reason this path exists", vibrated())
    }

    /**
     * Whether anything asked the vibrator to do something.
     *
     * Vibration rather than the tone, because it is the channel the app treats as primary
     * (Signals.kt) and because Robolectric records it: the shadow keeps the attributes of the
     * last vibration, which are null until something vibrates.
     */
    private fun vibrated(): Boolean {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        val shadow = shadowOf(vibrator)
        return shadow.isVibrating || shadow.vibrationAttributesFromLastVibration != null
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
