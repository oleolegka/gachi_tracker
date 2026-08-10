package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "Справился ли я с упражнением или нет" (owner). The app cannot tell this on its own — the
 * timer counts its seven seconds whether or not the lifter actually held on for all of them —
 * so it is a mark set by hand, on the SET, and it pulls in the opposite direction from
 * [warmup]'s own split: IN the volume and the time under tension (the effort was real), OUT of
 * the records (the number was not actually held). A change that satisfies only one half looks
 * right on whichever screen it was written for and is wrong on the other — the same warning
 * [WarmupTest] opens with, for the opposite pair of halves.
 */
class IncompleteTest {

    private fun strength(
        weight: Double?,
        reps: Int,
        incomplete: Boolean = false,
        day: String = "2026-08-01",
        id: Long = 1,
    ) = StrengthSet(
        exercise = "Bench press", reps = reps, weightKg = weight, ownWeight = weight == null,
        incomplete = incomplete, exerciseId = id, opDate = day,
    )

    private fun hold(
        addedKg: Double? = null,
        holdSec: Double? = null,
        incomplete: Boolean = false,
        day: String = "2026-08-01",
        id: Long = 5,
    ) = HoldSet(
        activity = "Hangs 20 mm", addedKg = addedKg, holdSec = holdSec, incomplete = incomplete,
        exerciseId = id, opDate = day,
    )

    private fun journal(vararg forms: ActivityForm): List<JournalEvent> =
        forms.mapIndexed { index, form ->
            JournalEvent(index + 1L, "2026-08-01T10:0$index:00", 1, 1, form.type, form.toPayload())
        }

    // --- the payload -------------------------------------------------------------------

    @Test
    fun `an entry written before the flag existed reads as no mark, not as a failure`() {
        val old = """{"exercise":"Bench press","reps":5,"weight_kg":80.0,""" +
            """"op_date":"2026-08-01","exercise_key":"bench press"}"""
        val form = formFromEvent(TYPE_STRENGTH_SET, old) as StrengthSet
        assertFalse("a set that could not say otherwise carries no mark", form.incomplete)
    }

    @Test
    fun `the flag survives a round trip through the journal`() {
        val written = strength(40.0, 10, incomplete = true).toPayload()
        assertTrue((formFromEvent(TYPE_STRENGTH_SET, written) as StrengthSet).incomplete)

        val hang = hold(addedKg = 5.0, incomplete = true).toPayload()
        assertTrue((formFromEvent(TYPE_HOLD_SET, hang) as HoldSet).incomplete)
    }

    @Test
    fun `warmup and incomplete are independent - a set can carry either, both or neither`() {
        val form = StrengthSet(
            exercise = "Bench press", reps = 5, weightKg = 40.0,
            warmup = true, incomplete = true, opDate = "2026-08-01",
        )
        assertTrue(form.warmup)
        assertTrue(form.incomplete)
    }

    // --- out of the records ------------------------------------------------------------

    @Test
    fun `a set marked not completed cannot break a strength record however heavy it is`() {
        val prior = listOf(strength(60.0, 5))
        assertNotNull(evaluateStrengthRecord(prior, 100.0, 5))
        assertNull(
            "a set marked as not completed is not a personal best",
            evaluateStrengthRecord(prior, 100.0, 5, incomplete = true),
        )
    }

    @Test
    fun `an incomplete set is not the baseline that silences the first working set`() {
        val onlyIncomplete = listOf(strength(20.0, 10, incomplete = true))
        assertNull(evaluateStrengthRecord(onlyIncomplete, 60.0, 5))
    }

    @Test
    fun `an incomplete hang neither sets a record nor holds one`() {
        val prior = listOf(hold(addedKg = 8.0))
        assertNull(evaluateHoldRecord(prior, hold(addedKg = 20.0, incomplete = true)))

        // and as a PRIOR it is invisible: 9 kg beats the 8 kg working hang even though a
        // heavier incomplete hang sits in the history
        val withIncomplete = listOf(hold(addedKg = 8.0), hold(addedKg = 20.0, incomplete = true))
        val hit = evaluateHoldRecord(withIncomplete, hold(addedKg = 9.0))
        assertNotNull(hit)
        assertEquals(8.0, hit!!.previous, 1e-9)
    }

    @Test
    fun `the all-time records are computed over completed sets only`() {
        val events = journal(
            strength(100.0, 5, incomplete = true, day = "2026-08-01"),
            strength(60.0, 5, day = "2026-08-02"),
        )
        val activities = readActivities(events)
        val link = ExerciseLink.ofId(1)

        assertEquals(est1rm(60.0, 5), strengthRecord(activities, link).single().value, 1e-9)
        assertEquals(60.0, heaviestSet(activities, link).single().value, 1e-9)
    }

    @Test
    fun `the all-time hold record ignores a heavier incomplete hang`() {
        val events = journal(
            hold(addedKg = 20.0, incomplete = true, day = "2026-08-01"),
            hold(addedKg = 8.0, day = "2026-08-02"),
        )
        val record = holdRecord(readActivities(events), ExerciseLink.ofId(5)).single()
        assertEquals(8.0, record.value, 1e-9)
        assertEquals("2026-08-02", record.opDate)
    }

    // --- in the volume, unlike a warm-up -------------------------------------------------

    @Test
    fun `tonnage counts a set that was not completed - the weight was really hung`() {
        val events = journal(
            strength(60.0, 5, incomplete = true, day = "2026-08-01"),
        )
        val volume = volumeSeries(readActivities(events), ExerciseLink.ofId(1), ExerciseForm.STRENGTH)!!
        assertEquals(1, volume.points.size)
        assertEquals(300.0, volume.points.single().value, 1e-9)
    }

    @Test
    fun `the count of hold sets keeps an incomplete hang, unlike a warm-up hang`() {
        val events = journal(
            hold(holdSec = 5.0, incomplete = true),
            hold(holdSec = 7.0),
        )
        val volume = volumeSeries(readActivities(events), ExerciseLink.ofId(5), ExerciseForm.HOLD)!!
        assertEquals(2.0, volume.points.single().value, 1e-9)
    }

    // --- still shows up in the feed, and is not silently promoted --------------------------

    @Test
    fun `an incomplete set stays in the day's feed and does not silently win a record`() {
        val events = journal(
            strength(100.0, 5, incomplete = true, day = "2026-08-01"),
            strength(60.0, 5, day = "2026-08-01"),
        )
        val session = buildSession(events, "2026-08-01")
        assertEquals(2, session.setCount)
        val sets = session.groups.single().sets
        assertNull(sets.first().record)
    }
}
