package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.ledByProtocol
import xyz.oleolegka.gachimuchi.domain.loggingDay
import xyz.oleolegka.gachimuchi.domain.restHintSec
import xyz.oleolegka.gachimuchi.domain.setsOutsideWorkouts
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.workoutsOn
import java.time.LocalDate

/**
 * The workout end to end through the real database: press start, add exercises before doing
 * any of them, log sets, fold the journal back into a workout.
 *
 * These overlap the pure reducer tests on purpose. Going through Room is what catches the
 * failures the reducers cannot see: a `workout_id` that never reaches the column, a payload
 * that will not read back, and a repository that forgets to file a set under the workout it
 * was done in — after which the set is still recorded (nothing is lost) but silently drops
 * out of the workout, which is the kind of bug a screen shows as "it just did not appear".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WorkoutFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    /**
     * The real current date, because [ActivityRepository.record] resolves the open workout
     * against the clock. Pinning a literal day here would make the test pass only on that day.
     */
    private val day: String = LocalDate.now().toString()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun ref(name: String, form: ExerciseForm, work: Double? = null, rest: Double? = null) =
        repo.exercise(repo.ensureExercise(name, form, workSec = work, restSec = rest))!!.toRef()

    @Test
    fun `start, add two exercises, log sets, and the workout folds back out of the journal`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val squat = ref("Squat", ExerciseForm.STRENGTH)

        val workoutId = repo.startWorkout()
        repo.addExerciseToWorkout(workoutId, bench.id, restSec = 150)
        repo.addExerciseToWorkout(workoutId, squat.id, restSec = 210)

        // both are in the workout before a single set exists — the reason the "added" event
        // has to be in the journal at all
        val planned = buildWorkout(repo.allEvents(), workoutId)!!
        assertEquals(listOf(bench.id, squat.id), planned.exercises.map { it.exerciseId })
        assertTrue(planned.exercises.all { it.isEmpty })

        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 62.5))
        repo.record(strengthSetOf(squat, day, reps = 5, weightKg = 100.0))

        val workout = buildWorkout(repo.allEvents(), workoutId)!!
        assertEquals(workoutId, workout.id)
        assertEquals(day, workout.opDate)
        assertEquals(listOf(bench.id, squat.id), workout.exercises.map { it.exerciseId })
        assertEquals(listOf(150, 210), workout.exercises.map { it.restSec })
        assertEquals(listOf(2, 1), workout.exercises.map { it.sets.size })
        assertEquals(3, workout.setCount)
        assertEquals(
            listOf(60.0, 62.5),
            workout.exercises.first().sets.map { (it.form as StrengthSet).weightKg },
        )

        // and the same rows still read as an ordinary day, because a workout is extra
        // structure over the journal rather than a replacement for it
        assertEquals(3, buildSession(repo.allEvents(), day).setCount)
        assertEquals(1, workoutsOn(repo.allEvents(), day).size)
    }

    @Test
    fun `recording without pressing start still works and the set belongs to no workout`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))

        assertNull(repo.currentWorkoutId())
        val row = repo.allEvents().single()
        assertNull("the start button must never be a toll gate in front of logging", row.workoutId)
        assertEquals(1, setsOutsideWorkouts(repo.allEvents(), day).size)
    }

    @Test
    fun `sets logged before the workout was started stay outside it`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))

        val workoutId = repo.startWorkout()
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 62.5))

        assertEquals(1, buildWorkout(repo.allEvents(), workoutId)!!.setCount)
        assertEquals(1, setsOutsideWorkouts(repo.allEvents(), day).size)
        // nothing has gone anywhere: the day still has both
        assertEquals(2, buildSession(repo.allEvents(), day).setCount)
    }

    @Test
    fun `a set is filed under the LAST workout started, and the earlier one keeps its own`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)

        val morning = repo.startWorkout()
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        val evening = repo.startWorkout()
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 62.5))

        assertEquals(evening, repo.currentWorkoutId())
        assertEquals(1, buildWorkout(repo.allEvents(), morning)!!.setCount)
        assertEquals(1, buildWorkout(repo.allEvents(), evening)!!.setCount)
        assertEquals(2, workoutsOn(repo.allEvents(), day).size)
    }

    @Test
    fun `cancelling a set removes it from the workout and leaves the journal append-only`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val workoutId = repo.startWorkout()
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        val mistake = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 600.0))

        repo.cancelSet(mistake)

        assertEquals(1, buildWorkout(repo.allEvents(), workoutId)!!.setCount)
        // start + two sets + the reversal: nothing was deleted
        assertEquals(4, repo.eventCount())
    }

    @Test
    fun `adding an exercise to a workout also remembers the rest for next time`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        assertNull(repo.exercise(bench.id)!!.defaultRestSec)

        repo.addExerciseToWorkout(repo.startWorkout(), bench.id, restSec = 150)

        val stored = repo.exercise(bench.id)!!
        assertEquals(150, stored.defaultRestSec)
        // and it is what the next workout would be offered, ahead of anything derived
        assertEquals(150, restHintSec(TimerSettings(), repo.allEvents(), stored.toRef()))
    }

    @Test
    fun `ensureExercise refreshes the rest of an exercise it found but nothing else about it`() = runTest {
        val id = repo.ensureExercise(
            "Hangs 20 mm", ExerciseForm.HOLD, edgeMm = 20.0, workSec = 7.0, restSec = 3.0,
            defaultRestSec = 150,
        )

        // the same exercise looked up again with a different identity and a new rest: the
        // rest is a preference and moves, the identity is §12-A and must not
        val again = repo.ensureExercise("hangs 20 mm", ExerciseForm.STRENGTH, defaultRestSec = 240)
        assertEquals(id, again)

        val stored = repo.exercise(id)!!
        assertEquals(240, stored.defaultRestSec)
        assertEquals(ExerciseForm.HOLD.code, stored.form)
        assertEquals("Hangs 20 mm", stored.name)
        assertEquals(20.0, stored.edgeMm!!, 1e-9)
        assertEquals(7.0, stored.protocolWorkSec!!, 1e-9)

        // and saying nothing about the rest leaves the remembered one alone
        repo.ensureExercise("hangs 20 mm", ExerciseForm.HOLD)
        assertEquals(240, repo.exercise(id)!!.defaultRestSec)
    }

    @Test
    fun `led by protocol is inferred until it is set, and then it is obeyed`() = runTest {
        val hangs = ref("Hangs 20 mm", ExerciseForm.HOLD, work = 7.0, rest = 3.0)
        assertNull(repo.exercise(hangs.id)!!.ledByProtocol)
        assertTrue(ledByProtocol(hangs))

        // the case the column exists for: a maximum-weight hang, protocol and all
        repo.setLedByProtocol(hangs.id, false)
        val stored = repo.exercise(hangs.id)!!
        assertEquals(false, stored.ledByProtocol)
        assertFalse(ledByProtocol(stored.toRef()))
        // the protocol itself is untouched — it is still part of the exercise's identity
        assertEquals(7.0, stored.protocolWorkSec!!, 1e-9)
    }

    @Test
    fun `a backdated workout collects the sets typed into it and stays silent`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val past = "2026-06-01"

        val workoutId = repo.startWorkout(opDate = past, slotId = 7L)
        repo.addExerciseToWorkout(workoutId, bench.id, restSec = 180)
        repo.record(strengthSetOf(bench, past, reps = 5, weightKg = 60.0))

        // it is the workout being worked on right now, even though it happened in June
        assertEquals(workoutId, repo.currentWorkoutId())

        val workout = repo.currentWorkout()!!
        assertEquals(past, workout.opDate)
        assertEquals(7L, workout.slotId)
        assertEquals(1, workout.setCount)
        assertTrue("no countdown may run in a workout from June", workout.isBackdated(day))

        // and it is filed under the day it happened, not the day it was typed
        assertEquals(1, workoutsOn(repo.allEvents(), past).size)
        assertEquals(0, workoutsOn(repo.allEvents(), day).size)
        assertEquals(1, buildSession(repo.allEvents(), past).setCount)
    }

    /**
     * The seam the previous step warned about, closed and pinned.
     *
     * A form built with TODAY's date while a backdated workout is open produces a row the
     * workout claims (it goes by `workout_id`) and the calendar files elsewhere (it reads
     * the payload). Both readings are of the same row, the journal is append-only, and the
     * disagreement is therefore permanent. `loggingDay` is what the caller has to go through
     * to avoid it, so the test writes one set each way and shows the difference.
     */
    @Test
    fun `a set typed into a backdated workout is dated by the workout, not by today`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val past = "2026-06-01"
        val workoutId = repo.startWorkout(opDate = past)

        val open = repo.currentWorkout()
        assertEquals(past, loggingDay(open, day))
        repo.record(strengthSetOf(bench, loggingDay(open, day), reps = 5, weightKg = 60.0))

        val events = repo.allEvents()
        // the workout has it, and so does the day it belongs to
        assertEquals(1, buildWorkout(events, workoutId)!!.setCount)
        assertEquals(1, buildSession(events, past).setCount)
        // and today knows nothing about it — the two views agree
        assertEquals(0, buildSession(events, day).setCount)
        assertEquals(0, setsOutsideWorkouts(events, past).size)

        /*
         * What the old code did, kept as the counter-example: the same write with today's
         * date lands in the workout AND in today's session, so the day it was trained shows
         * one set and the day it was typed shows another. Nothing here is asserting that
         * this is acceptable — it is asserting that it is what `loggingDay` prevents.
         */
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 62.5))
        val after = repo.allEvents()
        assertEquals(2, buildWorkout(after, workoutId)!!.setCount)
        assertEquals(1, buildSession(after, past).setCount)
        assertEquals(1, buildSession(after, day).setCount)
    }

    @Test
    fun `an entry logged on its own is not swallowed by the workout that happens to be open`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val stretching = ref("Stretching", ExerciseForm.STRENGTH)
        val workoutId = repo.startWorkout()

        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        repo.record(strengthSetOf(stretching, day, reps = 1, weightKg = 1.0), attachToWorkout = false)

        assertEquals(1, buildWorkout(repo.allEvents(), workoutId)!!.setCount)
        val loose = setsOutsideWorkouts(repo.allEvents(), day)
        assertEquals(listOf(stretching.id), loose.map { it.form.exerciseId })
    }
}
