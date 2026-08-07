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
import org.robolectric.shadows.ShadowSystemClock
import xyz.oleolegka.gachimuchi.data.FloorStore
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.RestFloor
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import xyz.oleolegka.gachimuchi.domain.bootReference
import xyz.oleolegka.gachimuchi.domain.isFromPreviousBoot
import xyz.oleolegka.gachimuchi.domain.progressAt
import xyz.oleolegka.gachimuchi.domain.startFloor
import java.time.Duration

/**
 * The parallel rests as a running thing: stored across a process, woken by an alarm, muted
 * by a conductor and released after one.
 *
 * The arithmetic is not retested here — domain/FloorsTest covers what may sound and when,
 * on the JVM, and this file would only duplicate it worse. What is checked is the wiring a
 * pure test cannot see: that the floors are on disk, that the alarm is armed at all, that
 * it is armed with a request code the CONDUCTOR'S cancel cannot reach, and that a conductor
 * stopping puts it back.
 *
 * ── What this still cannot show, and it is the important half ───────────────────
 * Robolectric's alarm manager and vibrator are ledgers, not hardware. Nothing here proves
 * that the platform delivers the alarm in deep Doze — where the quota is per app and now
 * shared with the conductor (see FloorController.armAlarm) — that two signals two seconds
 * apart are distinguishable through a pocket, or that the tone generator is free when the
 * second one asks for it. Those need a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FloorControllerTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<TimerController>()

    /** A lead-in and three hangs: something for the conductor to be busy with. */
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

    private fun newController(): TimerController =
        TimerController(context).also { controllers += it }

    private fun alarms() = shadowOf(context.getSystemService(AlarmManager::class.java)).scheduledAlarms

    private fun store() = FloorStore(context)

    private fun now() = SystemClock.elapsedRealtime()

    private fun advance(seconds: Long) = ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds))

    @After
    fun tearDown() {
        controllers.forEach { it.stop() }
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // --- keeping floors at all -------------------------------------------------------------

    @Test
    fun `starting a rest puts a floor on the state, on disk and on the clock`() {
        val timer = newController()

        timer.floors.start(exerciseId = 1, exerciseName = "Bench", orderedMs = 120_000)

        val floor = timer.floors.floors.value.single()
        assertEquals(1L, floor.exerciseId)
        assertEquals("Bench", floor.exerciseName)
        assertEquals(120_000L, floor.orderedMs)
        assertFalse(floor.signalled)

        assertEquals(listOf(floor), store().load())
        assertEquals(1, alarms().size)
        assertEquals(floor.readyAtMs, alarms().single().triggerAtTime)
    }

    @Test
    fun `a second rest for the same exercise replaces the first, and a different one does not`() {
        val timer = newController()
        timer.floors.start(1, "Bench", 120_000)
        timer.floors.start(2, "Abs", 60_000)
        assertEquals(2, timer.floors.floors.value.size)

        advance(30)
        timer.floors.start(1, "Bench", 180_000)

        val floors = timer.floors.floors.value
        assertEquals(2, floors.size)
        assertEquals(1, floors.count { it.exerciseId == 1L })
        // and the alarm follows the one that is now soonest, which is the abs
        assertEquals(floors.first { it.exerciseId == 2L }.readyAtMs, alarms().single().triggerAtTime)
    }

    @Test
    fun `dismissing a floor takes its alarm with it`() {
        val timer = newController()
        timer.floors.start(1, "Bench", 120_000)

        timer.floors.dismiss(1)

        assertTrue(timer.floors.floors.value.isEmpty())
        assertTrue(store().load().isEmpty())
        assertEquals(0, alarms().size)
    }

    @Test
    fun `switching the timer off clears the rests as well as the run`() {
        val timer = newController()
        timer.setEnabled(true)
        timer.floors.start(1, "Bench", 120_000)

        timer.setEnabled(false)

        assertTrue("a timer switched off must not buzz about a bench later", timer.floors.floors.value.isEmpty())
        assertEquals(0, alarms().size)
    }

    // --- surviving the process --------------------------------------------------------------

    /**
     * The case the whole store exists for: the app is killed with rests counting, and the
     * process that comes back — an alarm receiver, or the user opening the app — picks them
     * up mid-count rather than losing them.
     */
    @Test
    fun `a new process picks live floors back up and re-arms their alarm`() {
        val started = now()
        /*
         * Stamped with a wall reading thirty seconds behind the current one, because that is
         * what makes this the SAME BOOT once the clock below is advanced. Robolectric moves
         * the monotonic clock on request and leaves `System.currentTimeMillis` alone, so a
         * fixture stamped with "now" would look, thirty seconds later, exactly like a device
         * that had restarted — `bootReference` is wall minus monotonic, and here only one of
         * the two moved. On a phone they move together and no adjustment is needed.
         */
        val startedWall = System.currentTimeMillis() - 30_000
        store().save(
            listOf(
                startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = started, nowWall = startedWall),
                startFloor(2, "Abs", orderedMs = 300_000, nowElapsed = started, nowWall = startedWall),
            )
        )

        advance(30)
        val revived = newController()

        val floors = revived.floors.floors.value
        assertEquals(2, floors.size)
        assertEquals("within one boot the monotonic reading is left alone", started + 120_000, floors.first().readyAtMs)
        assertEquals(1, alarms().size)
        assertEquals("the soonest of the two", started + 120_000, alarms().single().triggerAtTime)
        assertTrue("and it is in the future, not behind us", alarms().single().triggerAtTime > now())
    }

    /**
     * ORDER, which is grabl number two: settle the floors onto this boot's clock FIRST, then
     * ask for a cue, then arm.
     *
     * A floor written down before a restart carries a `readyAtMs` from a monotonic clock that
     * no longer exists — here 500 s into a boot that has since ended, against a phone that has
     * been up for 30 s. Arming before settling would hand the alarm manager a moment 470
     * seconds in the past: it fires at once, the floor is resolved for being late, and the
     * user's rest evaporates the moment the phone comes up. Nothing throws, which is what
     * makes it worth a test rather than a comment.
     */
    @Test
    fun `a floor from a previous boot is settled before its alarm is computed`() {
        val wall = System.currentTimeMillis()
        // started 500 s into a boot that is now over, so its stored moment is 620 s
        val saved = startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = 500_000, nowWall = wall - 40_000)
        store().save(listOf(saved))
        assertTrue(
            "the fixture is only a reboot if the boot reference moved",
            saved.isFromPreviousBoot(bootReference(System.currentTimeMillis(), now())),
        )

        val revived = newController()

        /*
         * Forty seconds of the two minutes went by while the device was down. The few
         * milliseconds of slack are the wall clock being read twice — once to build the
         * fixture and once as the floor is taken up — which is the same jitter `isRunStale`
         * tolerates on a real phone. Forty seconds is what is being tested; a millisecond is
         * not.
         */
        val floor = revived.floors.floors.value.single()
        assertEquals(80_000.0, floor.progressAt(now()).remainingMs.toDouble(), 100.0)
        assertFalse("declaring it ready would be rest the user never had", floor.signalled)

        val alarm = alarms().single()
        assertEquals((now() + 80_000).toDouble(), alarm.triggerAtTime.toDouble(), 100.0)
        assertTrue("an alarm in the past fires instantly and looks like a bug", alarm.triggerAtTime > now())
    }

    @Test
    fun `a rest that ran out while the device was off is settled in silence and asks for no alarm`() {
        val wall = System.currentTimeMillis()
        // five minutes of downtime against a two minute rest
        store().save(
            listOf(startFloor(1, "Bench", orderedMs = 120_000, nowElapsed = 500_000, nowWall = wall - 300_000))
        )

        val revived = newController()

        assertTrue("nobody was there to hear it", revived.floors.floors.value.single().signalled)
        assertEquals(0, alarms().size)
    }

    // --- the alarm the conductor must not be able to cancel ---------------------------------

    /**
     * GRABL ONE, and the reason this file exists at all.
     *
     * `TimerController.cancelAlarm` fires on every application of run state — every step
     * boundary, every pause, every nudge — and cancels by request code plus
     * `Intent.filterEquals`. A floor alarm built with the conductor's 7001 and ACTION_ALARM
     * would be wiped by it several times a minute, and the symptom would be a feature that
     * silently never fires on any session containing a protocol run.
     *
     * `stop()` is the sharpest way to show it: it calls `cancelAlarm` unconditionally, even
     * with no run to stop, so it reaches for the conductor's PendingIntent and nothing else.
     */
    @Test
    fun `the conductor cancelling its own alarm does not take the floors' alarm with it`() {
        val timer = newController()
        timer.floors.start(1, "Bench", 120_000)
        val armedAt = alarms().single().triggerAtTime

        timer.stop()

        assertEquals("a shared request code would leave nothing here", 1, alarms().size)
        assertEquals(armedAt, alarms().single().triggerAtTime)
    }

    // --- the conductor mutes, and releasing is mandatory --------------------------------------

    /**
     * GRABL FOUR. While a conductor runs the floors have NO alarm at all — the domain returns
     * no wake moment on purpose, because the conductor holds a wake-up of its own. Which means
     * the conductor stopping is the only thing that can give the floors one back, and a
     * `conductorStopped` that forgot to re-arm would leave every rest silent for good, on
     * exactly the sessions that include a protocol.
     */
    @Test
    fun `a conductor takes the floor alarm down, and stopping puts it back`() {
        val timer = newController()
        timer.floors.start(1, "Bench", 300_000)
        val readyAt = timer.floors.floors.value.single().readyAtMs

        timer.start(tabata)
        assertEquals("only the conductor's own", 1, alarms().size)
        assertEquals(timer.run.value!!.state.stepEndAtMs, alarms().single().triggerAtTime)

        timer.stop()

        assertEquals(1, alarms().size)
        assertEquals("the floors are awake again", readyAt, alarms().single().triggerAtTime)
    }

    /**
     * A rest that matured while a protocol was running is NOT sounded afterwards, late, as a
     * beep nobody can attribute. It comes back as one line of text, and it is marked as dealt
     * with so that it cannot then also beep.
     */
    @Test
    fun `a floor that matured under a conductor is summarised, never sounded`() {
        val timer = newController()
        timer.start(tabata)
        timer.floors.start(1, "Bench", 1_000)

        advance(30)
        timer.floors.onAlarm()

        assertNull("a rest beep in the middle of a hang is worse than useless", timer.floors.summary.value)
        assertFalse("muted, so nothing is used up", timer.floors.floors.value.single().signalled)

        timer.stop()

        assertEquals("Bench has been ready for 0:29", timer.floors.summary.value)
        assertTrue(timer.floors.floors.value.single().signalled)

        // and summarised is dealt with: nothing is owed afterwards
        timer.floors.refresh()
        assertEquals(0, alarms().size)
    }

    /**
     * GRABL NINE, which is a decision rather than a discovery: a PAUSED conductor is still a
     * conductor. Somebody is standing with the phone in their hand in the middle of a set, and
     * a rest beep is no more welcome then than it was a second earlier.
     */
    @Test
    fun `a paused conductor still mutes the floors`() {
        val timer = newController()
        timer.start(tabata)
        timer.floors.start(1, "Bench", 1_000)

        timer.pause()
        advance(30)
        timer.floors.onAlarm()

        assertFalse("a pause is the conductor still holding the room", timer.floors.floors.value.single().signalled)
        assertEquals("and it still owns the wake-up", 0, alarms().size)
    }

    // --- two floors at once -------------------------------------------------------------------

    /**
     * The collision the whole feature is arranged around. A `ToneGenerator` plays one tone and
     * a `Vibrator` one waveform, so two floors coming due together do not make two signals,
     * they make one signal with a bite out of it.
     *
     * What is asserted is which floors have been SPENT after each wake-up, because that is
     * what the service layer actually controls; whether the two are distinguishable by ear is
     * a question for a phone.
     */
    @Test
    fun `two floors ready together are signalled one wake-up at a time`() {
        val timer = newController()
        timer.floors.start(1, "Bench", 10_000)
        timer.floors.start(2, "Abs", 10_000)
        val readyAt = timer.floors.floors.value.first().readyAtMs

        advance(10)
        timer.floors.onAlarm()

        val afterFirst = timer.floors.floors.value
        assertEquals(
            "exactly one of them may make a noise per call",
            listOf(1L),
            afterFirst.filter { it.signalled }.map { it.exerciseId },
        )
        assertEquals("the second waits out the first one's tone", readyAt + 2_000, alarms().single().triggerAtTime)

        advance(2)
        timer.floors.onAlarm()

        assertTrue(timer.floors.floors.value.all { it.signalled })
        assertEquals("nothing left to say", 0, alarms().size)
    }

    /**
     * GRABL THREE. `floorCue` hands back a NEW list with what it just spent marked on it, and
     * a caller that keeps the old one gets a floor that signals on every call for ever — and
     * one that comes back from the dead, unspent, after the process restarts.
     */
    @Test
    fun `a floor that has sounded stays spent, on disk as well as in memory`() {
        val timer = newController()
        timer.floors.start(1, "Bench", 10_000)

        advance(10)
        timer.floors.onAlarm()
        assertTrue(timer.floors.floors.value.single().signalled)

        assertTrue("or it would be heard again by the next process", store().load().single().signalled)

        timer.floors.onAlarm()
        assertEquals("and it asks for no further wake-up", 0, alarms().size)
    }

    @Test
    fun `a rest reached an hour late is resolved without a sound`() {
        val timer = newController()
        timer.floors.start(1, "Bench", 10_000)

        advance(3_600)
        timer.floors.onAlarm()

        val floor = timer.floors.floors.value.single()
        assertTrue("dealt with, so it does not sit pending for ever", floor.signalled)
        assertEquals(0, alarms().size)
        assertNull("and it was never a summary either", timer.floors.summary.value)
    }

    // --- what the screen gets -----------------------------------------------------------------

    @Test
    fun `the state a screen draws from carries both the live and the spent`() {
        val timer = newController()
        timer.floors.start(1, "Bench", 10_000)
        timer.floors.start(2, "Abs", 600_000)

        advance(10)
        timer.floors.onAlarm()
        advance(150)

        val byId = timer.floors.floors.value.associateBy(RestFloor::exerciseId)
        val bench = byId.getValue(1L).progressAt(now())
        assertTrue(bench.ready)
        assertEquals("'ready, and for how long' is the answer this exists to give", 150_000L, bench.overdueMs)

        val abs = byId.getValue(2L).progressAt(now())
        assertFalse(abs.ready)
        assertEquals(440_000L, abs.remainingMs)
    }

    @Test
    fun `the summary can be dismissed`() {
        val timer = newController()
        timer.start(tabata)
        timer.floors.start(1, "Bench", 1_000)
        advance(30)
        timer.stop()
        assertNotNull(timer.floors.summary.value)

        timer.floors.clearSummary()

        assertNull(timer.floors.summary.value)
    }
}
