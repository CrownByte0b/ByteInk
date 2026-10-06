package com.vivenotes.byteink.kit

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.byteink.core.InkMeshes
import kotlin.test.*

class DecodedPageReplayTest {
    @Test
    fun decodedReplaySupportsEraseUndoAndPreservesSourceMeshesAndDrawingOrder() {
        val ink = ViveBrushes.eraseMask(inputs(10f to 50f, 100f to 50f), 6f)
        val first = ViveInkCodec.encodeStroke(ink, "old", "page", 0, ViveBrushes.MARKER, 0, false, 1L)
        val later = first.copy(id = "later", seq = 1)
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), 18f)
        val erase = ViveInkCodec.encodeErase(mask, "cut", "page", InkEraseMode.Normal, 2L, listOf(first.id))
        val loaded = ViveInkPage.load(listOf(first, later), listOf(erase), emptyList())
        val replayed = ViveInkPage.replay(loaded.sourceStrokes, loaded.operations)
        assertEquals(listOf("old", "old", "later"), replayed.map { it.id })
        loaded.strokes.zip(replayed).forEach { (expected, actual) -> assertMesh(expected, actual) }
        val undone = ViveInkPage.replay(loaded.sourceStrokes, emptyList())
        assertEquals(listOf("old", "later"), undone.map { it.id })
        assertSame(loaded.sourceStrokes[0], undone[0])
        assertEquals(3, loaded.strokes.size, "undo leaves the previous rendered snapshot intact")
    }

    @Test
    fun decodedReplayOrdersMasksByPersistedTimeBeforeSplittingTheirTargets() {
        val ink = ViveBrushes.eraseMask(inputs(10f to 50f, 110f to 50f), 6f)
        val row = ViveInkCodec.encodeStroke(ink, "stroke", "page", 0, ViveBrushes.MARKER, 0, false, 1L)
        val cut = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(inputs(60f to 35f, 60f to 65f), 18f),
            "normal", "page", InkEraseMode.Normal, 2L, listOf(row.id))
        val objectErase = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(inputs(25f to 50f), 10f),
            "object", "page", InkEraseMode.Object, 3L, listOf(row.id))
        val loaded = ViveInkPage.load(listOf(row), listOf(cut, objectErase), emptyList())
        val replayed = ViveInkPage.replay(loaded.sourceStrokes, loaded.operations.reversed())
        assertEquals(1, replayed.size, "Object removes only the left fragment created by the earlier Normal mask")
        assertMesh(loaded.strokes.single(), replayed.single())
        assertEquals(listOf("normal", "object"), loaded.operations.map { it.id })
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.MOUSE, x, y, index * 10L) }
    }

    private fun assertMesh(expected: PageStroke, actual: PageStroke) {
        assertEquals(expected.pageBounds, actual.pageBounds)
        repeat(expected.stroke.shape.getRenderGroupCount()) { group ->
            val a = InkMeshes.triangles(expected.stroke.shape, group)
            val b = InkMeshes.triangles(actual.stroke.shape, group)
            assertEquals(a.size, b.size)
            a.zip(b).forEach { (x, y) -> assertContentEquals(x.positions, y.positions); assertContentEquals(x.triangles, y.triangles) }
        }
    }
}
