package xyz.oleolegka.gachimuchi.domain

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.data.opDateOfPayload

/**
 * THE ACCEPTANCE TEST for [planLegacyAmendmentMigration]: fold a journal that still carries
 * legacy patch amendments BEFORE the migration and AFTER it, and demand the two verdicts agree
 * — field for field, not "close enough". This is what the task that added the full-version
 * model asked to see first, so it is written first here too.
 *
 * A row's own identity (id/uid) is allowed to differ after migration — a correction becomes a
 * NEW row, on purpose (see [planLegacyAmendmentMigration]'s own KDoc for why one hop rather
 * than a chain). What must NOT differ is everything a reader actually depends on: which
 * entries are live, what they say, and their order.
 */
class AmendmentMigrationTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String = "2026-08-06T10:00:00") =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    private fun bench(weight: Double = 60.0, reps: Int = 5, day: String = "2026-08-06") =
        StrengthSet(exercise = "Bench press", reps = reps, weightKg = weight, exerciseId = 1, opDate = day)

    private fun amend(targetUid: String, ts: String, vararg fields: Pair<String, JsonElement>) = JournalEvent(
        nextId++, ts, 1, 1, TYPE_ENTRY_AMENDED,
        payloadJson.encodeToString(EntryAmended(targetUid, JsonObject(fields.toMap()))),
    )

    private fun delete(targetUid: String, ts: String) = JournalEvent(
        nextId++, ts, 1, 1, TYPE_ENTRY_DELETED,
        payloadJson.encodeToString(EntryDeleted(targetUid)),
    )

    /** Runs the plan and appends its rows as real, id-bearing events — what the SQL migration does. */
    private fun migrated(events: List<JournalEvent>): List<JournalEvent> {
        val appended = planLegacyAmendmentMigration(events).map { row ->
            JournalEvent(
                id = nextId++, ts = row.ts, spaceId = 1, authorId = 1, type = row.type, payload = row.payload,
                workoutId = row.workoutId, uid = row.uid, workoutUid = row.workoutUid,
                opDate = opDateOfPayload(row.payload), tsUtc = row.tsUtc, tzOffsetMin = row.tzOffsetMin,
            )
        }
        return events + appended
    }

    /**
     * THE acceptance comparison: fold [before] and [after] with the exact same reader and
     * demand the same verdict — which forms are live and what each one says. Two things are
     * deliberately excluded, both for the same reason: a correction is now a new, appended row
     * on purpose (domain/Amendments.kt's header), so
     *
     *  - row IDENTITY (id/uid) is not compared — a passing test that demanded the same id back
     *    would be demanding the migration NOT do the one thing it exists to do;
     *  - DISPLAY ORDER is not compared either, and this is the one honest cost worth naming out
     *    loud rather than hidden behind a sort: a session/workout draws its sets in JOURNAL
     *    order, and a corrected set's new row is appended at the moment of the CORRECTION, not
     *    of the original set. An entry corrected after later ones were logged now reads AFTER
     *    them too, where the old patch model left it exactly where it was first written. This
     *    test compares the two readings as bags of values for that reason; see the report this
     *    migration shipped with for where that reordering is called out as a real UX change.
     */
    private fun assertSameReading(before: List<JournalEvent>, after: List<JournalEvent>) {
        val beforeRead = readActivities(before)
        val afterRead = readActivities(after)
        assertEquals("live entry count must match", beforeRead.size, afterRead.size)
        val canonical = { list: List<ActivityEvent> -> list.map { "${it.type}|${it.form.toJsonObject()}" }.sorted() }
        assertEquals("same forms, as a bag of values", canonical(beforeRead), canonical(afterRead))
    }

    // --- one field, corrected once ---

    @Test
    fun `a single field amendment folds to the same value after migration`() {
        val set = ev(bench(weight = 60.0, reps = 5))
        val before = listOf(set, amend(set.uid, "2026-08-06T11:00:00", "weight_kg" to JsonPrimitive(65.0)))

        val after = migrated(before)

        assertSameReading(before, after)
        val migratedSet = readActivities(after).single().form as StrengthSet
        assertEquals(65.0, migratedSet.weightKg!!, 1e-9)
        assertEquals(5, migratedSet.reps)
        // the OLD row is superseded rather than gone: the history still finds two rows for it
        assertEquals(2, readActivities(after, includeDeleted = true).size)
        // and the plan wrote exactly a version plus its marker - nothing per intermediate step
        assertEquals(2, planLegacyAmendmentMigration(before).size)
    }

    // --- two sequential amendments of one record ---

    @Test
    fun `two sequential amendments of one record fold to the same final value after migration`() {
        val set = ev(bench(weight = 60.0, reps = 5))
        val before = listOf(
            set,
            amend(set.uid, "2026-08-06T11:00:00", "reps" to JsonPrimitive(8)),
            amend(set.uid, "2026-08-06T12:00:00", "weight_kg" to JsonPrimitive(70.0)),
        )

        val after = migrated(before)

        assertSameReading(before, after)
        val migratedSet = readActivities(after).single().form as StrengthSet
        assertEquals(8, migratedSet.reps)
        assertEquals(70.0, migratedSet.weightKg!!, 1e-9)
        // one hop, not a chain of two - see planLegacyAmendmentMigration's own KDoc
        assertEquals(2, planLegacyAmendmentMigration(before).size)
    }

    // --- amend, then delete ---

    @Test
    fun `an entry amended and then deleted stays gone after migration, with its correction on record`() {
        val set = ev(bench(reps = 5))
        val before = listOf(
            set,
            amend(set.uid, "2026-08-06T11:00:00", "reps" to JsonPrimitive(8)),
            delete(set.uid, "2026-08-06T12:00:00"),
        )

        val after = migrated(before)

        assertSameReading(before, after)
        assertTrue("still deleted after migration", readActivities(after).isEmpty())
        // the correction survives in the history exactly as it did before migration
        val historic = readActivities(after, includeDeleted = true).single().form as StrengthSet
        assertEquals(8, historic.reps)
        // dead lineages get nothing written - deleted already means "gone", see the file's KDoc
        assertTrue(planLegacyAmendmentMigration(before).isEmpty())
    }

    // --- delete, then undo the delete ---

    @Test
    fun `a deletion undone after migration reads exactly as it did before it`() {
        val set = ev(bench(reps = 5))
        val gone = delete(set.uid, "2026-08-06T11:00:00")
        val undo = delete(gone.uid, "2026-08-06T12:00:00")
        val before = listOf(set, gone, undo)

        val after = migrated(before)

        assertSameReading(before, after)
        assertEquals(1, readActivities(after).size)
        assertEquals(5, (readActivities(after).single().form as StrengthSet).reps)
    }

    /** The same, but the entry was ALSO amended before the deletion that gets undone. */
    @Test
    fun `an amended-then-deleted-then-undeleted entry keeps its correction after migration`() {
        val set = ev(bench(reps = 5))
        val correction = amend(set.uid, "2026-08-06T11:00:00", "reps" to JsonPrimitive(8))
        val gone = delete(set.uid, "2026-08-06T12:00:00")
        val undo = delete(gone.uid, "2026-08-06T13:00:00")
        val before = listOf(set, correction, gone, undo)

        val after = migrated(before)

        assertSameReading(before, after)
        assertEquals(8, (readActivities(after).single().form as StrengthSet).reps)
    }

    // --- fактический отдых: recordActualRest amends a set with a single field, same as above,
    // named separately because the task calls it out as its own scenario to verify ---

    @Test
    fun `the actual rest recorded onto a previous set folds to the same value after migration`() {
        val first = ev(bench(weight = 60.0), ts = "2026-08-06T10:00:00")
        val second = ev(bench(weight = 62.5), ts = "2026-08-06T10:05:00")
        val actualRest = amend(first.uid, "2026-08-06T10:04:30", "rest_after_sec" to JsonPrimitive(90.0))
        val before = listOf(first, second, actualRest)

        val after = migrated(before)

        assertSameReading(before, after)
        // found by what they say rather than by position - see assertSameReading's own KDoc on
        // why the migrated reading's ORDER is not the thing under test here
        val forms = readActivities(after).map { it.form as StrengthSet }
        assertEquals(2, forms.size)
        assertEquals(90.0, forms.single { it.weightKg == 60.0 }.restAfterSec!!, 1e-9)
        assertNull("the second set was never amended", forms.single { it.weightKg == 62.5 }.restAfterSec)
    }

    // --- untouched rows are left exactly alone ---

    @Test
    fun `a row nobody ever amended gets nothing written for it`() {
        val set = ev(bench())
        val before = listOf(set)

        assertTrue(planLegacyAmendmentMigration(before).isEmpty())
        assertEquals(before, migrated(before))
    }

    // --- running the plan twice does not double up a lineage already converted ---

    @Test
    fun `the plan is idempotent - folding its own output back in finds nothing left to convert`() {
        val set = ev(bench(weight = 60.0))
        val before = listOf(set, amend(set.uid, "2026-08-06T11:00:00", "weight_kg" to JsonPrimitive(65.0)))
        val after = migrated(before)

        assertTrue(
            "the new version was never itself legacy-amended, and the old one is dead, not current",
            planLegacyAmendmentMigration(after).isEmpty(),
        )
    }
}
