package xyz.oleolegka.gachimuchi.ui.components

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The arithmetic behind the charts: axis scales, tick values and label thinning.
 *
 * Kept apart from the drawing code and free of any Compose type on purpose — an axis that
 * silently picks bad ticks is a bug you can only see by squinting at a phone, whereas here
 * it is a unit test. The charts themselves then only turn numbers into pixels.
 */

/** A resolved axis: the range actually drawn and the values that get a gridline and a label. */
data class AxisScale(val min: Double, val max: Double, val ticks: List<Double>) {
    val span: Double get() = (max - min).takeIf { it > 0 } ?: 1.0

    /** Position of a value in 0..1 along the axis (0 = min, 1 = max). */
    fun fraction(value: Double): Float = ((value - min) / span).toFloat()
}

/**
 * Rounds a range step to a number a human would have chosen: 1, 2, 5 or 10 times a power
 * of ten. Axis labels of 0/25/50/75 read instantly; 0/23.7/47.4 do not, even though they
 * describe the same data.
 */
internal fun niceStep(rough: Double): Double {
    if (rough <= 0 || !rough.isFinite()) return 1.0
    val exponent = floor(log10(rough))
    val magnitude = 10.0.pow(exponent)
    val fraction = rough / magnitude
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * magnitude
}

/**
 * An axis over [values] with roughly [targetTicks] intervals.
 *
 * [includeZero] is false by default and that is deliberate: a body weight series between
 * 72 and 77 kg drawn from zero is a flat line that says nothing. Bar charts pass true,
 * because a bar whose baseline is not zero misstates the ratio between the bars — the one
 * thing a bar chart is read for.
 *
 * A series where every value is identical still gets a real range (padded around the
 * value) instead of a zero-height axis that would put the line on top of the frame.
 */
fun niceScale(values: List<Double>, targetTicks: Int = 4, includeZero: Boolean = false): AxisScale {
    val finite = values.filter { it.isFinite() }
    if (finite.isEmpty()) return AxisScale(0.0, 1.0, listOf(0.0, 1.0))

    var lo = finite.min()
    var hi = finite.max()
    if (includeZero) {
        lo = minOf(lo, 0.0)
        hi = maxOf(hi, 0.0)
    }
    if (lo == hi) {
        // one distinct value: pad by a tenth of it, or by 1 when the value is zero
        val pad = if (lo == 0.0) 1.0 else abs(lo) * 0.1
        lo -= pad
        hi += pad
        if (includeZero) lo = minOf(lo, 0.0)
    }

    val step = niceStep((hi - lo) / targetTicks.coerceAtLeast(1))
    val niceMin = floor(lo / step) * step
    val niceMax = ceil(hi / step) * step

    val ticks = ArrayList<Double>()
    var t = niceMin
    // the count is bounded rather than trusted to floating point: an accumulating epsilon
    // on a tiny step is exactly how a loop like this hangs
    var guard = 0
    while (t <= niceMax + step * 1e-6 && guard < 64) {
        // -0.0 prints as "-0", which on an axis reads as a rendering fault
        ticks.add(if (t == 0.0) 0.0 else t)
        t += step
        guard++
    }
    return AxisScale(niceMin, niceMax, ticks)
}

/**
 * Which of [count] items should get an X label so that at most [maxLabels] are drawn.
 *
 * The FIRST and the LAST are always among them: a time axis whose ends are unlabelled
 * does not tell you what period you are looking at, which is the first thing anyone asks.
 * The rest are spread evenly and de-duplicated.
 */
fun labelIndices(count: Int, maxLabels: Int): List<Int> {
    if (count <= 0) return emptyList()
    if (count == 1) return listOf(0)
    val slots = maxLabels.coerceAtLeast(2)
    if (count <= slots) return (0 until count).toList()
    return (0 until slots)
        .map { ((count - 1).toDouble() * it / (slots - 1)).toInt() }
        .distinct()
}

/**
 * Which bars may carry a value label on top.
 *
 * Bars get their numbers printed (the whole complaint that started this was unlabelled
 * bars), but past a couple of dozen bars the labels collide into a grey smear and stop
 * being readable. Beyond [maxLabels] bars only the extremes and the most recent bar are
 * labelled — the three a reader actually looks for — and the rest rely on the axis.
 */
fun barLabelIndices(values: List<Double>, maxLabels: Int = 14): Set<Int> {
    if (values.isEmpty()) return emptySet()
    if (values.size <= maxLabels) return values.indices.toSet()
    val maxAt = values.indices.maxBy { values[it] }
    val minAt = values.indices.minBy { values[it] }
    return setOf(maxAt, minAt, values.lastIndex)
}
