package xyz.oleolegka.gachimuchi.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isUnspecified
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.ui.ScreenTest

/**
 * The two scales Material serves on the app's behalf: corner radius and type size.
 *
 * Both are handed to [MaterialTheme] rather than repeated at the call site, which means a
 * screen can be entirely on the system and still come out wrong if the theme stops providing
 * them — the failure the colour roles already demonstrated (see [ColorRolesTest]): an unset
 * slot falls back to a Material default nobody chose, silently, on a library upgrade.
 */
class ScaleTest : ScreenTest() {

    /** Both scales as a composable inside the theme sees them, read in ONE composition. */
    private fun theme(): Pair<Shapes, Typography> {
        lateinit var shapes: Shapes
        lateinit var typography: Typography
        screen {
            shapes = MaterialTheme.shapes
            typography = MaterialTheme.typography
        }
        return shapes to typography
    }

    @Test
    fun `Material rounds corners by the three radii of the system`() {
        val (shapes, _) = theme()
        assertEquals("a chip, a button, a badge", RoundedCornerShape(Radius.Small), shapes.small)
        assertEquals("a card of any kind", RoundedCornerShape(Radius.Card), shapes.medium)
        assertEquals("a dialog", RoundedCornerShape(Radius.Dialog), shapes.large)
        // Material's own names for the same two sizes: a dropdown menu and a modal sheet
        assertEquals(RoundedCornerShape(Radius.Small), shapes.extraSmall)
        assertEquals(RoundedCornerShape(Radius.Dialog), shapes.extraLarge)
    }

    @Test
    fun `every type slot the app asks for is one of the five sizes`() {
        val (_, typography) = theme()
        val scale = setOf(
            TextSize.Figure, TextSize.Title, TextSize.Body, TextSize.Meta, TextSize.Caption,
        )
        val slots = mapOf(
            "headlineSmall" to typography.headlineSmall,
            "titleMedium" to typography.titleMedium,
            "titleSmall" to typography.titleSmall,
            "bodyLarge" to typography.bodyLarge,
            "bodyMedium" to typography.bodyMedium,
            "bodySmall" to typography.bodySmall,
            "labelLarge" to typography.labelLarge,
            "labelMedium" to typography.labelMedium,
            "labelSmall" to typography.labelSmall,
        )
        val strays = slots.filterValues { it.fontSize !in scale }
            .map { (name, style) -> "$name = ${style.fontSize}" }
            .sorted()
        assertEquals("type sizes off the scale", emptyList<String>(), strays)
    }

    /**
     * The floor, checked on its own because it is the rule with a number in it: 11 sp is the
     * smallest type in this app, and the redraw found 7 sp on a live screen.
     *
     * This covers what the theme offers, INCLUDING the slots nobody asks for yet — a screen
     * reaching for one of those should not be handed something unreadable.
     */
    @Test
    fun `nothing the theme offers is smaller than the floor`() {
        val (_, typography) = theme()
        val everySlot: List<Pair<String, TextStyle>> = listOf(
            "displayLarge" to typography.displayLarge,
            "displayMedium" to typography.displayMedium,
            "displaySmall" to typography.displaySmall,
            "headlineLarge" to typography.headlineLarge,
            "headlineMedium" to typography.headlineMedium,
            "headlineSmall" to typography.headlineSmall,
            "titleLarge" to typography.titleLarge,
            "titleMedium" to typography.titleMedium,
            "titleSmall" to typography.titleSmall,
            "bodyLarge" to typography.bodyLarge,
            "bodyMedium" to typography.bodyMedium,
            "bodySmall" to typography.bodySmall,
            "labelLarge" to typography.labelLarge,
            "labelMedium" to typography.labelMedium,
            "labelSmall" to typography.labelSmall,
        )
        val tooSmall = everySlot
            .filter { (_, style) -> smallerThanFloor(style.fontSize) }
            .map { (name, style) -> "$name = ${style.fontSize}" }
        assertTrue("type below the 11 sp floor: $tooSmall", tooSmall.isEmpty())
    }

    private fun smallerThanFloor(size: TextUnit): Boolean =
        !size.isUnspecified && size.value < TextSize.Caption.value
}
