package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * A week of pull-ups should not read as a week of doing nothing.
 *
 * That is the whole feature, and it takes two facts that live in different places: what share
 * of you the exercise lifts (on the catalog row, because it is true of the exercise) and what
 * you weighed at the time (on the set, because it is true of that day and must never be
 * recomputed from today's scales).
 *
 * The other half of the file is about NOT moving charts that nobody asked to move: with no
 * share stated, or no weight recorded, the numbers have to come out exactly as they did
 * before any of this existed.
 */
class BodyweightVolumeTest {

    private fun pullUp(
        reps: Int,
        bodyweightKg: Double? = null,
        addedKg: Double? = null,
        day: String = "2026-08-01",
        id: Long = 1,
    ) = StrengthSet(
        exercise = "Pull-ups", reps = reps, ownWeight = true, addedKg = addedKg,
        bodyweightKg = bodyweightKg, exerciseId = id, opDate = day,
    )

    private fun barbell(weight: Double, reps: Int, day: String = "2026-08-01", id: Long = 1) =
        StrengthSet(
            exercise = "Bench press", reps = reps, weightKg = weight,
            exerciseId = id, opDate = day,
        )

    private fun weighIn(kg: Double, day: String) = Bodyweight(weightKg = kg, opDate = day)

    private fun journal(vararg forms: ActivityForm): List<JournalEvent> =
        forms.mapIndexed { index, form ->
            JournalEvent(index + 1L, "2026-08-01T10:0$index:00", 1, 1, form.type, form.toPayload())
        }

    private fun volume(events: List<JournalEvent>, share: Double?) =
        volumeSeries(readActivities(events), ExerciseLink.ofId(1), ExerciseForm.STRENGTH, share)

    // --- the formula -------------------------------------------------------------------

    @Test
    fun `a body-weight set is worth its share of you, times the reps`() {
        val events = journal(pullUp(reps = 8, bodyweightKg = 70.0))
        val series = volume(events, share = 1.0)!!
        assertEquals("Volume, reps x weight", series.spec.label)
        assertEquals(560.0, series.points.single().value, 1e-9)
    }

    @Test
    fun `a push-up lifts about two thirds of you and the volume says so`() {
        val events = journal(pullUp(reps = 20, bodyweightKg = 70.0))
        assertEquals(0.65 * 70.0 * 20, volume(events, share = 0.65)!!.points.single().value, 1e-9)
    }

    @Test
    fun `added weight is added to the share, and assistance comes off it`() {
        val loaded = journal(pullUp(reps = 5, bodyweightKg = 70.0, addedKg = 10.0))
        assertEquals(400.0, volume(loaded, share = 1.0)!!.points.single().value, 1e-9)

        val assisted = journal(pullUp(reps = 5, bodyweightKg = 70.0, addedKg = -20.0))
        assertEquals(250.0, volume(assisted, share = 1.0)!!.points.single().value, 1e-9)
    }

    @Test
    fun `a band taking more off than the movement puts on lifts nothing, not less than nothing`() {
        // the floor matters: a negative load would let one assisted set subtract from the
        // week's tonnage, which is not a thing that can happen in a gym
        val events = journal(pullUp(reps = 5, bodyweightKg = 70.0, addedKg = -60.0))
        assertEquals(0.0, volume(events, share = 0.65)!!.points.single().value, 1e-9)
    }

    // --- nothing moves without both halves ----------------------------------------------

    @Test
    fun `with no share stated the chart counts reps, exactly as it always did`() {
        val events = journal(
            pullUp(reps = 8, bodyweightKg = 70.0, day = "2026-08-01"),
            pullUp(reps = 6, bodyweightKg = 70.0, day = "2026-08-01"),
        )
        val series = volume(events, share = null)!!
        assertEquals("Reps", series.spec.label)
        assertEquals(14.0, series.points.single().value, 1e-9)
    }

    @Test
    fun `a share nobody could mean is treated as none at all`() {
        val events = journal(pullUp(reps = 8, bodyweightKg = 70.0))
        // a share is a fraction of one body; 4.0 and 0.0 are rubbish on a catalog row and
        // drawing a chart from them is worse than drawing none
        assertEquals("Reps", volume(events, share = 4.0)!!.spec.label)
        assertEquals("Reps", volume(events, share = 0.0)!!.spec.label)
    }

    @Test
    fun `a set logged before anybody weighed themselves still has no volume`() {
        val events = journal(pullUp(reps = 8, bodyweightKg = null))
        assertEquals("Reps", volume(events, share = 1.0)!!.spec.label)
    }

    @Test
    fun `an ordinary barbell history is untouched by any of this`() {
        val events = journal(
            barbell(60.0, 5, day = "2026-08-01"),
            barbell(80.0, 3, day = "2026-08-02"),
        )
        val withShare = volume(events, share = 1.0)!!
        val without = volume(events, share = null)!!
        assertEquals(without.points, withShare.points)
        assertEquals(listOf(300.0, 240.0), withShare.points.map { it.value })
    }

    @Test
    fun `a mixed day adds the barbell and the pull-ups together`() {
        val events = journal(
            barbell(60.0, 5, day = "2026-08-01"),
            pullUp(reps = 8, bodyweightKg = 70.0, day = "2026-08-01"),
        )
        assertEquals(300.0 + 560.0, volume(events, share = 1.0)!!.points.single().value, 1e-9)
    }

    // --- the snapshot is a snapshot ----------------------------------------------------

    @Test
    fun `the weight stamped is the one the scales showed on or before that day`() {
        val events = journal(
            weighIn(72.0, "2026-07-01"),
            weighIn(69.0, "2026-08-01"),
        )
        assertEquals(72.0, bodyweightAt(events, "2026-07-15")!!, 1e-9)
        assertEquals(69.0, bodyweightAt(events, "2026-08-20")!!, 1e-9)
        // before the scales were ever used there is nothing honest to say
        assertNull(bodyweightAt(events, "2026-06-30"))
    }

    @Test
    fun `back-dated training is stamped with what the scales said then, not now`() {
        val events = journal(
            weighIn(72.0, "2026-07-01"),
            weighIn(69.0, "2026-08-01"),
        )
        val late = pullUp(reps = 5, day = "2026-07-10")
            .withBodyweightSnapshot { day -> bodyweightAt(events, day) } as StrengthSet
        assertEquals(72.0, late.bodyweightKg!!, 1e-9)
    }

    @Test
    fun `losing three kilograms does not make last year's pull-ups cheaper`() {
        /*
         * THE REASON THE NUMBER IS STORED RATHER THAN LOOKED UP. If the chart read the current
         * weight, a weigh-in today would rewrite the volume of every body-weight set ever
         * logged - on a chart whose entire job is to say whether last year was harder.
         */
        val old = journal(pullUp(reps = 10, bodyweightKg = 73.0, day = "2026-01-01"))
        val before = volume(old, share = 1.0)!!.points.single().value

        val nowLighter = old + journal(weighIn(70.0, "2026-08-01"))
        assertEquals(before, volume(nowLighter, share = 1.0)!!.points.single().value, 1e-9)
        assertEquals(730.0, before, 1e-9)
    }

    @Test
    fun `a caller that already knows the weight is not overruled`() {
        val events = journal(weighIn(70.0, "2026-08-01"))
        val imported = pullUp(reps = 5, bodyweightKg = 64.0, day = "2026-08-02")
            .withBodyweightSnapshot { day -> bodyweightAt(events, day) } as StrengthSet
        assertEquals(64.0, imported.bodyweightKg!!, 1e-9)
    }

    @Test
    fun `a set on an implement is never stamped, because it does not lift you`() {
        val events = journal(weighIn(70.0, "2026-08-01"))
        val bench = barbell(60.0, 5, day = "2026-08-02")
            .withBodyweightSnapshot { day -> bodyweightAt(events, day) } as StrengthSet
        assertNull(bench.bodyweightKg)
    }

    // --- storage -----------------------------------------------------------------------

    @Test
    fun `the snapshot survives the journal and an old entry simply has none`() {
        val written = pullUp(reps = 8, bodyweightKg = 70.0).toPayload()
        assertEquals(
            70.0,
            (formFromEvent(TYPE_STRENGTH_SET, written) as StrengthSet).bodyweightKg!!,
            1e-9,
        )

        val old = """{"exercise":"Pull-ups","reps":8,"own_weight":true,""" +
            """"op_date":"2026-08-01","exercise_key":"pull ups"}"""
        assertNull((formFromEvent(TYPE_STRENGTH_SET, old) as StrengthSet).bodyweightKg)
    }

    @Test
    fun `a body weight that is not a weight is refused`() {
        assertThrows(IllegalArgumentException::class.java) { pullUp(reps = 5, bodyweightKg = 0.0) }
        assertThrows(IllegalArgumentException::class.java) { pullUp(reps = 5, bodyweightKg = -70.0) }
    }

    @Test
    fun `a hang carries what you weighed too`() {
        val hang = HoldSet(
            activity = "Hangs 20 mm", addedKg = 5.0, ownWeight = true, bodyweightKg = 70.0,
            exerciseId = 2, opDate = "2026-08-01",
        )
        assertEquals(70.0, (formFromEvent(TYPE_HOLD_SET, hang.toPayload()) as HoldSet).bodyweightKg!!, 1e-9)
    }
}
