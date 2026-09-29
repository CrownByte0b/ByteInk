package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.ExperimentalInkCustomBrushApi
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.vive.PageStroke
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.subtract
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkPathRendererTest {
    private val renderer = InkPathRenderer()
    private val black = 0xff000000.toInt()

    @Test
    fun everyViveBrushProducesVisiblePixels() {
        val ids = listOf(ViveBrushes.MARKER, ViveBrushes.PRESSURE_PEN, ViveBrushes.DASHED_LINE, ViveBrushes.HIGHLIGHTER) +
            (0..5).map(ViveBrushes::calligraphy)
        ids.forEach { id ->
            val stroke = Stroke(ViveBrushes.brush(id, 1, black, 12f), inputs(20f to 40f, 100f to 40f))
            raster { assertTrue(renderer.draw(it, stroke), id) }.use { image ->
                assertTrue(pixels(image).count { it ushr 24 > 0 } > 30, id)
            }
        }
    }

    @Test
    fun aHighlighterCrossingIsFilledOnceButTwoStrokesStillAccumulate() {
        val brush = ViveBrushes.highlighter(0x80ff0000.toInt(), 14f)
        val stroke = Stroke(brush, inputs(20f to 20f, 100f to 100f, 20f to 100f, 100f to 20f))
        raster { renderer.draw(it, stroke) }.use { image ->
            assertEquals(128, image.getColor(60, 60) ushr 24)
            assertTrue(pixels(image).all { it ushr 24 <= 128 }, "no part of one stroke darkens")
        }
        raster { renderer.draw(it, stroke); renderer.draw(it, stroke) }.use { image ->
            assertEquals(192, image.getColor(60, 60) ushr 24)
        }
    }

    @Test
    fun splitPiecesDrawWithAnEmptyErasedGapAndNoTriangleSeams() {
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x80000000.toInt(), 12f), inputs(10f to 60f, 110f to 60f))
        val mask = ViveBrushes.eraseMask(inputs(60f to 20f, 60f to 100f), 20f)
        val pieces = listOf(PageStroke("cut", stroke)).subtract(mask, listOf("cut"))
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.stroke.shape.getOutlineCount(0) == 0 })
        raster { canvas -> pieces.forEach { renderer.draw(canvas, it.stroke) } }.use { image ->
            assertEquals(0, image.getColor(60, 60))
            for (x in 15..45) assertEquals(128, image.getColor(x, 60) ushr 24, "left piece at $x")
            for (x in 75..105) assertEquals(128, image.getColor(x, 60) ushr 24, "right piece at $x")
            assertTrue(pixels(image).all { it ushr 24 <= 128 })
        }
    }

    @Test
    fun aDiscardEraseKeepsTheHoleInsideItsOutline() {
        val stroke = Stroke(ViveBrushes.highlighter(0x80ff0000.toInt(), 24f), inputs(10f to 60f, 110f to 60f))
        val mask = ViveBrushes.eraseMask(inputs(60f to 60f), 8f)
        val pieces = listOf(PageStroke("cut", stroke)).subtract(mask, listOf("cut"))
        raster { canvas -> pieces.forEach { renderer.draw(canvas, it.stroke) } }.use { image ->
            assertEquals(0, image.getColor(60, 60))
            assertEquals(128, image.getColor(30, 60) ushr 24)
        }
    }

    @Test
    fun translationRotationAndNonuniformScaleApplyAndRestoreTheCanvas() {
        val stroke = marker(inputs(10f to 20f, 30f to 20f))
        // x' = -2y + 100, y' = x + 10: clockwise turn and unequal scales.
        val transform = ImmutableAffineTransform(0f, -2f, 100f, 1f, 0f, 10f)
        raster { canvas ->
            renderer.draw(canvas, stroke, transform)
            renderer.draw(canvas, stroke)
        }.use { image ->
            assertEquals(black, image.getColor(60, 30))
            assertEquals(black, image.getColor(20, 20))
            assertEquals(0, image.getColor(90, 90))
        }
    }

    @Test
    fun cullingUsesTransformedBoundsBeforeBuildingPaths() {
        val stroke = marker(inputs(10f to 20f, 30f to 20f))
        raster { canvas ->
            assertFalse(renderer.draw(canvas, stroke, viewport = Rect(70f, 70f, 120f, 120f)))
            assertEquals(0, renderer.pathBuildCount)
            assertTrue(renderer.draw(canvas, stroke, ImmutableAffineTransform(1f, 0f, 70f, 0f, 1f, 70f), Rect(70f, 70f, 120f, 120f)))
        }.use { image -> assertEquals(black, image.getColor(90, 90)) }
    }

    @Test
    fun recolourAndZoomReusePathsAndTheCacheIsBounded() {
        val cache = InkPathRenderer(cacheCapacity = 1)
        val stroke = marker(inputs(10f to 20f, 30f to 20f))
        raster { canvas ->
            cache.draw(canvas, stroke)
            cache.draw(canvas, stroke.copy(stroke.brush.copyWithColorIntArgb(0xffff0000.toInt())), AffineTransform.IDENTITY)
            cache.draw(canvas, stroke, ImmutableAffineTransform(2f, 0f, 0f, 0f, 2f, 0f))
            assertEquals(1, cache.pathBuildCount)
            cache.draw(canvas, marker(inputs(60f to 60f)))
            assertEquals(1, cache.cachedShapeCount)
            cache.draw(canvas, stroke)
            assertEquals(3, cache.pathBuildCount)
            cache.clearCache()
            assertEquals(0, cache.cachedShapeCount)
        }.close()
    }

    @Test
    fun livePathsUpdateThenMatchTheFinishedStroke() {
        val live = InProgressStroke()
        live.start(ViveBrushes.brush(ViveBrushes.MARKER, 0, black, 10f))
        live.enqueueInputs(inputs(10f to 60f, 50f to 60f), MutableStrokeInputBatch())
        live.updateShape(100L)
        raster { renderer.draw(it, live) }.close()
        val builds = renderer.pathBuildCount
        raster { renderer.draw(it, live) }.close()
        assertEquals(builds, renderer.pathBuildCount)
        live.enqueueInputs(MutableStrokeInputBatch().apply { add(InputToolType.MOUSE, 110f, 60f, 200L) }, MutableStrokeInputBatch())
        live.finishInput()
        live.updateShape(300L)
        raster { renderer.draw(it, live) }.use { wet ->
            assertTrue(renderer.pathBuildCount > builds)
            assertEquals(black, wet.getColor(90, 60))
            raster { renderer.draw(it, live.toImmutable()) }.use { dry -> assertEquals(pixels(wet), pixels(dry)) }
        }
        live.clear()
        raster { assertFalse(renderer.draw(it, live)) }.use { image -> assertTrue(pixels(image).all { it == 0 }) }
    }

    @OptIn(ExperimentalInkCustomBrushApi::class)
    @Test
    fun coatsUseTheirOwnColourFunctionsInDrawOrder() {
        val base = BrushTip()
        val family = BrushFamily(coats = listOf(
            BrushCoat(base, BrushPaint(colorFunctions = listOf(BrushPaint.ColorFunction.ReplaceColor.withColorIntArgb(black)))),
            BrushCoat(base, BrushPaint(colorFunctions = listOf(
                BrushPaint.ColorFunction.ReplaceColor.withColorIntArgb(0xffff0000.toInt()),
                BrushPaint.ColorFunction.OpacityMultiplier(0.5f),
            ))),
        ))
        val stroke = Stroke(Brush.createWithColorIntArgb(family, 0xff0000ff.toInt(), 12f, 0.25f), inputs(10f to 60f, 110f to 60f))
        raster { renderer.draw(it, stroke, colorArgb = 0xff00ff00.toInt()) }.use { image ->
            assertEquals(0xff800000.toInt(), image.getColor(60, 60))
        }
    }

    @Test
    fun accumulatePaintIsRefused() {
        val family = BrushFamily(paint = BrushPaint(selfOverlap = SelfOverlap.ACCUMULATE))
        val stroke = Stroke(Brush.createWithColorIntArgb(family, black, 12f, 0.25f), inputs(20f to 60f, 100f to 60f))
        assertFalse(renderer.canDraw(stroke))
        raster { canvas -> assertFailsWith<IllegalArgumentException> { renderer.draw(canvas, stroke) } }
            .use { image -> assertTrue(pixels(image).all { it == 0 }) }
    }

    @Test
    fun emptyAndUnstartedStrokesDrawNothing() {
        raster { canvas ->
            assertFalse(renderer.draw(canvas, InProgressStroke()))
            assertFalse(renderer.draw(canvas, marker(MutableStrokeInputBatch())))
        }.use { image -> assertTrue(pixels(image).all { it == 0 }) }
    }

    private fun marker(inputs: MutableStrokeInputBatch): Stroke =
        Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, black, 10f), inputs.toImmutable())

    private fun inputs(vararg points: Pair<Float, Float>): MutableStrokeInputBatch = MutableStrokeInputBatch().apply {
        points.forEachIndexed { i, (x, y) -> add(InputToolType.MOUSE, x, y, i * 100L) }
    }

    private fun raster(draw: (Canvas) -> Unit): Bitmap = Surface.makeRasterN32Premul(128, 128).use { surface ->
        surface.canvas.clear(0)
        draw(surface.canvas.asComposeCanvas())
        surface.makeImageSnapshot().use(Bitmap::makeFromImage)
    }

    private fun pixels(image: Bitmap): List<Int> = List(image.width * image.height) { i -> image.getColor(i % image.width, i / image.width) }
}
