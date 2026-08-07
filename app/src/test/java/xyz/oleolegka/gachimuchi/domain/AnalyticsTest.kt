package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Dashboard aggregation tests: the series the charts are drawn from, the heatmap buckets,
 * the hero counters, the door tiles and the period filter.
 *
 * The point of these is that the screens compute NOTHING: if a number appears on the
 * overview, it is asserted here.
 */
class AnalyticsTest {

    private var nextId = 1L

    private fun ev(form: ActivityForm, ts: String = "2026-08-06T10:00:00") =
        JournalEvent(nextId++, ts, 1, 1, form.type, form.toPayload())

    private fun strength(day: String, weight: Double?, reps: Int, id: Long = 1) = ev(
        StrengthSet(
            exercise = "Bench press", reps = reps, weightKg = weight,
            ownWeight = weight == null, exerciseId = id, opDate = day,
        )
    )

    private fun hold(day: String, addedKg: Double?, holdSec: Double? = null, id: Long = 2) = ev(
        HoldSet(
            activity = "Hangs 20 mm", addedKg = addedKg, holdSec = holdSec, ownWeight = true,
            exerciseId = id, opDate = day,
        )
    )

    private fun acts(events: List<JournalEvent>) = readActivities(events)

    // --- trend series ------------------------------------------------------------------

    @Test
    fun `the strength trend is the best estimated 1RM of the day`() {
        val events = listOf(
            strength("2026-08-01", 60.0, 5),
            strength("2026-08-01", 62.5, 3),   // 1RM 68.75 -- lower than 60x5 = 70
            strength("2026-08-03", 65.0, 5),
        )
        val series = trendSeries(acts(events), ExerciseLink.ofId(1), ExerciseForm.STRENGTH)!!
        assertEquals("Estimated 1RM", series.spec.label)
        assertEquals(2, series.points.size)
        assertEquals(est1rm(60.0, 5), series.points[0].value, 1e-9)
        assertEquals("2026-08-03", series.points[1].opDate)
        // the trend uses the same formula as the record, so the two cannot disagree
        assertEquals(strengthRecord(acts(events), ExerciseLink.ofId(1))!!.value, series.best!!.value, 1e-9)
    }

    @Test
    fun `a day of body-weight-only strength sets produces no trend point`() {
        val events = listOf(
            strength("2026-08-01", 60.0, 5),
            strength("2026-08-02", null, 12),  // no weight, so no 1RM to compute
        )
        val series = trendSeries(acts(events), ExerciseLink.ofId(1), ExerciseForm.STRENGTH)!!
        assertEquals(listOf("2026-08-01"), series.points.map { it.opDate })
        // the volume chart still sees that day, but as tonnage the body-weight set is zero
        val volume = volumeSeries(acts(events), ExerciseLink.ofId(1), ExerciseForm.STRENGTH)!!
        assertEquals("Volume, reps x weight", volume.spec.label)
        assertEquals(listOf("2026-08-01", "2026-08-02"), volume.points.map { it.opDate })
        assertEquals(300.0, volume.points[0].value, 1e-9)
        assertEquals(0.0, volume.points[1].value, 1e-9)
    }

    @Test
    fun `the hold trend is added weight, and falls back to seconds only without any weight`() {
        val weighted = listOf(
            hold("2026-08-01", 6.0),
            hold("2026-08-01", 8.0),
            hold("2026-08-03", 7.0),
        )
        val series = trendSeries(acts(weighted), ExerciseLink.ofId(2), ExerciseForm.HOLD)!!
        assertEquals("Added weight", series.spec.label)
        assertEquals(8.0, series.points[0].value, 1e-9)  // the max of the day, not the last

        val plank = listOf(
            hold("2026-08-01", null, holdSec = 40.0),
            hold("2026-08-02", null, holdSec = 55.0),
        )
        val fallback = trendSeries(acts(plank), ExerciseLink.ofId(2), ExerciseForm.HOLD)!!
        assertEquals("Longest hold", fallback.spec.label)
        assertEquals(ValueFormat.SECONDS, fallback.spec.format)
        assertEquals(55.0, fallback.best!!.value, 1e-9)
    }

