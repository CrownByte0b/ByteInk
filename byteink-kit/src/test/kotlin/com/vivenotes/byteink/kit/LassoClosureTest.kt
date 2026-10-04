package com.vivenotes.byteink.kit

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The Android app's `LassoClosureTest`, on the JVM, for ink: a lasso selects only once the gesture
 * closes into a loop. The app's own shapes are not part of byteink.
 */
class LassoClosureTest {

    /** A short horizontal stroke around (90..110, 100). */
    private fun ink(id: String = "stroke") = PageStroke(id, ViveBrushes.eraseMask(inputs(90f to 100f, 110f to 100f), 6f))

    /** The same stroke, cut in two, so both kinds of projection are held to one rule. */
    private fun erasedInk(): List<PageStroke> = listOf(
        PageStroke("stroke", ViveBrushes.eraseMask(inputs(60f to 100f, 140f to 100f), 6f)),
    ).subtract(ViveBrushes.eraseMask(inputs(100f to 85f, 100f to 115f), 18f), listOf("stroke"))

    private fun select(strokes: List<PageStroke>, path: List<InkPoint>) = strokes.selectInkWithLasso(path)

    /** An arc around the ink, [degrees] of the way round; less than 360 leaves a mouth. */
    private fun arc(degrees: Int, radius: Float = 70f): List<InkPoint> = (0..degrees step 5).map {
        val radians = Math.toRadians(it.toDouble())
        InkPoint(100f + radius * cos(radians).toFloat(), 100f + radius * sin(radians).toFloat())
    }

    /** Two legs and a lift: the triangle this would close into holds the ink. */
    private val elbow = listOf(InkPoint(40f, 40f), InkPoint(40f, 160f), InkPoint(220f, 160f))

    private val loop = listOf(
        InkPoint(40f, 40f), InkPoint(220f, 40f), InkPoint(220f, 160f), InkPoint(40f, 160f), InkPoint(40f, 40f),
    )

    @Test
    fun anElbowSelectsNothing() {
        assertNull(select(listOf(ink()), elbow))
    }

    @Test
    fun anElbowSelectsNoPieceOfErasedInkEither() {
        assertNull(select(erasedInk(), elbow))
    }

    @Test
    fun threeSidesOfARectangleSelectNothing() {
        assertNull(select(listOf(ink()), loop.dropLast(1)))
    }

    /** Nearly closed is not closed. */
    @Test
    fun aCLeftOpenByFiveDegreesSelectsNothing() {
        val almost = arc(355)

        assertNull(select(listOf(ink()), almost), "the mouth is 6dp wide and it is still a mouth")
        assertNull(select(erasedInk(), almost))
    }

    @Test
    fun aCircleClosedOnItsOwnStartSelects() {
        assertEquals(setOf("stroke"), select(listOf(ink()), arc(360))?.targetIds)
        assertEquals(setOf("stroke"), select(erasedInk(), arc(360))?.targetIds)
    }

    @Test
    fun aClosedRectangleSelects() {
        assertEquals(setOf("stroke"), select(listOf(ink()), loop)?.targetIds)
        assertEquals(setOf("stroke"), select(erasedInk(), loop)?.targetIds)
    }

    @Test
    fun aLoopThatCarriesOnPastItsStartSelects() {
        assertNotNull(select(listOf(ink()), loop + InkPoint(130f, 40f)))
        assertNotNull(select(listOf(ink()), arc(400)))
    }

    @Test
    fun aLoopThatCrossesItsOwnTailSelects() {
        // The last leg runs up through the first one rather than landing on its start.
        val crossed = listOf(
            InkPoint(60f, 60f), InkPoint(220f, 60f), InkPoint(220f, 160f), InkPoint(40f, 160f),
            InkPoint(40f, 40f), InkPoint(100f, 80f),
        )

        assertNotNull(select(listOf(ink()), crossed))
    }

    // The same questions, asked of paths sampled the way a hand leaves them.

    @Test
    fun aDenselyDrawnElbowSelectsNothing() {
        assertNull(select(listOf(ink()), asDrawn(elbow)))
    }

    @Test
    fun aDenselyDrawnCLeftOpenSelectsNothing() {
        assertNull(select(listOf(ink()), asDrawn(arc(355))))
        assertNull(select(erasedInk(), asDrawn(arc(355))))
    }

    @Test
    fun aDenselyDrawnOpenRectangleSelectsNothing() {
        assertNull(select(listOf(ink()), asDrawn(loop.dropLast(1))))
    }

    @Test
    fun aDenselyDrawnClosedLoopSelects() {
        assertEquals(setOf("stroke"), select(listOf(ink()), asDrawn(loop))?.targetIds)
        assertEquals(setOf("stroke"), select(listOf(ink()), asDrawn(arc(360)))?.targetIds)
        assertEquals(setOf("stroke"), select(erasedInk(), asDrawn(loop))?.targetIds)
    }

    @Test
    fun aDenselyDrawnLoopThatCarriesOnPastItsStartSelects() {
        assertNotNull(select(listOf(ink()), asDrawn(arc(400))))
    }

    /** The path as a hand leaves it: a point every half page unit. */
    private fun asDrawn(path: List<InkPoint>): List<InkPoint> {
        val dense = mutableListOf(path.first())
        path.zipWithNext().forEach { (from, to) ->
            val length = hypot(to.x - from.x, to.y - from.y)
            val steps = ceil(length / 0.5f).toInt().coerceAtLeast(1)
            (1..steps).forEach { step ->
                val fraction = step.toFloat() / steps
                dense += InkPoint(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)
            }
        }
        return dense
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }.toImmutable()
}
