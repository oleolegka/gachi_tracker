package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Work done one limb at a time is two histories, not one.
 *
 * The asymmetry between the hands is the thing this kind of training exists to close, so
 * folding both into one record answers the wrong question: it reports the stronger hand and
 * hides the gap. What is pinned down here is that each side is judged against ITSELF, and
 * that a set which should have named a hand and did not is REPORTED rather than guessed at.
 */
class OneSidedTest {

    private fun hang(
        addedKg: Double? = null,
        holdSec: Double? = null,
        side: HoldSide? = null,
        day: String = "2026-08-01",
        id: Long = 5,
    ) = HoldSet(
        activity = "One-arm hang 20 mm", addedKg = addedKg, holdSec = holdSec,
        side = side?.code, exerciseId = id, opDate = day,
    )

    private fun journal(vararg forms: ActivityForm): List<JournalEvent> =
        forms.mapIndexed { index, form ->
            JournalEvent(index + 1L, "2026-08-01T10:0$index:00", 1, 1, form.type, form.toPayload())
        }

    private fun records(vararg forms: ActivityForm, oneSided: Boolean = false) =
        holdRecord(readActivities(journal(*forms)), ExerciseLink.ofId(5), oneSided)

    // --- the payload -------------------------------------------------------------------

    @Test
    fun `the side round-trips through the journal as a plain word`() {
        val written = hang(addedKg = -10.0, side = HoldSide.LEFT).toPayload()
        assertTrue(written.contains("\"side\":\"left\""))

        val back = formFromEvent(TYPE_HOLD_SET, written) as HoldSet
        assertEquals(HoldSide.LEFT, back.sideOf)
    }

    @Test
    fun `a set written before sides existed names no side`() {
        val old = """{"activity":"Hangs","hold_sec":10.0,"op_date":"2026-08-01",""" +
            """"activity_key":"hangs"}"""
        assertNull((formFromEvent(TYPE_HOLD_SET, old) as HoldSet).sideOf)
    }

    @Test
    fun `a side that is neither left nor right is a corrupt row, not a third hand`() {
        // strict on purpose: quietly accepting it would file the set with the ones that named
        // no side at all, which is the one answer that is certainly wrong
        assertThrows(IllegalArgumentException::class.java) {
            HoldSet(activity = "Hangs", side = "both", opDate = "2026-08-01")
        }
        // and the reader skips it rather than taking the app down with it
        val corrupt = """{"activity":"Hangs","side":"both","op_date":"2026-08-01",""" +
            """"activity_key":"hangs"}"""
        assertNull(formFromEventOrNull(TYPE_HOLD_SET, corrupt))
    }

    @Test
    fun `the builder writes the side the card picked`() {
        val ref = ExerciseRef(id = 5, name = "One-arm hang 20 mm", form = ExerciseForm.HOLD, oneSided = true)
        assertEquals("right", holdSetOf(ref, "2026-08-01", addedKg = -10.0, side = HoldSide.RIGHT).side)
        // and a one-sided exercise logged without one does NOT throw: this runs inside the Add
        // button's click handler, and the defect is reported by the readers instead
        assertNull(holdSetOf(ref, "2026-08-01", addedKg = -10.0).side)
    }

    // --- each hand competes with itself ------------------------------------------------

    @Test
    fun `a personal best on one hand is not a personal best on the other`() {
        val prior = listOf(
            hang(addedKg = 12.0, side = HoldSide.RIGHT),
            hang(addedKg = 4.0, side = HoldSide.LEFT),
        )

        // 6 kg on the left beats the left's own 4 kg, even though the right has done 12
        val left = evaluateHoldRecord(prior, hang(addedKg = 6.0, side = HoldSide.LEFT))
        assertNotNull("the weaker hand can never break a record if it is compared with the stronger", left)
        assertEquals(4.0, left!!.previous, 1e-9)

        // and 6 kg on the right is not, because the right has been there already
        assertNull(evaluateHoldRecord(prior, hang(addedKg = 6.0, side = HoldSide.RIGHT)))
    }

    @Test
    fun `a two-handed set is not measured against one-handed ones`() {
        val prior = listOf(hang(addedKg = 20.0, side = HoldSide.LEFT))
        // nothing two-handed has been logged, so this is a baseline and stays quiet
        assertNull(evaluateHoldRecord(prior, hang(addedKg = 15.0)))
    }

    // --- the all-time records ----------------------------------------------------------