    @Test
    fun `cardio trends on pace, lower is better, and has no trend without pace`() {
        val withPace = listOf(
            ev(Cardio(activity = "Running", distanceM = 5000.0, paceSecPerKm = 330.0, exerciseId = 3, opDate = "2026-08-01")),
            ev(Cardio(activity = "Running", distanceM = 5000.0, paceSecPerKm = 315.0, exerciseId = 3, opDate = "2026-08-03")),
        )
        val series = trendSeries(acts(withPace), ExerciseLink.ofId(3), ExerciseForm.CARDIO)!!
        assertTrue(series.spec.lowerIsBetter)
        assertEquals(315.0, series.best!!.value, 1e-9)  // best = the LOWEST pace

        val noPace = listOf(
            ev(Cardio(activity = "Elliptical", durationSec = 1800, exerciseId = 4, opDate = "2026-08-01")),
        )
        // distance is volume, not progress: no invented trend
        assertNull(trendSeries(acts(noPace), ExerciseLink.ofId(4), ExerciseForm.CARDIO))
        assertEquals("Time", volumeSeries(acts(noPace), ExerciseLink.ofId(4), ExerciseForm.CARDIO)!!.spec.label)
    }

    @Test
    fun `a check-in has frequency and nothing else`() {
        val events = listOf(
            ev(Tick(activity = "Stretching", exerciseId = 5, opDate = "2026-08-01")),
            ev(Tick(activity = "Stretching", exerciseId = 5, opDate = "2026-08-01")),
            ev(Tick(activity = "Stretching", exerciseId = 5, opDate = "2026-08-04")),
        )
        assertNull(trendSeries(acts(events), ExerciseLink.ofId(5), ExerciseForm.TICK))
        val volume = volumeSeries(acts(events), ExerciseLink.ofId(5), ExerciseForm.TICK)!!
        assertEquals("Check-ins", volume.spec.label)
        assertEquals(listOf(2.0, 1.0), volume.points.map { it.value })
    }

    @Test
    fun `duration is its own volume and body weight has none`() {
        val events = listOf(
            ev(Duration(activity = "Emil hangs", durationSec = 480, exerciseId = 6, opDate = "2026-08-01")),
            ev(Duration(activity = "Emil hangs", durationSec = 120, exerciseId = 6, opDate = "2026-08-01")),
            ev(Bodyweight(weightKg = 74.2, opDate = "2026-08-01")),
            ev(Bodyweight(weightKg = 73.8, opDate = "2026-08-05")),
        )
        val duration = trendSeries(acts(events), ExerciseLink.ofId(6), ExerciseForm.DURATION)!!
        assertEquals(600.0, duration.points[0].value, 1e-9)  // summed over the day
        assertNull(volumeSeries(acts(events), ExerciseLink.ofId(6), ExerciseForm.DURATION))

        // body weight carries no exercise_id, so the id passed in is ignored by design
        val weight = trendSeries(acts(events), ExerciseLink.ofId(999), ExerciseForm.BODYWEIGHT)!!
        assertEquals(listOf(74.2, 73.8), weight.points.map { it.value })
        assertNull(volumeSeries(acts(events), ExerciseLink.ofId(999), ExerciseForm.BODYWEIGHT))
    }

    @Test
    fun `an exercise with no entries yields no series at all`() {
        val events = listOf(strength("2026-08-01", 60.0, 5, id = 1))
        assertNull(trendSeries(acts(events), ExerciseLink.ofId(42), ExerciseForm.STRENGTH))
        assertNull(volumeSeries(acts(events), ExerciseLink.ofId(42), ExerciseForm.STRENGTH))
    }

    @Test
    fun `a cancelled set leaves the series`() {
        val first = strength("2026-08-01", 60.0, 5)
        val second = strength("2026-08-02", 80.0, 5)
        val cancel = JournalEvent(
            900, "2026-08-02T11:00:00", 1, 1, TYPE_SET_CANCEL,
            payloadJson.encodeToString(SetCancel(second.id)),
        )
        val series = trendSeries(acts(listOf(first, second, cancel)), ExerciseLink.ofId(1), ExerciseForm.STRENGTH)!!
        assertEquals(listOf("2026-08-01"), series.points.map { it.opDate })
    }

