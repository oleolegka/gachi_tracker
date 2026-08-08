package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.time.LocalDate
import org.junit.Test

/**
 * The one time axis a detail screen draws all of its charts on.
 *
 * ── The defect these pin ────────────────────────────────────────────────────────
 * Every chart used to be laid out from its OWN points: first point at the left edge, last
 * point at the right, one step per point. Two charts of the same exercise therefore ended on
 * different days — the trend on the day of the last 1RM, the volume on the day of the last
 * session — while sitting one above the other at the same width, which is precisely the
 * arrangement that asks a reader to compare them column by column. The step was not a unit of
 * time either: a fortnight between two entries drew as wide as two consecutive days.
 *
 * So what is asserted here is not that the numbers are right (that is `AnalyticsTest`) but
 * that two series of one screen come out THE SAME LENGTH, on THE SAME DAYS, in the same
 * order, whatever each of them happens to contain.
 */
class SharedTimeAxisTest {

    private val today: LocalDate = LocalDate.parse("2026-08-07")

    private fun series(
        vararg points: Pair<String, Double>,
        label: String = "Volume",
        aggregation: Aggregation = Aggregation.SUM,
        lowerIsBetter: Boolean = false,
    ) = FormSeries(
        SeriesSpec(label, ValueFormat.COUNT, aggregation, lowerIsBetter),
        points.map { DayPoint(it.first, it.second) },
    )

    private fun axisOf(period: Period, granularity: Granularity, vararg of: FormSeries) =
        timeAxis(of.toList(), period, granularity, today)

    // --- the frame comes from the window ---------------------------------------------------

    @Test
    fun `a thirty-day window is thirty slots however little was trained in it`() {
        val one = series("2026-08-07" to 5.0)
        val axis = axisOf(Period.MONTH, Granularity.DAY, one)

        assertEquals(30, axis.size)
        assertEquals("2026-07-09", axis.slots.first())
        assertEquals("2026-08-07", axis.slots.last())
        // the slots are consecutive days, not the days that happen to carry entries
        assertEquals(axis.slots, axis.slots.sorted())
        assertEquals(axis.slots.distinct(), axis.slots)
    }

    /**
     * The bug itself, in the smallest form it can be written in: a trend that stopped on the
     * third and a volume that ran to the eighth used to be drawn edge to edge each, so the
     * same x meant two different days depending on which chart you were looking at.
     */
    @Test
    fun `two series that end on different days still end on the same slot`() {
        val trend = series("2026-07-28" to 100.0, "2026-08-03" to 105.0)
        val volume = series("2026-07-28" to 4.0, "2026-08-03" to 6.0, "2026-08-07" to 3.0)
        val axis = axisOf(Period.MONTH, Granularity.DAY, trend, volume)

        val onAxisTrend = trend.onAxis(axis)
        val onAxisVolume = volume.onAxis(axis)

        assertEquals(axis.size, onAxisTrend.slots.size)
        assertEquals(axis.size, onAxisVolume.slots.size)
        assertEquals(onAxisTrend.slots.map { it.opDate }, onAxisVolume.slots.map { it.opDate })

        // and each value sits under the day it was recorded on, in both of them
        fun valueOn(s: SeriesOnAxis, day: String) = s.slots.single { it.opDate == day }.value
        assertEquals(105.0, valueOn(onAxisTrend, "2026-08-03"))
        assertNull(valueOn(onAxisTrend, "2026-08-07"))
        assertEquals(3.0, valueOn(onAxisVolume, "2026-08-07"))

        // the trend's last day is no longer the end of its axis: it has four slots after it
        val lastTrendSlot = onAxisTrend.slots.indexOfLast { it.value != null }
        assertEquals(onAxisTrend.slots.lastIndex - 4, lastTrendSlot)
    }

    /**
     * An untrained day is a HOLE, not a zero. A zero bar claims a session that achieved
     * nothing, which is the same claim `byDay` refuses to make about a day of warm-ups.
     */
    @Test
    fun `a bucket with nothing in it is null rather than zero`() {
        val volume = series("2026-08-06" to 12.0)
        val placed = volume.onAxis(axisOf(Period.MONTH, Granularity.DAY, volume))

        assertEquals(1, placed.filled)
        assertEquals(listOf(12.0), placed.values)
        assertTrue(placed.slots.count { it.value == null } == 29)
        assertFalse(placed.slots.any { it.value == 0.0 })
    }

