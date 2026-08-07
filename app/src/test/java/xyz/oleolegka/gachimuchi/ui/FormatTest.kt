package xyz.oleolegka.gachimuchi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.ValueFormat
import java.time.LocalDate

/**
 * Formatting tests for the numbers the dashboard prints.
 *
 * These exist because a chart axis and a tile headline show THE SAME value through two
 * different functions ([fmtAxis] and [fmtValueParts]); if the two ever disagree about
 * rounding, the tile and the chart under it print different numbers for one fact.
 */
class FormatTest {

    @Test
    fun `a value splits into a number and its unit`() {
        assertEquals("108" to "kg", fmtValueParts(108.0, ValueFormat.KILOGRAMS))
        assertEquals("72.5" to "kg", fmtValueParts(72.46, ValueFormat.KILOGRAMS))
        assertEquals("5:00" to "/km", fmtValueParts(300.0, ValueFormat.PACE))
        assertEquals("5" to "km", fmtValueParts(5000.0, ValueFormat.DISTANCE))
        assertEquals("800" to "m", fmtValueParts(800.0, ValueFormat.DISTANCE))
        assertEquals("42" to "min", fmtValueParts(2520.0, ValueFormat.SECONDS))
        assertEquals("45" to "s", fmtValueParts(45.0, ValueFormat.SECONDS))
        // a count speaks for itself and gets no unit
        assertNull(fmtValueParts(18.0, ValueFormat.COUNT).second)
    }

    @Test
    fun `an axis tick is shorter than the same value spelled out`() {
        // the axis drops the unit (the title carries it) and never mixes two time units
        assertEquals("108", fmtAxis(108.0, ValueFormat.KILOGRAMS))
        assertEquals("42m", fmtAxis(2520.0, ValueFormat.SECONDS))
        assertEquals("45s", fmtAxis(45.0, ValueFormat.SECONDS))
        assertEquals("5:00", fmtAxis(300.0, ValueFormat.PACE))
        assertEquals("5", fmtAxis(5000.0, ValueFormat.DISTANCE))
        assertEquals("12", fmtAxis(12.0, ValueFormat.COUNT))
    }

    @Test
    fun `the axis unit follows the magnitude so the title stays truthful`() {
        assertEquals("kg", axisUnit(ValueFormat.KILOGRAMS, 100.0))
        assertEquals("s", axisUnit(ValueFormat.SECONDS, 45.0))
        assertEquals("min", axisUnit(ValueFormat.SECONDS, 2520.0))
        assertEquals("h", axisUnit(ValueFormat.SECONDS, 7200.0))
        assertEquals("m", axisUnit(ValueFormat.DISTANCE, 800.0))
        assertEquals("km", axisUnit(ValueFormat.DISTANCE, 5000.0))
        assertEquals("", axisUnit(ValueFormat.COUNT, 9.0))
    }

    @Test
    fun `a delta is signed in plain ASCII, never with an arrow glyph`() {
        assertEquals("+6 kg", fmtDelta(6.0, ValueFormat.KILOGRAMS))
        assertEquals("-1.7 kg", fmtDelta(-1.7, ValueFormat.KILOGRAMS))
        assertEquals("+3", fmtDelta(3.0, ValueFormat.COUNT))
        // the project bans emoji, and a triangle that renders in colour is not worth it
        val rendered = fmtDelta(-1.7, ValueFormat.KILOGRAMS) + fmtDelta(6.0, ValueFormat.COUNT)
        assert(rendered.all { it.code < 128 }) { "delta must stay ASCII, got $rendered" }
    }

    @Test
    fun `a recent day reads in words and an old one as a date`() {
        val today = LocalDate.parse("2026-08-06")
        assertEquals("today", fmtRelativeDay(today, today))
        assertEquals("yesterday", fmtRelativeDay(today.minusDays(1), today))
        assertEquals("3 days ago", fmtRelativeDay(today.minusDays(3), today))
        // past a week nobody counts days, so an absolute date is more use
        assertEquals("14 Jul", fmtRelativeDay(today.minusDays(23), today))
    }

    @Test
    fun `a record badge date is never blank`() {
        val today = LocalDate.parse("2026-08-06")
        assertEquals("today", fmtRecordDate(today, today))
        assertEquals("yesterday", fmtRecordDate(today.minusDays(1), today))
        assertEquals("1 Aug", fmtRecordDate(today.minusDays(5), today))
    }

    @Test
    fun `a count loses its decimal tail when it is whole`() {
        assertEquals("12", fmtCount(12.0))
        assertEquals("12.5", fmtCount(12.46))
        assertEquals("0", fmtCount(0.0))
    }

    /*
     * There used to be a test here pinning the wording of `LOG_BUTTON_LABEL`, the floating
     * "Log a set" button. The button is gone: the primary action now lives on the card of
     * the thing being done, so it is named by what it acts on rather than in general. See
     * ui/Format.kt for the note that replaced the constant.
     */
}
