package xyz.oleolegka.gachimuchi.timer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import xyz.oleolegka.gachimuchi.domain.ProgramBlock
import xyz.oleolegka.gachimuchi.domain.ProgramGroup
import xyz.oleolegka.gachimuchi.domain.StepKind
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.WorkoutProgram
import java.time.Duration

/**
 * THE WHOLE 7:3 SET, DRIVEN THE WAY THE COUNTDOWN DRIVES ITSELF.
 *
 * Six hangs of seven seconds with three seconds between them, run from the first step to the
 * last, asking for one signal at every single transition — not at the first one.
 *
 * ── Why this did not exist, and what it cost ────────────────────────────────────
 * The countdown loop sleeps in real milliseconds against a clock Robolectric only moves when
 * a test moves it, so under test the coroutine never wakes: every assertion about a run had
 * to be made by poking the controller from the side. A whole protocol was therefore never
 * run in a test at all. Three separate fixes and a release went out on top of tests that each
 * checked one situation, and a boundary that goes missing from the SECOND cycle onwards
 * survived all of them, because nothing ever reached a second cycle.
 *
 * [TimerController.countdownPass] closes that: a test asks for one pass, moves the clock by
 * what the pass asked to sleep, and asks again. That is exactly what the loop does with a
 * real clock, so what runs here is the production decision and not a copy of it.
 *
 * ── What it still cannot show ───────────────────────────────────────────────────
 * The counted thing is what the timer ASKED FOR — Robolectric's vibrator keeps only the last
 * vibration and there is no shadow for the tone generator, so nothing here says whether two
 * requests near each other are audible as one, two, or none. And the real coroutine is not
 * running: races between it and the alarm thread are covered, as far as they can be, in
 * BoundarySignalTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepeaterProtocolTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<TimerController>()

    /** Every signal the run asked for, in order, so a gap can be pointed at. */
    private class Recorder(context: Context) : Signals(context) {
        val boundaries = mutableListOf<StepKind>()
        val ticks = mutableListOf<Int>()

        override fun boundary(settings: TimerSettings, starting: StepKind) {
            boundaries += starting
        }

        override fun tick(settings: TimerSettings) {
            ticks += 0
        }
    }

    /** The owner's protocol: 7 s on, 3 s off, six times, no lead-in. */
    private val repeaters = WorkoutProgram(
        name = "Repeaters 7:3",
        prepareSec = 0,
        groups = listOf(
            ProgramGroup(
                name = "Repeaters",
                blocks = listOf(ProgramBlock("Hang", workSec = 7, restSec = 3, repeats = 6)),
            )
        ),
    )

    private fun newController(signals: Signals): TimerController =
        TimerController(context, signals).also { controllers += it }

    private fun advanceMs(ms: Long) = ShadowSystemClock.advanceBy(Duration.ofMillis(ms))

    @After
    fun tearDown() {
        controllers.forEach { it.stop() }
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * Runs the protocol to its end the way the loop would: one pass, sleep what it asked for,
     * next pass. [jitterMs] is added to every sleep, which is what an overrunning sleep or a
     * late alarm delivery looks like from in here.
     */
    private fun runToEnd(timer: TimerController, jitterMs: Long = 0) {
        var guard = 0
        while (guard++ < 2_000) {
            val sleep = timer.countdownPass()
            if (sleep == null) {
                /*
                 * The loop stops at every boundary and a fresh one is launched from inside
                 * `apply` — so a null here means either the program is over or the run has
                 * just moved a step and the next loop takes it from here. Asking again at the
                 * same instant is what that next loop does.
                 */
                if (timer.run.value == null) return
                continue
            }
            advanceMs(sleep + jitterMs)
        }
        throw AssertionError("the protocol never ended")
    }

    /** Six hangs and five rests: flatten drops the pause after the last effort. */
    private fun expectedKinds(): List<StepKind> =
        (1..11).map { if (it % 2 == 1) StepKind.WORK else StepKind.REST }

    @Test
    fun `every transition of a whole 7 to 3 set is signalled`() {
        val signals = Recorder(context)
        val timer = newController(signals)

        timer.start(repeaters)
        runToEnd(timer)

        assertEquals(
            "one signal per step, hang and rest alternating, from the first to the last",
            expectedKinds(),
            signals.boundaries,
        )
    }

    /**
     * The same set with every wake-up a quarter of a second late, which is what a phone that
     * is doing something else looks like. Lateness may cost a tick; it may not cost the
     * instruction to hang.
     */
    @Test
    fun `a whole set still signals every transition when every wake-up runs late`() {
        val signals = Recorder(context)
        val timer = newController(signals)

        timer.start(repeaters)
        runToEnd(timer, jitterMs = 250)

        assertEquals(expectedKinds(), signals.boundaries)
    }

    /** A second of overrun on every sleep: still eleven steps, still eleven signals. */
    @Test
    fun `a whole set still signals every transition when the phone is a second behind`() {
        val signals = Recorder(context)
        val timer = newController(signals)

        timer.start(repeaters)
        runToEnd(timer, jitterMs = 1_000)

        assertEquals(expectedKinds(), signals.boundaries)
    }

    /**
     * The countdown itself over the whole set, for completeness: three ticks on each of the
     * six hangs and two on each of the five rests, which is what `timerCue` promises.
     */
    @Test
    fun `a whole set counts three on every hang and two on every rest`() {
        val signals = Recorder(context)
        val timer = newController(signals)

        timer.start(repeaters)
        runToEnd(timer)

        assertEquals(6 * 3 + 5 * 2, signals.ticks.size)
    }
}
