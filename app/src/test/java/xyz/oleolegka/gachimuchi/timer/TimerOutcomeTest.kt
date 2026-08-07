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
import xyz.oleolegka.gachimuchi.data.TimerStore
import xyz.oleolegka.gachimuchi.domain.CompletedSet
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.OUTCOME_MAX_AGE_MS
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.RunOutcome
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
    private val controllers = mutableListOf<TimerController>()

    private val hangs = ExerciseRef(
        id = 42, name = "Hangs 20 mm", form = ExerciseForm.HOLD,
        edgeMm = 20.0, workSec = 7.0, restSec = 3.0,
    )

    /** Two sets of two hangs, no lead-in: seven steps, four of them efforts. */
    private val program = programFromExercise(
        exercise = hangs, reps = 2, sets = 2, restBetweenSetsSec = 60, prepareSec = 0,
    )!!

    private fun newController(): TimerController =
        TimerController(context).also { controllers += it }

    @After
    fun tearDown() {
        controllers.forEach { it.stop() }
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
        // the floors live in their own preference file and leak into the next test otherwise
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
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

    /**
     * There used to be a test here called "a rest between sets leaves no offer, however it
     * ends". It is gone with the thing it guarded: a rest is no longer a run at all (it is a
     * floor — domain/Floors.kt), so there is nothing on this path to exclude. What is left
     * worth checking is that a run STOPPED WITH NOTHING DONE still offers nothing, which is
     * the test above, and that a run that did complete something offers, which is below.
     */
    @Test
    fun `a one-step run that reached its end is offered`() {
        val timer = newController()

        timer.start(restProgram(120), exerciseId = hangs.id, origin = RunOrigin.EXERCISE)
        timer.skip() // one step, so this finishes the run

        val outcome = timer.outcome.value
        assertNotNull(outcome)
        assertTrue(outcome!!.offersLogging)
        assertEquals(1, outcome.sets.size)
    }

    @Test
    fun `a program that belongs to no exercise still leaves an offer`() {
        val timer = newController()
        timer.start(program) // no exercise, default origin

        repeat(7) { timer.skip() }

        // this is the case the user hit twice: a saved protocol, run from the timer tab,
        // counting a whole session and then offering nothing at all
        val outcome = timer.outcome.value
        assertNotNull(outcome)
        assertTrue(outcome!!.offersLogging)
        assertNull("it does not claim to know which exercise it was", outcome.exerciseId)
        assertEquals(listOf(2, 2), outcome.sets.map { it.reps })
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
    fun `an offer older than a day is dropped instead of raised against a written day`() {
        val store = TimerStore(context)
        store.saveOutcome(
            RunOutcome(
                programName = "Repeaters",
                origin = RunOrigin.EXERCISE,
                exerciseId = hangs.id,
                interrupted = false,
                sets = listOf(CompletedSet(1, reps = 6, plannedReps = 6, workSec = 7, restAfterSec = null)),
                endedAtWallMs = System.currentTimeMillis() - OUTCOME_MAX_AGE_MS - 1_000,
                opDate = "2026-01-01",
            )
        )

        val revived = newController()

        assertNull("by now that day is either written or lost, and guessing is worse", revived.outcome.value)
        assertNull(TimerStore(context).loadOutcome())
    }

    @Test
    fun `an offer from last night is still raised, and says which day it belongs to`() {
        val store = TimerStore(context)
        store.saveOutcome(
            RunOutcome(
                programName = "Repeaters",
                origin = RunOrigin.EXERCISE,
                exerciseId = hangs.id,
                interrupted = false,
                sets = listOf(CompletedSet(1, reps = 6, plannedReps = 6, workSec = 7, restAfterSec = null)),
                endedAtWallMs = System.currentTimeMillis() - 10 * 60 * 60 * 1000L,
                opDate = "2026-08-05",
            )
        )

        val outcome = newController().outcome.value

        assertNotNull(outcome)
        assertEquals("2026-08-05", outcome!!.opDate)
        assertFalse("and it must not pretend it just happened", outcome.isFresh(System.currentTimeMillis()))
    }

    @Test
    fun `starting the next run drops an offer that was never answered`() {
        val timer = newController()
        timer.start(program, exerciseId = hangs.id, origin = RunOrigin.EXERCISE)
        repeat(7) { timer.skip() }
        assertNotNull(timer.outcome.value)

        timer.start(restProgram(120), exerciseId = hangs.id, origin = RunOrigin.EXERCISE)

        assertNull("a stale offer must not follow the next run around", timer.outcome.value)
    }
}
