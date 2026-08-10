package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Record detection tests — the same cases as in the Python `tests/test_records.py`. */
class RecordsTest {

    private fun set(weight: Double?, reps: Int, id: Long = 1, day: String = "2026-08-01") =
        StrengthSet(
            exercise = "Bench press", reps = reps, weightKg = weight,
            ownWeight = weight == null, exerciseId = id, opDate = day,
        )

    @Test
    fun `Epley 1RM is weight times one plus reps over thirty`() {
        assertEquals(100.0, est1rm(100.0, 0), 1e-9)
        assertEquals(116.666666, est1rm(100.0, 5), 1e-5)
        assertEquals(62.5 * (1 + 5 / 30.0), est1rm(62.5, 5), 1e-9)
    }

    @Test
    fun `the first weighted set is a baseline, not a record`() {
        assertNull(evaluateStrengthRecord(emptyList(), 60.0, 5))
    }

    @Test
    fun `a rise in estimated 1RM is detected as a record`() {
        val prior = listOf(set(60.0, 5))
        val hit = evaluateStrengthRecord(prior, 62.5, 5)
        assertNotNull(hit)
        assertEquals(RecordHit.Axis.EST_1RM, hit!!.axis)
        assertEquals(est1rm(62.5, 5), hit.value, 1e-9)
    }

    @Test
    fun `best weight at the same rep count while the 1RM is lower`() {
        // the previous best 1RM has to stay out of reach, otherwise the 1RM axis wins and
        // the weight-at-reps axis is never evaluated: with 100x1 (1RM 103.3) and 60x8
        // (1RM 76) in history, a new 62.5x8 gives 1RM 79.3 < 103.3 but beats 60 kg at 8 reps
        val prior = listOf(set(100.0, 1), set(60.0, 8))
        val hit = evaluateStrengthRecord(prior, 62.5, 8)
        assertNotNull(hit)
        assertEquals(RecordHit.Axis.WEIGHT_AT_REPS, hit!!.axis)
        assertEquals(62.5, hit.value, 1e-9)
        assertEquals(60.0, hit.previous, 1e-9)
    }

    @Test
    fun `a set without an absolute weight yields no record`() {
        val prior = listOf(set(60.0, 5))
        assertNull(evaluateStrengthRecord(prior, null, 12))
        // and it takes no part in the comparison: a body-weight-only history is a baseline
        assertNull(evaluateStrengthRecord(listOf(set(null, 12)), 60.0, 5))
    }

    @Test
    fun `the hold record is added weight, not seconds and not the edge`() {
        val prior = listOf(
            HoldSet(activity = "Hangs 20 mm · 7:3", addedKg = 6.0, exerciseId = 5, opDate = "2026-08-01"),
            HoldSet(activity = "Hangs 20 mm · 7:3", addedKg = 8.0, exerciseId = 5, opDate = "2026-08-03"),
        )
        val heavier = HoldSet(activity = "Hangs 20 mm · 7:3", addedKg = 9.5, exerciseId = 5, opDate = "2026-08-06")
        val hit = evaluateHoldRecord(prior, heavier)
        assertNotNull(hit)
        assertEquals(RecordHit.Axis.HOLD_WEIGHT, hit!!.axis)
        assertEquals(9.5, hit.value, 1e-9)

        val lighter = HoldSet(activity = "Hangs 20 mm · 7:3", addedKg = 7.0, exerciseId = 5, opDate = "2026-08-06")
        assertNull(evaluateHoldRecord(prior, lighter))
    }

    @Test
    fun `unweighted holds fall back to the seconds axis`() {
        val prior = listOf(HoldSet(activity = "Plank", holdSec = 60.0, exerciseId = 9, opDate = "2026-08-01"))
        val hit = evaluateHoldRecord(
            prior, HoldSet(activity = "Plank", holdSec = 75.0, exerciseId = 9, opDate = "2026-08-06")
        )
        assertEquals(RecordHit.Axis.HOLD_SECONDS, hit!!.axis)
        assertEquals(75.0, hit.value, 1e-9)
    }

