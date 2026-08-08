package xyz.oleolegka.gachimuchi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import xyz.oleolegka.gachimuchi.domain.HoldSet
import xyz.oleolegka.gachimuchi.domain.StrengthSet
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

    /**
     * An impulse prints its unit everywhere a number is printed.
     *
     * It used to be declared a [ValueFormat.COUNT] with "kg·s" glued onto the series label,
     * so the axis, the tile headline and the delta caption all showed a bare five-figure
     * number and only the card title said what it was measured in.
     */
    @Test
    fun `an impulse carries its kilogram-seconds through every way of printing it`() {
        assertEquals("2940 kg·s", fmtValue(2940.0, ValueFormat.KILOGRAM_SECONDS))
        assertEquals("2940" to "kg·s", fmtValueParts(2940.0, ValueFormat.KILOGRAM_SECONDS))
        assertEquals("2940", fmtAxis(2940.0, ValueFormat.KILOGRAM_SECONDS))
        assertEquals("kg·s", axisUnit(ValueFormat.KILOGRAM_SECONDS, 2940.0))
        assertEquals("+2940 kg·s", fmtDelta(2940.0, ValueFormat.KILOGRAM_SECONDS))
        assertEquals("-840 kg·s", fmtDelta(-840.0, ValueFormat.KILOGRAM_SECONDS))
    }

    /**
     * The unit does not follow the magnitude, and that is the point: the axis title is chosen
     * from a series' LARGEST value while every tick is formatted alone, so a compacting
     * threshold would print small ticks in one unit under a title naming another.
     */
    @Test
    fun `a big impulse is not compacted into thousands behind the axis title's back`() {
        assertEquals("kg·s", axisUnit(ValueFormat.KILOGRAM_SECONDS, 176_400.0))
        assertEquals("176400", fmtAxis(176_400.0, ValueFormat.KILOGRAM_SECONDS))
        // and a small tick of the same axis reads in the same unit as the title
        assertEquals("5000", fmtAxis(5_000.0, ValueFormat.KILOGRAM_SECONDS))
        // the fraction goes: a tenth of a kg·s is a hundredth of a second of hanging
        assertEquals("2941 kg·s", fmtValue(2940.6, ValueFormat.KILOGRAM_SECONDS))
        assertEquals("0 kg·s", fmtValue(0.0, ValueFormat.KILOGRAM_SECONDS))
    }

    /**
     * Kilograms and kilogram-seconds are different quantities with no exchange rate, and the
     * printing must never let one pass for the other — a tonnage and an impulse of the same
     * number are not the same amount of anything.
     */
    @Test
    fun `kilograms and kilogram-seconds never print the same`() {
        val kg = fmtValue(2940.0, ValueFormat.KILOGRAMS)
        val kgSec = fmtValue(2940.0, ValueFormat.KILOGRAM_SECONDS)
        assert(kg != kgSec) { "an impulse printed as a weight: $kg" }
        assert(!kgSec.endsWith(" kg")) { "an impulse must not end in kilograms: $kgSec" }
        assert(axisUnit(ValueFormat.KILOGRAM_SECONDS, 1.0) != axisUnit(ValueFormat.KILOGRAMS, 1.0))
        assertEquals("kg" to "kg·s", fmtValueParts(1.0, ValueFormat.KILOGRAMS).second
            to fmtValueParts(1.0, ValueFormat.KILOGRAM_SECONDS).second)
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

    /**
     * The defect this pins: added weight is a signed axis, and the summary line used to print
     * it as `" +${fmtKg(it)}"`. A band taking twenty kilograms off a pull-up therefore read
     * "body weight +-20 kg" — two signs in a row, on the one line that says what was lifted.
     */
    @Test
    fun `an added weight carries exactly one sign, whichever way it goes`() {
        assertEquals("+20 kg", fmtAddedKg(20.0))
        assertEquals("-20 kg", fmtAddedKg(-20.0))
        assertEquals("+2.5 kg", fmtAddedKg(2.5))
        assertEquals("-7.5 kg", fmtAddedKg(-7.5))
        listOf(20.0, -20.0, 2.5, -7.5, 0.04, -0.04).forEach { kg ->
            val signs = fmtAddedKg(kg).count { it == '+' || it == '-' }
            assertEquals("$kg must print exactly one sign, got ${fmtAddedKg(kg)}", 1, signs)
        }
    }

    /** The same, through the line a person actually reads in the day's feed. */
    @Test
    fun `assistance reads as one minus on the summary line of a set`() {
        val assisted = StrengthSet(
            exercise = "Pull-ups", reps = 5, ownWeight = true, addedKg = -20.0,
            opDate = "2026-08-07",
        )
        assertEquals("body weight -20 kg × 5 reps", assisted.summaryLine())

        val weighted = assisted.copy(addedKg = 10.0)
        assertEquals("body weight +10 kg × 5 reps", weighted.summaryLine())

        // a hang off a band: the negative half of the axis is where most hangboard work is
        val hang = HoldSet(activity = "Hangs 20 mm", reps = 4, addedKg = -15.0, opDate = "2026-08-07")
        assertEquals("-15 kg, 4 reps", hang.summaryLine())
        assertEquals("+15 kg, 4 reps", hang.copy(addedKg = 15.0).summaryLine())
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
