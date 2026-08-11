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
import androidx.compose.ui.graphics.Brush
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
     * AT THE FLOOR OF THE TYPE SCALE, and the geometry moved to let them be.
     *
     * These two were 9 sp, with a comment arguing that a label pinned to a grid is not type
     * on a screen. The argument was answered rather than repeated: raising them IS a change
     * to the geometry of the chart, so the geometry changed with them. The gutter went from
     * 20 dp to 28 (HeatmapMetrics.weekdayGutter) so that "Wed" still fits, and the year lost
     * half a week of visible width for it.
     *
     * A month label still sits over a 14 dp column and is therefore still wider than its own
     * column - it was at 9 sp too. It is drawn on the ribbon in reading order and each label
     * simply starts at its week, so a long month name overhangs the next column rather than
     * overlapping another label; only one label per month is drawn, so there is nothing
     * underneath it to collide with.
     */
    val monthStyle = remember(colors) { TextStyle(fontSize = TextSize.Caption, color = colors.inkMuted) }
    val dayStyle = remember(colors) { TextStyle(fontSize = TextSize.Caption, color = colors.inkMuted) }

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
            Box(Modifier.weight(1f)) {
              Box(Modifier.horizontalScroll(scroll)) {
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
              /*
               * THE YEAR IS ALWAYS WIDER THAN THE PHONE, and until now nothing said so.
               *
               * 53 weeks at a 14 dp step is 739 dp against the 327 a 411 dp phone has left
               * after margins, card padding and the gutter — 23 columns, 43 % of the year;
               * on a 360 dp phone, 19 columns and 36 %. The grid opens at TODAY, so the part
               * that is missing is off to the LEFT, and a straight cut at the edge of a card
               * looks like the edge of the data.
               *
               * A fade over the cut column says "this continues" the way a straight edge
               * cannot. It is drawn only while there is something to the left: at the first
               * week of the year the grid really does end there, and a shadow over an edge
               * that is genuinely the end would be a lie in the other direction.
               *
               * Honest limit: there is no such mark on the RIGHT, because the right edge is
               * today and nothing is hidden past it — but that also means a reader who has
               * scrolled all the way back to week one sees a plain edge on both sides and
               * has, again, no sign that the year continues. Fixing that properly wants a
               * scrollbar or an "Aug 2025 - Aug 2026" range line; both cost a row on a card
               * that already has three.
               */
              if (scroll.value > 0) {
                  Box(
                      Modifier
                          .align(Alignment.CenterStart)
                          .width(Spacing.Cards)
                          .height(HeatmapMetrics.gridHeight + HeatmapMetrics.monthRibbon + 3.dp)
                          .background(
                              Brush.horizontalGradient(
                                  listOf(
                                      MaterialTheme.colorScheme.surface,
                                      MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                                  )
                              )
                          )
                  )
              }
            }
        }

        Text(
            selected?.let { "${fmtShortDay(LocalDate.parse(it.opDate))} - ${activityCount(it.count)}" }
                ?: "Tap a day to see what was logged",
            // Meta, not Caption: this is not an axis label, it is the ONLY place the result
            // of tapping a cell is ever spelled out.
            fontSize = TextSize.Meta,
            color = if (selected == null) colors.inkMuted else colors.inkSecondary,
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
 * The fixed weekday ribbon. Only alternate days are labelled: seven labels stacked in 100 dp
 * overlap at any size, and Mon / Wed / Fri / Sun is enough to orient by.
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
 * The two words were 10 sp, on the argument that a word taller than the 11 dp swatch it labels
 * turns a scale into a sentence. They are 11 now, like everything else: 11 sp caps are about
 * 8 dp tall, which is SHORTER than the swatch, so the argument did not survive being measured.
 */
@Composable
private fun Legend(levels: Int) {
    val colors = LocalGachiColors.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Less", fontSize = TextSize.Caption, color = colors.inkMuted, modifier = Modifier.padding(end = Spacing.Tight))
        for (level in 0..levels) {
            Box(
                Modifier
                    .padding(end = Spacing.Tight)
                    .size(11.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.forHeatmapLevel(level))
            )
        }
        Text("More", fontSize = TextSize.Caption, color = colors.inkMuted, modifier = Modifier.padding(start = Spacing.Tight))
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
                fontSize = TextSize.Meta,
                color = colors.inkMuted,
                modifier = Modifier.padding(top = Spacing.Tight, bottom = Spacing.Inset),
            )
            ActivityHeatmapView(heatmap, today)
            if (heatmap.totalActivities == 0) {
                // the grid is still drawn: an empty year is a row of empty cells, not a
                // blank space, so it is obvious that the calendar exists and is waiting.
                // One sentence and not two (SYSTEM.md rule 5) - the second one restated
                // the first with different words.
                Text(
                    "Empty at the start is normal - the first workouts light it up.",
                    fontSize = TextSize.Meta,
                    color = colors.inkMuted,
                    lineHeight = 17.sp,
                    modifier = Modifier.padding(top = Spacing.Line),
                )
            }
        }
    }
}