    // --- records ------------------------------------------------------------------------

    @Test
    fun `strength gets both the 1RM and the heaviest set, holds get weight, cardio gets none`() {
        val events = listOf(
            strength("2026-08-01", 100.0, 1),   // heaviest, 1RM 103.3
            strength("2026-08-02", 60.0, 20),   // 1RM 100.0 -- lighter but not the record
            hold("2026-08-02", 9.0),
            ev(Cardio(activity = "Running", distanceM = 5000.0, paceSecPerKm = 300.0, exerciseId = 3, opDate = "2026-08-02")),
        )
        val a = acts(events)
        val strengthRecs = recordsOf(a, ExerciseLink.ofId(1), ExerciseForm.STRENGTH)
        assertEquals(2, strengthRecs.size)
        assertEquals("2026-08-01", strengthRecs[0].opDate)
        assertEquals(100.0, heaviestSet(a, ExerciseLink.ofId(1))!!.value, 1e-9)
        assertTrue(heaviestSet(a, ExerciseLink.ofId(1))!!.text.contains("heaviest set"))

        assertEquals(1, recordsOf(a, ExerciseLink.ofId(2), ExerciseForm.HOLD).size)
        assertEquals(RecordHit.Axis.HOLD_WEIGHT, recordsOf(a, ExerciseLink.ofId(2), ExerciseForm.HOLD)[0].axis)
        // no record model for cardio yet -- an empty list, not a fabricated badge
        assertTrue(recordsOf(a, ExerciseLink.ofId(3), ExerciseForm.CARDIO).isEmpty())
        assertTrue(recordsOf(a, ExerciseLink.ofId(6), ExerciseForm.DURATION).isEmpty())
        assertTrue(recordsOf(a, ExerciseLink.ofId(5), ExerciseForm.TICK).isEmpty())
    }

    // --- hangboard siblings --------------------------------------------------------------

    @Test
    fun `hangboard siblings share a base key once the measurements are stripped`() {
        assertEquals("hangs", holdBaseKey("Hangs 20 mm - 7:3"))
        assertEquals("hangs", holdBaseKey("Hangs 15 mm - 7:3"))
        assertEquals("hangs", holdBaseKey("Hangs 20 mm - 10:50"))
        // a different exercise does not get merged in
        assertTrue(holdBaseKey("Pull-ups") != holdBaseKey("Hangs 20 mm - 7:3"))
        // a name with no measurements at all survives whole, so it forms a group of one
        assertEquals("front lever", holdBaseKey("Front lever"))
    }

    // --- heatmap --------------------------------------------------------------------------

    @Test
    fun `a heatmap level is the activity count, capped at the top bucket`() {
        // absolute buckets, not quantiles: the legend has to be able to say "2 activities"
        assertEquals(0, heatmapLevel(0, 4))
        assertEquals(1, heatmapLevel(1, 4))
        assertEquals(3, heatmapLevel(3, 4))
        assertEquals(4, heatmapLevel(4, 4))
        assertEquals(4, heatmapLevel(9, 4))
    }

    @Test
    fun `a day counts distinct exercises, not entries`() {
        val events = listOf(
            // twelve sets of two exercises is a two-activity day, not a twelve-activity day
            strength("2026-08-05", 60.0, 5, id = 1),
            strength("2026-08-05", 62.5, 5, id = 1),
            strength("2026-08-05", 65.0, 5, id = 1),
            hold("2026-08-05", 8.0, id = 2),
            hold("2026-08-05", 9.0, id = 2),
            ev(Bodyweight(weightKg = 74.0, opDate = "2026-08-05")),
        )
        val perDay = activitiesByDay(events, "2026-08-01", "2026-08-31")
        assertEquals(2, perDay.getValue("2026-08-05").size)
        // first-appearance order, and a weigh-in is not an activity
        assertEquals(listOf("Bench press", "Hangs 20 mm"), perDay.getValue("2026-08-05").map { it.name })
        assertEquals(listOf(1L, 2L), perDay.getValue("2026-08-05").map { it.exerciseId })
    }

