package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Added weight is a SIGNED axis: a minus means the load was taken off.
 *
 * The point of the sign living on one field is that one progression stays one progression —
 * -20, -10, 0, +5 is a lifter getting stronger at a pull-up, and every comparison in the app
 * has to read it that way round. So the cases here are mostly about the direction of "better"
 * rather than about storage: needing less help IS the improvement, and needing more of it is
 * not a personal best however large the number is.
 */
class AssistedWeightTest {

    private fun hold(
        addedKg: Double? = null,
        holdSec: Double? = null,
        day: String = "2026-08-01",
        id: Long = 5,
    ) = HoldSet(
        activity = "One-arm hang 20 mm", addedKg = addedKg, holdSec = holdSec,
        exerciseId = id, opDate = day,
    )

    private fun journal(vararg forms: ActivityForm): List<JournalEvent> =
        forms.mapIndexed { index, form ->
            JournalEvent(index + 1L, "2026-08-0${index + 1}T10:00:00", 1, 1, form.type, form.toPayload())
        }

    private val ref = ExerciseRef(id = 5, name = "One-arm hang 20 mm", form = ExerciseForm.HOLD)

    // --- storage -----------------------------------------------------------------------

    @Test
    fun `a negative added weight is storable and survives the journal`() {
        val written = hold(addedKg = -20.0).toPayload()
        assertTrue(written.contains("\"added_kg\":-20.0"))
        assertEquals(-20.0, (formFromEvent(TYPE_HOLD_SET, written) as HoldSet).addedKg!!, 1e-9)
    }

    @Test
    fun `a strength set can carry assistance too, on top of own body weight`() {
        val set = StrengthSet(
            exercise = "Pull-ups", reps = 8, ownWeight = true, addedKg = -20.0,
            exerciseId = 1, opDate = "2026-08-01",
        )
        assertEquals(-20.0, (formFromEvent(TYPE_STRENGTH_SET, set.toPayload()) as StrengthSet).addedKg!!, 1e-9)
    }

