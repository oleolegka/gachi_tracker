package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * THE ACCEPTANCE TEST for `MIGRATION_21_22` (schema version 22, `occurred_ts`): the plain SQL
 * `UPDATE events SET occurred_ts = ts` backfill, and why it is safe.
 *
 * This migration is structurally different from [planLegacyAmendmentMigration]'s: there is no
 * fold to get right, because [happenedAt] already falls back to [JournalEvent.ts] for a row
 * with no `occurred_ts` — which is every row before this migration runs. Backfilling
 * `occurred_ts = ts` therefore does not CHANGE what [happenedAt] answers for any existing row;
 * it only makes explicit, in a column a future correction can inherit, the value the fallback
 * was already giving. This test is what proves that claim rather than asserting it in a
 * comment: build a journal with no `occurred_ts` at all (a phone before the migration), and one
 * where every row has been backfilled with its own `ts` (a phone after it), and demand
 * [buildSession] and [buildWorkout] read them identically.
 */
class OccurredTsMigrationTest {

    private fun bench(weight: Double, day: String = "2026-08-06") =
        StrengthSet(exercise = "Bench press", reps = 5, weightKg = weight, exerciseId = 1, opDate = day)

    /**
     * Rows deliberately written OUT OF `ts` ORDER (id 1 is the LATEST `ts`, id 3 the EARLIEST):
     * if the backfill mattered at all rather than being a no-op, sorting by `happenedAt` would
     * answer differently before and after it — this is what would catch that.
     */
    private fun rows(withOccurredTs: Boolean): List<JournalEvent> {
        fun row(id: Long, ts: String, weight: Double) = JournalEvent(
            id, ts, 1, 1, TYPE_STRENGTH_SET, bench(weight).toPayload(),
            occurredTs = if (withOccurredTs) ts else null,
        )
        return listOf(
            row(1, "2026-08-06T10:10:00", 65.0),
            row(2, "2026-08-06T10:05:00", 62.5),
            row(3, "2026-08-06T10:00:00", 60.0),
        )
    }

    @Test
    fun `buildSession reads the same order before and after the occurred_ts backfill`() {
        val before = buildSession(rows(withOccurredTs = false), "2026-08-06")
        val after = buildSession(rows(withOccurredTs = true), "2026-08-06")

        val weightsBefore = before.groups.single().sets.map { (it.form as StrengthSet).weightKg }
        val weightsAfter = after.groups.single().sets.map { (it.form as StrengthSet).weightKg }
        assertEquals(listOf(60.0, 62.5, 65.0), weightsBefore)
        assertEquals(weightsBefore, weightsAfter)
    }

    @Test
    fun `workoutsOn reads the same order before and after the occurred_ts backfill`() {
        fun started(id: Long, ts: String) =
            JournalEvent(id, ts, 1, 1, TYPE_WORKOUT_STARTED, payloadJson.encodeToString(WorkoutStarted(opDate = "2026-08-06")))

        fun withOccurredTs(row: JournalEvent) = row.copy(occurredTs = row.ts)

        val raw = listOf(started(1, "2026-08-06T18:10:00"), started(2, "2026-08-06T09:00:00"))
        val before = workoutsOn(raw, "2026-08-06").map { it.id }
        val after = workoutsOn(raw.map(::withOccurredTs), "2026-08-06").map { it.id }

        assertEquals(listOf(2L, 1L), before)
        assertEquals(before, after)
    }
}
