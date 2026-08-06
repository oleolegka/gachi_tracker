package xyz.oleolegka.gachimuchi.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.data.db.AliasEntity
import xyz.oleolegka.gachimuchi.data.db.ExerciseEntity
import xyz.oleolegka.gachimuchi.data.db.LOCAL_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.SEED_AUTHOR_ID
import xyz.oleolegka.gachimuchi.data.db.SlotEntity
import xyz.oleolegka.gachimuchi.data.seed.demoInventory
import xyz.oleolegka.gachimuchi.data.seed.demoWipePlan
import xyz.oleolegka.gachimuchi.domain.ActivityForm
import xyz.oleolegka.gachimuchi.domain.ExerciseForm
import xyz.oleolegka.gachimuchi.domain.JournalEvent
import xyz.oleolegka.gachimuchi.domain.SetCancel
import xyz.oleolegka.gachimuchi.domain.StrengthSet
import xyz.oleolegka.gachimuchi.domain.TYPE_SET_CANCEL
import xyz.oleolegka.gachimuchi.domain.payloadJson
import xyz.oleolegka.gachimuchi.domain.toPayload

/**
 * Which rows the "remove demo data" button would take, decided on data alone.
 *
 * These are the rules that stand between a delete button and somebody's training history,
 * and they are tested here rather than through the database because what has to be pinned
 * down is the JUDGEMENT, not the SQL: an exercise that looks like the seed's but carries
 * real sets, a slot the user wrote that happens to be called "Gym", a journal in which
 * both kinds of row have been sitting side by side for months.
 *
 * The direction of every assertion is the same: it is fine to leave demo data behind, and
 * it is never fine to take a real record.
 */
class DemoWipePlanTest {

    private var nextId = 1L

    private fun exercise(name: String, seeded: Boolean = false, id: Long = nextId++) =
        ExerciseEntity(
            id = id, name = name, form = ExerciseForm.STRENGTH.code,
            createdAt = "2026-05-01T10:00:00", seeded = seeded,
        )

    private fun set(exerciseId: Long, name: String = "Bench press"): ActivityForm =
        StrengthSet(exercise = name, reps = 5, weightKg = 80.0, exerciseId = exerciseId, opDate = "2026-05-01")

    private fun event(form: ActivityForm, author: Long, id: Long = nextId++) = JournalEvent(
        id = id, ts = "2026-05-01T10:00:00", spaceId = 1, authorId = author,
        type = form.type, payload = form.toPayload(),
    )

    private fun slot(name: String, atTime: String?, rule: String, seeded: Boolean = false) = SlotEntity(
        id = nextId++, name = name, atTime = atTime, repeatRule = rule,
        anchorDate = "2026-05-04", createdAt = "2026-05-01T09:00:00", seeded = seeded,
    )

    // --- the catalog -----------------------------------------------------------------------

    @Test
    fun `a marked exercise with nothing but demo sets on it goes`() {
        val bench = exercise("Bench press", seeded = true)
        val plan = demoWipePlan(
            events = listOf(event(set(bench.id), SEED_AUTHOR_ID)),
            exercises = listOf(bench),
            aliases = emptyList(),
            slots = emptyList(),
        )

        assertEquals(listOf(bench.id), plan.exerciseIds)
        assertEquals(1, plan.eventCount)
        assertTrue(plan.keptExerciseNames.isEmpty())
    }

    @Test
    fun `a marked exercise the user has since recorded against is kept`() {
        val bench = exercise("Bench press", seeded = true)
        val plan = demoWipePlan(
            events = listOf(
                event(set(bench.id), SEED_AUTHOR_ID),
                event(set(bench.id), LOCAL_AUTHOR_ID),
            ),
            exercises = listOf(bench),
            aliases = emptyList(),
            slots = emptyList(),
        )

        /*
         * The demo SETS still go — they are the seed author's and nobody wants them. What
         * cannot go is the catalog row underneath the user's own sets: deleting it would
         * leave real records pointing at an exercise that no longer exists, which no screen
         * can label and no undo can restore.
         */
        assertEquals(1, plan.eventCount)
        assertTrue(plan.exerciseIds.isEmpty())
        assertEquals(listOf("Bench press"), plan.keptExerciseNames)
    }

    @Test
    fun `a CANCELLED real set is still a reason to keep the exercise`() {
        val bench = exercise("Bench press", seeded = true)
        val real = event(set(bench.id), LOCAL_AUTHOR_ID)
        val cancel = JournalEvent(
            id = nextId++, ts = "2026-05-01T11:00:00", spaceId = 1, authorId = LOCAL_AUTHOR_ID,
            type = TYPE_SET_CANCEL, payload = payloadJson.encodeToString(SetCancel(real.id)),
        )

        val plan = demoWipePlan(listOf(real, cancel), listOf(bench), emptyList(), emptyList())

        // the reducers hide a cancelled set; the journal still holds the row, and the row
        // still names its exercise
        assertTrue(plan.exerciseIds.isEmpty())
        assertEquals(listOf("Bench press"), plan.keptExerciseNames)
    }

