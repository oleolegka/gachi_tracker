package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the timer is supposed to make a noise, and — the part that was wrong — when it is
 * supposed to keep quiet.
 *
 * The bug this covers was reported as "on short intervals it sometimes fails to beep the
 * change of step". The cause was not a missing call: it was TWO calls at the same instant.
 * A step three seconds long is entirely inside the three-second countdown window, so the
 * "three" tick was due at the exact moment the step began, which is the moment the boundary
 * signal fires. A [android.media.ToneGenerator] plays one tone at a time and a
 * [android.os.Vibrator] plays one waveform at a time, so the 90 ms tick cut off the 250 ms
 * boundary beep and the 40 ms tap cut off the 400 ms buzz. On 7:3 repeaters that happened at
 * every single rest — twenty-four times in a session.
 *
 * These are JVM tests over pure arithmetic, which is the point of having pulled the decision
 * out of the coroutine. What they cannot show is stated plainly: whether the phone's audio
 * path adds latency of its own, and whether the result is audible through a pocket. That
 * needs a phone.
 */
class SignalTimingTest {

    private fun steps(vararg durationsSec: Int): List<WorkoutStep> =
        durationsSec.map { WorkoutStep(kind = StepKind.WORK, name = "Hang", durationSec = it) }

    private fun running(steps: List<WorkoutStep>, startedAt: Long = 0) =
        startRun(steps, startedAt)

    // --- the bug ---------------------------------------------------------------------------

    @Test
    fun `a three second step does not tick at the instant it starts`() {
        val list = steps(3, 3)
        val cue = timerCue(list, running(list), countdownTicks = true, now = 0)

        assertFalse(cue.boundary)
        assertNull("the boundary signal must not be cut off by its own countdown", cue.tickSecond)
        // the first tick worth making is "two", one second in
        assertEquals(1_000, cue.wakeAtMs)
    }

    @Test
    fun `a three second step still counts the two seconds it has room for`() {
        val list = steps(3)
        assertEquals(2, timerCue(list, running(list), true, now = 1_000).tickSecond)
        assertEquals(1, timerCue(list, running(list), true, now = 2_000).tickSecond)
        assertTrue(timerCue(list, running(list), true, now = 3_000).boundary)
    }

    @Test
    fun `a one second step is silent until it ends`() {
        val list = steps(1, 7)
        val cue = timerCue(list, running(list), countdownTicks = true, now = 0)

        assertNull(cue.tickSecond)
        assertEquals("nothing to say before the boundary itself", 1_000, cue.wakeAtMs)
    }

    @Test
    fun `a two second step ticks once, at one`() {
        val list = steps(2)
        assertNull(timerCue(list, running(list), true, now = 0).tickSecond)
        assertEquals(1, timerCue(list, running(list), true, now = 1_000).tickSecond)
    }

    // --- the ordinary case, which must not have changed ------------------------------------

    @Test
    fun `a seven second step sleeps to the window and then counts three, two, one`() {
        val list = steps(7)
        val state = running(list)

        val first = timerCue(list, state, countdownTicks = true, now = 0)
        assertNull(first.tickSecond)
        assertEquals("wakes when three seconds are left, not before", 4_000, first.wakeAtMs)

        assertEquals(3, timerCue(list, state, true, now = 4_000).tickSecond)
        assertEquals(2, timerCue(list, state, true, now = 5_000).tickSecond)
        assertEquals(1, timerCue(list, state, true, now = 6_000).tickSecond)
        assertTrue(timerCue(list, state, true, now = 7_000).boundary)
    }

    @Test
    fun `with ticks switched off the loop sleeps straight to the boundary`() {
        val list = steps(7)
        val cue = timerCue(list, running(list), countdownTicks = false, now = 0)

        assertNull(cue.tickSecond)
        assertEquals(7_000, cue.wakeAtMs)
    }

    /**
     * The whole 7:3 set, walked one wake-up at a time. This is the test that would have
     * failed before the fix: it asserts that no tick is ever due at a moment a step begins.
     */
    @Test
    fun `no tick in a whole 7 to 3 set ever lands on a step boundary`() {
        val program = WorkoutProgram(
            name = "Repeaters",
            prepareSec = 0,
            groups = listOf(
                ProgramGroup(
                    name = "Repeaters",
                    blocks = listOf(ProgramBlock(name = "Hang", workSec = 7, restSec = 3, repeats = 6)),
                )
            ),
        )
        val list = program.flatten()
        val boundaries = list.runningFold(0L) { at, step -> at + step.durationMs }.toSet()

        var state = startRun(list, 0)
        var now = 0L
        var ticks = 0
        var guard = 0
        while (guard++ < 500) {
            val cue = timerCue(list, state, countdownTicks = true, now = now)
            if (cue.boundary) {
                state = settleRun(list, state, now)
                if (state.finished) break
                continue
            }
            cue.tickSecond?.let {
                ticks++
                assertFalse(
                    "a tick at $now would silence the boundary signal there",
                    now in boundaries,
                )
            }
            now = cue.wakeAtMs
        }

        // six hangs of 7 s tick three times each, six rests of 3 s tick twice each, and the
        // last rest is dropped by flatten, so: 6*3 + 5*2 = 28
        assertEquals(28, ticks)
    }

    // --- states that owe nothing -----------------------------------------------------------

    @Test
    fun `a paused run is asked for nothing`() {
        val list = steps(7)
        val paused = pauseRun(list, running(list), now = 2_000)

        val cue = timerCue(list, paused, countdownTicks = true, now = 2_000)

        assertFalse(cue.boundary)
        assertNull(cue.tickSecond)
    }

    @Test
    fun `a state left far behind reports the boundary rather than a stale tick`() {
        val list = steps(7, 3)
        // the whole program is over twice by now
        val cue = timerCue(list, running(list), countdownTicks = true, now = 30_000)

        assertTrue(cue.boundary)
        assertNull(cue.tickSecond)
    }
}
