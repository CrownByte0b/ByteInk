package com.vivenotes.byteink.kit

import java.math.BigDecimal
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class LassoClosureSafetyTest {
    @Test(timeout = 5000)
    fun hugeFiniteRectanglesAndSaturatedCellDimensionsFinishWithTheExactVerdict() {
        for (span in listOf(10_000f, 1_000_000f, 1e20f)) {
            val open = listOf(InkPoint(0f, 0f), InkPoint(span, span),
                InkPoint(span * 2f, span), InkPoint(span * 3f, span))
            val closed = listOf(InkPoint(0f, 0f), InkPoint(span, 0f),
                InkPoint(span, span), InkPoint(0f, span), InkPoint(0f, 0f))
            assertFalse(open.closesIntoALoop(4f), "open diagonal at $span")
            assertTrue(closed.closesIntoALoop(4f), "closed rectangle at $span")
            assertEquals(wideScan(open, 4f), open.closesIntoALoop(4f))
            assertEquals(wideScan(closed, 4f), closed.closesIntoALoop(4f))
        }
        // Both cell dimensions saturate from Int.MIN_VALUE to Int.MAX_VALUE. Their product
        // cannot be represented by Long, and even the endpoint subtraction overflows Float.
        val huge = Float.MAX_VALUE
        val open = listOf(InkPoint(-huge, -huge), InkPoint(huge, huge),
            InkPoint(huge, 0f), InkPoint(huge, -huge))
        val closed = listOf(InkPoint(-huge, -huge), InkPoint(huge, -huge),
            InkPoint(huge, huge), InkPoint(-huge, huge), InkPoint(-huge, -huge))
        val crossed = listOf(InkPoint(-huge, -huge), InkPoint(huge, huge),
            InkPoint(huge, -huge), InkPoint(-huge, huge))
        for ((path, expected) in listOf(open to false, closed to true, crossed to true)) {
            assertTrue(path.all { it.x.isFinite() && it.y.isFinite() })
            assertEquals(expected, wideScan(path, 4f))
            assertEquals(expected, path.closesIntoALoop(4f))
        }
    }

    @Test(timeout = 5000)
    fun exhaustingTheTotalBudgetStillFindsALaterLoopAndRejectsItsOpenVersion() {
        val prefix = List(1025) { InkPoint(it * 64f, it * 64f) }
        val corner = prefix.last()
        val closed = prefix + listOf(InkPoint(corner.x + 100f, corner.y),
            InkPoint(corner.x + 100f, corner.y + 100f), InkPoint(corner.x, corner.y + 100f), corner)
        val open = closed.dropLast(1)
        assertTrue(closed.closesIntoALoop(4f))
        assertFalse(open.closesIntoALoop(4f))
        assertEquals(floatScan(closed, 4f), closed.closesIntoALoop(4f))
        assertEquals(floatScan(open, 4f), open.closesIntoALoop(4f))
        // Small per-segment rectangles on either side of the budget boundary must have the
        // same result; a limit is a choice of algorithm, never a reason to reject a gesture.
        for (span in listOf(1500f, 2000f, 2500f)) {
            val rectangle = listOf(InkPoint(0f, 0f), InkPoint(span, span),
                InkPoint(span * 2f, span), InkPoint(span * 2f, 0f), InkPoint(0f, 0f))
            assertEquals(floatScan(rectangle, 4f), rectangle.closesIntoALoop(4f), "span $span")
        }
    }

    @Test(timeout = 5000)
    fun ordinaryRandomPathsMatchTheOriginalFloatRulesWithoutTheGrid() {
        val random = Random(846291)
        repeat(400) { case ->
            var x = random.nextFloat() * 80f - 40f
            var y = random.nextFloat() * 80f - 40f
            val path = List(random.nextInt(4, 33)) { index ->
                if (index > 0 && random.nextInt(5) != 0) {
                    val scale = if (case % 4 == 0) 10_000f else 80f
                    x += (random.nextFloat() - 0.5f) * scale
                    y += (random.nextFloat() - 0.5f) * scale
                }
                InkPoint(x, y)
            }
            val touch = listOf(-1f, 0f, 2f, 4f, 8f, 16f)[case % 6]
            assertEquals(floatScan(path, touch), path.closesIntoALoop(touch), "random case $case, touch $touch")
        }
    }

    @Test(timeout = 5000)
    fun dotsTravelStrictnessAndEndpointToleranceKeepTheirFloatBoundaryRules() {
        assertFalse(List(256) { InkPoint(1f, 1f) }.closesIntoALoop(4f), "sharing-list budget with no travel")
        assertFalse(listOf(InkPoint(0f, 0f), InkPoint(1f, 1f), InkPoint(0f, 0f)).closesIntoALoop(4f))
        val exactlyApart = listOf(InkPoint(0f, 0f), InkPoint(8f, 0f),
            InkPoint(8f, 8f), InkPoint(0f, 8f), InkPoint(0f, 0f))
        assertFalse(exactlyApart.closesIntoALoop(2f), "travel must be strictly greater than 8 times reach")
        assertTrue(exactlyApart.map { if (it.y == 8f) it.copy(y = 8.5f) else it }.closesIntoALoop(2f))
        for (touch in listOf(-1f, 0f, 2f, 4f)) {
            val reach = maxOf(touch, 2f)
            for ((gap, expected) in listOf(reach to true, reach + 0.001f to false)) {
                val path = listOf(InkPoint(0f, 0f), InkPoint(60f, 0f),
                    InkPoint(60f, 60f), InkPoint(0f, 60f), InkPoint(0f, gap))
                assertEquals(expected, floatScan(path, touch))
                assertEquals(expected, path.closesIntoALoop(touch), "touch $touch, gap $gap")
            }
            val roundedBoundary = listOf(InkPoint(0f, 0f), InkPoint(60f, 0f),
                InkPoint(60f, 60f), InkPoint(0f, 60f), InkPoint(0f, Math.nextUp(reach)))
            assertEquals(floatScan(roundedBoundary, touch), roundedBoundary.closesIntoALoop(touch))
        }
    }

    @Test(timeout = 5000)
    fun fallbackRetainsTheLegacyGridCandidateRuleForLargeFiniteRounding() {
        val open = listOf(InkPoint(0f, 1000f), InkPoint(0f, 0f), InkPoint(0f, -1000f),
            InkPoint(1e9f, -1000f), InkPoint(1e9f, 100f), InkPoint(30f, 1f))
        // The old Float endpoint interpolation rounds the final segment to x=0, but its
        // expanded cells do not overlap the x=0 segments. The fallback must keep that reject.
        assertFalse(floatScan(open, 4f))
        assertFalse(open.closesIntoALoop(4f))
    }

    @Test(timeout = 5000)
    fun aSmallLoopAfterOverflowingTravelIsNotLostToPrefixCancellation() {
        val huge = Float.MAX_VALUE
        // The entry approaches from the northeast; the local square lies southwest of it.
        val prefix = listOf(InkPoint(-huge, -huge), InkPoint(huge, huge), InkPoint(0f, -1000f))
        val closed = prefix + listOf(InkPoint(-100f, -1000f), InkPoint(-100f, -1100f),
            InkPoint(0f, -1100f), InkPoint(0f, -1000f))
        val open = closed.dropLast(1) + InkPoint(0f, -1010f)
        assertTrue(wideScan(closed, 4f))
        assertFalse(wideScan(open, 4f))
        assertTrue(closed.closesIntoALoop(4f))
        assertFalse(open.closesIntoALoop(4f))
    }

    @Test(timeout = 5000)
    fun extremeSegmentEndpointsKeepTheirSmallLocalDistances() {
        // Interpolation from the large start rounds the small endpoint to (0, 1), creating
        // a false touch. The true closest endpoint to (0, 0) is (9, 1), more than 4 away.
        val open = listOf(InkPoint(-3e38f, -3e38f), InkPoint(3e38f, -3e38f),
            InkPoint(3e38f, 0f), InkPoint(0f, 1000f), InkPoint(0f, 0f),
            InkPoint(0f, -1000f), InkPoint(1e20f, -1000f), InkPoint(1e20f, 100f), InkPoint(9f, 1f))
        assertFalse(wideScan(open, 4f))
        assertFalse(open.closesIntoALoop(4f))
        // A finite Float result can also hide an infinite segment length. The final point
        // is 1 unit above the early horizontal segment, despite Float projection becoming 0.
        val close = listOf(InkPoint(-3e38f, -3e38f), InkPoint(3e38f, -3e38f),
            InkPoint(3e38f, 0f), InkPoint(0f, 1000f), InkPoint(0f, 0f),
            InkPoint(1e20f, 0f), InkPoint(1e20f, 100f), InkPoint(9f, 1f))
        assertTrue(InkPoint(9f, 1f).distanceSquaredToSegment(close[4], close[5]).isFinite())
        assertTrue(wideScan(close, 4f))
        assertTrue(close.closesIntoALoop(4f))
    }

    @Test(timeout = 5000)
    fun overflowingReachSquaresDoNotTurnDistantSegmentsIntoTouches() {
        val huge = 1e20f
        val path = listOf(InkPoint(0f, 0f), InkPoint(huge, huge),
            InkPoint(huge * 3f, huge), InkPoint(huge * 5f, huge))
        val touch = 2e19f
        assertFalse(wideScan(path, touch))
        assertFalse(path.closesIntoALoop(touch))
    }

    /** The old Float predicates and grid candidate rule, without enumerating any grid cells. */
    private fun floatScan(path: List<InkPoint>, touch: Float): Boolean {
        if (path.size < 4) return false
        val reach = maxOf(touch, 2f)
        val travelled = FloatArray(path.size)
        for (index in 1 until path.size) {
            travelled[index] = travelled[index - 1] + hypot(path[index].x - path[index - 1].x,
                path[index].y - path[index - 1].y)
        }
        fun side(a: InkPoint, b: InkPoint, p: InkPoint): Float =
            (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)
        val touching = reach * reach
        fun cell(value: Float): Int = floor(value / (reach * 4f)).toInt()
        for (later in 0 until path.size - 1) {
            for (earlier in 0 until later) {
                if (!(travelled[later] - travelled[earlier + 1] > reach * 8f)) continue
                val a = path[earlier]
                val b = path[earlier + 1]
                val c = path[later]
                val d = path[later + 1]
                if (cell(minOf(a.x, b.x) - reach) > cell(maxOf(c.x, d.x) + reach) ||
                    cell(maxOf(a.x, b.x) + reach) < cell(minOf(c.x, d.x) - reach) ||
                    cell(minOf(a.y, b.y) - reach) > cell(maxOf(c.y, d.y) + reach) ||
                    cell(maxOf(a.y, b.y) + reach) < cell(minOf(c.y, d.y) - reach)) continue
                if (side(a, b, c) * side(a, b, d) < 0f && side(c, d, a) * side(c, d, b) < 0f) return true
                if (a.distanceSquaredToSegment(c, d) <= touching || b.distanceSquaredToSegment(c, d) <= touching ||
                    c.distanceSquaredToSegment(a, b) <= touching || d.distanceSquaredToSegment(a, b) <= touching) return true
            }
        }
        return false
    }

    /** All-pairs oracle: exact decimal geometry on Float values, plus local Double travel. */
    private fun wideScan(path: List<InkPoint>, touch: Float): Boolean {
        val reach = maxOf(touch, 2f).toDouble()
        fun number(value: Float): BigDecimal = BigDecimal(value.toDouble())
        val touching = BigDecimal(reach) * BigDecimal(reach)
        fun side(a: InkPoint, b: InkPoint, p: InkPoint): BigDecimal =
            (number(b.x) - number(a.x)) * (number(p.y) - number(a.y)) -
                (number(b.y) - number(a.y)) * (number(p.x) - number(a.x))
        fun within(p: InkPoint, a: InkPoint, b: InkPoint): Boolean {
            val x = number(p.x) - number(a.x)
            val y = number(p.y) - number(a.y)
            val dx = number(b.x) - number(a.x)
            val dy = number(b.y) - number(a.y)
            val length = dx * dx + dy * dy
            if (length.signum() == 0 || (x * dx + y * dy).signum() <= 0) return x * x + y * y <= touching
            val endX = number(p.x) - number(b.x)
            val endY = number(p.y) - number(b.y)
            if ((endX * dx + endY * dy).signum() >= 0) return endX * endX + endY * endY <= touching
            val area = x * dy - y * dx
            return area * area <= touching * length
        }
        for (later in 0 until path.size - 1) {
            for (earlier in 0 until later) {
                var travelled = 0.0
                for (segment in earlier + 1 until later) {
                    travelled += hypot(path[segment + 1].x.toDouble() - path[segment].x.toDouble(),
                        path[segment + 1].y.toDouble() - path[segment].y.toDouble())
                }
                if (travelled <= reach * 8.0) continue
                val a = path[earlier]
                val b = path[earlier + 1]
                val c = path[later]
                val d = path[later + 1]
                if ((side(a, b, c) * side(a, b, d)).signum() < 0 &&
                    (side(c, d, a) * side(c, d, b)).signum() < 0) return true
                if (within(a, c, d) || within(b, c, d) || within(c, a, b) || within(d, a, b)) return true
            }
        }
        return false
    }
}
