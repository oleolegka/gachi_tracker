package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Radius
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * The dashboard building blocks of `design/design-system/dashboard/`: the card shell, the
 * hero row, the door tile, the stat card, the record badge, the segment control and the
 * empty state.
 *
 * ── Why these are not Material components ───────────────────────────────────────
 * M3's Card, FilterChip and SegmentedButton all carry their own elevation, shape and
 * tonal-colour behaviour, and every one of those fights the design system: the surfaces
 * here are flat with a hairline ring, the "raised" segment is raised by a shadow of a
 * specific size, and the tonal palette is fixed by `research_visual.md` §6. Re-skinning
 * the Material versions into that costs more code than drawing them, and leaves defaults
 * that come back on a library upgrade.
 *
 * ── Font weights ────────────────────────────────────────────────────────────────
 * The design uses the variable-font weights 550 and 650, which the system typeface does
 * not have. They are mapped to [FontWeight.Medium] and [FontWeight.SemiBold], which
 * collapses the distinction between 600 and 650. That is survivable because everywhere
 * the two meet they also differ in size by a factor of two or more.
 */

// --- shared type scale -------------------------------------------------------------------

/**
 * The uppercase, wide-tracked caption above a block ("FORMS - TAP FOR DETAILS").
 * The tracking is what makes 11 sp uppercase readable rather than a grey brick.
 */
val EyebrowStyle = TextStyle(
    fontSize = TextSize.Caption, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp,
)


/**
 * The card shell: flat surface, hairline ring, no elevation.
 *
 * Elevation is deliberately absent — the design separates a card from the page with a
 * 10 % ink ring and a slightly lighter surface, which stays legible in the dark theme
 * where an M3 shadow is invisible anyway.
 */
@Composable
fun GachiCard(
    modifier: Modifier = Modifier,
    radius: Dp = Radius.Card,
    background: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = LocalGachiColors.current
    Column(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(background ?: MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.border, RoundedCornerShape(radius)),
        content = content,
    )
}

/**
 * The caption row above a block: an eyebrow on the left, a quiet note on the right, and
 * optionally the one control that belongs to the block itself.
 *
 * [action] exists because of what the overview did without it: the way into the exercise
 * catalog was a full-width button placed UNDER this header, so the header captioned the
 * button and the feed it was written for started below both. A control that belongs to a
 * section belongs on the section's own line.
 */
@Composable
fun SectionHeader(
    title: String,
    note: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val colors = LocalGachiColors.current
    Row(
        modifier.fillMaxWidth().padding(bottom = Spacing.Line),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Line),
        verticalAlignment = if (action == null) Alignment.Bottom else Alignment.CenterVertically,
    ) {
        Text(
            title.uppercase(),
            style = EyebrowStyle,
            color = colors.inkMuted,
            modifier = Modifier.weight(1f),
        )
        if (note != null) {
            Text(note, fontSize = TextSize.Caption, color = colors.inkMuted, maxLines = 1)
        }
        action?.invoke()
    }
}

// --- hero row ------------------------------------------------------------------------------

/**
 * The top of the overview: one number large enough that nothing competes with it.
 *
 * "Score-first" (§4.1): the headline is the count of workouts in the window, the unit sits
 * beside it at less than half the size, and everything else is a caption. There is no
 * streak ring — decisions.md §12-C removed it from this screen (the design-system mock-up
 * still shows one and is out of date on that point).
 */
@Composable
fun HeroCard(
    eyebrow: String,
    value: String,
    unit: String,
    subtitle: String,
    meta: String?,
    modifier: Modifier = Modifier,
    highlight: String? = null,
) {
    val colors = LocalGachiColors.current
    GachiCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.Block)) {
            Text(
                eyebrow.uppercase(),
                style = EyebrowStyle,
                color = colors.inkMuted,
            )
            Row(Modifier.padding(top = Spacing.Line), verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    // Figure, not Display: the owner compared the two side by side in the
                    // design system (app-next/hero-size.html) and chose the quieter one.
                    // Display stays what SYSTEM.md says it is - the conductor's clock, read
                    // from two metres with wet hands - and this card is read at arm's length.
                    fontSize = TextSize.Figure,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-1.5).sp,
                    lineHeight = TextSize.Figure,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    " $unit",
                    fontSize = TextSize.Figure,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkSecondary,
                    modifier = Modifier.padding(bottom = Spacing.Tight),
                )
            }
            Text(
                subtitle,
                fontSize = TextSize.Body,
                color = colors.inkSecondary,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = Spacing.Line),
            )
            if (meta != null) {
                Text(
                    meta,
                    fontSize = TextSize.Meta,
                    color = colors.inkMuted,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }
            if (highlight != null) {
                Row(
                    Modifier.padding(top = Spacing.Line),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
                ) {
                    CheckGlyph(colors.goodText, 13.dp)
                    Text(highlight, fontSize = TextSize.Meta, fontWeight = FontWeight.SemiBold, color = colors.goodText)
                }
            }
        }
    }
}

