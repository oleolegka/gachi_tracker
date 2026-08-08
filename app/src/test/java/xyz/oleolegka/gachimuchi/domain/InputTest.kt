package xyz.oleolegka.gachimuchi.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Parsing of what gets typed into the entry card. These are the functions standing
 * between a fat-fingered field and a journal write, so they are tested on the ugly
 * inputs rather than the neat ones.
 */
class InputTest {

    @Test
    fun `numbers survive a comma, spaces and an empty field`() {
        assertEquals(62.5, parseNumber("62.5")!!, 1e-9)
        assertEquals(62.5, parseNumber(" 62,5 ")!!, 1e-9) // a comma keyboard must not lose a set
        assertNull(parseNumber(""))
        assertNull(parseNumber("-"))
        assertNull(parseNumber("kg"))
    }

    @Test
    fun `counts round to whole numbers and reject negatives`() {
        assertEquals(5, parseCount("5"))
        assertEquals(5, parseCount("4.6"))
        assertNull(parseCount("-2"))
        assertNull(parseCount(""))
    }

    @Test
    fun `formatting trims the trailing zeros so the field stays readable`() {
        assertEquals("60", formatNumber(60.0))
        assertEquals("62.5", formatNumber(62.5))
        assertEquals("0.1", formatNumber(0.1))
    }

    @Test
    fun `step buttons add, subtract and never go below zero`() {
        assertEquals("62.5", applyStep("60", 2.5))
        assertEquals("57.5", applyStep("60", -2.5))
        // an empty field counts as zero: the first tap gives the step itself
        assertEquals("5", applyStep("", 5.0))
        assertEquals("0", applyStep("2", -5.0))
        // no floating point rubbish leaking into the field
        assertEquals("76.4", applyStep("76.5", -0.1))
    }

    @Test
    fun `pace reads both minutes-seconds and bare seconds`() {
        assertEquals(270.0, parsePace("4:30")!!, 1e-9)
        assertEquals(270.0, parsePace("270")!!, 1e-9)
        assertEquals("4:30", formatPace(270.0))
        assertNull(parsePace("4:75")) // 75 seconds is a typo, not a pace
        assertNull(parsePace(""))
    }

    /**
     * A protocol is stored as a program and a program's steps are whole seconds, so the
     * fraction has to go somewhere. It goes to the NEAREST whole second here, at the field,
     * rather than to truncation later inside the store, where 7.6 would quietly become 7.
     */
    @Test
    fun `protocol seconds are whole, and rounded rather than truncated`() {
        assertEquals(8.0, parseProtocolSeconds("7.6")!!, 1e-9)
        assertEquals(7.0, parseProtocolSeconds("7.4")!!, 1e-9)
        assertEquals(7.0, parseProtocolSeconds("7")!!, 1e-9)
        assertEquals(8.0, parseProtocolSeconds("7,6")!!, 1e-9) // the comma keyboard, too
        // a protocol of zero seconds is not a protocol; the set validator rejects it outright
        assertNull(parseProtocolSeconds("0"))
        assertNull(parseProtocolSeconds("0.4"))
        assertNull(parseProtocolSeconds("-3"))
        assertNull(parseProtocolSeconds(""))
        assertNull(parseProtocolSeconds("-"))
    }
}
