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

    // --- the same bug by its second route: a boundary NOTICED LATE -------------------------

    /*
     * The rule above holds only while a step is entered at its full length. It is not always:
     * the exact alarm can be delivered a second after the moment, or the countdown coroutine
     * can oversleep, and the run is then settled onto a step that is already part-way through.
     * The boundary signal is fired at that late moment — it is still worth firing — and the
     * step has less than its own length left, which used to make a tick due IMMEDIATELY,
     * milliseconds after the beep it would cut off. Same silence, same 7:3 rests, different
     * route, and only on the sessions where the phone happened to be busy.
     *
     * These fix the moment the signal was REALLY made rather than the moment the step
     * nominally began, which is what `boundaryAtMs` carries.
     *
     * Each of these asks the question the way the controller asks it: the run has ALREADY
     * been settled onto the late step and the boundary has ALREADY been signalled, and what
     * is being decided is what the freshly restarted loop owes next. Handing in an unsettled
     * state instead would be asking a different question — that one now answers "boundary",
     * which is the whole of the fix below.
     */

    private fun settledAt(list: List<WorkoutStep>, now: Long) = settleRun(list, running(list), now)

    /** The 3 s rest of a 7:3 set, reached 1.2 s late: its "two" tick is now behind the beep. */
    @Test
    fun `a rest boundary noticed late is not cut off by the tick it makes due`() {
        val list = steps(7, 3)
        // the second step runs 7 000..10 000; the boundary is only handled at 8 200
        val late = 8_200L

        val cue = timerCue(list, settledAt(list, late), countdownTicks = true, now = late, boundaryAtMs = late)

        assertFalse(cue.boundary)
        assertNull("'two' was due at 8 000, before the beep that has just gone out", cue.tickSecond)
        assertEquals("and the next tick worth making is 'one'", 9_000, cue.wakeAtMs)
    }

    /** The tick a whole second clear of the late beep is still made: this suppresses, not mutes. */
    @Test
    fun `the tick that clears the late beep still sounds`() {
        val list = steps(7, 3)
        val cue = timerCue(
            list, settledAt(list, 8_200), countdownTicks = true, now = 9_000, boundaryAtMs = 8_200,
        )

        assertEquals(1, cue.tickSecond)
    }

    /**
     * A late boundary can also land INSIDE the last second, at which point the step owes no
     * tick at all rather than one on top of the beep.
     */
    @Test
    fun `a rest reached with under a second left owes no tick at all`() {
        val list = steps(7, 3)
        val late = 9_100L

        val cue = timerCue(list, settledAt(list, late), countdownTicks = true, now = late, boundaryAtMs = late)

        assertNull(cue.tickSecond)
        assertEquals(10_000, cue.wakeAtMs)
    }

    /** The seven second hang has more room, but it is the same rule and the same failure. */
    @Test
    fun `a work boundary noticed four seconds late is not cut off either`() {
        val list = steps(3, 7)
        val late = 7_200L

        val cue = timerCue(list, settledAt(list, late), countdownTicks = true, now = late, boundaryAtMs = late)

        assertNull("'three' was due at 7 000", cue.tickSecond)
        assertEquals(8_000, cue.wakeAtMs)
    }

    // --- the boundary flag itself, which used to be unreachable ----------------------------

    /*
     * `timerCue` reported a boundary only when the WHOLE PROGRAM had run out, because the test
     * it used could not be true for anything else: `settleRun` walks forward until the clock is
     * inside the current step, so "the clock is past the end of the current step" is false by
     * construction. Every step change inside a program answered "no boundary", the countdown
     * loop therefore never advanced the run, and the exact alarm — described everywhere as the
     * backstop — was the only thing in the app that moved a run from one step to the next.
     */

    @Test
    fun `a step that has run out is a boundary, not just the end of the program`() {
        val list = steps(7, 3, 7)
        val state = running(list)

        val cue = timerCue(list, state, countdownTicks = true, now = 7_000)

        assertTrue("the run has moved on and the caller has to settle and signal", cue.boundary)
        assertNull(cue.tickSecond)
    }

    @Test
    fun `a settled state standing inside its own step is not a boundary`() {
        val list = steps(7, 3, 7)

        val cue = timerCue(list, settledAt(list, 7_000), countdownTicks = true, now = 7_000)

        assertFalse("settled and signalled: the next thing owed is a tick, not another beep", cue.boundary)
    }

    /**
     * Every transition of a whole 7:3 set reports a boundary exactly once — the assertion the
     * old flag could never have made, and the one the owner's missing "hang" beep needed.
     */
    @Test
    fun `every step change of a 7 to 3 set is reported as a boundary`() {
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
        var state = startRun(list, 0)
        var now = 0L
        var boundaries = 0
        var guard = 0

        while (guard++ < 500) {
            val cue = timerCue(list, state, countdownTicks = true, now = now)
            if (cue.boundary) {
                state = settleRun(list, state, now)
                if (state.finished) break
                boundaries++
                continue
            }
            now = cue.wakeAtMs
        }

        // eleven steps, so ten changes between them; the eleventh signal is the start itself
        assertEquals(10, boundaries)
    }

    /**
     * A boundary moment left over from an EARLIER step protects nothing and must not be read
     * as permission: the guard never moves earlier than the step's own start.
     */
    @Test
    fun `a stale boundary moment does not re-open the tick a short step has no room for`() {
        val list = steps(3, 3)
        // as if the previous step's beep were still being carried
        val cue = timerCue(list, running(list), countdownTicks = true, now = 0, boundaryAtMs = -5_000)

        assertNull("a three second step has never had room for 'three'", cue.tickSecond)
        assertEquals(1_000, cue.wakeAtMs)
    }

    // --- a tick the loop woke up too late for ----------------------------------------------

    /*
     * The second way the countdown doubles, and it needs no boundary at all. The loop sleeps
     * to the exact moment a tick is due; a sleep that overruns used to fire the tick anyway,
     * at whatever moment the loop woke, so a wake-up 900 ms late made "two" 900 ms late and
     * "one" a tenth of a second after it. The vibrator plays one waveform at a time, so what
     * comes out of two taps that close together is a stutter rather than two ticks.
     */

    @Test
    fun `a tick the loop is nearly a second late for is dropped, not crowded onto the next`() {
        val list = steps(7)
        // "two" was due at 5 000 and the loop only gets there at 5 900
        val cue = timerCue(list, running(list), countdownTicks = true, now = 5_900)

        assertNull("firing it here would leave 100 ms to the next tap", cue.tickSecond)
        assertEquals("and 'one' is still made, on its own moment", 6_000, cue.wakeAtMs)
    }

    @Test
    fun `an ordinary overrun still ticks`() {
        val list = steps(7)
        val cue = timerCue(list, running(list), countdownTicks = true, now = 5_100)

        assertEquals("a tenth of a second late is a tick, not a stutter", 2, cue.tickSecond)
    }

    /**
     * The tolerance is a floor on the gap between two ticks, so the worst case it allows is
     * still comfortably clear of the 90 ms tone a tick is made of.
     */
    @Test
    fun `the worst overrun that still ticks leaves three quarters of a second to the next one`() {
        val list = steps(7)
        val worst = 5_000L + TICK_LATENESS_MS

        val cue = timerCue(list, running(list), countdownTicks = true, now = worst)

        assertEquals(2, cue.tickSecond)
        assertEquals(750, cue.wakeAtMs - worst)
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
