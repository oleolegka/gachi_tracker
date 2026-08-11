package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * WHY the button under it is grey — one short line, beside the button it is about.
 *
 * ── The failure it closes ───────────────────────────────────────────────────────
 * A confirm button gated on `enabled = <expression>` and nothing else is a screen that says
 * no without saying what is missing. Both screens redrawn against
 * `design-system/app-next/SYSTEM.md` had exactly that — the program editor's
 * `name.isNotBlank() && totalSec() > 0`, the create form's three-branch gate — and the user's
 * only way to find the answer was to fill fields at random until the grey went away.
 *
 * ── Why a strip and not a sentence under the field ──────────────────────────────
 * It is not attached to any ONE field: what is missing may be the name, or a number three
 * cards up, or a choice that has not been made at all. So it sits where the refusal is
 * visible — immediately above the button — and the coloured rule on its left is what stops
 * it reading as one more piece of explanatory prose (rule 5: an explanation is not a
 * paragraph, and this is one line).
 *
 * The colour is [xyz.oleolegka.gachimuchi.ui.theme.GachiColors.warning] and never
 * `critical`: nothing has gone wrong, a form is simply not finished yet. It is never colour
 * alone either — the words say the whole of it, which is the point of the component.
 */
@Composable
fun MissingNote(text: String, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Small))
            .background(colors.recessed)
            .height(IntrinsicSize.Min),
    ) {
        Box(
            Modifier
                .width(Spacing.Tight)
                .fillMaxHeight()
                .background(colors.warning),
        )
        Text(
            text,
            fontSize = TextSize.Meta,
            color = colors.inkSecondary,
            modifier = Modifier
                .padding(Spacing.Inset)
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}
