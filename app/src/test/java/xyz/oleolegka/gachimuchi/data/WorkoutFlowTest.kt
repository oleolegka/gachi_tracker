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
import xyz.oleolegka.gachimuchi.domain.PlannedExercise
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TimerSettings
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.ledByProtocol
import xyz.oleolegka.gachimuchi.domain.loggingDay
import xyz.oleolegka.gachimuchi.domain.restHintSec
import xyz.oleolegka.gachimuchi.domain.setsOutsideWorkouts
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.toDraft
import xyz.oleolegka.gachimuchi.domain.withExerciseAdded
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
    fun `finishing closes the workout, and the next set belongs to nothing`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val workoutId = repo.startWorkout()
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))

        repo.finishWorkout(workoutId)

        assertNull(repo.currentWorkoutId())
        assertTrue(buildWorkout(repo.allEvents(), workoutId)!!.finished)
        // and logging still works with nothing open, exactly as it does for somebody who
        // never presses start at all
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 62.5))
        assertEquals(1, buildWorkout(repo.allEvents(), workoutId)!!.setCount)
        assertEquals(1, setsOutsideWorkouts(repo.allEvents(), day).size)
    }

    /** Nobody presses "finish" reliably, so starting the next one does it for them. */
    @Test
    fun `starting a workout quietly finishes the one that was left open`() = runTest {
        val morning = repo.startWorkout()
        val evening = repo.startWorkout()

        assertTrue(buildWorkout(repo.allEvents(), morning)!!.finished)
        assertFalse(buildWorkout(repo.allEvents(), evening)!!.finished)
        assertEquals(evening, repo.currentWorkoutId())
    }

    /**
     * The set remembered on the way to the car. "Finished" is a status and not a lock, so a
     * screen that names the workout it is drawing gets the set into it — which is not where
     * "the open workout" would have put it, because there is not one.
     */
    @Test
    fun `a set can be added to a finished workout by naming it`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val workoutId = repo.startWorkout()
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))
        repo.finishWorkout(workoutId)

        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 62.5), intoWorkoutId = workoutId)

        val workout = buildWorkout(repo.allEvents(), workoutId)!!
        assertEquals(2, workout.setCount)
        assertTrue("adding a set does not re-open it", workout.finished)
        assertEquals(0, setsOutsideWorkouts(repo.allEvents(), day).size)
        // and the end moved with the set, because it is read off the last one recorded
        assertEquals(repo.allEvents().last().ts, workout.endTs)
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

    // --- a workout started from a plan (§13.7) --------------------------------------------

    private suspend fun gymSlot(exercises: List<PlannedExercise>): Long = repo.saveSlot(
        SlotDraft(
            name = "Gym", timeText = "18:00", repeatRule = REPEAT_WEEKLY,
            anchorDate = day, exercises = exercises,
        )
    )!!

    /**
     * The whole of §13.7: a workout started from a plan arrives with the plan in it, so the
     * screen it opens on is the list of what the session is meant to be rather than a note
     * saying there is nothing here yet.
     */
    @Test
    fun `a workout started from a plan arrives with the plan's exercises in it`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val squat = ref("Squat", ExerciseForm.STRENGTH)
        val slotId = gymSlot(
            listOf(PlannedExercise(bench.id, restSec = 180), PlannedExercise(squat.id, restSec = 240))
        )

        val workoutId = repo.startWorkout(day, slotId)
        repo.copyPlannedExercises(workoutId, slotId, TimerSettings())

        val workout = buildWorkout(repo.allEvents(), workoutId)!!
        // in the plan's order, which is the order the session is meant to be done in
        assertEquals(listOf(bench.id, squat.id), workout.exercises.map { it.exerciseId })
        assertEquals(listOf(180, 240), workout.exercises.map { it.restSec })
        // and every one of them is empty, which is the normal early state and not a defect
        assertTrue(workout.exercises.all { it.isEmpty })
    }

    /**
     * A COPY and not a reference. §13.7 fixes this on the principle that the plan is editable
     * and the facts are not: rewriting the slot next month must leave a workout already done
     * exactly as it was.
     */
    @Test
    fun `editing the plan afterwards does not change a workout already started from it`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val squat = ref("Squat", ExerciseForm.STRENGTH)
        val slotId = gymSlot(listOf(PlannedExercise(bench.id, restSec = 180)))

        val workoutId = repo.startWorkout(day, slotId)
        repo.copyPlannedExercises(workoutId, slotId, TimerSettings())

        repo.saveSlot(repo.slot(slotId)!!.toDraft().withExerciseAdded(squat.id, restSec = 240), id = slotId)

        val workout = buildWorkout(repo.allEvents(), workoutId)!!
        assertEquals(listOf(bench.id), workout.exercises.map { it.exerciseId })
    }

    /**
     * Which rest wins, the question §13.8 left open: the plan's, when it names one, because a
     * rest written next to an exercise in a planned session is a statement about that session.
     * Otherwise what the exercise itself remembers.
     */
    @Test
    fun `the plan's rest beats the remembered one, and its absence falls back to it`() = runTest {
        val bench = ref("Bench press", ExerciseForm.STRENGTH)
        val squat = ref("Squat", ExerciseForm.STRENGTH)
        repo.setDefaultRest(bench.id, 90)
        repo.setDefaultRest(squat.id, 210)
        val slotId = gymSlot(
            // the plan overrules the 90 remembered on the bench; the squat says nothing
            listOf(PlannedExercise(bench.id, restSec = 180), PlannedExercise(squat.id))
        )

        val workoutId = repo.startWorkout(day, slotId)
        repo.copyPlannedExercises(workoutId, slotId, TimerSettings())

        assertEquals(
            listOf(180, 210),
            buildWorkout(repo.allEvents(), workoutId)!!.exercises.map { it.restSec },
        )
    }

    /** A slot with nothing in it is a perfectly good plan, and starts a perfectly empty workout. */
    @Test
    fun `a plan with no exercises starts an empty workout, as it always did`() = runTest {
        val slotId = gymSlot(emptyList())

        val workoutId = repo.startWorkout(day, slotId)
        assertEquals(emptyList<Long>(), repo.copyPlannedExercises(workoutId, slotId, TimerSettings()))
        assertTrue(buildWorkout(repo.allEvents(), workoutId)!!.isEmpty)
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

    // --- the plan a workout was started from, and the name it was started under -----------

    @Test
    fun `starting from a plan records the plan's identity and its name as a snapshot`() = runTest {
        val slotId = repo.createSlot("Gym", atTime = "19:00", repeatRule = REPEAT_WEEKLY, anchorDate = day)
        val slot = repo.slot(slotId)!!

        // the id first: the argument list is evaluated left to right, so reading the events
        // before the workout is started would fold a journal that does not contain it yet
        val workoutId = repo.startWorkout(slotId = slotId)
        val workout = buildWorkout(repo.allEvents(), workoutId)!!

        assertEquals("Gym", workout.name)
        assertTrue("the workout lost the plan it was started from", workout.slot!!.matches(slot.link))
        assertEquals(slot.uid, workout.slot!!.uid)
        assertEquals(slotId, workout.slotId)
    }

    @Test
    fun `renaming a plan afterwards leaves the workouts already started from it alone`() = runTest {
        val slotId = repo.createSlot("Gym", atTime = "19:00", repeatRule = REPEAT_WEEKLY, anchorDate = day)
        val workoutId = repo.startWorkout(slotId = slotId)

        repo.saveSlot(repo.slot(slotId)!!.toDraft().copy(name = "Powerlifting"), id = slotId)

        // the plan is editable and the facts are not: what the session was called on the day
        // is a fact about that day
        assertEquals("Powerlifting", repo.slot(slotId)!!.name)
        assertEquals("Gym", buildWorkout(repo.allEvents(), workoutId)!!.name)
    }

    @Test
    fun `a workout started off-plan names no plan and carries no name`() = runTest {
        val workoutId = repo.startWorkout()
        val workout = buildWorkout(repo.allEvents(), workoutId)!!

        assertNull(workout.slot)
        assertNull(workout.name)
    }

    @Test
    fun `a name the caller passes wins over the plan's own`() = runTest {
        val slotId = repo.createSlot("Gym", atTime = "19:00", repeatRule = REPEAT_WEEKLY, anchorDate = day)

        val workoutId = repo.startWorkout(slotId = slotId, name = "Deadlift day")

        assertEquals("Deadlift day", buildWorkout(repo.allEvents(), workoutId)!!.name)
    }

    @Test
    fun `a plan number naming nothing in this database writes no identity and no name`() = runTest {
        // the slot was deleted between the screen reading it and the button being pressed
        val workoutId = repo.startWorkout(slotId = 404L)
        val workout = buildWorkout(repo.allEvents(), workoutId)!!

        assertNull(workout.name)
        assertNull(workout.slot!!.uid)
        assertEquals(404L, workout.slotId)
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
