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
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.atomic.AtomicInteger

/**
 * ONE STEP BOUNDARY MAKES ONE SIGNAL, however many things notice it.
 *
 * ── What was wrong ──────────────────────────────────────────────────────────────
 * A live run has its boundary handled TWICE, at every boundary. The countdown coroutine
 * wakes for it on [kotlinx.coroutines.Dispatchers.Default]; the exact alarm armed at the end
 * of the same step fires for it in [TimerReceiver], on the main thread. That is by design —
 * the alarm is the backstop for a process that has been frozen — but nothing demotes the
 * backstop while the first line is working, so on 7:3 repeaters the two arrive together
 * eleven times a set.
 *
 * What kept that from being audible was `if (index == signalledStep) return` over a plain
 * field: a check followed by an act, on two threads, with no lock and no barrier between
 * them. Losing that race gives two boundary signals a few tens of milliseconds apart, which
 * is the doubled beep the timer was reported for. Losing the corresponding race in
 * `restartLoop` leaves TWO countdown loops running, which doubles the ticks as well.
 *
 * ── These tests DO NOT REPRODUCE that race, and the honest reason why ───────────
 * The last test was written to, and it does not: it passes against the unsynchronized code
 * as reliably as against the fixed code. Two reasons, and both are worth knowing before
 * anybody trusts it.
 *
 *  - The window is tiny. Each racer does a settle, two clock reads and a data-class copy
 *    before it reaches the check, so eight threads released from one barrier still arrive
 *    spread out, and the first one through has set the field long before the second looks.
 *    Widening the window would mean putting a sleep in production code, which is worse than
 *    not having the test.
 *  - The half of the bug that actually bites is INVISIBLE ON THIS MACHINE. The failure is
 *    not only the interleaving, it is that a write to a plain field on the main thread has
 *    no happens-before edge to a read of it on a `Dispatchers.Default` thread. x86 orders
 *    stores strongly enough to hide that almost always; the phone is ARM, where it is not
 *    hidden. A JVM test on this hardware cannot show it.
 *
 * So the fix rests on reading the code, not on a red test turning green, and this file
 * records the invariant rather than the bug: one boundary, one signal, whatever reaches it.
 * That is worth having — it would catch a gross regression, and the first two tests are
 * ordinary and deterministic — but it is not evidence, and it should not be described as
 * evidence.
 *
 * ── And nothing here can show what any of it SOUNDS like ────────────────────────
 * Robolectric's vibrator records the last vibration and there is no shadow for
 * `ToneGenerator` at all, so what is counted is what the timer ASKED FOR. Whether two
 * requests a few milliseconds apart are heard as a stutter, and whether the boundary
 * survives it, needs a phone.
 *
 * The countdown coroutine is also effectively inert under Robolectric: it sleeps in real time
 * against a clock the test moves by hand, so it does not take part here at all. What is
 * driven is the alarm side, which reaches the same fields by the same path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BoundarySignalTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val controllers = mutableListOf<TimerController>()

    /** Counts what was asked for instead of asking the hardware. See [Signals]. */
    private class CountingSignals(context: Context) : Signals(context) {
        val boundaries = AtomicInteger()
        val ticks = AtomicInteger()

        override fun boundary(settings: TimerSettings, starting: StepKind) {
            boundaries.incrementAndGet()
        }

        override fun tick(settings: TimerSettings) {
            ticks.incrementAndGet()
        }
    }

    /** Six hangs of seven seconds with three seconds between them, and no lead-in. */
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

    private fun advance(seconds: Long) = ShadowSystemClock.advanceBy(Duration.ofSeconds(seconds))

    @After
    fun tearDown() {
        controllers.forEach { it.stop() }
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `every path that notices one boundary between them makes one signal`() {
        val signals = CountingSignals(context)
        val timer = newController(signals)
        timer.start(repeaters)
        val afterStart = signals.boundaries.get()

        advance(7)
        // the backstop alarm, then the same alarm redelivered, then a screen coming back
        timer.onAlarm()
        timer.onAlarm()
        timer.refresh()

        assertEquals("the rest began once and is announced once", afterStart + 1, signals.boundaries.get())
    }

    @Test
    fun `a whole 7 to 3 set signals once per step and no more`() {
        val signals = CountingSignals(context)
        val timer = newController(signals)
        timer.start(repeaters)

        // six hangs and five rests: flatten drops the rest after the last hang
        val steps = timer.run.value!!.steps
        assertEquals(11, steps.size)

        // sit out each step in turn, which lands on the one after it. The last step is left
        // alone: running it out ends the program, and that is a finish rather than a boundary.
        steps.dropLast(1).forEach { step ->
            advance(step.durationSec.toLong())
            // both mechanisms reach for it, as they do on a phone
            timer.onAlarm()
            timer.refresh()
        }

        assertEquals("one per step, the first one included", 11, signals.boundaries.get())
    }

    /**
     * Eight threads reach the same boundary at once, which is the shape of the two that do it
     * on a phone. Read the note at the top of this file before believing it: this passes
     * against the unsynchronized code too, so it pins the invariant and proves nothing about
     * the bug.
     */
    @Test
    fun `a boundary several threads reach at once is still one signal`() {
        val signals = CountingSignals(context)
        val timer = newController(signals)
        timer.start(repeaters)
        val afterStart = signals.boundaries.get()

        advance(7)
        val racers = 8
        val gate = CyclicBarrier(racers)
        val failures = mutableListOf<Throwable>()
        val threads = (1..racers).map {
            Thread {
                runCatching {
                    gate.await()
                    timer.onAlarm()
                }.onFailure { error -> synchronized(failures) { failures += error } }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }

        assertEquals("nothing here is allowed to throw", emptyList<Throwable>(), failures)
        assertEquals(
            "a check-then-act on a plain field is what made this two beeps",
            afterStart + 1,
            signals.boundaries.get(),
        )
    }
}
