package xyz.oleolegka.gachimuchi.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.oleolegka.gachimuchi.domain.AxisSlot
import xyz.oleolegka.gachimuchi.domain.ValueFormat
import xyz.oleolegka.gachimuchi.ui.fmtAxis
import xyz.oleolegka.gachimuchi.ui.fmtOnChart
import xyz.oleolegka.gachimuchi.ui.fmtShortDay
import xyz.oleolegka.gachimuchi.ui.theme.GachiColors
import xyz.oleolegka.gachimuchi.ui.theme.LocalGachiColors
import java.time.LocalDate
import xyz.oleolegka.gachimuchi.ui.theme.TextSize

/**
 * The charts, drawn on a Compose [Canvas] with no charting library behind them.
 *
 * ── Why hand-drawn ──────────────────────────────────────────────────────────────
 * A charting dependency would bring its own palette, its own type scale and its own idea
 * of what an axis looks like, and the whole point of `research_visual.md` §6 is that those
 * three are decided once for the app. Two of the four charts here (the heatmap and the
 * sparkline) have no equivalent in the usual libraries anyway.
 *
 * ── Every chart has axes and every bar has its number ───────────────────────────
 * A line without a labelled Y axis says "it went up" and nothing else — not from what, not
 * to what, not by how much. A bar without its value is a coloured rectangle. So the
 * chart bodies here reserve room for labels FIRST and draw the data into what is left,
 * rather than drawing the data and hoping labels fit. Where labels genuinely cannot fit
 * (dozens of bars on a phone), [barLabelIndices] thins them down to the ones a reader
 * actually looks for instead of dropping all of them.
 *
 * ── And a number drawn on the data carries its unit ─────────────────────────────
 * The Y ticks are the SCALE and stay bare — they repeat up the side of the card and the unit
 * is stated once beside the chart's title. A value printed ON the data is the figure a reader
 * quotes, and it goes out with its unit attached (`ui/Format.kt`'s `fmtOnChart`): "2940" over a
 * bar of impulse names no quantity at all, and kilogram-seconds are an invention of this app
 * rather than something a reader arrives already knowing.
 *
 * ── The X axis belongs to the SCREEN, not to the series ─────────────────────────
 * Both plot charts here take [AxisSlot]s off a shared
 * [xyz.oleolegka.gachimuchi.domain.TimeAxis] rather than a bare list of points, and the slot
 * INDEX is the position: slot k is at the same x in every chart on the screen, because every
 * chart was handed the same slots. A slot with no value is simply not drawn — no bar, no dot
 * — which is how a series with a thin week says so instead of stretching itself to fill the
 * card. The reasoning, and the defect that forced it, are on `TimeAxis` itself.
 *
 * The arithmetic lives in `ChartMath.kt` and is unit-tested; this file is only pixels.
 */

/**
 * Text sizes of chart furniture: the floor of the type scale, because chart furniture is
 * the smallest text in the app and the scale says where that stops.
 */
private val AXIS_TEXT_SIZE = TextSize.Caption
private val VALUE_TEXT_SIZE = TextSize.Caption

private fun axisTextStyle(color: Color) = TextStyle(fontSize = AXIS_TEXT_SIZE, color = color)

private fun valueTextStyle(color: Color) = TextStyle(fontSize = VALUE_TEXT_SIZE, color = color)

/** Measures a string once so the layout can reserve room for it before anything is drawn. */
private fun TextMeasurer.width(text: String, style: TextStyle): Float =
    measure(text, style).size.width.toFloat()

private fun DrawScope.drawLabel(
    measurer: TextMeasurer,
    text: String,
    style: TextStyle,
    x: Float,
    y: Float,
    align: TextAlign = TextAlign.Start,
) {
    val layout: TextLayoutResult = measurer.measure(text, style)
    val dx = when (align) {
        TextAlign.End -> -layout.size.width.toFloat()
        TextAlign.Center -> -layout.size.width / 2f
        else -> 0f
    }
    drawText(layout, topLeft = Offset(x + dx, y))
}

// --- sparkline --------------------------------------------------------------------------

/**
 * A sparkline: the shape of a series and nothing else.
 *
 * Deliberately axis-free — that is what makes it a sparkline rather than a small chart.
 * It lives inside a door tile whose headline already states the last value in words, so
 * the line only has to answer "rising or falling", and axis furniture at this size would
 * cost more pixels than the data.
 *
 * The last point gets a dot: without it a two-pixel line ending mid-tile reads as clipped.
 */
