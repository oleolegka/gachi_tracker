package xyz.oleolegka.gachimuchi.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.oleolegka.gachimuchi.ui.components.barLabelIndices
import xyz.oleolegka.gachimuchi.ui.components.labelIndices
import xyz.oleolegka.gachimuchi.ui.components.niceScale
import xyz.oleolegka.gachimuchi.ui.components.niceStep

/**
 * Axis arithmetic tests. An axis that picks bad ticks is otherwise only visible by
 * squinting at a phone, and there is no phone in this loop.
 */
class ChartMathTest {

    @Test
    fun `a step is rounded to one, two or five times a power of ten`() {
        assertEquals(1.0, niceStep(0.7), 1e-9)
        assertEquals(2.0, niceStep(1.6), 1e-9)
        assertEquals(5.0, niceStep(4.2), 1e-9)
        assertEquals(10.0, niceStep(9.1), 1e-9)
        assertEquals(20.0, niceStep(17.0), 1e-9)
        assertEquals(0.5, niceStep(0.42), 1e-9)
        // a degenerate input must not produce a zero or infinite step (that loop would hang)
        assertEquals(1.0, niceStep(0.0), 1e-9)
        assertEquals(1.0, niceStep(-3.0), 1e-9)
        assertEquals(1.0, niceStep(Double.NaN), 1e-9)
    }

    @Test
    fun `ticks are round numbers that bracket the data`() {
        val scale = niceScale(listOf(62.0, 71.5, 68.0), targetTicks = 4)
        assertTrue(scale.min <= 62.0)
        assertTrue(scale.max >= 71.5)
        assertTrue(scale.ticks.size in 3..8)
        assertEquals(scale.min, scale.ticks.first(), 1e-9)
        // evenly spaced
        val gaps = scale.ticks.zipWithNext { a, b -> b - a }
        assertTrue(gaps.all { kotlin.math.abs(it - gaps[0]) < 1e-6 })
    }

    @Test
    fun `a body weight range is not forced down to zero`() {
        // the reason includeZero defaults to false: from zero this series is a flat line
        val scale = niceScale(listOf(72.4, 76.5, 74.0))
        assertTrue("axis must not start at zero for a narrow range", scale.min > 50.0)
        // a bar chart asks for zero explicitly, because bar length has to be proportional
        assertEquals(0.0, niceScale(listOf(72.4, 76.5), includeZero = true).min, 1e-9)
    }

    @Test
    fun `a flat series still gets a drawable range`() {
        val scale = niceScale(listOf(8.0, 8.0, 8.0))
        assertTrue(scale.max > scale.min)
        assertTrue(scale.fraction(8.0) in 0f..1f)

        val zeros = niceScale(listOf(0.0, 0.0))
        assertTrue(zeros.max > zeros.min)
    }

    @Test
    fun `an empty series yields a safe unit axis`() {
        val scale = niceScale(emptyList())
        assertEquals(0.0, scale.min, 1e-9)
        assertEquals(1.0, scale.max, 1e-9)
        assertTrue(scale.ticks.isNotEmpty())
        assertEquals(1.0, scale.span, 1e-9)
    }

    @Test
    fun `fraction maps the range onto zero to one`() {
        val scale = niceScale(listOf(0.0, 100.0), includeZero = true)
        assertEquals(0f, scale.fraction(scale.min), 1e-6f)
        assertEquals(1f, scale.fraction(scale.max), 1e-6f)
        assertEquals(0.5f, scale.fraction((scale.min + scale.max) / 2), 1e-6f)
    }

    @Test
    fun `x labels always include the first and the last point`() {
        val picked = labelIndices(count = 90, maxLabels = 5)
        assertEquals(0, picked.first())
        assertEquals(89, picked.last())
        assertTrue(picked.size <= 5)
        assertEquals(picked, picked.distinct())
        assertEquals(picked, picked.sorted())
    }

    @Test
    fun `few points are all labelled and edge cases do not crash`() {
        assertEquals(listOf(0, 1, 2), labelIndices(3, 5))
        assertEquals(listOf(0), labelIndices(1, 5))
        assertEquals(emptyList<Int>(), labelIndices(0, 5))
        // asking for one label still yields both ends: an unlabelled end is unreadable
        assertEquals(listOf(0, 9), labelIndices(10, 1))
    }

    @Test
    fun `every bar is labelled while there is room, and only the notable ones past that`() {
        val few = listOf(3.0, 8.0, 5.0)
        assertEquals(setOf(0, 1, 2), barLabelIndices(few, maxLabels = 14))

        val many = (1..40).map { it.toDouble() }
        val picked = barLabelIndices(many, maxLabels = 14)
        assertTrue(picked.size <= 3)
        assertTrue("the tallest bar must keep its number", 39 in picked)
        assertTrue("the shortest bar must keep its number", 0 in picked)

        assertEquals(emptySet<Int>(), barLabelIndices(emptyList()))
    }
}