// --- record badge ----------------------------------------------------------------------------

/**
 * The record pill: a tick, the word, and THE DATE.
 *
 * The date is not decoration — decisions.md §12-C forbids a bare badge outright, because
 * "record" with no date is indistinguishable from "record set eighteen months ago". The
 * design-system component omits it; this is a deliberate departure from the mock-up in
 * favour of the written decision.
 *
 * Colour never carries the meaning alone: the tick and the word say "record" without it.
 *
 * The corner is [Radius.Small] and not a circle. In this system the radius states the SIZE
 * of a thing rather than its character, and a fully rounded pill was the only shape on the
 * overview that disagreed with the chips and badges of every other screen.
 */
@Composable
fun RecordBadge(date: String?, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    val shape = RoundedCornerShape(Radius.Small)
    Row(
        modifier
            .clip(shape)
            .background(colors.good.copy(alpha = 0.13f))
            .border(1.dp, colors.good.copy(alpha = 0.34f), shape)
            .padding(horizontal = Spacing.Line, vertical = Spacing.Tight),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        CheckGlyph(colors.goodText, 11.dp)
        Text(
            if (date == null) "Record" else "Record - $date",
            fontSize = TextSize.Caption,
            fontWeight = FontWeight.Bold,
            color = colors.goodText,
            maxLines = 1,
        )
    }
}

/** A tick drawn rather than imported: the icon pack has no stroke weight this light. */
@Composable
private fun CheckGlyph(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawPath(
            Path().apply {
                moveTo(w * 0.21f, w * 0.55f)
                lineTo(w * 0.42f, w * 0.78f)
                lineTo(w * 0.79f, w * 0.28f)
            },
            color = color,
            style = Stroke(width = w * 0.15f, cap = StrokeCap.Round),
        )
    }
}

// --- door tile ---------------------------------------------------------------------------------

/**
 * A row of the overview feed: a summary and the way into the form's detail screen.
 *
 * The coloured SPINE on the left carries the form identity, but never on its own — the
 * caption spells the form out in words, so the tile is readable with any colour vision.
 * The chevron is the affordance that says the whole row is a door.
 */
@Composable
fun DoorTile(
    name: String,
    caption: String,
    value: String,
    unit: String?,
    delta: String?,
    spineColor: Color,
    recordDate: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    figure: @Composable () -> Unit,
) {
    val colors = LocalGachiColors.current
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp)
            .clip(RoundedCornerShape(Radius.Card))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.Card))
            .clickable(onClick = onClick)
            .padding(end = Spacing.Inset),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(4.dp)
                .heightIn(min = 80.dp)
                .clip(RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .background(spineColor),
        )
        Column(
            Modifier.weight(1f).padding(
                start = Spacing.Inset, top = Spacing.Inset, bottom = Spacing.Inset,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            /*
             * The name is the TITLE of this tile, and it took until the redraw to be sized
             * like one: the name was 15 and the value on the right was 18, so a tile
             * announced how much louder than what. They are both 17 now and told apart by
             * weight, position and the tabular digits on the right - two equal facts, "what"
             * and "how much".
             */
            Text(
                name,
                fontSize = TextSize.Title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            /*
             * THE BADGE IS A ROW OF ITS OWN, always under the name.
             *
             * It used to share a FlowRow with the name, which put it to the RIGHT of a short
             * name and UNDER a long one — the same tile in two different shapes depending on
             * how many letters the exercise happens to be called by, and the feed then read as
             * a column of misaligned pills. Owner's report, 2026-08-11: "sometimes underneath,
             * sometimes to the right. It has to be underneath, always."
             *
             * Under the name rather than under the caption because that is where the wrapped
             * case already put it, and because the badge is about the exercise (this name has a
             * record) rather than about the form-and-recency line below.
             */
            if (recordDate != null) RecordBadge(recordDate)
            Text(caption, fontSize = TextSize.Meta, color = colors.inkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Column(
            Modifier.padding(
                start = Spacing.Line, top = Spacing.Inset, bottom = Spacing.Inset,
            ),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(Spacing.Tight),
        ) {
            Box(Modifier.width(76.dp).height(22.dp)) { figure() }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = TextSize.Title,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (unit != null) {
                    Text(
                        " $unit",
                        fontSize = TextSize.Caption,
                        fontWeight = FontWeight.Medium,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(bottom = 1.dp),
                    )
                }
            }
            if (delta != null) {
                Text(delta, fontSize = TextSize.Caption, fontWeight = FontWeight.SemiBold, color = colors.goodText)
            }
        }
        Chevron(Modifier.padding(start = Spacing.Line))
    }
}

@Composable
private fun Chevron(modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Canvas(modifier.size(16.dp)) {
        val w = size.width
        drawPath(
            Path().apply {
                moveTo(w * 0.38f, w * 0.19f)
                lineTo(w * 0.69f, w * 0.5f)
                lineTo(w * 0.38f, w * 0.81f)
            },
            color = colors.inkMuted,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round),
        )
    }
}

