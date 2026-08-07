package xyz.oleolegka.gachimuchi.data

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
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.readActivities

/**
 * Room + the repository, exercised by running them rather than by compiling them.
 *
 * Robolectric runs Android code on the JVM, so no emulator is needed for this check.
 * The test uses the REAL Room schema (in-memory), which means it catches both a broken
 * DAO query and a payload serialization that cannot be read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        repo = ActivityRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `writing a form and reading the journal — a round-trip through the database`() = runTest {
        val id = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val eventId = repo.record(
            StrengthSet(exercise = "Bench press", reps = 5, weightKg = 62.5, exerciseId = id, opDate = "2026-08-06")
        )
        val events = repo.allEvents()
        assertEquals(1, events.size)
        val form = readActivities(events).single().form as StrengthSet
        assertEquals(62.5, form.weightKg!!, 1e-9)
        assertEquals(id, form.exerciseId)
        assertTrue(eventId > 0)
    }

    @Test
    fun `cancelling a set keeps the event but drops it from the reducers`() = runTest {
        val id = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val eventId = repo.record(
            StrengthSet(exercise = "Bench press", reps = 5, weightKg = 60.0, exerciseId = id, opDate = "2026-08-06")
        )
        repo.cancelSet(eventId)
        val events = repo.allEvents()
        assertEquals(2, events.size) // the set plus the reversal, the journal is append-only
        assertEquals(0, readActivities(events).size)
    }

    @Test
    fun `exercises are deduplicated by normalized name`() = runTest {
        val a = repo.ensureExercise("Squat", ExerciseForm.STRENGTH)
        val b = repo.ensureExercise("squat ", ExerciseForm.STRENGTH)
        assertEquals(a, b)
        assertEquals(1, repo.allExercises().size)
    }

    @Test
    fun `an exercise found by name keeps its identity and only picks up the rest`() = runTest {
        val id = repo.ensureExercise(
            "Hangs 20 mm", ExerciseForm.HOLD, edgeMm = 20.0, workSec = 7.0, restSec = 3.0,
        )

        // the second call describes the same exercise differently — and is ignored, except
        // for the rest, which is a preference and not identity (§12-A)
        val again = repo.ensureExercise(
            "hangs 20 mm", ExerciseForm.STRENGTH, edgeMm = 15.0, defaultRestSec = 150,
        )

        assertEquals(id, again)
        val stored = repo.exercise(id)!!
        assertEquals(ExerciseForm.HOLD.code, stored.form)
        assertEquals(20.0, stored.edgeMm!!, 1e-9)
        assertEquals(150, stored.defaultRestSec)
    }
}
