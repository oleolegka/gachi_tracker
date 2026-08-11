package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config
import xyz.oleolegka.gachimuchi.ui.ScreenTest

/**
 * Where the record pill sits on an overview tile: UNDER the name, whatever the name is.
 *
 * ── The defect this pins down ────────────────────────────────────────────────────
 * The pill used to share a `FlowRow` with the exercise name, so it landed to the RIGHT of a
 * short name and wrapped UNDER a long one. Reported from the phone on 2026-08-11: "sometimes
 * underneath, sometimes to the right. It has to be underneath, always." A feed whose pills
 * jump between two positions depending on the length of a word cannot be scanned down.
 *
 * ── Why the assertion is geometric and not a semantics lookup ────────────────────
 * "Below" is not a thing the semantics tree states; both arrangements produce exactly the same
 * two nodes with exactly the same text. Only the bounds tell them apart, which is also the one
 * kind of check that would have caught this before it reached a phone.
 *
 * Both widths are exercised because the wrap is a function of the width: at 411 dp a name has
 * to be long to push the pill down, at 360 dp less so, and the point of the change is that
 * neither width decides anything any more (SYSTEM.md rule 8).
 */
class DoorTileRecordTest : ScreenTest() {

    private val shortName = "Row"

    /** Long enough to take more than one line at either width, and to have wrapped before. */
    private val longName = "One-arm hang on a twenty millimetre edge with added weight"

    private fun tile(name: String) {
        screen {
            DoorTile(
                name = name,
                caption = "hangs - yesterday",
                value = "12",
                unit = "kg",
                delta = null,
                spineColor = Color(0xFF3B82F6),
                recordDate = "8 Aug",
                onClick = {},
            ) {}
        }
    }

    /*
     * THE UNMERGED TREE, because the tile is `clickable` and that MERGES its descendants: on
     * the merged tree both lookups below resolve to the one node covering the whole tile, and
     * every arrangement of its insides then measures identical. The first version of this test
     * passed the same bounds to itself twice and was comparing the tile with itself.
     */
    private fun boundsOf(text: String): DpRect =
        compose.onNodeWithText(text, substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()

    private fun assertPillUnderName(name: String) {
        val title = boundsOf(name)
        // the pill reads "Record - 8 Aug"; nothing else on the tile carries the word
        val pill = boundsOf("Record")

        // only the vertical relation is asserted: the pill's own text sits inside the pill's
        // padding, so its left edge is legitimately a few dp in from the name's and a
        // horizontal assertion would be measuring the badge's insets rather than the layout
        assertTrue(
            "the record pill must start below the name, not beside it " +
                "(name bottom ${title.bottom}, pill top ${pill.top})",
            pill.top >= title.bottom,
        )
    }

    @Test
    fun `a short name does not pull the pill up beside it`() {
        tile(shortName)
        assertPillUnderName(shortName)
    }

    @Test
    fun `a long name keeps the pill in the same place a short one does`() {
        tile(longName)
        assertPillUnderName(longName)
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun `a short name on the narrow phone keeps the pill underneath`() {
        tile(shortName)
        assertPillUnderName(shortName)
    }

    @Test
    @Config(qualifiers = "w360dp-h740dp-xhdpi")
    fun `a long name on the narrow phone keeps the pill underneath`() {
        tile(longName)
        assertPillUnderName(longName)
    }
}
