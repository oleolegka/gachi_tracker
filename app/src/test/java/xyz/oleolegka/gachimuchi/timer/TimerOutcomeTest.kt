package xyz.oleolegka.gachimuchi.timer

import android.content.Context
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
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.programFromExercise
import xyz.oleolegka.gachimuchi.domain.restProgram

/**
 * Whether a run that ends actually leaves an offer behind, and whether the right ones do.
 *
 * The arithmetic of "which sets were completed" is covered on the JVM (domain/RunLogTest).
 * What is checked here is the wiring: that finishing and stopping BOTH produce an offer,
 * that a rest and a plain program produce none, and that an offer cannot outlive the run
 * it belongs to and turn up attached to the next one.
 *
 * The runs here are advanced with Skip rather than by waiting, which is also the case the
 * feature is weakest at: skipping counts the skipped effort as done (see domain/RunLog.kt).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TimerOutcomeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var controller: TimerController? = null

    private val hangs = ExerciseRef(
        id = 42, name = "Hangs 20 mm", form = ExerciseForm.HOLD,
        edgeMm = 20.0, workSec = 7.0, restSec = 3.0,
    )

    /** Two sets of two hangs, no lead-in: seven steps, four of them efforts. */
    private val program = programFromExercise(
        exercise = hangs, reps = 2, sets = 2, restBetweenSetsSec = 60, prepareSec = 0,
    )!!

    private fun newController(): TimerController = TimerController(context).also { controller = it }

    @After
    fun tearDown() {
        controller?.stop()
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun `a program from an exercise, run to the end, leaves an offer of both sets`() {
        val timer = newController()
        timer.start(program, exerciseId = hangs.id, origin = RunOrigin.EXERCISE)
        assertEquals(7, timer.run.value!!.steps.size)

        repeat(7) { timer.skip() }

        assertNull("the run is over", timer.run.value)
        val outcome = timer.outcome.value
        assertNotNull(outcome)
        assertFalse(outcome!!.interrupted)
        assertEquals(listOf(2, 2), outcome.sets.map { it.reps })
        assertEquals(hangs.id, outcome.exerciseId)
    }

    @Test
    fun `stopping part-way leaves an offer of the part that ran`() {
        val timer = newController()
        timer.start(program, exerciseId = hangs.id, origin = RunOrigin.EXERCISE)

        repeat(3) { timer.skip() } // through both hangs of set 1 and into the pause
        timer.stop()

        val outcome = timer.outcome.value
        assertNotNull(outcome)
        assertTrue(outcome!!.interrupted)
        assertEquals(listOf(2), outcome.sets.map { it.reps })
        // the pause was cut short, so it is not offered as a fact
        assertNull(outcome.sets.single().restAfterSec)
    }

    @Test
    fun `a run stopped before anything was completed offers nothing`() {
        val timer = newController()
        timer.start(program, exerciseId = hangs.id, origin = RunOrigin.EXERCISE)

        timer.stop()

        assertNull(timer.outcome.value)
    }

    @Test
    fun `a rest between sets leaves no offer, however it ends`() {
        val timer = newController()

        timer.start(restProgram(120), exerciseId = hangs.id, origin = RunOrigin.REST)
        timer.skip() // one step, so this finishes the rest
        assertNull(timer.outcome.value)

        timer.start(restProgram(120), exerciseId = hangs.id, origin = RunOrigin.REST)
        timer.stop()
        assertNull(timer.outcome.value)
    }

    @Test
    fun `a plain program leaves no offer, because it belongs to no exercise`() {
        val timer = newController()
        timer.start(program) // no exercise, default origin

        repeat(7) { timer.skip() }

        assertNull(timer.outcome.value)
    }

    @Test
    fun `answering the offer clears it`() {
        val timer = newController()
        timer.start(program, exerciseId = hangs.id, origin = RunOrigin.EXERCISE)
        repeat(7) { timer.skip() }
        assertNotNull(timer.outcome.value)

        timer.clearOutcome()

        assertNull(timer.outcome.value)
    }

    @Test
    fun `starting the next run drops an offer that was never answered`() {
        val timer = newController()
        timer.start(program, exerciseId = hangs.id, origin = RunOrigin.EXERCISE)
        repeat(7) { timer.skip() }
        assertNotNull(timer.outcome.value)

        timer.start(restProgram(120), exerciseId = hangs.id, origin = RunOrigin.REST)

        assertNull("a stale offer must not follow the next run around", timer.outcome.value)
    }
}
