package xyz.oleolegka.gachimuchi.ui.theme

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The three scales every screen measures itself with: space, corner, type.
 *
 * They come from `design-system/app-next/SYSTEM.md`, which fixed them once so that four
 * people redrawing four screens would not arrive with four systems. Before it there were
 * twenty different spacings in the app (9, 11, 13, 15, 18 among them), four corner radii
 * chosen ad hoc, and type as small as 7 sp.
 *
 * A number typed straight into a screen is the thing this file exists to prevent: it looks
 * harmless, and it is how the twenty happened.
 */

/**
 * Space between things. Seven values, and nothing in between.
 *
 * The name says what the gap MEANS, because that is the only question with an answer at
 * the call site — "how many dp" has as many answers as there are authors.
 */
object Spacing {
    /** Inside one thought: a number and its caption, an icon and its label. */
    val Tight = 4.dp

    /** Between neighbouring lines of the same block. */
    val Line = 8.dp

    /** The inner padding of a card — how far its content sits from its own edge. */
    val Inset = 12.dp

    /** Between blocks inside one card, and the screen's own side margin. */
    val Block = 16.dp

    /** Between cards, and the inner padding of a dialog (which is a card in a hurry). */
    val Cards = 24.dp

    /** A major division of a screen. */
    val Section = 32.dp

    /** Air around an empty state, and the bottom of a scrolling screen. */
    val Empty = 48.dp
}

/**
 * Corner radius. Three values, and the radius says how big the element is — not who wrote it.
 *
 * These are for the odd place that has to build its own shape (a Canvas, a `clip`, a border
 * drawn by hand). Anything Material draws for itself takes them from [androidx.compose.material3.MaterialTheme.shapes],
 * which `GachimuchiTheme` fills from exactly these numbers.
 */
object Radius {
    /** Small elements: a chip, a button, a badge, a coloured tag. */
    val Small = 8.dp

    /** A card of any kind, and anything card-shaped (a panel, a picture, a tile). */
    val Card = 16.dp

    /** A dialog, and a sheet that behaves like one. */
    val Dialog = 28.dp
}

/**
 * Type sizes. Five, and the smallest is the smallest: **below 11 sp there is nothing.**
 *
 * Screens normally take their size from `MaterialTheme.typography` (see `GachiTypography`
 * in `Theme.kt`, which is built from these five). These constants are for the places that
 * still set `fontSize` by hand — a Canvas label, a number that has to be the crown of its
 * card — so that even those land on the scale.
 */
object TextSize {
    /** The one large number of a screen: the clock on the timer, the value of a hero card. */
    val Figure = 22.sp

    /** The title of a card. */
    val Title = 17.sp

    /** Body text. */
    val Body = 15.sp

    /** Secondary text and the metadata of a card. */
    val Meta = 13.sp

    /** A caption, and the floor: nothing in this app is smaller. */
    val Caption = 11.sp
}