    @Test
    fun `zero is still refused, and so is a value that is not a number`() {
        // "I added nothing" is said by leaving the field out; two ways of saying it would be
        // one way too many, and a NaN poisons every maximum it reaches
        assertThrows(IllegalArgumentException::class.java) { hold(addedKg = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { hold(addedKg = Double.NaN) }
    }

    @Test
    fun `the builder keeps the sign and still drops a zero`() {
        assertEquals(-15.0, holdSetOf(ref, "2026-08-01", addedKg = -15.0).addedKg!!, 1e-9)
        assertNull(holdSetOf(ref, "2026-08-01", addedKg = 0.0).addedKg)

        val strength = ExerciseRef(id = 1, name = "Pull-ups", form = ExerciseForm.STRENGTH)
        val assisted = strengthSetOf(strength, "2026-08-01", reps = 8, ownWeight = true, addedKg = -20.0)
        assertEquals(-20.0, assisted.addedKg!!, 1e-9)
    }

    // --- which way is better -----------------------------------------------------------

    @Test
    fun `needing less help is a record, and needing more is not`() {
        val prior = listOf(hold(addedKg = -25.0))

        val better = evaluateHoldRecord(prior, hold(addedKg = -20.0))
        assertNotNull("dropping from 25 kg of help to 20 is progress", better)
        assertEquals(RecordHit.Axis.HOLD_WEIGHT, better!!.axis)
        assertEquals(-20.0, better.value, 1e-9)
        assertTrue(better.text, better.text.contains("assistance 20 kg"))
        assertTrue(better.text, better.text.contains("was 25"))

        assertNull(evaluateHoldRecord(prior, hold(addedKg = -30.0)))
    }

    @Test
    fun `an assisted hang is not a record over a history of unassisted ones`() {
        // the case that made the sign worth checking: a clean hang stands at zero on this
        // axis, so minus twenty is worse than everything already in the journal
        val prior = listOf(hold(holdSec = 12.0), hold(holdSec = 15.0))
        assertNull(evaluateHoldRecord(prior, hold(addedKg = -20.0)))
    }

    @Test
    fun `coming off the band is a record over the assisted history`() {
        val prior = listOf(hold(addedKg = -25.0), hold(addedKg = -15.0))
        val clean = evaluateHoldRecord(prior, hold(holdSec = 8.0))
        assertNotNull("hanging with no help at all beats needing 15 kg of it", clean)
        assertEquals(RecordHit.Axis.HOLD_WEIGHT, clean!!.axis)
        assertEquals(0.0, clean.value, 1e-9)
        assertTrue(clean.text, clean.text.contains("no assistance"))
    }

    @Test
    fun `a plank is still judged on seconds, not dragged onto the weight axis`() {
        // the gate: without a single added weight anywhere, the axis does not exist and the
        // fallback that unweighted holds depend on has to be reached
        val prior = listOf(hold(holdSec = 40.0))
        val hit = evaluateHoldRecord(prior, hold(holdSec = 55.0))
        assertEquals(RecordHit.Axis.HOLD_SECONDS, hit!!.axis)
    }

    // --- the all-time record and the chart ---------------------------------------------

    @Test
    fun `the all-time record of an assisted history is the least help ever needed`() {
        val events = journal(
            hold(addedKg = -25.0, day = "2026-08-01"),
            hold(addedKg = -10.0, day = "2026-08-02"),
            hold(addedKg = -18.0, day = "2026-08-03"),
        )
        val record = holdRecord(readActivities(events), ExerciseLink.ofId(5))!!
        assertEquals(-10.0, record.value, 1e-9)
        assertEquals("2026-08-02", record.opDate)
        assertEquals("assistance 10 kg", record.text)
    }

    @Test
    fun `an unassisted day outranks every assisted one in the all-time record`() {
        val events = journal(
            hold(addedKg = -25.0, day = "2026-08-01"),
            hold(holdSec = 9.0, day = "2026-08-02"),
        )
        val record = holdRecord(readActivities(events), ExerciseLink.ofId(5))!!
        assertEquals(0.0, record.value, 1e-9)
        assertEquals("2026-08-02", record.opDate)
    }

    @Test
    fun `the day the band came off is a point on the chart and not a gap in it`() {
        val events = journal(
            hold(addedKg = -25.0, day = "2026-08-01"),
            hold(holdSec = 9.0, day = "2026-08-02"),
        )
        val series = trendSeries(readActivities(events), ExerciseLink.ofId(5), ExerciseForm.HOLD)!!
        assertEquals("Added weight", series.spec.label)
        assertEquals(2, series.points.size)
        assertEquals(0.0, series.points[1].value, 1e-9)
        assertEquals("2026-08-02", series.best!!.opDate)
    }

    @Test
    fun `the volume of a hold is a count of sets and does not care about the sign`() {
        val events = journal(
            hold(addedKg = -25.0, day = "2026-08-01"),
            hold(addedKg = -20.0, day = "2026-08-01"),
        )
        val volume = volumeSeries(readActivities(events), ExerciseLink.ofId(5), ExerciseForm.HOLD)!!
        assertEquals(2.0, volume.points.single().value, 1e-9)
    }

    @Test
    fun `a positive history reads exactly as it did before the sign was allowed`() {
        val events = journal(
            hold(addedKg = 6.0, day = "2026-08-01"),
            hold(addedKg = 12.0, day = "2026-08-02"),
        )
        val record = holdRecord(readActivities(events), ExerciseLink.ofId(5))!!
        assertEquals(12.0, record.value, 1e-9)
        assertEquals("added weight 12 kg", record.text)

        val hit = evaluateHoldRecord(listOf(hold(addedKg = 6.0)), hold(addedKg = 8.0))!!
        assertEquals("added weight 8 kg (was 6)", hit.text)
    }
}
