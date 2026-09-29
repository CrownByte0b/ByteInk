package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Android app's `DeleteProjectionTest`, on the JVM: deleting one piece of a cut stroke. */
class DeleteProjectionTest {

    /** A stroke straight across the page, cut in two by an eraser through its middle. */
    private fun cutInHalf(): List<PageStroke> {
        val ink = PageStroke("stroke", ViveBrushes.eraseMask(inputs(10f to 50f, 90f to 50f), 6f))
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        return listOf(ink).subtract(mask, listOf("stroke"))
    }

    private fun List<PageStroke>.left() = minBy { it.pageBounds!!.left }

    private fun List<PageStroke>.right() = maxBy { it.pageBounds!!.left }

    @Test
    fun deletingOnePieceStoresOneEraseAndKeepsTheOther() {
        val pieces = cutInHalf()

        val plan = pieces.planProjectionDelete(setOf(pieces.left().projectionKey))

        assertEquals(emptyList(), plan.wholeRows, "the row still holds ink, so nothing may be tombstoned")
        assertEquals(1, plan.erases.size)
        assertEquals("stroke", plan.erases.single().rowId)
        assertEquals(1, plan.after.size)
        assertEquals(pieces.right().pageBounds!!.left, plan.after.single().pageBounds!!.left, 0.001f)
    }

    /** The mask is the claim; this is the proof of it, put to the mesh. */
    @Test
    fun theStoredMaskTakesTheCircledPieceAndNotItsNeighbour() {
        val pieces = cutInHalf()

        val mask = pieces.planProjectionDelete(setOf(pieces.left().projectionKey)).erases.single().mask

        assertTrue(pieces.left().touches(mask), "the mask missed the piece it was built for")
        assertFalse(pieces.right().touches(mask), "the mask reached across the gap")
    }

    /** What page-open replay does with the stored row has to produce the page the gesture produced. */
    @Test
    fun replayingTheStoredEraseReachesTheSamePage() {
        val pieces = cutInHalf()
        val plan = pieces.planProjectionDelete(setOf(pieces.left().projectionKey))
        val stored = ViveInkCodec.encodeErase(plan.erases.single().mask, "erase", "page", InkEraseMode.Object, 1L, listOf("stroke"))

        val decoded = assertNotNull(ViveInkCodec.decodeErase(stored), "the mask did not survive the codec")
        val replayed = pieces.eraseObjects(decoded, listOf("stroke"))

        assertEquals(1, replayed.size)
        assertEquals(pieces.right().pageBounds!!.left, replayed.single().pageBounds!!.left, 0.001f)
    }

    @Test
    fun deletingEveryPieceTombstonesTheRow() {
        val pieces = cutInHalf()

        val plan = pieces.planProjectionDelete(pieces.map { it.projectionKey }.toSet())

        assertEquals(listOf("stroke"), plan.wholeRows)
        assertEquals(0, plan.erases.size, "a tombstone says it; an erase would say it twice")
        assertEquals(emptyList(), plan.after)
    }

    @Test
    fun deletingAnUncutStrokeTombstonesIt() {
        val whole = listOf(PageStroke("stroke", ViveBrushes.eraseMask(inputs(10f to 50f, 38f to 50f), 6f)))

        val plan = whole.planProjectionDelete(setOf(whole.single().projectionKey))

        assertEquals(listOf("stroke"), plan.wholeRows)
        assertEquals(0, plan.erases.size)
        assertEquals(emptyList(), plan.after)
    }

    @Test
    fun aRowNothingIsHeldOfIsLeftAlone() {
        val pieces = cutInHalf()
        val bystander = PageStroke("other", ViveBrushes.eraseMask(inputs(10f to 200f, 90f to 200f), 6f))

        val plan = (pieces + bystander).planProjectionDelete(setOf(pieces.left().projectionKey))

        assertEquals(listOf("stroke"), plan.erases.map { it.rowId }, "only the cut row is operated on")
        assertEquals(bystander.projectionKey, plan.after.single { it.id == "other" }.projectionKey, "a row nobody circled was re-projected")
    }

    @Test
    fun everyLivePieceOffersAPointOnItsInk() {
        cutInHalf().forEach { piece ->
            val point = assertNotNull(piece.pointOnInk(), "a live projection had no point to place a mask on")
            val bounds = piece.pageBounds!!
            assertTrue(point.x >= bounds.left - 0.5f && point.x <= bounds.right + 0.5f)
            assertTrue(point.y >= bounds.top - 0.5f && point.y <= bounds.bottom + 0.5f)
        }
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }.toImmutable()
}