    @Test
    fun `the heatmap grid is whole Monday-to-Sunday weeks and excludes weigh-ins`() {
        val events = listOf(
            ev(Tick(activity = "Stretching", exerciseId = 5, opDate = "2026-08-05")),
            ev(Tick(activity = "Stretching", exerciseId = 5, opDate = "2026-08-05")),
            ev(Bodyweight(weightKg = 74.0, opDate = "2026-08-06")),
        )
        // 2026-08-05 is a Wednesday; the range deliberately starts and ends mid-week
        val map = activityHeatmap(events, LocalDate.parse("2026-08-04"), LocalDate.parse("2026-08-12"))
        assertEquals(0, map.days.size % 7)
        assertEquals(map.days.size / 7, map.weeks)
        assertEquals("2026-08-03", map.days.first().opDate)   // the Monday before
        assertEquals("2026-08-16", map.days.last().opDate)    // the Sunday after
        // two check-ins of ONE exercise is one activity
        assertEquals(1, map.days.first { it.opDate == "2026-08-05" }.count)
        // stepping on the scales is not training and must not paint a rest day
        assertEquals(0, map.days.first { it.opDate == "2026-08-06" }.count)
        assertEquals(1, map.activeDays)
        assertEquals(1, map.maxCount)
        assertEquals(LocalDate.parse("2026-08-03"), map.weekStart(0))
    }

    @Test
    fun `heatmap cells are addressable by week and weekday`() {
        val map = activityHeatmap(emptyList(), LocalDate.parse("2026-08-03"), LocalDate.parse("2026-08-16"))
        assertEquals(2, map.weeks)
        assertEquals("2026-08-03", map.cell(0, 0)!!.opDate)  // week 0, Monday
        assertEquals("2026-08-09", map.cell(0, 6)!!.opDate)  // week 0, Sunday
        assertEquals("2026-08-10", map.cell(1, 0)!!.opDate)
        assertNull(map.cell(5, 0))
    }

    // --- hero -----------------------------------------------------------------------------

    @Test
    fun `the hero counts active days, not events, and compares with the window before`() {
        val today = LocalDate.parse("2026-08-06")   // a Thursday
        val events = listOf(
            // this window: two active days, four entries
            strength(today.toString(), 60.0, 5),
            strength(today.toString(), 62.5, 5),
            strength(today.minusDays(2).toString(), 60.0, 5),
            ev(Bodyweight(weightKg = 74.0, opDate = today.minusDays(1).toString())),  // not training
            // the previous window: one active day
            strength(today.minusDays(9).toString(), 55.0, 5),
        )
        val hero = heroStats(events, today, windowDays = 7)
        assertEquals(2, hero.workouts)
        assertEquals(3, hero.entries)      // the weigh-in is not counted
        assertEquals(1, hero.previousWorkouts)
        assertEquals(1, hero.delta)
    }

    @Test
    fun `an unlogged today does not break the streak but a two-day gap does`() {
        val today = LocalDate.parse("2026-08-06")
        val yesterday = today.minusDays(1).toString()
        val before = today.minusDays(2).toString()

        assertEquals(2, currentStreak(setOf(yesterday, before), today))
        assertEquals(3, currentStreak(setOf(today.toString(), yesterday, before), today))
        // a gap of two days: the last activity was the day before yesterday
        assertEquals(0, currentStreak(setOf(before), today))
        assertEquals(0, currentStreak(emptySet(), today))
    }

    // --- period filter ---------------------------------------------------------------------

