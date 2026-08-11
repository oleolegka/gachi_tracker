package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import xyz.oleolegka.gachimuchi.domain.Heatmap
import xyz.oleolegka.gachimuchi.domain.HeatmapDay
import xyz.oleolegka.gachimuchi.ui.fmtShortDay
import xyz.oleolegka.gachimuchi.ui.fmtShortMonth
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import xyz.oleolegka.gachimuchi.ui.theme.Spacing
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * A year of activity as a grid of weeks: columns are weeks, rows are Monday to Sunday.
 *
 * ── Drawn on one Canvas, not built out of 370 Boxes ─────────────────────────────
 * A year is 52 to 53 columns of 7 cells. As composables that is upwards of 370 layout
 * nodes inside a horizontal scroller, re-measured on every scroll frame. One Canvas draws
 * the same thing as rectangles and stays flat.
 *
 * ── The year is not squeezed to fit ─────────────────────────────────────────────
 * Cells keep their size and the grid scrolls sideways instead of shrinking to the screen
 * width. A year compressed into 360 px is a smear, and the whole point of the component is
 * that individual days stay distinguishable. The scroller opens at TODAY (the right-hand
 * end), because the recent weeks are the ones anyone looks at first — the web version
 * opens on the oldest week, which is the wrong default on a phone.
 *
 * ── Hover does not exist here ───────────────────────────────────────────────────
 * The design's tooltip is a CSS :hover, which a touchscreen never fires. A tap selects a
 * cell instead: it gets an outline and its date and count are spelled out in the caption
 * under the grid, which also keeps the information reachable for a screen reader.
 */
