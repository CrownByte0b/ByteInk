package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The Android app's `InkEraserTest`, on the JVM. */
class InkEraserTest {

    @Test
    fun normalEraserRemovesOnlyTheCrossedPartOfAStroke() {
        val ink = ViveBrushes.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f)
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        val original = listOf(PageStroke("stroke", ink))

        assertEquals(listOf("stroke"), original.targetsFor(mask))
        val erased = original.subtract(mask, listOf("stroke")).map(PageStroke::stroke)

        assertTrue(erased.any { it.shape.overlaps(box(20f, 48f, 30f, 52f)) })
        assertFalse(erased.any { it.shape.overlaps(box(48f, 48f, 52f, 52f)) })
        assertTrue(erased.any { it.shape.overlaps(box(70f, 48f, 80f, 52f)) })
    }

    @Test
    fun replayTargetsProtectInkDrawnAfterTheErase() {
        val oldInk = ViveBrushes.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f)
        val newInk = ViveBrushes.eraseMask(inputs(50f to 10f, 50f to 90f), sizeDp = 6f)
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        val page = listOf(PageStroke("old", oldInk), PageStroke("new", newInk))

        val replayed = page.subtract(mask, targetIds = listOf("old"))

        assertFalse(replayed.filter { it.id == "old" }.any { it.stroke.shape.overlaps(box(48f, 48f, 52f, 52f)) })
        assertTrue(replayed.first { it.id == "new" }.stroke.shape.overlaps(box(48f, 48f, 52f, 52f)))
    }

    /**
     * A highlighter is drawn from its outlines (DISCARD is path-rendered) and `split` returns pieces
     * without outlines, so a cut highlighter is cut but never split.
     */
    @Test
    fun aCutHighlighterKeepsTheOutlinesItIsDrawnFrom() {
        val ink = Stroke(brush = highlighterBrush(), inputs = inputs(10f to 50f, 90f to 50f))
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)

        val erased = listOf(PageStroke("stroke", ink)).subtract(mask, listOf("stroke"))

        assertEquals(1, erased.size, "a highlighter stays one object")
        val shape = erased.single().stroke.shape
        val outlines = (0 until shape.getRenderGroupCount()).sumOf { shape.getOutlineCount(it) }
        assertTrue(outlines > 0, "nothing left to draw the stroke from")
        assertTrue(shape.overlaps(box(20f, 48f, 30f, 52f)))
        assertFalse(shape.overlaps(box(48f, 48f, 52f, 52f)))
        assertTrue(shape.overlaps(box(70f, 48f, 80f, 52f)))
    }

    @Test
    fun aCutPenIsStillSplitIntoIndependentProjections() {
        val ink = Stroke(brush = startingPen(), inputs = inputs(10f to 50f, 90f to 50f))
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)

        val erased = listOf(PageStroke("stroke", ink)).subtract(mask, listOf("stroke"))

        assertEquals(2, erased.size)
    }

    @Test
    fun objectEraseRemovesAnEntireHighlighterAtFirstContact() {
        val highlighter = PageStroke("highlighter", Stroke(highlighterBrush(), inputs(10f to 50f, 90f to 50f)))
        val elsewhere = PageStroke("pen", ViveBrushes.eraseMask(inputs(10f to 200f, 90f to 200f), 6f))
        val page = listOf(highlighter, elsewhere)
        val mask = ViveBrushes.eraseMask(inputs(20f to 45f, 20f to 55f), sizeDp = 12f)

        val erased = page.eraseObjects(mask, page.targetsFor(mask))

        assertEquals(listOf("pen"), erased.map(PageStroke::id))
    }

    @Test
    fun objectEraserRemovesOnlyTheDisconnectedRegionItTouches() {
        val ink = ViveBrushes.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f)
        val separatingMask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)
        val separated = listOf(PageStroke("stroke", ink)).subtract(separatingMask, targetIds = listOf("stroke"))
        val objectMask = ViveBrushes.eraseMask(inputs(20f to 45f, 20f to 55f), sizeDp = 12f)

        val erased = separated.eraseObjects(objectMask, targetIds = listOf("stroke")).single().stroke

        assertFalse(erased.shape.overlaps(box(20f, 48f, 30f, 52f)))
        assertFalse(erased.shape.overlaps(box(48f, 48f, 52f, 52f)))
        assertTrue(erased.shape.overlaps(box(70f, 48f, 80f, 52f)))
    }

    @Test
    fun eraseMaskRoundTripsForPageReload() {
        val mask = ViveBrushes.eraseMask(inputs = inputs(12f to 34f, 56f to 78f), sizeDp = 23f)

        val row = ViveInkCodec.encodeErase(mask, "erase", "page", InkEraseMode.Object, createdAt = 42L, targetIds = emptyList())
        val restored = assertNotNull(ViveInkCodec.decodeErase(row))

        assertEquals(23f, restored.brush.size, 0.001f)
        assertEquals(mask.inputs.size, restored.inputs.size)
        assertEquals(InkEraseMode.Object, InkEraseMode.of(row.mode))
        assertEquals(42L, row.createdAt)
    }

    @Test
    fun lassoSelectsAndMovesOnlyTheObjectWhoseCenterItEncloses() {
        val left = PageStroke("left", ViveBrushes.eraseMask(inputs(20f to 50f, 40f to 50f), 6f))
        val right = PageStroke("right", ViveBrushes.eraseMask(inputs(120f to 50f, 140f to 50f), 6f))
        val page = listOf(left, right)
        val path = rectangle(5f, 30f, 60f, 70f)

        val selection = assertNotNull(page.selectWithLasso(path))
        val moved = page.moveSelected(
            InkLassoMove(path = path, targetIds = selection.targetIds, projections = selection.projections, dx = 50f, dy = 20f),
        )

        assertEquals(setOf("left"), selection.targetIds)
        assertEquals(50f, moved.first { it.id == "left" }.offsetX, 0.001f)
        assertEquals(20f, moved.first { it.id == "left" }.offsetY, 0.001f)
        assertEquals(0f, moved.first { it.id == "right" }.offsetX, 0.001f)
    }

    @Test
    fun touchingOneGroupedStrokeSelectsTheWholeGroupAndUsesOneUnionBounds() {
        val left = PageStroke("left", ViveBrushes.eraseMask(inputs(20f to 50f, 40f to 50f), 6f), groupId = "group")
        val right = PageStroke("right", ViveBrushes.eraseMask(inputs(120f to 50f, 140f to 50f), 6f), groupId = "group")

        val selection = assertNotNull(listOf(left, right).selectWithLasso(rectangle(5f, 30f, 60f, 70f)))

        assertEquals(setOf("left", "right"), selection.targetIds)
        assertTrue(selection.bounds.left < 20f)
        assertTrue(selection.bounds.right > 140f)
    }

    @Test
    fun aTightLassoSelectsAnInnerObjectWithoutItsLargerSurround() {
        val outer = PageStroke("outer", ViveBrushes.eraseMask(inputs(10f to 10f, 100f to 100f), 6f))
        val inner = PageStroke("inner", ViveBrushes.eraseMask(inputs(47f to 47f, 57f to 57f), 6f))

        val selection = listOf(outer, inner).selectWithLasso(rectangle(40f, 40f, 65f, 65f))

        assertEquals(setOf("inner"), selection?.targetIds)
    }

    @Test
    fun aTightCurvedLassoUsesTheInkOutlineInsteadOfEmptyBoundingBoxCorners() {
        val triangle = PageStroke(
            "triangle",
            ViveBrushes.eraseMask(inputs(50f to 20f, 40f to 50f, 60f to 50f, 50f to 20f), 4f),
        )
        // Encloses the drawn triangle closely; the top corners of its bounds are outside.
        val tightLasso = listOf(InkPoint(50f, 14f), InkPoint(34f, 56f), InkPoint(66f, 56f))

        val selection = listOf(triangle).selectWithLasso(tightLasso, edgeTolerance = 3f)

        assertEquals(setOf("triangle"), selection?.targetIds)
    }

    @Test
    fun replayedLassoMoveCanMoveOneDisconnectedProjectionWithASharedRowId() {
        val left = PageStroke("stroke", ViveBrushes.eraseMask(inputs(20f to 50f, 40f to 50f), 6f))
        val right = PageStroke("stroke", ViveBrushes.eraseMask(inputs(120f to 50f, 140f to 50f), 6f))

        val moved = listOf(left, right).replayMove(path = rectangle(5f, 30f, 60f, 70f), targetIds = listOf("stroke"), dx = 80f, dy = 0f)

        assertEquals(80f, moved[0].offsetX, 0.001f)
        assertEquals(0f, moved[1].offsetX, 0.001f)
    }

    @Test
    fun cornerResizeScalesSelectedInkAroundTheOppositeCorner() {
        val stroke = PageStroke("stroke", ViveBrushes.eraseMask(inputs(20f to 20f, 40f to 40f), 6f))
        val selection = assertNotNull(listOf(stroke).selectWithLasso(rectangle(10f, 10f, 50f, 50f)))

        val resized = listOf(stroke).resizeSelected(
            InkLassoResize(
                path = selection.path,
                targetIds = selection.targetIds,
                projections = selection.projections,
                anchor = InkPoint(10f, 10f),
                scaleX = 2f,
                scaleY = 1.5f,
            ),
        ).single()

        assertEquals(2f, resized.scaleX, 0.001f)
        assertEquals(1.5f, resized.scaleY, 0.001f)
        assertEquals(-10f, resized.offsetX, 0.001f)
        assertEquals(-5f, resized.offsetY, 0.001f)
    }

    @Test
    fun eraserGeometryFollowsAMovedStroke() {
        val ink = PageStroke(id = "stroke", stroke = ViveBrushes.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 6f), offsetX = 100f)
        val movedMask = ViveBrushes.eraseMask(inputs(150f to 35f, 150f to 65f), sizeDp = 18f)
        val oldMask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)

        assertEquals(listOf("stroke"), listOf(ink).targetsFor(movedMask))
        assertTrue(listOf(ink).targetsFor(oldMask).isEmpty())
        val erased = listOf(ink).subtract(movedMask, listOf("stroke"))
        assertTrue(erased.all { it.offsetX == 100f })
        assertFalse(erased.any { it.stroke.shape.overlaps(box(48f, 48f, 52f, 52f)) })
    }

    @Test
    fun lassoPathRoundTripsForPageReload() {
        val path = rectangle(10f, 20f, 80f, 90f)
        val row = ViveInkCodec.encodeMove(InkLassoMove(path, setOf("a"), emptySet(), dx = 25f, dy = -5f), "move", "page", createdAt = 42L)

        assertEquals(path, ViveInkCodec.decodeMove(row))
        assertEquals(25f, row.dxDp, 0.001f)
        assertEquals(-5f, row.dyDp, 0.001f)
        assertEquals(42L, row.createdAt)
        assertEquals(listOf("a"), row.targetIds)
    }

    /** A highlighter erased down to nothing must not survive as a stroke with an empty mesh. */
    @Test
    fun aHighlighterErasedAwayEntirelyDoesNotSurviveAsAnEmptyMesh() {
        val ink = Stroke(brush = highlighterBrush(), inputs = inputs(48f to 50f, 52f to 50f))
        val coversEverything = ViveBrushes.eraseMask(inputs(10f to 50f, 90f to 50f), sizeDp = 64f)

        val erased = listOf(PageStroke("stroke", ink)).subtract(coversEverything, listOf("stroke"))

        assertTrue(erased.isEmpty(), "an erased highlighter must not survive as an empty mesh")
    }

    /**
     * The guard that keeps an empty mesh away from Ink's shape comparison, which aborts the process
     * on one (`CHECK failed: !meshes_.empty()`). This test fails by crashing the test JVM; that it
     * returns at all is what is asserted.
     */
    @Test
    fun aStrokeWithNoGeometryIsNeverComparedAgainstAMask() {
        val empty = Stroke(brush = startingPen(), inputs = MutableStrokeInputBatch().toImmutable())
        val real = PageStroke("real", ViveBrushes.eraseMask(inputs(10f to 50f, 90f to 50f), 6f))
        val page = listOf(PageStroke("empty", empty), real)
        val mask = ViveBrushes.eraseMask(inputs(50f to 35f, 50f to 65f), sizeDp = 18f)

        assertEquals(listOf("real"), page.targetsFor(mask))
        assertFalse(page.first().touches(mask))
    }

    /** `PenPreset.starting(0)`: the black fountain pen, at stabilization 1 and 1.5 dp. */
    private fun startingPen() = ViveBrushes.brush(ViveBrushes.MARKER, 1, 0xFF000000.toInt(), 1.5f)

    /** `HighlighterSettings()`. */
    private fun highlighterBrush() = ViveBrushes.highlighter(0x66FFEB3B, 18f)

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }.toImmutable()

    private fun box(left: Float, top: Float, right: Float, bottom: Float): ImmutableBox =
        ImmutableBox.fromTwoPoints(ImmutableVec(left, top), ImmutableVec(right, bottom))

    private fun rectangle(left: Float, top: Float, right: Float, bottom: Float): List<InkPoint> =
        listOf(InkPoint(left, top), InkPoint(right, top), InkPoint(right, bottom), InkPoint(left, bottom))

    private fun PartitionedMesh.overlaps(area: ImmutableBox): Boolean = computeCoverageIsGreaterThan(area, 0f)
}