@Composable
fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = LocalGachiColors.current.accent,
    height: Dp = 32.dp,
    lowerIsBetter: Boolean = false,
) {
    val colors = LocalGachiColors.current
    Canvas(modifier.fillMaxWidth().height(height)) {
        if (values.isEmpty()) return@Canvas
        val lo = values.min()
        val hi = values.max()
        val span = (hi - lo).takeIf { it > 0 } ?: 1.0
        val inset = 3.dp.toPx()
        val usableHeight = (size.height - inset * 2).coerceAtLeast(1f)

        fun pointAt(index: Int): Offset {
            val x = if (values.size == 1) size.width / 2f
            else size.width * index / (values.size - 1).toFloat()
            val y = inset + usableHeight * (1f - ((values[index] - lo) / span).toFloat())
            return Offset(x.coerceIn(0f, size.width), y)
        }

        // a single point cannot make a line; a lone dot is the honest drawing of it
        if (values.size == 1) {
            drawCircle(color, radius = 3.dp.toPx(), center = pointAt(0))
            return@Canvas
        }

        val path = Path().apply {
            moveTo(pointAt(0).x, pointAt(0).y)
            for (i in 1 until values.size) lineTo(pointAt(i).x, pointAt(i).y)
        }
        drawPath(path, color, style = Stroke(width = 1.5.dp.toPx()))

        val lastIndex = values.lastIndex
        val best = if (lowerIsBetter) values.min() else values.max()
        // the closing dot is coloured only when the series ENDS on its best value: a
        // highlight that fires on every tile stops carrying information
        val dotColor = if (values[lastIndex] == best) colors.good else color
        drawCircle(dotColor, radius = 2.5.dp.toPx(), center = pointAt(lastIndex))
    }
}

// --- shared plot furniture ---------------------------------------------------------------

/** The rectangle the data is drawn into, once the labels have taken their room. */
private data class Plot(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}

/** How many dates go under a plot. One number for every chart — see [drawXDates]. */
private const val X_LABELS = 4

/**
 * Where slot number [index] of [count] sits horizontally: the MIDDLE of its slot.
 *
 * The line chart used to run its first point at the very left edge and its last at the very
 * right, while a bar sat in the middle of its slot — so a line above a bar chart of the same
 * days was offset from it by half a bar at one end and half a bar at the other. Sharing the
 * axis is only worth something if the columns line up, so both charts and the date captions
 * now use this one mapping.
 */
private fun slotX(index: Int, count: Int, plot: Plot): Float =
    plot.left + plot.width * (index + 0.5f) / count.coerceAtLeast(1)

/**
 * Horizontal gridlines and the Y labels beside them, plus the baseline.
 *
 * Gridlines are thin, SOLID and in the recessive grid colour, exactly as the design
 * system draws them — a dashed grid at this line weight turns into visual noise on a
 * phone. The bottom gridline doubles as the baseline and is drawn in the stronger axis
 * colour, which is why there is no separate frame around the plot: the design deliberately
 * has no left-hand axis rule, only labels.
 */
private fun DrawScope.drawYAxis(
    measurer: TextMeasurer,
    scale: AxisScale,
    plot: Plot,
    format: ValueFormat,
    colors: GachiColors,
) {
    val style = axisTextStyle(colors.inkMuted)
    val labelHalf = measurer.measure("0", style).size.height / 2f
    for (tick in scale.ticks) {
        val y = plot.bottom - plot.height * scale.fraction(tick)
        if (y < plot.top - 1f || y > plot.bottom + 1f) continue
        val isBase = kotlin.math.abs(y - plot.bottom) < 0.5f
        drawLine(
            color = if (isBase) colors.axis else colors.grid,
            start = Offset(plot.left, y),
            end = Offset(plot.right, y),
            strokeWidth = 1.dp.toPx(),
        )
        drawLabel(
            measurer, fmtAxis(tick, format), style,
            x = plot.left - 4.dp.toPx(),
            y = y - labelHalf,
            align = TextAlign.End,
        )
    }
}

/**
 * Date labels under the plot, thinned by [labelIndices] so they never overlap.
 *
 * The dates are the AXIS's, not the series' — so the same days are named under every chart of
 * a screen, and [maxLabels] is the same for all of them for the same reason: two charts whose
 * captions fell on different days would invite exactly the mis-reading the shared axis exists
 * to remove.
 */
