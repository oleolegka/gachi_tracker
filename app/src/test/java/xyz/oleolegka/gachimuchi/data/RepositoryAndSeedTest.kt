package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.seed.DemoSeed
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.activeDays
import xyz.oleolegka.gachimuchi.domain.holdRecord
import xyz.oleolegka.gachimuchi.domain.readActivities
import xyz.oleolegka.gachimuchi.domain.strengthRecord
import java.time.LocalDate

/**
 * Room + the repository + the demo seed, exercised by running them rather than by
 * compiling them.
 *
 * Robolectric runs Android code on the JVM, so no emulator is needed for this check.
 * The test uses the REAL Room schema (in-memory), which means it catches both a broken
 * DAO query and a payload serialization that cannot be read back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositoryAndSeedTest {

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
    fun `aliases resolve to an exercise, a conflicting word gets blocked`() = runTest {
        val legPress = repo.ensureExercise("Leg press", ExerciseForm.STRENGTH)
        repo.learnAlias("leg press", legPress)
        repo.learnAlias("leg", legPress)
        assertEquals(legPress, repo.resolveExercise("leg")!!.id)

        // the same first word leads to a different exercise — the word stops being evidence
        val legCurl = repo.ensureExercise("Leg curl", ExerciseForm.STRENGTH)
        repo.learnAlias("leg curl", legCurl)
        assertNull(repo.resolveExercise("leg"))
        assertEquals(legCurl, repo.resolveExercise("leg curl")!!.id)
    }

    @Test
    fun `the demo seed writes history, catalog and slots`() = runTest {
        val today = LocalDate.of(2026, 8, 6)
        val summary = DemoSeed.seed(repo, today = today, days = 90, rngSeed = 20260806)

        assertEquals(12, summary.exercises)
        assertTrue("there should be plenty of events, got ${summary.events}", summary.events > 200)
        assertEquals(6, summary.slots)
        assertEquals(summary.events, repo.eventCount())

        // every event is read back by the domain (the payload matches the form schemas)
        val events = repo.allEvents()
        val activities = readActivities(events)
        assertEquals(events.size, activities.size)

        // the history ends today and starts 90 days earlier
        val days = activities.map { it.opDate }
        assertTrue(days.max() <= today.toString())
        assertTrue(days.min() >= today.minusDays(89).toString())

        // active days are counted without the weigh-ins
        val active = activeDays(events, today.minusDays(89).toString(), today.toString())
        assertTrue("active days: ${active.size}", active.size in 30..80)
    }

    @Test
    fun `the seeded history holds strength and hang records, and they sit on recent dates`() = runTest {
        val today = LocalDate.of(2026, 8, 6)
        DemoSeed.seed(repo, today = today, days = 90, rngSeed = 20260806)
        val activities = readActivities(repo.allEvents())
        val exercises = repo.allExercises()

        val bench = exercises.first { it.name == "Bench press" }
        val benchRecord = strengthRecord(activities, bench.id)
        assertNotNull("the bench press should have a 1RM record", benchRecord)
        // progression is monotonic in sessions, so the peak lands in the last third of the period
        assertTrue(benchRecord!!.opDate >= today.minusDays(30).toString())

        val hangs = exercises.first { it.name == "Hangs 20 mm · 7:3" }
        val hangRecord = holdRecord(activities, hangs.id)
        assertNotNull("the hangs should have an added weight record (§12-A)", hangRecord)
        assertTrue(hangRecord!!.value > 6.0)

        // §12-A: siblings on another edge or protocol are separate exercises with separate histories
        val hangs15 = exercises.first { it.name == "Hangs 15 mm · 7:3" }
        assertTrue(holdRecord(activities, hangs15.id)!!.value < hangRecord.value)
    }

    @Test
    fun `reseeding does not duplicate events`() = runTest {
        val today = LocalDate.of(2026, 8, 6)
        val first = DemoSeed.seed(repo, today = today, days = 30)
        val second = DemoSeed.seed(repo, today = today, days = 30)
        assertEquals(first.events, second.events)
        // the previous demo is removed by its marks before the new one is written, slots
        // included — see DemoDataTest for the rules that removal follows
        assertEquals(second.events, repo.eventCount())
        assertEquals(12, repo.allExercises().size)
        assertEquals(first.slots, repo.allSlots().size)
    }
}
