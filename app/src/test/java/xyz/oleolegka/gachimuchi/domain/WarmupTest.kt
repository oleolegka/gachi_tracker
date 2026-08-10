package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A warm-up is training that does not count towards anything competitive.
 *
 * The two halves of that sentence are what this file pins down, and they pull in opposite
 * directions on purpose: OUT of the records and out of the volume, IN the feed and in the
 * count of active days. A change that satisfies only one half looks right on whichever
 * screen it was written for and is wrong on the other.
 */
class WarmupTest {

    private fun strength(
        weight: Double?,
        reps: Int,
        warmup: Boolean = false,
        day: String = "2026-08-01",
        id: Long = 1,
    ) = StrengthSet(
        exercise = "Bench press", reps = reps, weightKg = weight, ownWeight = weight == null,
        warmup = warmup, exerciseId = id, opDate = day,
    )

    private fun hold(
        addedKg: Double? = null,
        holdSec: Double? = null,
        warmup: Boolean = false,
        day: String = "2026-08-01",
        id: Long = 5,
    ) = HoldSet(
        activity = "Hangs 20 mm", addedKg = addedKg, holdSec = holdSec, warmup = warmup,
        exerciseId = id, opDate = day,
    )

    private fun journal(vararg forms: ActivityForm): List<JournalEvent> =
        forms.mapIndexed { index, form ->
            JournalEvent(index + 1L, "2026-08-01T10:0$index:00", 1, 1, form.type, form.toPayload())
        }

    // --- the payload -------------------------------------------------------------------

    @Test
    fun `an entry written before the flag existed reads as a working set`() {
        val old = """{"exercise":"Bench press","reps":5,"weight_kg":80.0,""" +
            """"op_date":"2026-08-01","exercise_key":"bench press"}"""
        val form = formFromEvent(TYPE_STRENGTH_SET, old) as StrengthSet
        assertFalse("a set that could not say otherwise is a working set", form.warmup)
    }

    @Test
    fun `the flag survives a round trip through the journal`() {
        val written = strength(40.0, 10, warmup = true).toPayload()
        assertTrue((formFromEvent(TYPE_STRENGTH_SET, written) as StrengthSet).warmup)

        val hang = hold(addedKg = 5.0, warmup = true).toPayload()
        assertTrue((formFromEvent(TYPE_HOLD_SET, hang) as HoldSet).warmup)
    }

    // --- out of the records ------------------------------------------------------------

    @Test
    fun `a warm-up cannot break a strength record however heavy it is`() {
        val prior = listOf(strength(60.0, 5))
        assertNotNull(evaluateStrengthRecord(prior, 100.0, 5))
        assertNull(
            "a set marked as a warm-up is not a personal best",
            evaluateStrengthRecord(prior, 100.0, 5, warmup = true),
        )
    }

    @Test
    fun `a warm-up is not the baseline that silences the first working set`() {
        // the first WEIGHTED set of an exercise is a baseline and breaks nothing; if a warm-up
        // counted as that baseline, the working set right after it would be judged against the
        // empty bar and would be announced as a record over nothing
        val onlyWarmups = listOf(strength(20.0, 10, warmup = true))
        assertNull(evaluateStrengthRecord(onlyWarmups, 60.0, 5))
    }

    @Test
    fun `a warm-up hang neither sets a record nor holds one`() {
        val prior = listOf(hold(addedKg = 8.0))
        assertNull(evaluateHoldRecord(prior, hold(addedKg = 20.0, warmup = true)))

        // and as a PRIOR it is invisible: 9 kg beats the 8 kg working hang even though a
        // heavier warm-up sits in the history
        val withWarmup = listOf(hold(addedKg = 8.0), hold(addedKg = 20.0, warmup = true))
        val hit = evaluateHoldRecord(withWarmup, hold(addedKg = 9.0))
        assertNotNull(hit)
        assertEquals(8.0, hit!!.previous, 1e-9)
    }

