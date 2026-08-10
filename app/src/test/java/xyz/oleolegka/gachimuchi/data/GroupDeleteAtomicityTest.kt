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
import xyz.oleolegka.gachimuchi.domain.workoutEventIds
import java.time.LocalDate

/**
 * [ActivityRepository.deleteEntries] — the transaction closing the gap
 * [xyz.oleolegka.gachimuchi.ui.MainViewModel.deleteWorkout] and
 * [xyz.oleolegka.gachimuchi.ui.MainViewModel.deleteEntries] used to leave open: a loop of
 * [ActivityRepository.deleteEntry] calls with nothing tying the inserts together, so a process
 * dying between two of them left the journal holding some of a workout's rows and not others.
 *
 * The interruption is simulated the way the task asks — an exception thrown from the middle of
 * the batch — by handing the batch an id [ActivityRepository.deleteEntry] cannot resolve
 * ([deleteEntries] fails loudly rather than silently skipping it, exactly so this is possible to
 * provoke without a mock). What is being checked is not that the throw happens; it is that
 * NOTHING landed because of it — the ids ahead of the bad one in the list resolved cleanly and
 * would have committed under the old, un-transacted loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class GroupDeleteAtomicityTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

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

    @Test
    fun `a batch interrupted by a bad id in the middle leaves the journal exactly as it was`() = runTest {
        val benchId = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val workoutId = repo.startWorkout(day)
        val setA = repo.record(
            StrengthSet(exercise = "Bench press", reps = 5, weightKg = 60.0, exerciseId = benchId, opDate = day)
        )
        val setB = repo.record(
            StrengthSet(exercise = "Bench press", reps = 5, weightKg = 60.0, exerciseId = benchId, opDate = day)
        )
        val realIds = workoutEventIds(repo.allEvents(), workoutId)
        assertEquals("the start row plus the two sets", 3, realIds.size)

        val eventsBefore = repo.allEvents()
        val bogusId = eventsBefore.maxOf { it.id } + 1000L // a numeric id this journal never handed out

        // the first two of these resolve fine and would have committed under a plain forEach —
        // the whole point of the test is that they must NOT, once the third one fails
        val poisoned = listOf(realIds[0], realIds[1], bogusId, realIds[2])

        val outcome = runCatching { repo.deleteEntries(poisoned) }

        assertTrue("deleteEntries must fail loudly on an id it cannot resolve", outcome.isFailure)

        // --- nothing landed: not even the rows ahead of the bad id in the batch ---
        val eventsAfter = repo.allEvents()
        assertEquals(
            "no deletion event should have been appended at all",
            eventsBefore.size,
            eventsAfter.size,
        )
        assertEquals(eventsBefore.map { it.id }, eventsAfter.map { it.id })

        // and the workout is readable exactly as it was before the attempt — not half gone
        val stillThere = readActivities(eventsAfter).map { it.id }.toSet()
        assertTrue(setA in stillThere)
        assertTrue(setB in stillThere)
        assertEquals(3, workoutEventIds(eventsAfter, workoutId).size)
    }

    @Test
    fun `a clean batch with no bad id deletes every row named, together`() = runTest {
        val benchId = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        val workoutId = repo.startWorkout(day)
        repo.record(StrengthSet(exercise = "Bench press", reps = 5, weightKg = 60.0, exerciseId = benchId, opDate = day))
        repo.record(StrengthSet(exercise = "Bench press", reps = 5, weightKg = 60.0, exerciseId = benchId, opDate = day))
        val realIds = workoutEventIds(repo.allEvents(), workoutId)

        val written = repo.deleteEntries(realIds)

        assertEquals(realIds.size, written.size)
        assertTrue(
            "the workout and every set it held are gone from the live reading",
            readActivities(repo.allEvents()).isEmpty(),
        )
        assertTrue(workoutEventIds(repo.allEvents(), workoutId).isEmpty())
    }
}