private fun DrawScope.drawXDates(
    measurer: TextMeasurer,
    dates: List<String>,
    plot: Plot,
    colors: GachiColors,
    maxLabels: Int = X_LABELS,
) {
    val style = axisTextStyle(colors.inkMuted)
    val y = plot.bottom + 5.dp.toPx()
    for (index in labelIndices(dates.size, maxLabels)) {
        val x = slotX(index, dates.size, plot)
        val text = fmtShortDay(LocalDate.parse(dates[index]))
        val half = measurer.width(text, style) / 2f
        // the end labels are pulled inside the canvas instead of being clipped in half
        val clamped = x.coerceIn(plot.left - 2.dp.toPx() + half, size.width - half)
        drawLabel(measurer, text, style, clamped, y, TextAlign.Center)
    }
}

/** How wide the Y labels of this scale get — the plot starts after that. */
private fun DrawScope.yLabelWidth(
    measurer: TextMeasurer,
    scale: AxisScale,
    format: ValueFormat,
    colors: GachiColors,
): Float {
    val style = axisTextStyle(colors.inkMuted)
    return scale.ticks.maxOfOrNull { measurer.width(fmtAxis(it, format), style) } ?: 0f
}

// --- line chart ---------------------------------------------------------------------------

/** Opacity of the wash under a trend line (`--form-*` at 9 %, per the design system). */
private const val AREA_ALPHA = 0.09f

/**
 * The trend chart: a line with a labelled Y axis, a dated X axis, recessive gridlines, a
 * flat wash beneath the line and the latest value called out in words.
 *
 * Points get a dot only while there are few enough for dots to mean something. Past that
 * only the first, the extreme and the latest point are marked, which are the three anyone
 * looks for.
 *
 * ── What an empty slot does to the line ─────────────────────────────────────────
 * The line is drawn through the slots that HAVE a value and straight across the ones that do
 * not, so a fortnight off is one long segment rather than a break — a trend metric does not
 * fall to zero because nobody trained, and a broken line at this size reads as a rendering
 * fault. What the gap now costs is width: the segment is as wide as the fortnight it spans,
 * where before it was one step like any other. Dots go on real entries only, so the line
 * never claims a measurement on a day that has none.
 */