/** The bar figure of a duration tile: totals have no shape, so they are drawn as columns. */
@Composable
fun MiniBars(values: List<Double>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        if (values.isEmpty()) return@Canvas
        val top = values.max().takeIf { it > 0 } ?: 1.0
        val slot = size.width / values.size
        val barWidth = (slot * 0.68f).coerceAtLeast(1f)
        for (i in values.indices) {
            val h = (size.height * (values[i] / top)).toFloat().coerceAtLeast(2f)
            drawRoundRect(
                color = color,
                topLeft = Offset(slot * i + (slot - barWidth) / 2f, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx()),
            )
        }
    }
}

/**
 * The dot figure of a check-in tile: one dot per day, filled when it happened.
 *
 * A line would imply a magnitude; a check-in has none (§3), so the pattern of days IS the
 * data. Hollow dots keep the missed days visible instead of collapsing the row.
 */
@Composable
fun MiniDots(present: List<Boolean>, color: Color, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Canvas(modifier.fillMaxWidth().height(22.dp)) {
        if (present.isEmpty()) return@Canvas
        val slot = size.width / present.size
        val r = minOf(3.dp.toPx(), slot / 2f - 0.5f).coerceAtLeast(1f)
        for (i in present.indices) {
            val center = Offset(slot * (i + 0.5f), size.height / 2f)
            if (present[i]) {
                drawCircle(color, radius = r, center = center)
            } else {
                drawCircle(colors.recessed, radius = r, center = center)
                drawCircle(colors.axis, radius = r, center = center, style = Stroke(1.dp.toPx()))
            }
        }
    }
}

// --- stat card -----------------------------------------------------------------------------------

/**
 * The two-column tile of the records block and of Today: a label, one number, and the date
 * the number belongs to.
 *
 * [when] is a separate line rather than part of the badge because a stat card is also used
 * for things that are NOT records (current body weight, sets in the period), and those
 * need a date just as much.
 */
@Composable
fun StatCard(
    label: String,
    value: String,
    unit: String? = null,
    delta: String? = null,
    `when`: String? = null,
    badge: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    GachiCard(modifier.heightIn(min = 96.dp)) {
        Column(Modifier.padding(Spacing.Inset)) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = Spacing.Line),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Text(
                    label,
                    fontSize = TextSize.Caption,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.inkSecondary,
                    lineHeight = 14.sp,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (badge) RecordBadge(null, Modifier.padding(start = Spacing.Line))
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value,
                    fontSize = TextSize.Figure,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.5).sp,
                    lineHeight = 28.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (unit != null) {
                    Text(
                        " $unit",
                        fontSize = TextSize.Meta,
                        fontWeight = FontWeight.Medium,
                        color = colors.inkMuted,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
            }
            if (delta != null) {
                Text(
                    delta,
                    fontSize = TextSize.Caption,
                    fontWeight = FontWeight.SemiBold,
                    color = colors.goodText,
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }
            if (`when` != null) {
                Text(
                    `when`,
                    fontSize = TextSize.Caption,
                    color = colors.inkMuted,
                    modifier = Modifier.padding(top = Spacing.Tight),
                )
            }
        }
    }
}

// --- segment control -------------------------------------------------------------------------------

/**
 * The period switch: a recessed track with one raised, selected pill.
 *
 * The tap target is 44 dp rather than the design's 38 px — a 38 dp control is below the
 * Material minimum and this one sits at the top of a scrolling screen, which is where a
 * mis-tap is most annoying. The visual pill keeps the design's height; the extra height is
 * padding around it.
 */
