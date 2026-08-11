package xyz.oleolegka.gachimuchi.ui

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.ActivityRepository
import xyz.oleolegka.gachimuchi.data.ProgramRepository
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.HoldSide
import xyz.oleolegka.gachimuchi.domain.TYPE_WORKOUT_STARTED
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.timer.TimerController
import java.time.LocalDate

/**
 * Starting a workout "like last time" (§13.9), end to end through the real database — the
 * layer the pure reducer tests in domain/WorkoutTest.kt cannot see: whether
 * [MainViewModel.beginDraft] actually calls the right domain functions with the right
 * arguments, and — the whole point of landing this on top of the lazy-start rule that shipped
 * an hour before this feature — that composing the draft writes NOTHING to the journal.
 *
 * These overlap the pure tests on purpose, the same reasoning WorkoutFlowTest gives for
 * overlapping domain/WorkoutTest.kt: a wiring mistake here (the wrong events list handed to
 * [xyz.oleolegka.gachimuchi.domain.lastWorkoutNamed], the catalog ref resolved against the
 * wrong exercise) would still pass every pure test and only show up going through the real
 * repository.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PastWorkoutDraftTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository
    private lateinit var viewModel: MainViewModel
    private lateinit var timer: TimerController

    /** Runs Room's executors inline — see RunLoggingChainTest for why this is what [settle] needs. */
    private val inline = java.util.concurrent.Executor { it.run() }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(inline)
            .setTransactionExecutor(inline)
            .build()
        repo = ActivityRepository(db)
        timer = TimerController(context)
        viewModel = MainViewModel(repo, ProgramRepository(db), timer)
    }

    @After
    fun tearDown() {
        timer.stop()
        context.getSharedPreferences("timer", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("floors", Context.MODE_PRIVATE).edit().clear().commit()
        db.close()
    }

    /** Drains the coroutine [MainViewModel.beginDraft] launched on the main looper. */
    private fun settle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private suspend fun ref(name: String, form: ExerciseForm, oneSided: Boolean = false) =
        repo.toRef(repo.exercise(repo.ensureExercise(name, form))!!)
            .let { if (oneSided) it.copy(oneSided = true) else it }
            .also { if (oneSided) repo.setOneSided(it.id, true) }

    @Test
    fun `three workouts sharing a name offer the last one's composition, off-plan and unstarted`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val squat = ref("Squat", ExerciseForm.STRENGTH)
        val hangs = ref("Hangs", ExerciseForm.HOLD)

        // three "Push day" workouts, oldest to newest, each with its own composition
        val old = repo.startWorkout("2026-08-01", name = "Push day")
        repo.addExerciseToWorkout(old, bench.id, restSec = 60)

        val mid = repo.startWorkout("2026-08-03", name = "Push day")
        repo.addExerciseToWorkout(mid, squat.id, restSec = 90)

        val recent = repo.startWorkout("2026-08-05", name = "Push day")
        repo.addExerciseToWorkout(recent, hangs.id, restSec = 45)
        repo.finishWorkout(recent)

        // three workouts already own their own workout_started events by this point; what
        // matters is whether beginDraft appends a FOURTH one of its own
        val startsBefore = repo.allEvents().count { it.type == TYPE_WORKOUT_STARTED }
        val eventsBefore = repo.allEvents()

        viewModel.beginDraft(LocalDate.parse("2026-08-07"), name = "Push day")
        settle()

        val draft = viewModel.draft.value
        assertTrue("a draft was opened", draft != null)
        assertEquals(
            "composed from the LAST workout named this, not the first or the middle one",
            listOf(hangs.id),
            draft!!.cards.map { it.exerciseId },
        )
        assertEquals(45, draft.cards.single().restSec)

        // the whole point of landing on top of the lazy-start rule: nothing was written,
        // and specifically no new workout_started event was appended
        assertEquals("beginDraft must not write to the journal", eventsBefore, repo.allEvents())
        assertEquals(
            "no new workout_started event may appear from composing a draft",
            startsBefore,
            repo.allEvents().count { it.type == TYPE_WORKOUT_STARTED },
        )
    }

    @Test
    fun `an exercise removed from the source workout does not arrive in the draft`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val squat = ref("Squat", ExerciseForm.STRENGTH)

        val source = repo.startWorkout("2026-08-01", name = "Push day")
        val addedBench = repo.addExerciseToWorkout(source, bench.id, restSec = 60)
        repo.addExerciseToWorkout(source, squat.id, restSec = 90)
        repo.deleteEntry(addedBench)
        repo.finishWorkout(source)

        viewModel.beginDraft(LocalDate.parse("2026-08-07"), name = "Push day")
        settle()

        assertEquals(listOf(squat.id), viewModel.draft.value!!.cards.map { it.exerciseId })
    }

    @Test
    fun `a one-sided exercise from the source workout arrives as two cards, not four`() = runTest {
        val pistol = ref("Pistol squat", ExerciseForm.STRENGTH, oneSided = true)

        val source = repo.startWorkout("2026-08-01", name = "Legs")
        repo.addExerciseToWorkout(source, pistol.id, restSec = 90, side = HoldSide.LEFT)
        repo.addExerciseToWorkout(source, pistol.id, restSec = 90, side = HoldSide.RIGHT)
        repo.finishWorkout(source)

        viewModel.beginDraft(LocalDate.parse("2026-08-07"), name = "Legs")
        settle()

        val cards = viewModel.draft.value!!.cards
        assertEquals(2, cards.size)
        assertEquals(listOf(HoldSide.LEFT, HoldSide.RIGHT), cards.map { it.side })
        assertTrue(cards.all { it.exerciseId == pistol.id && it.restSec == 90 })
    }

    @Test
    fun `a name nothing was ever started under opens an empty, unstarted draft`() = runTest {
        val before = repo.allEvents().size

        viewModel.beginDraft(LocalDate.parse("2026-08-07"), name = "Never trained this")
        settle()

        assertEquals(emptyList<Long>(), viewModel.draft.value!!.cards.map { it.exerciseId })
        assertEquals(before, repo.allEvents().size)
    }

    // --- a rest must not outlive the thing it was counting for (23.A2) --------------------

    /** The card's own key: the ordinary case, and the one that already worked. */
    @Test
    fun `taking an exercise out of a workout stops the rest counting under it`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val workout = repo.startWorkout("2026-08-07")
        repo.addExerciseToWorkout(workout, bench.id, restSec = 180)
        timer.floors.start(bench.id, "Bench press", orderedMs = 180_000)

        val card = buildWorkout(repo.allEvents(), workout)!!.exercises.single()
        viewModel.removeWorkoutExercise(workout, card.addedEventIds, card.exerciseId, card.side)
        settle()

        assertEquals(emptyList<Long>(), timer.floors.floors.value.map { it.exerciseId })
    }

    /*
     * The half that was missing. A floor is keyed by the side of the SET that started it and a
     * card by the side of the row that added it; where those two disagree the card's own key
     * finds nothing, and the countdown was left running with nothing on screen to stop it.
     */
    @Test
    fun `a rest keyed differently from its card is stopped too, once the card is gone`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val workout = repo.startWorkout("2026-08-07")
        repo.addExerciseToWorkout(workout, bench.id, restSec = 180)
        // a side the card does not carry: exactly the mismatch the second pass exists for
        timer.floors.start(bench.id, "Bench press", orderedMs = 180_000, side = HoldSide.LEFT.code)

        val card = buildWorkout(repo.allEvents(), workout)!!.exercises.single()
        viewModel.removeWorkoutExercise(workout, card.addedEventIds, card.exerciseId, card.side)
        settle()

        assertEquals(emptyList<Long>(), timer.floors.floors.value.map { it.exerciseId })
    }

    /** The other hand is still in the workout, so its own countdown is none of this act's business. */
    @Test
    fun `removing one hand of a one-sided exercise leaves the other hand counting`() = runTest {
        val pistol = ref("Pistol squat", ExerciseForm.STRENGTH, oneSided = true)
        val workout = repo.startWorkout("2026-08-07")
        repo.addExerciseToWorkout(workout, pistol.id, restSec = 90, side = HoldSide.LEFT)
        repo.addExerciseToWorkout(workout, pistol.id, restSec = 90, side = HoldSide.RIGHT)
        timer.floors.start(pistol.id, "Pistol squat - left", orderedMs = 90_000, side = HoldSide.LEFT.code)
        timer.floors.start(pistol.id, "Pistol squat - right", orderedMs = 90_000, side = HoldSide.RIGHT.code)

        val left = buildWorkout(repo.allEvents(), workout)!!
            .exercises.single { it.side == HoldSide.LEFT }
        viewModel.removeWorkoutExercise(workout, left.addedEventIds, left.exerciseId, left.side)
        settle()

        assertEquals(listOf(HoldSide.RIGHT.code), timer.floors.floors.value.map { it.side })
    }

    /** Deleting the WHOLE workout: every card of it, and every rest of every card. */
    @Test
    fun `deleting a workout stops every rest it had running`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val squat = ref("Squat", ExerciseForm.STRENGTH)
        val workout = repo.startWorkout("2026-08-07")
        repo.addExerciseToWorkout(workout, bench.id, restSec = 180)
        repo.addExerciseToWorkout(workout, squat.id, restSec = 120)
        timer.floors.start(bench.id, "Bench press", orderedMs = 180_000)
        timer.floors.start(squat.id, "Squat", orderedMs = 120_000)
        // and one that has nothing to do with this workout, which must survive
        timer.floors.start(999, "Stretching", orderedMs = 60_000)

        viewModel.deleteWorkout(workout)
        settle()

        assertEquals(listOf(999L), timer.floors.floors.value.map { it.exerciseId })
    }
}