@Composable
fun LineChart(
    slots: List<AxisSlot>,
    format: ValueFormat,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    lowerIsBetter: Boolean = false,
    lineColor: Color = LocalGachiColors.current.accent,
) {
    val colors = LocalGachiColors.current
    val measurer = rememberTextMeasurer()
    val scale = remember(slots) { niceScale(slots.mapNotNull { it.value }, targetTicks = 4) }
    val filled = remember(slots) { slots.indices.filter { slots[it].value != null } }
    val dates = remember(slots) { slots.map { it.opDate } }

    Canvas(modifier.fillMaxWidth().height(height)) {
        if (filled.isEmpty()) return@Canvas
        val labelHeight = measurer.measure("0", axisTextStyle(colors.inkMuted)).size.height.toFloat()
        val plot = Plot(
            left = yLabelWidth(measurer, scale, format, colors) + 8.dp.toPx(),
            // room above the line for the callout on the latest point
            top = labelHeight + 8.dp.toPx(),
            right = size.width - 4.dp.toPx(),
            bottom = size.height - labelHeight - 8.dp.toPx(),
        )
        drawYAxis(measurer, scale, plot, format, colors)
        drawXDates(measurer, dates, plot, colors)

        fun pointAt(index: Int): Offset {
            val y = plot.bottom - plot.height * scale.fraction(slots[index].value ?: 0.0)
            return Offset(slotX(index, slots.size, plot), y.coerceIn(plot.top, plot.bottom))
        }

        /*
         * ONE point is a dot, and the line and the wash are skipped outright.
         *
         * Not a saving: a path holding a single moveTo and an area closed back onto its own x
         * are both zero-width shapes that happen to draw nothing today, so this used to work by
         * accident — the kind that survives until a stroke cap or a join is added and a stray
         * mark appears on a chart of one entry. The card above already says "only one entry in
         * this window - not a trend yet"; this is the same statement in the drawing.
         */
        if (filled.size > 1) {
            val line = Path()
            val area = Path()
            for ((n, i) in filled.withIndex()) {
                val p = pointAt(i)
                if (n == 0) {
                    line.moveTo(p.x, p.y)
                    area.moveTo(p.x, plot.bottom)
                    area.lineTo(p.x, p.y)
                } else {
                    line.lineTo(p.x, p.y)
                    area.lineTo(p.x, p.y)
                }
            }
            area.lineTo(pointAt(filled.last()).x, plot.bottom)
            area.close()

            // a flat wash, not a gradient: the design system fills the area with the line's own
            // colour at 9 % so it reads as shading rather than as a second series
            clipRect(plot.left, plot.top, plot.right, plot.bottom) {
                drawPath(area, color = lineColor.copy(alpha = AREA_ALPHA))
            }
            drawPath(line, lineColor, style = Stroke(width = 2.dp.toPx()))

            val bestIndex = if (lowerIsBetter) filled.minBy { slots[it].value!! }
            else filled.maxBy { slots[it].value!! }
            val marked = if (filled.size <= 24) filled.toSet()
            else setOf(filled.first(), bestIndex, filled.last())
            for (i in marked) {
                val p = pointAt(i)
                // the dot is filled with the card surface so the line does not show through it
                drawCircle(colors.plane, radius = 3.dp.toPx(), center = p)
                drawCircle(lineColor, radius = 3.dp.toPx(), center = p, style = Stroke(1.5.dp.toPx()))
            }
        }
        // the closing point is solid and larger, with a 2 px ring of the surface around it
        val endPoint = pointAt(filled.last())
        drawCircle(lineColor, radius = 4.dp.toPx(), center = endPoint)
        drawCircle(colors.plane, radius = 4.dp.toPx(), center = endPoint, style = Stroke(2.dp.toPx()))

        // the latest value in words, above its point: the number the screen is opened for, and
        // therefore the one that carries its unit rather than borrowing it from the caption
        val lastPoint = endPoint
        val text = fmtOnChart(slots[filled.last()].value!!, format)
        val style = valueTextStyle(colors.inkSecondary)
        val half = measurer.width(text, style) / 2f
        drawLabel(
            measurer, text, style,
            x = lastPoint.x.coerceIn(plot.left + half, size.width - half),
            y = (lastPoint.y - 6.dp.toPx() - measurer.measure(text, style).size.height)
                .coerceAtLeast(0f),
            align = TextAlign.Center,
        )
    }
}

// --- bar chart -----------------------------------------------------------------------------

/**
 * The volume chart: bars from a ZERO baseline, each carrying its value.
 *
 * The baseline is not negotiable. A bar chart is read by comparing lengths, so a truncated
 * axis makes a 5 % difference look like a doubling — that is why [niceScale] is asked for
 * `includeZero` here and not for the line above.
 *
 * ── An untrained bucket has no bar, and does not have a zero one ────────────────
 * A slot with no value is left blank. Drawing it at zero would be a different claim — that a
 * session happened and moved nothing — and it is the same claim `domain/Analytics.kt` refuses
 * to make when it drops a day made only of warm-ups. The width is spent all the same, so a
 * fortnight with two sessions in it now LOOKS like a fortnight with two sessions in it
 * instead of like two busy days side by side.
 */
