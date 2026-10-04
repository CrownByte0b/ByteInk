package com.vivenotes.byteink.kit

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The Android app's `LassoAfterEraseTest`, on the JVM: lassoing pieces of erased ink. */
class LassoAfterEraseTest {

    /** A stroke straight across the page, cut in two by an eraser through its middle. */
    private fun cutInHalf(): List<PageStroke> {
        val ink = PageStroke("stroke", ViveBrushes.eraseMask(inputs(10f to 50f, 90f to 50f), 6f))
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        return listOf(ink).subtract(mask, listOf("stroke"))
    }

    /** Pins the library fact the fallback is built on: a split piece has no outline to walk. */
    @Test
    fun aPieceOfAnErasedStrokeHasNoOutlines() {
        val pieces = cutInHalf()
        assertEquals(2, pieces.size, "the cut made two pieces")
        pieces.forEach { piece ->
            val shape = piece.stroke.shape
            val vertices = (0 until shape.getRenderGroupCount()).sumOf { group ->
                (0 until shape.getOutlineCount(group)).sumOf { outline -> shape.getOutlineVertexCount(group, outline) }
            }
            assertEquals(0, vertices, "a split piece carries no outlines")
        }
    }

    @Test
    fun aHandDrawnLassoSelectsAPieceOfAnErasedStroke() {
        val pieces = cutInHalf()
        val left = pieces.minBy { it.pageBounds!!.left }

        val selection = pieces.selectWithLasso(handDrawnLoop(right = 45f))

        assertNotNull(selection, "a loop around the left piece selected nothing")
        assertEquals(setOf("stroke"), selection.targetIds)
        assertEquals(setOf(left.projectionKey), selection.projections)
    }

    @Test
    fun aHandDrawnLassoLeavesThePieceItDidNotCircle() {
        val pieces = cutInHalf()
        val right = pieces.maxBy { it.pageBounds!!.left }

        val selection = assertNotNull(pieces.selectWithLasso(handDrawnLoop(right = 45f)))

        assertFalse(right.projectionKey in selection.projections, "the piece outside the loop was selected too")
        assertTrue(selection.bounds.right < right.pageBounds!!.left, "the selection reached across the erased gap")
    }

    @Test
    fun aLassoRoundTheWholeCutLineTakesBothPieces() {
        val pieces = cutInHalf()

        val selection = pieces.selectWithLasso(handDrawnLoop(right = 99f))

        assertEquals(pieces.map { it.projectionKey }.toSet(), selection?.projections)
    }

    @Test
    fun aHandDrawnLassoLeavesAPieceItOnlyHalfCoversAlone() {
        // The left piece runs from x=7 to x=41; this loop reaches x=25, cutting it in the middle.
        assertNull(cutInHalf().selectWithLasso(handDrawnLoop(right = 25f)))
    }

    @Test
    fun aHandDrawnLassoOverThePaperBetweenTwoPiecesSelectsNothing() {
        assertNull(
            cutInHalf().selectWithLasso(
                listOf(InkPoint(44f, 30f), InkPoint(56f, 30f), InkPoint(55f, 50f), InkPoint(56f, 70f), InkPoint(44f, 70f)),
            ),
        )
    }

    @Test
    fun aConvexLassoSelectsAPieceOfAnErasedStroke() {
        val selection = cutInHalf().selectWithLasso(
            listOf(InkPoint(0f, 30f), InkPoint(45f, 30f), InkPoint(45f, 70f), InkPoint(0f, 70f)),
        )

        assertEquals(setOf("stroke"), selection?.targetIds)
        assertEquals(1, selection?.projections?.size)
    }

    @Test
    fun aHandDrawnLassoSelectsAStrokeThatWasNeverErased() {
        val whole = listOf(PageStroke("stroke", ViveBrushes.eraseMask(inputs(10f to 50f, 38f to 50f), 6f)))

        assertNotNull(whole.selectWithLasso(handDrawnLoop(right = 45f)))
    }

    /** A stored move replays through the same test, so a piece of erased ink moves on the next open too. */
    @Test
    fun aReplayedMoveReachesAPieceOfAnErasedStroke() {
        val moved = cutInHalf().replayMove(handDrawnLoop(right = 45f), targetIds = listOf("stroke"), dx = 5f, dy = 7f)

        val left = moved.minBy { it.pageBounds!!.left }
        val right = moved.maxBy { it.pageBounds!!.left }
        assertEquals(5f, left.offsetX, 0.001f)
        assertEquals(7f, left.offsetY, 0.001f)
        assertEquals(0f, right.offsetX, 0.001f, "the piece outside the loop stayed put")
    }

    /** Concave, because only a loop that is not convex reaches the exact test, as a hand-drawn one is. */
    private fun handDrawnLoop(right: Float): List<InkPoint> = listOf(
        InkPoint(0f, 30f),
        InkPoint(right, 30f),
        InkPoint(right - 1f, 45f),
        InkPoint(right, 50f),
        InkPoint(right - 1f, 55f),
        InkPoint(right, 70f),
        InkPoint(0f, 70f),
    )

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }.toImmutable()
}
