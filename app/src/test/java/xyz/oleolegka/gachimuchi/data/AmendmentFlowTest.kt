package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.EventEntity
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.ExerciseLink
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TYPE_STRENGTH_SET
import xyz.oleolegka.gachimuchi.domain.buildSession
import xyz.oleolegka.gachimuchi.domain.buildWorkout
import xyz.oleolegka.gachimuchi.domain.openWorkoutRow
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.strengthSetOf
import xyz.oleolegka.gachimuchi.domain.strengthSetsOfExercise
import xyz.oleolegka.gachimuchi.domain.toPayload
import xyz.oleolegka.gachimuchi.domain.workoutsOn
import java.time.LocalDate

/**
 * Correcting and removing entries through the real database.
 *
 * These overlap the pure tests in domain/ on purpose, and the overlap is the point: the domain
 * tests hand-build the amendment events, so they prove the FOLD is right and prove nothing
 * about whether the repository writes an event the fold can read. Going through Room is what
 * catches a target uid that never reached the payload, a patch that was serialized under the
 * wrong key, and a refusal that was documented and not implemented.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AmendmentFlowTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    /**
     * The real current date, because the repository resolves the open workout against the
     * clock. Pinning a literal day here would make the test pass only on that day.
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

    private suspend fun ref(name: String, form: ExerciseForm = ExerciseForm.STRENGTH) =
        repo.exercise(repo.ensureExercise(name, form))!!.toRef()

    private fun fields(vararg pairs: Pair<String, Any>) = JsonObject(
        pairs.associate { (key, value) ->
            key to when (value) {
                is Int -> JsonPrimitive(value)
                is Double -> JsonPrimitive(value)
                else -> JsonPrimitive(value.toString())
            }
        }
    )

    // --- correcting a set ---

    @Test
    fun `a set corrected through the repository reads corrected everywhere`() = runTest {
        val bench = ref("Bench press")
        val eventId = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), attachToWorkout = false)

        val newId = repo.amendEntry(eventId, fields("reps" to 8, "weight_kg" to 62.5))
        assertNotNull(newId)
        assertNotEquals("the correction is a NEW row, not the old one rewritten", eventId, newId)

        val set = strengthSetsOfExercise(repo.allEvents(), bench.link).single()
        assertEquals(8, set.reps)
        assertEquals(62.5, set.weightKg!!, 1e-9)
        // the original, the new full version and the marker superseding the original: three rows
        assertEquals(3, repo.eventCount())
    }

    /**
     * THE regression this occurred_ts field exists to close. Without it, correcting the first
     * set would append its new version at the END of the journal — after the second and third
     * sets, which were written later — and the session screen (drawing sets in journal order)
     * would show it last. [ActivityRepository.amendEntry] carries the ORIGINAL set's
     * occurred_ts onto the new version instead of stamping the correction's own moment, and
     * [buildSession] sorts by it, so the display order is unmoved by an edit however long
     * after the fact it happens.
     *
     * The three sets are inserted with explicit, minutes-apart `ts` (rather than through
     * [repo.record], which stamps the real clock) so the test is not at the mercy of three
     * calls happening to land in the same wall-clock second, which would make `happenedAt` tie
     * between them and prove nothing about the ordering this test exists to check.
     */
    @Test
    fun `correcting the first of three sets does not move it in the session's display order`() = runTest {
        val bench = ref("Bench press")
        val firstId = db.events().insert(
            EventEntity(
                ts = "${day}T10:00:00", type = TYPE_STRENGTH_SET,
                payload = strengthSetOf(bench, day, reps = 5, weightKg = 60.0).toPayload(),
            )
        )
        db.events().insert(
            EventEntity(
                ts = "${day}T10:05:00", type = TYPE_STRENGTH_SET,
                payload = strengthSetOf(bench, day, reps = 5, weightKg = 62.5).toPayload(),
            )
        )
        db.events().insert(
            EventEntity(
                ts = "${day}T10:10:00", type = TYPE_STRENGTH_SET,
                payload = strengthSetOf(bench, day, reps = 5, weightKg = 65.0).toPayload(),
            )
        )

        // a typo fixed well after the fact - the third set is already in the journal by now
        repo.amendEntry(firstId, fields("reps" to 8))

        val weights = buildSession(repo.allEvents(), day).groups.single().sets.map { (it.form as StrengthSet).weightKg }
        assertEquals(listOf(60.0, 62.5, 65.0), weights)
        // and the correction itself did land - this is not a test of the edit failing silently
        val corrected = buildSession(repo.allEvents(), day).groups.single().sets.first()
        assertEquals(8, (corrected.form as StrengthSet).reps)
    }

    @Test
    fun `a whole form can be handed in, exercise and all - moving a set is a deletion and a new entry now`() = runTest {
        val bench = ref("Bench press")
        val squat = ref("Squat")
        val eventId = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), attachToWorkout = false)

        // the editor never builds a candidate naming a different exercise (EntryEditorDialog),
        // but the repository itself no longer refuses one: a full rewrite protects nothing an
        // old patch onto the SAME row's identity needed protecting, and this is now exactly the
        // "delete it, log it again correctly" act TYPE_ENTRY_AMENDED's own KDoc always asked for
        repo.amendEntry(eventId, strengthSetOf(squat, day, reps = 3, weightKg = 90.0))

        val events = repo.allEvents()
        assertTrue("the original stays under its own exercise, superseded rather than rewritten", strengthSetsOfExercise(events, bench.link).isEmpty())
        val set = strengthSetsOfExercise(events, squat.link).single()
        assertEquals(3, set.reps)
        assertEquals(90.0, set.weightKg!!, 1e-9)
        assertEquals("Squat", set.exercise)
        // the original is still there, unread but not gone - includeDeleted still finds it
        assertEquals(2, readActivities(events, includeDeleted = true).size)
    }

    @Test
    fun `patching just the exercise id through the JsonObject overload is no longer refused either`() = runTest {
        val bench = ref("Bench press")
        val eventId = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), attachToWorkout = false)

        // nothing this app ships does this (recordActualRest and renameWorkout never touch an
        // exercise or workout key) - it is exercised here only to document that the check is
        // gone, on purpose, per the model change: see amendEntry's own KDoc for the risk this
        // narrow overload still carries, unlike the whole-form one above
        val newId = repo.amendEntry(eventId, fields("exercise_id" to 99))
        assertNotNull(newId)
        assertEquals(3, repo.eventCount())
    }

    @Test
    fun `an amendment that would make the entry unreadable is refused`() = runTest {
        val bench = ref("Bench press")
        val eventId = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), attachToWorkout = false)

        // zero reps fails the form's own validator, and a reader skips what will not parse -
        // so accepting this would be a deletion arriving disguised as a correction
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repo.amendEntry(eventId, fields("reps" to 0)) }
        }
        // a date that is not a date, same reasoning
        assertThrows(Exception::class.java) {
            kotlinx.coroutines.runBlocking { repo.amendEntry(eventId, fields("op_date" to "yesterday")) }
        }
        assertEquals(1, repo.eventCount())
        assertEquals(5, strengthSetsOfExercise(repo.allEvents(), bench.link).single().reps)
    }

    @Test
    fun `correcting or deleting an event that is not there answers null`() = runTest {
        assertNull(repo.deleteEntry(4242))
        assertNull(repo.amendEntry(4242, fields("reps" to 8)))
    }

    // --- deleting a set ---

    @Test
    fun `a deleted set leaves the readings and stays in the table`() = runTest {
        val bench = ref("Bench press")
        val kept = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), attachToWorkout = false)
        val gone = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 80.0), attachToWorkout = false)

        assertNotNull(repo.deleteEntry(gone))

        val events = repo.allEvents()
        assertEquals(1, strengthSetsOfExercise(events, bench.link).size)
        assertEquals(1, buildSession(events, day).setCount)
        assertEquals(kept, buildSession(events, day).lastEventId)
        assertEquals("nothing is ever removed from the table", 3, repo.eventCount())
        assertEquals(2, readActivities(events, includeDeleted = true).size)
    }

    @Test
    fun `deleting the deletion brings the set back`() = runTest {
        val bench = ref("Bench press")
        val setId = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), attachToWorkout = false)

        val deletion = repo.deleteEntry(setId)!!
        assertTrue(strengthSetsOfExercise(repo.allEvents(), bench.link).isEmpty())

        // there is no "restore": the deletion is an event, and removing it is how it is undone
        repo.deleteEntry(deletion)
        assertEquals(1, strengthSetsOfExercise(repo.allEvents(), bench.link).size)
    }

    @Test
    fun `the older cancelSet and the new deleteEntry mean the same thing`() = runTest {
        val bench = ref("Bench press")
        val a = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), attachToWorkout = false)
        val b = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 70.0), attachToWorkout = false)

        repo.cancelSet(a)
        repo.deleteEntry(b)
        assertTrue(strengthSetsOfExercise(repo.allEvents(), bench.link).isEmpty())
    }

    @Test
    fun `a set cancelled the old way can be brought back the new way`() = runTest {
        val bench = ref("Bench press")
        val setId = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0), attachToWorkout = false)
        val cancelId = repo.cancelSet(setId)

        // the hole this closes: cancelling a cancellation used to do nothing at all
        repo.deleteEntry(cancelId)
        assertEquals(1, strengthSetsOfExercise(repo.allEvents(), bench.link).size)
    }

    // --- the service events, which had no way to be undone before ---

    @Test
    fun `a workout started by mistake can be deleted and its sets survive it`() = runTest {
        val bench = ref("Bench press")
        val workoutId = repo.startWorkout()
        repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))

        assertEquals(1, workoutsOn(repo.allEvents(), day).size)

        repo.deleteEntry(workoutId)
        val events = repo.allEvents()
        assertTrue(workoutsOn(events, day).isEmpty())
        assertNull(buildWorkout(events, workoutId))
        assertNull(openWorkoutRow(events))
        // the set was training and is not touched by removing the container it was in
        assertEquals(1, buildSession(events, day).setCount)
    }

    @Test
    fun `an exercise added to a workout by mistake can be deleted out of it`() = runTest {
        val bench = ref("Bench press")
        val squat = ref("Squat")
        val workoutId = repo.startWorkout()
        repo.addExerciseToWorkout(workoutId, bench.id, restSec = 120)
        val wrong = repo.addExerciseToWorkout(workoutId, squat.id, restSec = 90)

        assertEquals(2, buildWorkout(repo.allEvents(), workoutId)!!.exercises.size)

        repo.deleteEntry(wrong)
        val left = buildWorkout(repo.allEvents(), workoutId)!!.exercises.single()
        assertEquals(bench.id, left.exerciseId)
    }

    @Test
    fun `the rest chosen for an exercise in a workout can be corrected`() = runTest {
        val bench = ref("Bench press")
        val workoutId = repo.startWorkout()
        val added = repo.addExerciseToWorkout(workoutId, bench.id, restSec = 90)

        repo.amendEntry(added, fields("rest_sec" to 150))
        assertEquals(150, buildWorkout(repo.allEvents(), workoutId)!!.exercises.single().restSec)
    }

    @Test
    fun `a workout started on the wrong day can be corrected onto the right one`() = runTest {
        val yesterday = LocalDate.now().minusDays(1).toString()
        val workoutId = repo.startWorkout()

        repo.amendEntry(workoutId, fields("op_date" to yesterday))
        val events = repo.allEvents()
        assertTrue(workoutsOn(events, day).isEmpty())
        assertEquals(1, workoutsOn(events, yesterday).size)
        assertEquals(yesterday, buildWorkout(events, workoutId)!!.opDate)
    }

    @Test
    fun `a workout can be renamed by correcting the event that named it`() = runTest {
        val workoutId = repo.startWorkout(name = "Gym")
        assertEquals("Gym", buildWorkout(repo.allEvents(), workoutId)!!.name)

        repo.amendEntry(workoutId, fields("name" to "Fingerboard"))
        assertEquals("Fingerboard", buildWorkout(repo.allEvents(), workoutId)!!.name)
    }

    @Test
    fun `finishing a workout by mistake can be undone by deleting the finish`() = runTest {
        val workoutId = repo.startWorkout()
        val finishId = repo.finishWorkout(workoutId)

        assertNull("a finished workout is not the open one", openWorkoutRow(repo.allEvents()))
        // the finish is an event like any other, so there is no separate re-open button
        repo.deleteEntry(finishId)
        assertEquals(workoutId, openWorkoutRow(repo.allEvents())!!.id)
    }

    @Test
    fun `a set logged inside a workout can be corrected without leaving it`() = runTest {
        val bench = ref("Bench press")
        val workoutId = repo.startWorkout()
        val setId = repo.record(strengthSetOf(bench, day, reps = 5, weightKg = 60.0))

        repo.amendEntry(setId, fields("weight_kg" to 65.0))

        val workout = buildWorkout(repo.allEvents(), workoutId)!!
        assertEquals(1, workout.setCount)
        val set = workout.exercises.single().sets.single().form as StrengthSet
        assertEquals(65.0, set.weightKg!!, 1e-9)
        assertEquals(ExerciseLink(bench.uid, bench.id).key, workout.exercises.single().exercise.key)
    }
}