@Composable
fun ActivityHeatmapView(
    heatmap: Heatmap,
    today: LocalDate,
    modifier: Modifier = Modifier,
) {
    val colors = LocalGachiColors.current
    val measurer = rememberTextMeasurer()
    val scroll = rememberScrollState()
    var selected by remember(heatmap) { mutableStateOf<HeatmapDay?>(null) }

    /*
     * BELOW THE FLOOR OF THE TYPE SCALE, and left there deliberately.
     *
     * TextSize.Caption (11 sp) is the smallest size anything in this app is allowed to be, and
     * these two are 9. They are not type on a screen, they are labels pinned to a GRID: a
     * weekday label has to fit inside one cell of the ribbon (HeatmapMetrics.cell) and a month
     * label inside the width of one week's column. At 11 sp they overlap their neighbours and
     * the ribbon stops reading as a calendar. Raising them is a change to the geometry of the
     * chart — bigger cells, a taller ribbon, fewer weeks on the screen — and not a change of
     * type size, so it is not made here by renaming a constant.
     */
    val monthStyle = remember(colors) { TextStyle(fontSize = 9.sp, color = colors.inkMuted) }
    val dayStyle = remember(colors) { TextStyle(fontSize = 9.sp, color = colors.inkMuted) }

    // open on the most recent week rather than the oldest one, once the grid has been
    // measured (maxValue is 0 until then) and NOT again afterwards, so scrolling back
    // through the year is not undone by the next recomposition
    LaunchedEffect(heatmap.weeks) {
        val end = snapshotFlow { scroll.maxValue }.first { it > 0 }
        scroll.scrollTo(end)
    }

    Column(modifier) {
        Legend(heatmap.levels)

        Row(Modifier.fillMaxWidth().padding(top = Spacing.Line)) {
            WeekdayGutter(dayStyle)
            Box(Modifier.weight(1f).horizontalScroll(scroll)) {
                val gridWidth = HeatmapMetrics.widthFor(heatmap.weeks)
                Canvas(
                    Modifier
                        .width(gridWidth)
                        .height(HeatmapMetrics.gridHeight + HeatmapMetrics.monthRibbon + 3.dp)
                        .pointerInput(heatmap) {
                            detectTapGestures { offset ->
                                selected = cellAt(
                                    heatmap, offset,
                                    step = (HeatmapMetrics.cell + HeatmapMetrics.gap).toPx(),
                                    ribbon = (HeatmapMetrics.monthRibbon + 3.dp).toPx(),
                                )
                            }
                        }
                ) {
                    val cell = HeatmapMetrics.cell.toPx()
                    val step = cell + HeatmapMetrics.gap.toPx()
                    val radius = CornerRadius(HeatmapMetrics.radius.toPx(), HeatmapMetrics.radius.toPx())
                    val ribbon = (HeatmapMetrics.monthRibbon + 3.dp).toPx()

                    // the month ribbon: a label in the first column that contains a 1st
                    var previousMonth = -1
                    for (week in 0 until heatmap.weeks) {
                        val start = heatmap.weekStart(week) ?: continue
                        val monthOfWeek = (0..6).map { start.plusDays(it.toLong()) }
                            .firstOrNull { it.dayOfMonth == 1 } ?: continue
                        if (monthOfWeek.monthValue == previousMonth) continue
                        previousMonth = monthOfWeek.monthValue
                        val layout = measurer.measure(fmtShortMonth(monthOfWeek), monthStyle)
                        drawText(layout, topLeft = Offset(step * week, 0f))
                    }

                    for (week in 0 until heatmap.weeks) {
                        for (day in 0 until 7) {
                            val cellDay = heatmap.cell(week, day) ?: continue
                            val topLeft = Offset(step * week, ribbon + step * day)
                            drawRoundRect(
                                color = colors.forHeatmapLevel(cellDay.level),
                                topLeft = topLeft,
                                size = Size(cell, cell),
                                cornerRadius = radius,
                            )
                            if (cellDay.opDate == today.toString()) {
                                // today is ringed, because "the last cell" is otherwise
                                // indistinguishable from any other cell in the column
                                drawRoundRect(
                                    color = colors.accent,
                                    topLeft = topLeft,
                                    size = Size(cell, cell),
                                    cornerRadius = radius,
                                    style = Stroke(1.5.dp.toPx()),
                                )
                            }
                            if (cellDay.opDate == selected?.opDate) {
                                drawRoundRect(
                                    color = colors.inkSecondary,
                                    topLeft = Offset(topLeft.x - 1.5f, topLeft.y - 1.5f),
                                    size = Size(cell + 3f, cell + 3f),
                                    cornerRadius = radius,
                                    style = Stroke(2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
        }

        Text(
            selected?.let { "${fmtShortDay(LocalDate.parse(it.opDate))} - ${activityCount(it.count)}" }
                ?: "Tap a day to see what was logged",
            fontSize = TextSize.Caption,
            color = colors.inkMuted,
            modifier = Modifier.padding(top = Spacing.Line),
        )
    }
}

/** Plural that reads like English rather than "1 activities". */
private fun activityCount(count: Int): String = when (count) {
    0 -> "no activity"
    1 -> "1 activity"
    else -> "$count activities"
}

/** Which cell a tap landed on, or null for the gaps and the month ribbon. */
private fun cellAt(heatmap: Heatmap, offset: Offset, step: Float, ribbon: Float): HeatmapDay? {
    if (offset.y < ribbon) return null
    val week = (offset.x / step).toInt()
    val day = ((offset.y - ribbon) / step).toInt()
    if (day !in 0..6) return null
    return heatmap.cell(week, day)
}

/**
 * The fixed weekday ribbon. Only alternate days are labelled: seven 9 sp labels stacked in
 * 100 dp overlap, and Mon / Wed / Fri / Sun is enough to orient by.
 */
@Composable
private fun WeekdayGutter(style: TextStyle) {
    val labels = listOf("Mon", "", "Wed", "", "Fri", "", "Sun")
    Column(
        Modifier
            .width(HeatmapMetrics.weekdayGutter)
            .padding(top = HeatmapMetrics.monthRibbon + 3.dp, end = 6.dp)
    ) {
        labels.forEach { label ->
            Box(
                Modifier.height(HeatmapMetrics.cell).padding(bottom = 0.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (label.isNotEmpty()) {
                    Text(label, fontSize = style.fontSize, color = style.color, maxLines = 1)
                }
            }
            Box(Modifier.height(HeatmapMetrics.gap))
        }
    }
}

/**
 * "Less [][][][][] More" — the scale, right-aligned above the grid.
 *
 * The two words are 10 sp, below the scale's floor of 11, for the same reason the ribbon labels
 * are 9: they are set against the 11 dp swatches between them, and a word taller than the swatch
 * it labels turns a scale into a sentence. Same trade as above — geometry, not type.
 */
@Composable
private fun Legend(levels: Int) {
    val colors = LocalGachiColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Less", fontSize = 10.sp, color = colors.inkMuted, modifier = Modifier.padding(end = Spacing.Tight))
        for (level in 0..levels) {
            Box(
                Modifier
                    .padding(end = Spacing.Tight)
                    .size(11.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.forHeatmapLevel(level))
            )
        }
        Text("More", fontSize = 10.sp, color = colors.inkMuted, modifier = Modifier.padding(start = Spacing.Tight))
    }
}

/** The card the heatmap lives in, with its title and its empty state. */
@Composable
fun ActivityHeatmapCard(heatmap: Heatmap, today: LocalDate, modifier: Modifier = Modifier) {
    val colors = LocalGachiColors.current
    GachiCard(modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.Inset)) {
            Text(
                "Activity over the year",
                fontSize = TextSize.Title,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "all forms - tap a day",
                fontSize = TextSize.Caption,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = Spacing.Tight, bottom = Spacing.Inset),
            )
            ActivityHeatmapView(heatmap, today)
            if (heatmap.totalActivities == 0) {
                // the grid is still drawn: an empty year is a row of empty cells, not a
                // blank space, so it is obvious that the calendar exists and is waiting
                Text(
                    "Empty at the start is normal. A couple of squares will light up with " +
                        "your first workouts.",
                    fontSize = TextSize.Meta,
                    color = colors.inkMuted,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = Spacing.Line),
                )
            }
        }
    }
}
