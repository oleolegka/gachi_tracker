package xyz.oleolegka.gachimuchi.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.data.db.AppDatabase
import xyz.oleolegka.gachimuchi.data.db.SEED_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.seed.DemoSeed
import xyz.oleolegka.gachimuchi.data.seed.planDemoWipe
import xyz.oleolegka.gachimuchi.data.seed.removeDemoData
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.REPEAT_WEEKLY
import xyz.oleolegka.gachimuchi.domain.SlotDraft
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.readActivities
import java.time.LocalDate

/**
 * Writing the demo data and taking it back out, through the real database.
 *
 * The rules themselves are pinned down in DemoWipePlanTest. What only a database can show
 * is that the two halves agree: everything the seed creates is something the wipe can find,
 * and everything the wipe leaves behind is what the user had before the seed ran. The
 * property being defended is a round trip — journal, catalog, aliases and plan back to
 * where they started, with the user's own records untouched in the middle of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DemoDataTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: ActivityRepository

    private val today = LocalDate.of(2026, 8, 6)

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
    fun `everything the seed writes is marked as the seed's`() = runTest {
        DemoSeed.seed(repo, today = today, days = 30)

        assertTrue(repo.allExercises().all { it.seeded })
        assertTrue(repo.allSlotRows().all { it.seeded })
        assertTrue(repo.allAliases().all { it.seeded })
        assertTrue(repo.allEvents().all { it.authorId == SEED_AUTHOR_ID })
    }

    @Test
    fun `seeding and then removing leaves the database as it was found`() = runTest {
        DemoSeed.seed(repo, today = today, days = 30)
        assertTrue(repo.eventCount() > 0)

        val plan = removeDemoData(repo)

        assertTrue("nothing should be left: ${repo.allEvents().size} events", repo.eventCount() == 0)
        assertTrue(repo.allExercises().isEmpty())
        assertTrue(repo.allSlotRows().isEmpty())
        assertTrue(repo.allAliases().isEmpty())
        assertTrue(plan.eventCount > 0)
        assertEquals(12, plan.exerciseCount)
        assertEquals(6, plan.slotCount)
    }

    @Test
    fun `a removal takes the demo and leaves the user's own records, plan and words`() = runTest {
        // the user's phone before the demo: one exercise, one set, one word, one session
        val pullUps = repo.ensureExercise("Weighted pull-ups", ExerciseForm.STRENGTH)
        repo.learnAlias("pull-ups", pullUps)
        repo.record(
            StrengthSet(
                exercise = "Weighted pull-ups", reps = 5, addedKg = 12.0, ownWeight = true,
                exerciseId = pullUps, opDate = "2026-08-05",
            )
        )
        val mySlot = repo.createSlot("Gym", "19:30", REPEAT_WEEKLY, "2026-08-03")

        DemoSeed.seed(repo, today = today, days = 30)
        removeDemoData(repo)

        val events = repo.allEvents()
        assertEquals(1, events.size)
        assertEquals(5, (readActivities(events).single().form as StrengthSet).reps)
        assertEquals(listOf("Weighted pull-ups"), repo.allExercises().map { it.name })
        assertEquals(listOf(mySlot), repo.allSlotRows().map { it.id })
        assertNotNull("the word the user taught still leads somewhere", repo.resolveExercise("pull-ups"))
    }

    @Test
    fun `an exercise the user has adopted survives the removal, with its history`() = runTest {
        DemoSeed.seed(repo, today = today, days = 30)
        val bench = repo.allExercises().first { it.name == "Bench press" }

        // the user carries on using an exercise the demo created, which is the whole reason
        // the wipe cannot simply delete everything it once wrote
        repo.record(
            StrengthSet(
                exercise = "Bench press", reps = 3, weightKg = 95.0,
                exerciseId = bench.id, opDate = "2026-08-06",
            )
        )

        val plan = removeDemoData(repo)

        assertTrue("Bench press" in plan.keptExerciseNames)
        val survivor = repo.allExercises().single { it.name == "Bench press" }
        assertEquals(bench.id, survivor.id)
        // it stops being demo data, so the next removal does not come back for it
        assertFalse(survivor.seeded)
        assertEquals(1, repo.eventCount())
        assertEquals(95.0, (readActivities(repo.allEvents()).single().form as StrengthSet).weightKg!!, 1e-9)

        assertTrue(planDemoWipe(repo).isEmpty)
    }

    @Test
    fun `the seed never repoints a word the user taught`() = runTest {
        val pullUps = repo.ensureExercise("Bench press", ExerciseForm.STRENGTH)
        repo.learnAlias("bench", pullUps)

        DemoSeed.seed(repo, today = today, days = 30)

        // the catalog deduplicates by name, so the demo reuses the user's row rather than
        // making a second one; the word still means what it meant, and the row is not marked
        assertEquals(pullUps, repo.resolveExercise("bench")!!.id)
        assertFalse(repo.allExercises().single { it.name == "Bench press" }.seeded)
        assertFalse(repo.allAliases().single { it.key == "bench" }.seeded)
    }

    @Test
    fun `seeding twice leaves one demo, not two`() = runTest {
        val first = DemoSeed.seed(repo, today = today, days = 30)
        val second = DemoSeed.seed(repo, today = today, days = 30)

        assertEquals(first.events, second.events)
        assertEquals(second.events, repo.eventCount())
        assertEquals(12, repo.allExercises().size)
        // the slots used to be the leak here: with no mark on them the seed had to skip
        // them entirely on a second run, and a phone that already had a plan never got one
        assertEquals(first.slots, repo.allSlotRows().size)
    }

    @Test
    fun `editing a demo session makes it the user's, and the removal respects that`() = runTest {
        DemoSeed.seed(repo, today = today, days = 30)
        val hangboard = repo.allSlotRows().first { it.name == "Hangboard" }

        repo.saveSlot(
            SlotDraft(
                name = "Hangboard", timeText = "21:15", repeatRule = REPEAT_WEEKLY,
                anchorDate = hangboard.anchorDate,
            ),
            id = hangboard.id,
        )
        removeDemoData(repo)

        val survivor = repo.allSlotRows().singleOrNull()
        assertNotNull("a session the user has edited is theirs and must survive", survivor)
        assertEquals("21:15", survivor!!.atTime)
        assertFalse(survivor.seeded)
    }
}