    @Test
    fun `the period filter keeps the window inclusive of today and All keeps everything`() {
        val today = LocalDate.parse("2026-08-06")
        val points = listOf(
            DayPoint("2025-01-01", 1.0),
            DayPoint(today.minusDays(40).toString(), 2.0),
            DayPoint(today.minusDays(29).toString(), 3.0),   // exactly on the 30-day edge
            DayPoint(today.toString(), 4.0),
        )
        assertEquals(4, points.inPeriod(Period.ALL, today).size)
        assertEquals(listOf(3.0, 4.0), points.inPeriod(Period.MONTH, today).map { it.value })
        assertEquals(listOf(2.0, 3.0, 4.0), points.inPeriod(Period.QUARTER, today).map { it.value })
        assertEquals(listOf(2.0, 3.0, 4.0), points.inPeriod(Period.YEAR, today).map { it.value })
        assertEquals(30, Period.MONTH.days)
        assertNull(Period.ALL.days)
    }

    // --- door tiles ---------------------------------------------------------------------------

    @Test
    fun `door tiles skip unused exercises and lead with the most recent`() {
        val events = listOf(
            strength("2026-08-01", 60.0, 5, id = 1),
            hold("2026-08-04", 8.0, id = 2),
            ev(Tick(activity = "Stretching", exerciseId = 5, opDate = "2026-08-03")),
        )
        val catalog = listOf(
            CatalogExercise(1L, "Bench press", ExerciseForm.STRENGTH),
            CatalogExercise(2L, "Hangs 20 mm", ExerciseForm.HOLD),
            CatalogExercise(5L, "Stretching", ExerciseForm.TICK),
            CatalogExercise(9L, "Never used", ExerciseForm.STRENGTH),
        )
        val tiles = doorTiles(events, catalog)
        assertEquals(listOf("Hangs 20 mm", "Stretching", "Bench press"), tiles.map { it.name })
        // a check-in tile falls back to its frequency, so no tile is ever blank
        assertEquals("Check-ins", tiles.first { it.form == ExerciseForm.TICK }.series.spec.label)
        // holds carry a record, check-ins do not -- and the tile says so by carrying null
        assertNotNull(tiles.first { it.form == ExerciseForm.HOLD }.record)
        assertNull(tiles.first { it.form == ExerciseForm.TICK }.record)
        // the first weighted set of an exercise is a baseline, but the whole-history
        // reducer still reports it as the current record with its date
        assertEquals("2026-08-01", tiles.first { it.name == "Bench press" }.record!!.opDate)
    }

    @Test
    fun `an empty journal produces no tiles at all`() {
        val catalog = listOf(CatalogExercise(1L, "Bench press", ExerciseForm.STRENGTH))
        assertTrue(doorTiles(emptyList(), catalog).isEmpty())
    }

    // --- bucketing by week and month ------------------------------------------------------------

    @Test
    fun `a bucket is labelled by its Monday or by the first of its month`() {
        // 2026-08-05 is a Wednesday
        assertEquals("2026-08-03", bucketStart("2026-08-05", Granularity.WEEK))
        assertEquals("2026-08-03", bucketStart("2026-08-09", Granularity.WEEK))  // the Sunday
        assertEquals("2026-08-10", bucketStart("2026-08-10", Granularity.WEEK))  // the next Monday
        assertEquals("2026-08-01", bucketStart("2026-08-31", Granularity.MONTH))
        assertEquals("2026-08-05", bucketStart("2026-08-05", Granularity.DAY))
    }

    @Test
    fun `a volume sums inside a bucket while a progress metric takes the best of it`() {
        val days = listOf(
            DayPoint("2026-08-03", 100.0),   // Monday
            DayPoint("2026-08-06", 120.0),   // Thursday, same week
            DayPoint("2026-08-11", 90.0),    // the following week
        )
        val volume = FormSeries(SeriesSpec("Volume", ValueFormat.KILOGRAMS, Aggregation.SUM), days)
        assertEquals(listOf(220.0, 90.0), volume.bucketed(Granularity.WEEK).points.map { it.value })

        val trend = FormSeries(SeriesSpec("1RM", ValueFormat.KILOGRAMS, Aggregation.BEST), days)
        assertEquals(listOf(120.0, 90.0), trend.bucketed(Granularity.WEEK).points.map { it.value })

        // for pace the best of a week is the LOWEST number, not the highest
        val pace = FormSeries(
            SeriesSpec("Pace", ValueFormat.PACE, Aggregation.BEST, lowerIsBetter = true), days,
        )
        assertEquals(listOf(100.0, 90.0), pace.bucketed(Granularity.WEEK).points.map { it.value })

        // a day-granular request is a no-op rather than a rebuild
        assertEquals(days, volume.bucketed(Granularity.DAY).points)
    }

