package xyz.oleolegka.gachimuchi.ui

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.Aggregation
import xyz.oleolegka.gachimuchi.domain.DayPoint
import xyz.oleolegka.gachimuchi.domain.FormSeries
import xyz.oleolegka.gachimuchi.domain.Period
import xyz.oleolegka.gachimuchi.domain.SeriesSpec
import xyz.oleolegka.gachimuchi.domain.ValueFormat
import xyz.oleolegka.gachimuchi.domain.granularity
import xyz.oleolegka.gachimuchi.domain.inPeriod
import xyz.oleolegka.gachimuchi.domain.onAxis
import xyz.oleolegka.gachimuchi.domain.timeAxis
import xyz.oleolegka.gachimuchi.ui.components.niceScale

/**
 * One entry, four windows: what the detail screen's charts are handed when there is a single
 * point to draw and the segment control is being tapped through.
 *
 * ── What was measured before the fix (today = 2026-08-11, one entry that day) ────
 * The lone point's position across the card, as a fraction of the plot's width:
 *
 *     30 days   0.98      3 months  0.96      Year  0.99      All  0.50
 *
 * and with the entry three days old, "All" moved it to 0.25. "All" builds its frame from the
 * data, so a journal of one entry asked for an axis ONE BUCKET wide — a card holding one dot
 * under one date — and every other window put the same dot hard against the right-hand edge.
 * Tapping between them threw the measurement across the card. "The scale goes mad with a
 * single point, especially switching ranges", from the phone, 2026-08-11.
 *
 * What is asserted here is the property that was missing, not the numbers above: whichever
 * window is chosen, a recent entry stays in the last part of the axis, and no window collapses
 * the frame to a bucket or two.
 */
class SinglePointChartTest {

    private val today: LocalDate = LocalDate.parse("2026-08-11")

    private fun series(day: String, value: Double) = FormSeries(
        SeriesSpec("Best set", ValueFormat.KILOGRAMS, Aggregation.BEST),
        listOf(DayPoint(day, value)),
    )

    /** The lone point's position along the axis, 0 at the left edge and 1 at the right. */
    private fun placement(period: Period, day: String, spanDays: Int): Pair<Int, Float> {
        val one = series(day, 102.5)
        val windowed = one.inPeriod(period, today)
        val axis = timeAxis(listOf(windowed), period, period.granularity(spanDays), today)
        val placed = windowed.onAxis(axis)
        val at = placed.slots.indexOfFirst { it.value != null }
        return axis.size to (at + 0.5f) / axis.size
    }

    @Test
    fun `one entry today sits near the end of every window`() {
        for (period in Period.entries) {
            val (size, x) = placement(period, today.toString(), spanDays = 0)
            assertTrue("$period collapsed to $size slots", size >= 4)
            assertTrue("$period put today's only entry at $x", x > 0.75f)
        }
    }

    @Test
    fun `one entry three days old sits near the end of every window`() {
        val day = today.minusDays(3).toString()
        for (period in Period.entries) {
            val (size, x) = placement(period, day, spanDays = 3)
            assertTrue("$period collapsed to $size slots", size >= 4)
            assertTrue("$period put a three-day-old entry at $x", x >= 0.65f)
        }
    }

    /**
     * The Y axis of a lone value is invented — there is no spread to measure — but it has to be
     * the SAME invention in every window, or the dot would also move up and down as the
     * segment control is tapped.
     */
    @Test
    fun `the value axis of one entry does not depend on the window`() {
        val scales = Period.entries.map {
            val windowed = series(today.toString(), 102.5).inPeriod(it, today)
            val axis = timeAxis(listOf(windowed), it, it.granularity(0), today)
            niceScale(windowed.onAxis(axis).values)
        }
        assertEquals(1, scales.distinct().size)
        assertTrue("the point must not sit on the frame", scales.first().fraction(102.5) in 0.1f..0.9f)
    }

    /**
     * A bar chart's baseline is zero, so the padding that gives a flat series a height may not
     * carry the axis below it. A single value of zero used to produce an axis of -1..1 with a
     * gridline at -0.5: half a bar chart below its own baseline, describing negative volume.
     */
    @Test
    fun `a zero-baseline axis never opens below zero for a single value`() {
        for (v in listOf(0.0, 0.5, 7.0, 1240.0)) {
            val scale = niceScale(listOf(v), includeZero = true)
            assertEquals("v=$v opened below zero: $scale", 0.0, scale.min, 1e-9)
            assertTrue("v=$v has no height: $scale", scale.max > scale.min)
        }
    }
}
