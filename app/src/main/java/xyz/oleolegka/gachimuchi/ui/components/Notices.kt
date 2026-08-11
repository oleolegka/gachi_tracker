package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * Ink at its full strength, stated as a literal because it has to survive the DARK theme too.
 *
 * `warning` (#FAB219) is the one status colour the palette does NOT redefine per theme, so a
 * patch of it is a light patch in both. Text on it must therefore be dark in both, which
 * `onSurface` is not — in the dark theme that role is white, and white on #FAB219 measures
 * 1.96:1. The same literal is used by SetTable's badge for the same reason.
 */
private val InkOnWarning = Color(0xFF0B0B0B)

/**
 * A message the app must not let the reader skip: a write that failed, a picker that came back
 * empty-handed.
 *
 * ── Why a fill and not coloured type ────────────────────────────────────────────
 * Every one of these used to be `warning`-coloured text sitting on a light surface, which
 * measures **1.6 to 1.8:1** — under the 4.5:1 floor by a factor of three, on the one line of
 * the screen written specifically to be noticed. The colour was doing the shouting and the
 * legibility was paying for it.
 *
 * Turned inside out, the same colour becomes the FILL and the type goes black: 10.7:1 in both
 * themes. The message reads as a warning at a glance (the yellow patch) and reads as words up
 * close (black on yellow), which coloured type managed neither of.
 *
 * Reserved for what has gone wrong. A note that merely wants attention takes [RailNote] — a
 * screen where three different things are filled yellow has no emphasis left to spend.
 */
@Composable
fun WarningNotice(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Small))
            .background(LocalGachiColors.current.warning)
            .padding(Spacing.Inset)
    ) {
        Text(
            text,
            fontSize = TextSize.Meta,
            fontWeight = FontWeight.Medium,
            color = InkOnWarning,
        )
    }
}

/**
 * A remark about the numbers next to it — not an error, but not ordinary prose either.
 *
 * The rail carries the colour and the words stay ink, which is the whole trick: contrast comes
 * from the type being ink on a recessed surface (17.1:1) rather than from the type being the
 * status colour, and the status colour still says at a glance what kind of remark this is.
 *
 * Set at [TextSize.Meta] and not at the caption floor. These notes are read while a decision is
 * being made about the field above them — 11 sp is for a label that names something already
 * understood, not for a sentence someone has to weigh.
 */
@Composable
fun RailNote(text: String, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(0.dp, Radius.Small, Radius.Small, 0.dp))
            .background(colors.recessed)
    ) {
        Spacer(
            Modifier
                .width(Spacing.Tight)
                .fillMaxHeight()
                .background(colors.warning)
        )
        Text(
            text,
            fontSize = TextSize.Meta,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = Spacing.Inset, vertical = Spacing.Line),
        )
    }
}
