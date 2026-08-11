package xyz.oleolegka.gachimuchi.data

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseRef
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.RunOrigin
import xyz.oleolegka.gachimuchi.domain.holdSetOf
import xyz.oleolegka.gachimuchi.domain.programFromExercise
import xyz.oleolegka.gachimuchi.timer.TimerController
import xyz.oleolegka.gachimuchi.ui.MainViewModel
import java.time.LocalDate

/**
 * ONE QUESTION, ASKED AFTER EVERY KIND OF DELETE: is anything still counting for what has
 * just been removed?
 *
 * ── Why this file exists rather than another case in an existing one ────────────
 * "I deleted the workout and the exercises ages ago and their timer is still going in the
 * background" has now been reported from the phone twice. The first attempt to close it
 * fixed the rests — `deleteWorkout` did not dismiss any, and the floor key disagreed with
 * the card key for a sideless set of a one-sided exercise — and both fixes were real. It
 * still did not close the report, because the app has TWO kinds of countdown and only one of
 * them is a rest. The other is the conductor: one run for the whole process, holding a
 * foreground service, a wake lock, an exact alarm, a notification and the speaker, and asking
 * nothing about whether the exercise it was started from still exists.
 *
 * So the assertion here is deliberately not "the floors were dismissed". It is [nothingCounts]
 * — no rest, no run, no offer, and nothing left that would keep the foreground service up —
 * because that is the property the owner is actually reporting on, and a test that names one
 * mechanism can be satisfied while the other one is still buzzing in a pocket.
 *
 * ── End to end, through the real objects ────────────────────────────────────────
 * A real in-memory database, a real [ActivityRepository], a real [TimerController] and the
 * real [MainViewModel] method the delete button calls. Nothing is mocked: the previous round
 * of this bug was invisible precisely because every layer, tested on its own, was right.
 *
 * ── What it still cannot show ───────────────────────────────────────────────────
 * Robolectric's alarm manager, notification manager and service starter are ledgers rather
 * than the platform. This proves the app asks for the countdowns to be taken down and believes
 * they are; it does not prove Android actually cancels a scheduled alarm, tears the foreground
 * service down, or that a process resurrected later cannot rebuild something from disk — the
 * restore path reads a preference file and never consults the catalog, which remains an open
 * hole and is stated as one rather than covered here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeletionSilencesCountdownsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var programs: ProgramRepository
    private val controllers = mutableListOf<TimerController>()

    /** Room's executors, run on the calling thread — see RunLoggingChainTest for the whole of it. */
    private val inline = java.util.concurrent.Executor { it.run() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        repo = ActivityRepository(db)
        programs = ProgramRepository(db)
    }

    @After
    fun tearDown() {
        controllers.forEach { it.stop() }
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
        // the rests live in their own preference file and leak into the next test otherwise
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
    }

    private fun newController(): TimerController =
        TimerController(context).also { controllers += it }
            .also { it.setEnabled(true) }

    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private val today = LocalDate.now()

    /**
     * A hangboard exercise with a work:rest pair, so it can be both rested under and conducted.
     *
     * The name is unique per test on purpose: Robolectric keeps one database per test CLASS,
     * and `ensureExercise` is find-or-create, so two tests asking for "Hangs" would share a row
     * and each other's history.
     */
    private suspend fun hangs(name: String, oneSided: Boolean = false): ExerciseRef = repo.toRef(
        repo.exercise(
            repo.ensureExercise(name, ExerciseForm.HOLD, workSec = 7.0, restSec = 3.0, oneSided = oneSided)
        )!!
    )

    /** THE assertion. Not "the floors are gone" — nothing at all is counting. */
    private fun nothingCounts(timer: TimerController, what: String) {
        assertEquals("$what: a rest is still counting", emptyList<Any>(), timer.floors.floors.value)
        assertNull("$what: the conductor is still running", timer.run.value)
        assertNull("$what: an offer is left proposing sets against it", timer.outcome.value)
        assertFalse("$what: the foreground service is still wanted", timer.serviceNeeded())
    }

    // --- the report, walked ------------------------------------------------------------------

    @Test
    fun `deleting a workout leaves nothing counting - not a rest and not a conducted set`() = runTest {
        val timer = newController()
        val exercise = hangs("Hangs whole workout")
        val vm = MainViewModel(repo, programs, timer)

        val workoutId = repo.startWorkout(today.toString())
        repo.addExerciseToWorkout(workoutId, exercise.id, restSec = 180)

        // 1. a set by hand, which is what starts a rest under the card
        vm.addSet(holdSetOf(exercise, today.toString(), reps = 6, holdSec = 7.0), intoWorkoutId = workoutId)
        settle()
        assertEquals("the rest under the card must be running for this test to mean anything",
            1, timer.floors.floors.value.size)

        // 2. and a conducted set on top of it — the second kind of countdown, and the one the
        // first round of this fix never touched
        timer.start(
            programFromExercise(exercise, reps = 6, sets = 4, restBetweenSetsSec = 180)!!,
            exercise.id,
            RunOrigin.EXERCISE,
        )
        assertNotNull("the conductor must be running for this test to mean anything", timer.run.value)

        // 3. the delete button
        vm.deleteWorkout(workoutId)
        settle()

        nothingCounts(timer, "workout deleted")
    }

    @Test
    fun `deleting the catalog exercise leaves nothing counting either`() = runTest {
        val timer = newController()
        val exercise = hangs("Hangs catalog row")
        val vm = MainViewModel(repo, programs, timer)

        val workoutId = repo.startWorkout(today.toString())
        repo.addExerciseToWorkout(workoutId, exercise.id, restSec = 180)
        vm.addSet(holdSetOf(exercise, today.toString(), reps = 6, holdSec = 7.0), intoWorkoutId = workoutId)
        settle()
        timer.start(
            programFromExercise(exercise, reps = 6, sets = 4, restBetweenSetsSec = 180)!!,
            exercise.id,
            RunOrigin.EXERCISE,
        )
        assertTrue(timer.floors.floors.value.isNotEmpty() && timer.run.value != null)

        /*
         * What `rememberExerciseEditor`'s delete does, in the order it does it.
         *
         * A COPY, and the weakness is worth stating rather than glossing: the composable builds
         * its own repository and controller out of a context and cannot be driven from here, so
         * this test asserts that the two calls have the effect they are relied on for — NOT that
         * the delete button still makes them. Remove them from `ExerciseEditor.kt` and this stays
         * green while the report comes straight back. Closing that needs the catalog screen
         * driven through Compose, which this project has no harness for.
         */
        timer.floors.dismissAllOf(exercise.id)
        timer.stopFor(exercise.id)
        repo.deleteExercise(repo.exercise(exercise.id)!!)
        settle()

        nothingCounts(timer, "catalog exercise deleted")
    }

    @Test
    fun `removing one hand's card leaves the other hand counting, and takes only its own`() = runTest {
        val timer = newController()
        val exercise = hangs("Hangs two hands", oneSided = true)
        val vm = MainViewModel(repo, programs, timer)

        val workoutId = repo.startWorkout(today.toString())
        val leftAdded = repo.addExerciseToWorkout(workoutId, exercise.id, 180, HoldSide.LEFT)
        repo.addExerciseToWorkout(workoutId, exercise.id, 180, HoldSide.RIGHT)

        val left = holdSetOf(exercise, today.toString(), reps = 6, holdSec = 7.0, side = HoldSide.LEFT)
        val right = holdSetOf(exercise, today.toString(), reps = 6, holdSec = 7.0, side = HoldSide.RIGHT)
        vm.addSet(left, intoWorkoutId = workoutId)
        settle()
        vm.addSet(right, intoWorkoutId = workoutId)
        settle()
        assertEquals("two cards, two rests", 2, timer.floors.floors.value.size)

        // the conductor is on the RIGHT hand while the LEFT card is taken out
        timer.start(
            programFromExercise(exercise, reps = 6, sets = 4, restBetweenSetsSec = 180)!!,
            exercise.id,
            RunOrigin.EXERCISE,
            HoldSide.RIGHT,
        )

        vm.removeWorkoutExercise(workoutId, listOf(leftAdded), exercise.id, HoldSide.LEFT)
        settle()

        // the left hand's rest is gone and ONLY the left hand's
        assertEquals(listOf(HoldSide.RIGHT.code), timer.floors.floors.value.map { it.side })
        // and the right hand's conducted set is untouched: this is the whole reason a rest is
        // keyed by (exercise, side) rather than by exercise
        assertNotNull("removing the left card must not stop the right hand's run", timer.run.value)
    }

    @Test
    fun `a run started for the left hand is not stopped by removing the right hand's card`() = runTest {
        val timer = newController()
        val exercise = hangs("Hangs left run", oneSided = true)
        val vm = MainViewModel(repo, programs, timer)

        val workoutId = repo.startWorkout(today.toString())
        repo.addExerciseToWorkout(workoutId, exercise.id, 180, HoldSide.LEFT)
        val rightAdded = repo.addExerciseToWorkout(workoutId, exercise.id, 180, HoldSide.RIGHT)

        timer.start(
            programFromExercise(exercise, reps = 6, sets = 4, restBetweenSetsSec = 180)!!,
            exercise.id,
            RunOrigin.EXERCISE,
            HoldSide.LEFT,
        )

        vm.removeWorkoutExercise(workoutId, listOf(rightAdded), exercise.id, HoldSide.RIGHT)
        settle()

        assertNotNull("the left hand's run belongs to a card that is still there", timer.run.value)
        assertEquals(HoldSide.LEFT.code, timer.run.value!!.side)
    }
}
