package xyz.oleolegka.gachimuchi.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.ui.theme.AccentDark
import xyz.oleolegka.gachimuchi.ui.theme.AccentLight
import xyz.oleolegka.gachimuchi.ui.theme.CalendarAheadDark
import xyz.oleolegka.gachimuchi.ui.theme.CalendarAheadLight
import xyz.oleolegka.gachimuchi.ui.theme.CalendarGoneDark
import xyz.oleolegka.gachimuchi.ui.theme.CalendarGoneLight

/**
 * The two tones a calendar cell can be filled with (§18.14), measured rather than eyeballed.
 *
 * ── The defect these pin ────────────────────────────────────────────────────────
 * The pair used to be `surface` and five percent black composited over `surface`. That is a
 * fixed fraction of the surface's own brightness, so it produced a contrast ratio of about
 * 1.06 in the light theme and 1.001 in the dark one — the dark theme's two tones were, for
 * practical purposes, one colour. "The tone of the day is indistinguishable by eye", from the
 * phone, 2026-08-11.
 *
 * A ratio is used rather than a difference in channel values because that is what the eye
 * reads: twelve levels apart near black is obvious and twelve levels apart near white is
 * nothing, and a single alpha cannot be right for both ends.
 */
class CalendarToneTest {

    /** WCAG relative contrast, which is what "can you see the difference" means numerically. */
    private fun contrast(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        val hi = maxOf(la, lb)
        val lo = minOf(la, lb)
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * Not a WCAG text threshold — nothing is read off these tones. It is the level at which a
     * flat fill next to another flat fill stops being deniable, and it is comfortably above
     * what the old pair managed in EITHER theme (1.06 light, 1.001 dark).
     */
    private val visible = 1.15

    @Test
    fun `a day gone is darker than a day ahead in both themes`() {
        assertTrue(
            "light: ahead must be the lighter tone",
            CalendarAheadLight.luminance() > CalendarGoneLight.luminance(),
        )
        assertTrue(
            "dark: ahead must be the lighter tone, the same way round as in light",
            CalendarAheadDark.luminance() > CalendarGoneDark.luminance(),
        )
    }

    @Test
    fun `the difference between the two tones is visible in both themes`() {
        val light = contrast(CalendarAheadLight, CalendarGoneLight)
        val dark = contrast(CalendarAheadDark, CalendarGoneDark)
        assertTrue("light tones too close: $light", light >= visible)
        assertTrue("dark tones too close: $dark", dark >= visible)
    }

    /**
     * The selected day is filled with `accent` outright, and it has to stay the loudest thing
     * on the grid: a selection that reads as "a slightly different grey" is not a selection.
     */
    @Test
    fun `the selected fill stands apart from both tones`() {
        assertTrue(contrast(AccentLight, CalendarAheadLight) >= 2.0)
        assertTrue(contrast(AccentLight, CalendarGoneLight) >= 2.0)
        assertTrue(contrast(AccentDark, CalendarAheadDark) >= 2.0)
        assertTrue(contrast(AccentDark, CalendarGoneDark) >= 2.0)
    }
}