    @Test
    fun `demo data from a build with no marker is recognised by name`() {
        // exactly the state on a phone that seeded itself before any of this existed
        val bench = exercise("Bench press", seeded = false)
        val plan = demoWipePlan(
            events = listOf(event(set(bench.id), SEED_AUTHOR_ID)),
            exercises = listOf(bench),
            aliases = emptyList(),
            slots = emptyList(),
        )

        assertEquals(listOf(bench.id), plan.exerciseIds)
    }

    @Test
    fun `an unmarked exercise with a demo name is kept once it carries real sets`() {
        val bench = exercise("Bench press", seeded = false)
        val plan = demoWipePlan(
            events = listOf(event(set(bench.id), LOCAL_AUTHOR_ID)),
            exercises = listOf(bench),
            aliases = emptyList(),
            slots = emptyList(),
        )

        // the name is a guess and the journal is evidence; evidence wins
        assertTrue(plan.exerciseIds.isEmpty())
        assertEquals(listOf("Bench press"), plan.keptExerciseNames)
    }

    @Test
    fun `an exercise that is neither marked nor named like the demo is never looked at`() {
        val pullUps = exercise("Weighted pull-ups")
        val plan = demoWipePlan(emptyList(), listOf(pullUps), emptyList(), emptyList())

        assertTrue(plan.exerciseIds.isEmpty())
        assertTrue(plan.keptExerciseNames.isEmpty())
        assertTrue(plan.isEmpty)
    }

    // --- aliases ---------------------------------------------------------------------------

    @Test
    fun `a word goes only when the exercise it means is going`() {
        val doomed = exercise("Squat", seeded = true)
        val kept = exercise("Bench press", seeded = true)

        val plan = demoWipePlan(
            events = listOf(event(set(kept.id), LOCAL_AUTHOR_ID)),
            exercises = listOf(doomed, kept),
            aliases = listOf(
                AliasEntity(key = "squats", value = doomed.id, seeded = true),
                AliasEntity(key = "bench", value = kept.id, seeded = true),
                AliasEntity(key = "my bench", value = kept.id, seeded = false),
            ),
            slots = emptyList(),
        )

        // "squats" loses its meaning with the row it pointed at; both words for the exercise
        // that survives survive with it, including the one the seed wrote
        assertEquals(listOf("squats"), plan.aliasKeys)
    }

    @Test
    fun `a marked word left pointing at nothing is cleaned up`() {
        val plan = demoWipePlan(
            events = emptyList(),
            exercises = emptyList(),
            aliases = listOf(AliasEntity(key = "hang20", value = 404L, seeded = true)),
            slots = emptyList(),
        )

        assertEquals(listOf("hang20"), plan.aliasKeys)
    }

    // --- the plan --------------------------------------------------------------------------

    @Test
    fun `demo slots go, by mark and by shape, and the user's plan stays`() {
        val slots = listOf(
            slot("Hangboard", "20:00", "weekly", seeded = true),
            // written by an older build: no mark, but exactly the seed's shape
            slot("Gym", "18:00", "weekly", seeded = false),
            // the user's own: the same name at their own time, which is the collision the
            // shape match has to survive
            slot("Gym", "19:30", "weekly", seeded = false),
            slot("Physio", null, "none", seeded = false),
        )

        val plan = demoWipePlan(emptyList(), emptyList(), emptyList(), slots)

        assertEquals(2, plan.slotCount)
        val doomed = plan.slotIds.toSet()
        val survivors = slots.filterNot { it.id in doomed }
        assertEquals(listOf("Gym", "Physio"), survivors.map { it.name })
        assertEquals(listOf("19:30", null), survivors.map { it.atTime })
    }

    // --- what the user is told -------------------------------------------------------------

    @Test
    fun `the inventory counts what is going, in words, and says nothing when nothing is`() {
        val bench = exercise("Bench press", seeded = true)
        val plan = demoWipePlan(
            events = listOf(event(set(bench.id), SEED_AUTHOR_ID), event(set(bench.id), SEED_AUTHOR_ID)),
            exercises = listOf(bench),
            aliases = emptyList(),
            slots = listOf(slot("Gym", "18:00", "weekly", seeded = true)),
        )

        assertEquals("2 journal entries, 1 exercise and 1 planned session", demoInventory(plan))
        assertFalse(plan.isEmpty)

        val nothing = demoWipePlan(emptyList(), emptyList(), emptyList(), emptyList())
        assertTrue(nothing.isEmpty)
        assertEquals("", demoInventory(nothing))
    }
}