@Composable
fun <T> SegmentControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Small))
            .background(colors.recessed)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.Small))
            .padding(Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        options.forEach { option ->
            val isOn = option == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(Radius.Small))
                    .then(
                        if (isOn) Modifier
                            .background(MaterialTheme.colorScheme.surface)
                            .border(1.dp, colors.border, RoundedCornerShape(Radius.Small))
                        else Modifier
                    )
                    .clickable { onSelect(option) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label(option),
                    fontSize = TextSize.Meta,
                    fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (isOn) MaterialTheme.colorScheme.onSurface
                    else colors.inkSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}

// --- chips -----------------------------------------------------------------------------------------------

/**
 * A small pill chip with a selected state, outlined in [accent] when chosen.
 *
 * It started life as the §12-A hold-sibling switcher's own chip (comparing hangboard
 * exercises by edge — the switcher and the edge attribute it compared are both gone now, see
 * `MIGRATION_17_18` in `data/db/AppDatabase.kt`), but it is a generic reusable component: the
 * plan editor also uses it for name suggestions and quick-time picking (`SlotEditor.kt`), and
 * neither of those is hangboard-related. Kept as-is on purpose — only the one real §12-A call
 * site left with the feature it served.
 */
@Composable
fun SiblingChip(
    text: String,
    selected: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    Box(
        modifier
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.surface else colors.recessed
            )
            .border(1.dp, if (selected) accent else colors.border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.Inset, vertical = Spacing.Line),
    ) {
        Text(
            text,
            fontSize = TextSize.Caption,
            fontWeight = FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onSurface
            else colors.inkSecondary,
            maxLines = 1,
        )
    }
}

/** An identity fact of a hangboard exercise: "protocol 7:3", "metric weight". */
@Composable
fun IdentityChip(label: String, value: String, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Row(
        modifier
            .clip(RoundedCornerShape(Radius.Small))
            .background(colors.recessed)
            .border(1.dp, colors.border, RoundedCornerShape(Radius.Small))
            .padding(horizontal = Spacing.Line, vertical = Spacing.Tight),
        horizontalArrangement = Arrangement.spacedBy(Spacing.Tight),
    ) {
        Text(label, fontSize = TextSize.Meta, fontWeight = FontWeight.Medium, color = colors.inkMuted)
        Text(
            value,
            fontSize = TextSize.Meta,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** The small print that explains a modelling decision on a detail screen. */
@Composable
fun NoteText(text: String, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Text(text, fontSize = TextSize.Caption, color = colors.inkMuted, lineHeight = 16.sp, modifier = modifier)
}

// --- empty state ---------------------------------------------------------------------------------------

/**
 * "There is nothing here yet" — never "something went wrong".
 *
 * §4.4 asks for a friendly, apology-free tone that invites the next action, and for empty
 * to be visibly different from loading. This is the empty half; loading is a separate
 * concern the app does not currently have (the database answers instantly).
 */
@Composable
fun EmptyState(
    title: String,
    hint: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    val colors = LocalGachiColors.current
    GachiCard(modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(
                horizontal = Spacing.Block, vertical = Spacing.Cards,
            ),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = colors.inkMuted.copy(alpha = 0.55f),
                    modifier = Modifier.size(40.dp).padding(bottom = Spacing.Inset),
                )
            }
            Text(
                title,
                fontSize = TextSize.Title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                lineHeight = TextSize.Title * 1.25f,
            )
            Text(
                hint,
                fontSize = TextSize.Body,
                color = colors.inkSecondary,
                lineHeight = 18.sp,
                modifier = Modifier.padding(top = Spacing.Line),
            )
        }
    }
}

/** A dashed placeholder for an empty slot INSIDE a populated screen (an empty calendar day). */
@Composable
fun DashedNote(text: String, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.Card))
            .dashedBorder(colors.axis, Radius.Card)
            .padding(Spacing.Inset),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = TextSize.Meta, color = colors.inkMuted)
    }
}

/** A dashed hairline ring; Compose has no dashed border modifier of its own. */
fun Modifier.dashedBorder(color: Color, radius: Dp): Modifier = this.then(
    Modifier.drawBehindDashed(color, radius)
)

private fun Modifier.drawBehindDashed(color: Color, radius: Dp) = drawBehind {
    val stroke = Stroke(
        width = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(
            floatArrayOf(4.dp.toPx(), 4.dp.toPx())
        ),
    )
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx(), radius.toPx()),
        style = stroke,
    )
}