@Composable
fun BarChart(
    slots: List<AxisSlot>,
    format: ValueFormat,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
) {
    val colors = LocalGachiColors.current
    val measurer = rememberTextMeasurer()
    val scale = remember(slots) {
        niceScale(slots.mapNotNull { it.value }, targetTicks = 4, includeZero = true)
    }
    val labelled = remember(slots) { barLabelIndices(slots.map { it.value }) }
    val filled = remember(slots) { slots.indices.filter { slots[it].value != null } }
    val dates = remember(slots) { slots.map { it.opDate } }

    Canvas(modifier.fillMaxWidth().height(height)) {
        if (filled.isEmpty()) return@Canvas
        val axisStyle = axisTextStyle(colors.inkMuted)
        val valueStyle = valueTextStyle(colors.inkSecondary)
        val labelHeight = measurer.measure("0", axisStyle).size.height.toFloat()
        val plot = Plot(
            left = yLabelWidth(measurer, scale, format, colors) + 8.dp.toPx(),
            top = labelHeight + 6.dp.toPx(),   // room for the number on top of the tallest bar
            right = size.width - 4.dp.toPx(),
            bottom = size.height - labelHeight - 8.dp.toPx(),
        )
        drawYAxis(measurer, scale, plot, format, colors)
        drawXDates(measurer, dates, plot, colors)

        val slot = plot.width / slots.size
        // the design caps a bar at 20 dp and asks for air between bars; below that the bar
        // takes what the slot leaves, but never thins to nothing (90 bars on a phone)
        val barWidth = minOf(20.dp.toPx(), (slot - 6.dp.toPx())).coerceAtLeast(1f)
        val zeroY = plot.bottom - plot.height * scale.fraction(0.0)

        for ((n, i) in filled.withIndex()) {
            val value = slots[i].value ?: continue
            val centerX = slotX(i, slots.size, plot)
            val valueY = plot.bottom - plot.height * scale.fraction(value)
            val top = minOf(valueY, zeroY)
            val bottom = maxOf(valueY, zeroY)
            val barHeight = (bottom - top).coerceAtLeast(1f)
            // rounded on top, SQUARE at the base: a bar that is rounded at the bottom too
            // looks as if it floats, and the baseline is the whole point of a bar chart
            val radius = minOf(4.dp.toPx(), barWidth / 2f, barHeight)
            drawPath(
                Path().apply {
                    val l = centerX - barWidth / 2f
                    val r = centerX + barWidth / 2f
                    moveTo(l, bottom)
                    lineTo(l, top + radius)
                    quadraticTo(l, top, l + radius, top)
                    lineTo(r - radius, top)
                    quadraticTo(r, top, r, top + radius)
                    lineTo(r, bottom)
                    close()
                },
                color = colors.accent,
            )
            if (i in labelled) {
                // a number is allowed the empty slots on either side of its bar as well as its
                // own: with a shared axis a lone session in a quiet fortnight sits in a narrow
                // slot but has nothing anywhere near it, and dropping its number then would
                // lose exactly the value a reader came for
                val toPrevious = i - (filled.getOrNull(n - 1) ?: -1)
                val toNext = (filled.getOrNull(n + 1) ?: slots.size) - i
                val room = slot * minOf(toPrevious, toNext)
                fun fits(text: String) =
                    measurer.width(text, valueStyle) <= room * 1.1f
                /*
                 * WITH ITS UNIT WHERE THERE IS ROOM, bare where there is not — and nothing at
                 * all where even the bare number would run into its neighbour.
                 *
                 * "2940" over a bar of impulse is a number nobody can name the quantity of
                 * ([fmtOnChart]), so the unit goes on the value itself. It is also the first
                 * thing to lose when the bars crowd: the caption beside the chart title still
                 * states the unit, so a bare number under a stated unit is a smaller loss than
                 * a bar with no number over it at all.
                 */
                val text = fmtOnChart(value, format).takeIf { fits(it) }
                    ?: fmtAxis(value, format).takeIf { fits(it) }
                if (text != null) {
                    val half = measurer.width(text, valueStyle) / 2f
                    drawLabel(
                        measurer, text, valueStyle,
                        x = centerX.coerceIn(plot.left + half, size.width - half),
                        y = (top - 3.dp.toPx() - measurer.measure(text, valueStyle).size.height)
                            .coerceAtLeast(0f),
                        align = TextAlign.Center,
                    )
                }
            }
        }
    }
}

// --- activity heatmap ------------------------------------------------------------------------

/**
 * Geometry of the heatmap, shared by the grid, the ribbons and the scrolling container.
 *
 * These are the design-system numbers (11 px cell, 3 px gap, 3 px radius) read as dp, so
 * the grid keeps its proportions on any density instead of its pixel size on one phone.
 */
object HeatmapMetrics {
    val cell: Dp = 11.dp
    val gap: Dp = 3.dp
    val radius: Dp = 3.dp

    /**
     * Room on the left for the weekday ribbon (Mon / Wed / Fri / Sun).
     *
     * 28 and not the 20 it was: the labels were 9 sp, which is below the floor of the type
     * scale, and at 11 sp "Wed" no longer fits in 20 dp. The eight dp come out of the grid,
     * which is to say out of HALF A WEEK of the visible year (a week is 14 dp) - 23 columns
     * on a 411 dp phone instead of 23.5. Paid knowingly: the gutter is the only thing tying
     * a row of the grid to a day of the week, and 9 sp is not a size this app has.
     */
    val weekdayGutter: Dp = 28.dp

    /** Room on top for the month ribbon. */
    val monthRibbon: Dp = 13.dp

    val gridHeight: Dp = cell * 7 + gap * 6

    fun widthFor(weeks: Int): Dp = cell * weeks + gap * (weeks - 1).coerceAtLeast(0)
}
