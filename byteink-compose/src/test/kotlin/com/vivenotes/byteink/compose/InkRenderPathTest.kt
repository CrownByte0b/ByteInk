package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asSkiaPath
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.vive.ViveBrushes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkRenderPathTest {
    @Test
    fun bulkContoursPreserveEmptyDotsRepeatedPointsHolesAndCrossings() {
        val outer = floatArrayOf(8f, 8f, 112f, 8f, 112f, 112f, 8f, 112f)
        val hole = floatArrayOf(32f, 32f, 32f, 96f, 96f, 96f, 96f, 32f)
        val cases = listOf(
            emptyList(), listOf(floatArrayOf()), listOf(floatArrayOf(20f, 20f)),
            listOf(floatArrayOf(20f, 20f, 20f, 20f)),
            listOf(outer, hole), listOf(outer, outer),
            listOf(floatArrayOf(8f, 8f, 112f, 112f, 8f, 112f, 112f, 8f, 8f, 8f)),
        )
        cases.forEach(::compare)
    }

    @Test
    fun shortAndLongRealInkOutlinesPreserveExactVerbsPointsAndPixels() {
        val brushes = listOf(ViveBrushes.MARKER, ViveBrushes.PRESSURE_PEN, ViveBrushes.DASHED_LINE,
            ViveBrushes.HIGHLIGHTER) + (0..5).map(ViveBrushes::calligraphy)
        for (id in brushes) for (count in listOf(9, 1024, 8192)) {
            val inputs = MutableStrokeInputBatch().apply {
                repeat(count) { i ->
                    add(InputToolType.MOUSE, 16f + i * .01f,
                        60f + sin(i * .08).toFloat() * 40f, i * 8L)
                }
            }
            val stroke = Stroke(ViveBrushes.brush(id, 0, 0x804020e0.toInt(), 3f), inputs)
            for (coat in 0 until stroke.shape.getRenderGroupCount()) compare(InkMeshes.outlines(stroke.shape, coat))
        }
    }

    @Test
    fun retirementReleasesGeometryAndClosesTheNativeSnapshotIdempotently() {
        val path = outlineInkPath(listOf(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f)))
        val snapshot = path.path.asSkiaPath()
        assertFalse(snapshot.isClosed)
        assertTrue(snapshot.pointsCount > 0)
        path.close()
        assertTrue(path.isClosed)
        assertTrue(snapshot.isClosed)
        path.close()
    }

    private fun compare(outlines: List<FloatArray>) {
        val scalar = Path().apply {
            fillType = PathFillType.NonZero
            for (p in outlines) if (p.isNotEmpty()) {
                moveTo(p[0], p[1])
                for (i in 2 until p.size step 2) lineTo(p[i], p[i + 1])
                close()
            }
        }
        val reference = scalar.asSkiaPath()
        try {
            outlineInkPath(outlines).use { bulk ->
                val actual = bulk.path.asSkiaPath()
                assertEquals(reference.fillMode, actual.fillMode)
                assertContentEquals(reference.verbs, actual.verbs)
                assertContentEquals(reference.points, actual.points)
                for (background in listOf(0, 0xffffffff.toInt(), 0xff16171a.toInt())) {
                    for (transform in listOf(Matrix(), Matrix().apply {
                        this[0, 0] = -.7f; this[1, 0] = .12f; this[3, 0] = 114.25f
                        this[0, 1] = .2f; this[1, 1] = .8f; this[3, 1] = 3.75f
                    })) {
                        assertContentEquals(raster(scalar, background, transform), raster(bulk.path, background, transform))
                    }
                }
            }
        } finally {
            scalar.reset()
            reference.close()
        }
    }

    private fun raster(path: Path, background: Int, transform: Matrix): ByteArray =
        Surface.makeRasterN32Premul(128, 128).use { surface ->
            surface.canvas.clear(background)
            val canvas: Canvas = surface.canvas.asComposeCanvas()
            canvas.clipRect(2.25f, 1.5f, 121.5f, 122.75f)
            canvas.concat(transform)
            val paint = Paint().apply { isAntiAlias = true; color = androidx.compose.ui.graphics.Color(0x804020e0) }
            canvas.drawPath(path, paint)
            surface.makeImageSnapshot().use { image ->
                Bitmap.makeFromImage(image).use { requireNotNull(it.readPixels()) }
            }
        }
}