    @Test
    fun `the all-time records are computed over working sets only`() {
        val events = journal(
            strength(100.0, 5, warmup = true, day = "2026-08-01"),
            strength(60.0, 5, day = "2026-08-02"),
        )
        val activities = readActivities(events)
        val link = ExerciseLink.ofId(1)

        assertEquals(est1rm(60.0, 5), strengthRecord(activities, link).single().value, 1e-9)
        assertEquals(60.0, heaviestSet(activities, link).single().value, 1e-9)
    }

    @Test
    fun `the all-time hold record ignores a heavier warm-up hang`() {
        val events = journal(
            hold(addedKg = 20.0, warmup = true, day = "2026-08-01"),
            hold(addedKg = 8.0, day = "2026-08-02"),
        )
        val record = holdRecord(readActivities(events), ExerciseLink.ofId(5)).single()
        assertEquals(8.0, record.value, 1e-9)
        assertEquals("2026-08-02", record.opDate)
    }

    // --- out of the volume -------------------------------------------------------------

    @Test
    fun `tonnage counts the working sets and not the ramp-up to them`() {
        val events = journal(
            strength(20.0, 10, warmup = true, day = "2026-08-01"),
            strength(60.0, 5, day = "2026-08-01"),
        )
        val volume = volumeSeries(readActivities(events), ExerciseLink.ofId(1), ExerciseForm.STRENGTH)!!
        assertEquals(1, volume.points.size)
        assertEquals(300.0, volume.points.single().value, 1e-9)
    }

    @Test
    fun `a day of nothing but warm-ups has no volume point at all`() {
        // not a zero bar: a zero would claim there was a session that achieved nothing
        val events = journal(strength(20.0, 10, warmup = true, day = "2026-08-01"))
        assertNull(volumeSeries(readActivities(events), ExerciseLink.ofId(1), ExerciseForm.STRENGTH))
    }

    @Test
    fun `the count of hold sets leaves the warm-up hangs out`() {
        val events = journal(
            hold(holdSec = 10.0, warmup = true),
            hold(holdSec = 20.0),
            hold(holdSec = 20.0),
        )
        val volume = volumeSeries(readActivities(events), ExerciseLink.ofId(5), ExerciseForm.HOLD)!!
        assertEquals(2.0, volume.points.single().value, 1e-9)
    }

    @Test
    fun `the trend line and the record agree about which set was the best`() {
        val events = journal(
            strength(100.0, 5, warmup = true, day = "2026-08-01"),
            strength(60.0, 5, day = "2026-08-01"),
        )
        val activities = readActivities(events)
        val trend = trendSeries(activities, ExerciseLink.ofId(1), ExerciseForm.STRENGTH)!!
        assertEquals(
            strengthRecord(activities, ExerciseLink.ofId(1)).single().value,
            trend.best!!.value,
            1e-9,
        )
    }

    // --- still training ----------------------------------------------------------------

    @Test
    fun `a day spent warming up is an active day and a streak survives it`() {
        val events = journal(strength(20.0, 10, warmup = true, day = "2026-08-05"))
        val days = activeDays(events, "2026-08-01", "2026-08-05")
        assertEquals(setOf("2026-08-05"), days)
        assertEquals(1, currentStreak(days, LocalDate.parse("2026-08-05")))
    }

    @Test
    fun `a warm-up stays in the day's feed like any other set`() {
        val events = journal(
            strength(20.0, 10, warmup = true, day = "2026-08-01"),
            strength(60.0, 5, day = "2026-08-01"),
        )
        val session = buildSession(events, "2026-08-01")
        assertEquals(2, session.setCount)
        // and it is not silently promoted to a record inside the feed either
        val sets = session.groups.single().sets
        assertNull(sets.first().record)
    }

    @Test
    fun `the heatmap counts a warm-up only day as a day that happened`() {
        val events = journal(strength(20.0, 10, warmup = true, day = "2026-08-01"))
        val perDay = activitiesByDay(events, "2026-08-01", "2026-08-01")
        assertEquals(1, perDay["2026-08-01"]?.size)
    }
}