    @Test
    fun `a series with nothing in the window is empty rather than rescaled onto its own data`() {
        val old = series("2026-05-01" to 40.0, "2026-05-08" to 44.0)
        val recent = series("2026-08-06" to 3.0)
        // the window filter runs first, exactly as the screen runs it
        val axis = axisOf(
            Period.MONTH, Granularity.DAY,
            old.inPeriod(Period.MONTH, today), recent.inPeriod(Period.MONTH, today),
        )
        val placed = old.inPeriod(Period.MONTH, today).onAxis(axis)

        assertTrue(placed.isEmpty)
        assertEquals(0, placed.filled)
        // and it did NOT shrink the axis to May: the shared frame is still the window
        assertEquals(30, axis.size)
        assertEquals("2026-08-07", axis.slots.last())
    }

    // --- coarser buckets ---------------------------------------------------------------------

    @Test
    fun `a weekly axis is Mondays, and a value lands on the Monday of its week`() {
        val volume = series("2026-08-05" to 10.0, "2026-08-06" to 5.0, "2026-07-30" to 7.0)
        val axis = axisOf(Period.QUARTER, Granularity.WEEK, volume)

        assertTrue(axis.slots.all { LocalDate.parse(it).dayOfWeek.value == 1 })
        assertEquals("2026-08-03", axis.slots.last())

        val placed = volume.onAxis(axis)
        // the two August days are one week and SUM inside it; the July one is the week before
        assertEquals(15.0, placed.slots.single { it.opDate == "2026-08-03" }.value)
        assertEquals(7.0, placed.slots.single { it.opDate == "2026-07-27" }.value)
        assertEquals(2, placed.filled)
    }

    @Test
    fun `a monthly axis is first-of-months and skips nothing in between`() {
        val volume = series("2024-03-14" to 1.0, "2026-08-01" to 2.0)
        val axis = axisOf(Period.ALL, Granularity.MONTH, volume)

        assertEquals("2024-03-01", axis.slots.first())
        assertEquals("2026-08-01", axis.slots.last())
        // 2024-03 .. 2026-08 inclusive, every month of it, including the empty years
        assertEquals(30, axis.size)
        assertEquals(2, volume.onAxis(axis).filled)
    }

    @Test
    fun `an all-time axis starts at the first entry and runs to today`() {
        val volume = series("2026-07-20" to 1.0)
        val axis = axisOf(Period.ALL, Granularity.WEEK, volume)

        assertEquals("2026-07-20", axis.slots.first())   // itself a Monday
        assertEquals("2026-08-03", axis.slots.last())    // the Monday of today's week
        assertEquals(3, axis.size)
    }

    /** No data at all: one slot for today rather than an empty frame or a crash. */
    @Test
    fun `an all-time axis with nothing logged is today alone`() {
        val axis = timeAxis(emptyList(), Period.ALL, Granularity.DAY, today)
        assertEquals(listOf("2026-08-07"), axis.slots)
    }

    /**
     * An entry dated ahead of today is not silently cut off the end of an all-time axis. The
     * fixed windows drop it (their [inPeriod] ends at today) and this is the one case where
     * "everything" has to mean everything.
     */
    @Test
    fun `a future-dated entry extends the all-time axis instead of falling off it`() {
        val volume = series("2026-08-06" to 1.0, "2026-08-10" to 2.0)
        val axis = axisOf(Period.ALL, Granularity.DAY, volume)

        assertEquals("2026-08-10", axis.slots.last())
        assertEquals(2, volume.onAxis(axis).filled)
    }

    // --- the bucket walk itself --------------------------------------------------------------

    @Test
    fun `bucket slots are inclusive of both ends and empty when the range is inverted`() {
        assertEquals(
            listOf("2026-08-05", "2026-08-06", "2026-08-07"),
            bucketSlots(LocalDate.parse("2026-08-05"), LocalDate.parse("2026-08-07"), Granularity.DAY),
        )
        assertEquals(
            listOf("2026-08-05"),
            bucketSlots(LocalDate.parse("2026-08-05"), LocalDate.parse("2026-08-05"), Granularity.DAY),
        )
        assertTrue(
            bucketSlots(LocalDate.parse("2026-08-07"), LocalDate.parse("2026-08-05"), Granularity.DAY)
                .isEmpty()
        )
    }

    /**
     * A single mis-dated entry must not ask for a hundred thousand buckets. Granularity is
     * already monthly past 400 days, so the bound is eighty years of months — a backstop, not
     * a window anybody will meet.
     */
    @Test
    fun `the bucket walk is bounded against absurd dates`() {
        val slots = bucketSlots(LocalDate.parse("1900-01-01"), today, Granularity.DAY)
        assertEquals(1000, slots.size)
    }
}