    @Test
    fun `an all-time exercise record comes with its date`() {
        val events = listOf(
            JournalEvent(1, "t", 1, 1, TYPE_STRENGTH_SET, set(60.0, 5, day = "2026-08-01").toPayload()),
            JournalEvent(2, "t", 1, 1, TYPE_STRENGTH_SET, set(70.0, 3, day = "2026-08-05").toPayload()),
        )
        // one record and not two: nothing here names a side, so this is two-limbed work
        val record = strengthRecord(readActivities(events), ExerciseLink.ofId(1)).single()
        assertEquals("2026-08-05", record.opDate)
        assertEquals(est1rm(70.0, 3), record.value, 1e-9)
    }

    @Test
    fun `an all-time hold record is the maximum added weight with its date`() {
        val a = HoldSet(activity = "Hangs", addedKg = 6.0, exerciseId = 5, opDate = "2026-08-01")
        val b = HoldSet(activity = "Hangs", addedKg = 12.0, exerciseId = 5, opDate = "2026-08-04")
        val events = listOf(
            JournalEvent(1, "t", 1, 1, TYPE_HOLD_SET, a.toPayload()),
            JournalEvent(2, "t", 1, 1, TYPE_HOLD_SET, b.toPayload()),
        )
        // one record and not two: nothing here names a side, so this is two-handed work
        val record = holdRecord(readActivities(events), ExerciseLink.ofId(5)).single()
        assertEquals(12.0, record.value, 1e-9)
        assertEquals("2026-08-04", record.opDate)
    }

    // --- a strength set carries a side too, the same as a hold ----------------------------

    private fun sided(weight: Double?, reps: Int, side: HoldSide?, id: Long = 1, day: String = "2026-08-01") =
        StrengthSet(
            exercise = "Pistol squat", reps = reps, weightKg = weight,
            side = side?.code, exerciseId = id, opDate = day,
        )

    @Test
    fun `a strength record is judged within its own side, the same as a hold`() {
        val prior = listOf(
            sided(60.0, 5, HoldSide.LEFT),
            sided(80.0, 5, HoldSide.RIGHT),
        )
        // the left leg's own history is beaten...
        val left = evaluateStrengthRecord(prior, 65.0, 5, side = HoldSide.LEFT)
        assertNotNull(left)
        assertEquals(RecordHit.Axis.EST_1RM, left!!.axis)
        // ...but the same weight is nowhere near the right leg's own best
        assertNull(evaluateStrengthRecord(prior, 65.0, 5, side = HoldSide.RIGHT))
    }

    @Test
    fun `an all-time strength record is one entry per side`() {
        val events = listOf(
            JournalEvent(1, "t", 1, 1, TYPE_STRENGTH_SET, sided(60.0, 5, HoldSide.LEFT, day = "2026-08-01").toPayload()),
            JournalEvent(2, "t", 1, 1, TYPE_STRENGTH_SET, sided(80.0, 3, HoldSide.RIGHT, day = "2026-08-03").toPayload()),
        )
        val records = strengthRecord(readActivities(events), ExerciseLink.ofId(1))
        assertEquals(listOf(HoldSide.LEFT, HoldSide.RIGHT), records.map { it.side })
        assertEquals(est1rm(60.0, 5), records[0].value, 1e-9)
        assertEquals(est1rm(80.0, 3), records[1].value, 1e-9)
    }

    @Test
    fun `the heaviest-single-set axis is split per side too`() {
        val events = listOf(
            JournalEvent(1, "t", 1, 1, TYPE_STRENGTH_SET, sided(60.0, 5, HoldSide.LEFT, day = "2026-08-01").toPayload()),
            JournalEvent(2, "t", 1, 1, TYPE_STRENGTH_SET, sided(90.0, 1, HoldSide.RIGHT, day = "2026-08-03").toPayload()),
        )
        val records = heaviestSet(readActivities(events), ExerciseLink.ofId(1))
        assertEquals(listOf(HoldSide.LEFT, HoldSide.RIGHT), records.map { it.side })
        assertEquals(60.0, records[0].value, 1e-9)
        assertEquals(90.0, records[1].value, 1e-9)
    }
}