    @Test
    fun `the bucket width follows the window`() {
        assertEquals(Granularity.DAY, Period.MONTH.granularity(1000))
        assertEquals(Granularity.WEEK, Period.QUARTER.granularity(10))
        assertEquals(Granularity.WEEK, Period.YEAR.granularity(10))
        // "all" only goes monthly once the history is genuinely long
        assertEquals(Granularity.WEEK, Period.ALL.granularity(200))
        assertEquals(Granularity.MONTH, Period.ALL.granularity(900))
    }

    // --- deltas ----------------------------------------------------------------------------------

    @Test
    fun `a delta knows which direction counts as progress`() {
        val rising = FormSeries(
            SeriesSpec("1RM", ValueFormat.KILOGRAMS, Aggregation.BEST),
            listOf(DayPoint("2026-08-01", 100.0), DayPoint("2026-08-02", 105.0)),
        )
        assertEquals(5.0, rising.delta()!!.change, 1e-9)
        assertTrue(rising.delta()!!.improved)

        val fasterPace = FormSeries(
            SeriesSpec("Pace", ValueFormat.PACE, Aggregation.BEST, lowerIsBetter = true),
            listOf(DayPoint("2026-08-01", 330.0), DayPoint("2026-08-02", 315.0)),
        )
        // the pace fell, which for pace is an improvement
        assertEquals(-15.0, fasterPace.delta()!!.change, 1e-9)
        assertTrue(fasterPace.delta()!!.improved)

        // one point has nothing to compare with, and that is not the same as "no change"
        val single = FormSeries(
            SeriesSpec("1RM", ValueFormat.KILOGRAMS, Aggregation.BEST),
            listOf(DayPoint("2026-08-01", 100.0)),
        )
        assertNull(single.delta())
    }

    // --- hangboard siblings ------------------------------------------------------------------------

    @Test
    fun `siblings are grouped by base name and ordered from the widest edge down`() {
        val catalog = listOf(
            HoldSibling(1, "Hangs 15 mm - 7:3", 15.0, 7.0, 3.0),
            HoldSibling(2, "Hangs 20 mm - 7:3", 20.0, 7.0, 3.0),
            HoldSibling(3, "Hangs 20 mm - 10:50", 20.0, 10.0, 50.0),
            HoldSibling(4, "Front lever", null, null, null),
        )
        val siblings = holdSiblings(catalog, 2)
        // the exercise asked about is included, and the thinner edge sorts last (harder)
        assertEquals(listOf(3L, 2L, 1L), siblings.map { it.exerciseId })

        // an exercise with no siblings is a group of one, and the screen hides the switcher
        assertEquals(listOf(4L), holdSiblings(catalog, 4).map { it.exerciseId })
        assertTrue(holdSiblings(catalog, 999).isEmpty())
    }

    // --- the check-in presence window ------------------------------------------------------------------

    @Test
    fun `the presence window is oldest-first and ends on today`() {
        val today = LocalDate.parse("2026-08-06")
        val points = listOf(
            DayPoint(today.toString(), 1.0),
            DayPoint(today.minusDays(2).toString(), 1.0),
            DayPoint(today.minusDays(9).toString(), 1.0),
            DayPoint(today.minusDays(40).toString(), 1.0),   // outside the window
        )
        val window = presenceWindow(points, today, days = 10)
        assertEquals(10, window.size)
        assertTrue("today is the last slot", window.last())
        assertTrue("nine days back is the first slot", window.first())
        assertTrue(window[7])            // two days ago
        assertTrue(!window[8])           // yesterday: nothing logged
        assertEquals(3, window.count { it })
    }
}