    @Test
    fun `each hand gets its own record, left first`() {
        val records = records(
            hang(addedKg = 4.0, side = HoldSide.LEFT, day = "2026-08-01"),
            hang(addedKg = 12.0, side = HoldSide.RIGHT, day = "2026-08-01"),
            hang(addedKg = 6.0, side = HoldSide.LEFT, day = "2026-08-03"),
        )

        assertEquals(2, records.size)
        assertEquals(HoldSide.LEFT, records[0].side)
        assertEquals(6.0, records[0].value, 1e-9)
        assertEquals("2026-08-03", records[0].opDate)
        assertTrue(records[0].text, records[0].text.contains("(left)"))

        assertEquals(HoldSide.RIGHT, records[1].side)
        assertEquals(12.0, records[1].value, 1e-9)
        assertFalse(records.any { it.sideMissing })
    }

    @Test
    fun `an exercise nobody trains one-handed still gets exactly one record`() {
        val records = records(
            hang(addedKg = 6.0, day = "2026-08-01"),
            hang(addedKg = 12.0, day = "2026-08-02"),
        )
        assertEquals(1, records.size)
        assertNull(records.single().side)
        assertFalse(records.single().sideMissing)
        assertEquals("added weight 12 kg", records.single().text)
    }

    // --- the defect, stated out loud ---------------------------------------------------

    @Test
    fun `a one-sided exercise with sideless sets reports them as a record of unknown side`() {
        val records = records(
            hang(addedKg = 8.0, day = "2026-08-01"),
            hang(addedKg = 5.0, side = HoldSide.LEFT, day = "2026-08-02"),
            oneSided = true,
        )

        assertEquals(2, records.size)
        assertEquals(HoldSide.LEFT, records[0].side)

        val unknown = records[1]
        assertNull("a set that named no hand must not be given one", unknown.side)
        assertTrue("the gap in the data has to be stated", unknown.sideMissing)
        assertEquals(8.0, unknown.value, 1e-9)
        assertTrue(unknown.text, unknown.text.contains("side not recorded"))
    }

    @Test
    fun `the sideless best is kept apart from the hands rather than crowned overall`() {
        // the failure this guards: 8 kg by an unknown hand must not be reported as the
        // exercise's record, because it may well have been the strong hand's easy day
        val records = records(
            hang(addedKg = 8.0, day = "2026-08-01"),
            hang(addedKg = 5.0, side = HoldSide.LEFT, day = "2026-08-02"),
            hang(addedKg = 6.0, side = HoldSide.RIGHT, day = "2026-08-03"),
            oneSided = true,
        )

        assertEquals(3, records.size)
        assertEquals(listOf(HoldSide.LEFT, HoldSide.RIGHT, null), records.map { it.side })
        assertEquals(listOf(5.0, 6.0, 8.0), records.map { it.value })
        assertEquals(listOf(false, false, true), records.map { it.sideMissing })
    }

    @Test
    fun `a side logged on an exercise nobody has flagged still splits the history`() {
        // the flag is not the only source of truth: a set that names a hand is evidence that
        // this exercise has hands, whatever the catalog has been told
        val records = records(
            hang(addedKg = 4.0, side = HoldSide.LEFT, day = "2026-08-01"),
            hang(addedKg = 9.0, day = "2026-08-02"),
            oneSided = false,
        )

        assertEquals(2, records.size)
        assertEquals(HoldSide.LEFT, records[0].side)
        assertTrue(records[1].sideMissing)
    }

    @Test
    fun `warm-ups stay out of it on every side`() {
        val records = records(
            hang(addedKg = 20.0, side = HoldSide.LEFT, day = "2026-08-01").copy(warmup = true),
            hang(addedKg = 4.0, side = HoldSide.LEFT, day = "2026-08-02"),
            oneSided = true,
        )
        assertEquals(1, records.size)
        assertEquals(4.0, records.single().value, 1e-9)
    }

    @Test
    fun `the seconds axis is per side too`() {
        val records = records(
            hang(holdSec = 30.0, side = HoldSide.LEFT, day = "2026-08-01"),
            hang(holdSec = 45.0, side = HoldSide.RIGHT, day = "2026-08-02"),
            oneSided = true,
        )
        assertEquals(2, records.size)
        assertEquals(RecordHit.Axis.HOLD_SECONDS, records[0].axis)
        assertEquals(30.0, records[0].value, 1e-9)
        assertEquals(45.0, records[1].value, 1e-9)
    }
}
