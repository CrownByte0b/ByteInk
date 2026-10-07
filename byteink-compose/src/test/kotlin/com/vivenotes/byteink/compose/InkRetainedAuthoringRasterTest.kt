@file:OptIn(androidx.ink.brush.ExperimentalInkAnimationApi::class, androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.skiaCanvas
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.subtract
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

class InkRetainedAuthoringRasterTest {
    private fun marker(color: Int = 0xff000000.toInt(), size: Float = 12f) =
        ViveBrushes.brush(ViveBrushes.MARKER, 0, color, size)

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) ->
            add(InputToolType.STYLUS, x, y, index * 10L, pressure = 1f)
        }
    }

    private fun sample(x: Float, y: Float, time: Long = 1000L) =
        InkPointerSample(x, y, time, InputToolType.STYLUS, 1f)

    private class Fixture(
        budget: Long = 64L * 1024L * 1024L,
        val renderer: InkMeshRenderer = InkMeshRenderer(),
        val referenceRenderer: InkMeshRenderer = InkMeshRenderer(),
        routeRenderer: (InkMeshRenderer) -> InkRenderer = { it },
        routeReferenceRenderer: (InkMeshRenderer) -> InkRenderer = { it },
    ) : AutoCloseable {
        val retained = InkRetainedAuthoringRaster(budget)
        val session = InkAuthoringSession(predictorFactory = null)
        val finished = mutableListOf<InkSceneStroke>()
        val routedRenderer = routeRenderer(renderer)
        val routedReferenceRenderer = routeReferenceRenderer(referenceRenderer)
        var width = 128
        var height = 128
        var scale = 1f
        var clearColor = 0xffffffff.toInt()
        var overrideTransform: AffineTransform? = null
        var overlay: (Canvas, Int, Int) -> Unit = { _, _, _ -> }
        var contentCalls = 0
        private val drawContent: (Canvas, Int, Int) -> Unit = { canvas, width, height ->
            contentCalls++
            content(canvas, width, height, routedRenderer)
        }

        init {
            session.onLiveStrokeRetired = { stroke ->
                retained.retireLiveStroke(stroke)
                routedRenderer.releaseLiveStroke(stroke)
                routedReferenceRenderer.releaseLiveStroke(stroke)
            }
        }

        fun send(event: InkInputEvent, brush: Brush, transform: AffineTransform = AffineTransform.IDENTITY) {
            session.handle(event, brush, transform) { _, stroke ->
                finished.add(InkSceneStroke(stroke, transform))
                retained.invalidateContent()
            }
        }

        private fun live() = session.liveStrokes.map { live ->
            overrideTransform?.let { live.copy(strokeToView = it) } ?: live
        }

        private fun content(canvas: Canvas, width: Int, height: Int, renderer: InkRenderer) {
            overlay(canvas, width, height)
            val viewport = Rect(0f, 0f, width.toFloat(), height.toFloat())
            finished.forEach { renderer.render(canvas, it.stroke, it.strokeToScene, viewport, it.colorArgb) }
        }

        fun drawRetained(): IntArray = rasterPixels(width, height) { canvas ->
            retained.draw(canvas, width, height, scale, clearColor, routedRenderer, live(), drawContent)
        }

        fun frame(label: String): IntArray {
            val actual = drawRetained()
            val expected = rasterPixels(width, height) { canvas ->
                canvas.clear(clearColor)
                canvas.save()
                try {
                    canvas.scale(scale, scale)
                    val logicalWidth = (width / scale).toInt()
                    val logicalHeight = (height / scale).toInt()
                    val compose = canvas.asComposeCanvas()
                    content(compose, logicalWidth, logicalHeight, routedReferenceRenderer)
                    val viewport = Rect(0f, 0f, logicalWidth.toFloat(), logicalHeight.toFloat())
                    live().forEach { routedReferenceRenderer.render(compose, it.stroke, it.strokeToView, viewport) }
                } finally {
                    canvas.restore()
                }
            }
            if (!expected.contentEquals(actual)) {
                val at = expected.indices.first { expected[it] != actual[it] }
                val count = expected.indices.count { expected[it] != actual[it] }
                fail("$label: $count differing pixels; first (${at % width}, ${at / width}): " +
                    "expected ${expected[at].toUInt().toString(16)}, actual ${actual[at].toUInt().toString(16)}")
            }
            return actual
        }

        override fun close() {
            session.close()
            retained.close()
            renderer.close()
            referenceRenderer.close()
        }
    }

    @Test
    fun shortMovesKeepFinishedContentAndRepaintOnlyDamage() {
        Fixture().use { fixture ->
            fixture.finished.add(InkSceneStroke(Stroke(marker(0x806080c0.toInt()), inputs(20f to 70f, 110f to 70f))))
            fixture.frame("initial finished background")
            val brush = marker(0x80000000.toInt())
            fixture.send(InkInputEvent.Begin(sample(12f, 20f)), brush)
            fixture.frame("first wet dot")
            repeat(12) { index ->
                fixture.send(InkInputEvent.Move(sample(18f + index * 5f, 20f, 1001L + index)), brush)
                fixture.session.advance(1001L + index)
                fixture.frame("short move $index")
                assertTrue(fixture.retained.lastRedrawnPixelCount in 1L until 128L * 128L)
            }
            assertEquals(1L, fixture.retained.backgroundBuildCount)
            assertEquals(1, fixture.contentCalls, "wet frames preserve the finished raster")
            assertTrue(fixture.retained.dirtyRedrawCount >= 12L)
            val unchanged = fixture.frame("unchanged exposure")
            assertEquals(0L, fixture.retained.lastRedrawnPixelCount, "exposure only composites retained pixels")
            assertTrue(unchanged.any { it != 0xffffffff.toInt() })
        }
    }

    @Test
    fun replacementShrinkingAndExpiryRemoveEveryOldPredictionPixel() {
        Fixture().use { fixture ->
            val brush = marker()
            fixture.send(InkInputEvent.Begin(sample(15f, 40f)), brush)
            fixture.send(InkInputEvent.Move(sample(45f, 40f, 1001L)), brush)
            fixture.send(InkInputEvent.Predict(listOf(sample(110f, 40f, 1012L))), brush)
            fixture.session.advance(1001L)
            val extended = fixture.frame("extended horizontal prediction")
            fixture.send(InkInputEvent.Predict(listOf(sample(45f, 110f, 1012L))), brush)
            fixture.session.advance(1001L)
            fixture.frame("prediction turns downward")
            fixture.send(InkInputEvent.Predict(listOf(sample(62f, 40f, 1012L))), brush)
            fixture.session.advance(1001L)
            fixture.frame("prediction shrinks")
            fixture.session.advance(1012L)
            assertEquals(0, fixture.session.liveStrokes.single().stroke.getPredictedInputCount())
            val expired = fixture.frame("prediction expires without new input")
            assertFalse(extended.contentEquals(expired), "the control visibly changes when prediction is removed")
            assertEquals(0xffffffff.toInt(), expired[40 * 128 + 100])
            assertEquals(1L, fixture.retained.backgroundBuildCount)
        }
    }

    @Test
    fun overlappingTranslucentPointersCancelAndFinishWithoutAccumulatingOldInk() {
        Fixture().use { fixture ->
            val brush = marker(0x80000000.toInt())
            fixture.send(InkInputEvent.Begin(sample(20f, 60f), 1L), brush)
            fixture.send(InkInputEvent.Move(sample(100f, 60f, 1010L), 1L), brush)
            fixture.session.advance(1010L)
            val horizontal = fixture.frame("first translucent pointer")
            fixture.send(InkInputEvent.Begin(sample(60f, 20f), 2L), brush)
            fixture.send(InkInputEvent.Move(sample(60f, 100f, 1010L), 2L), brush)
            fixture.session.advance(1010L)
            val crossing = fixture.frame("overlapping translucent pointers")
            assertTrue((crossing[60 * 128 + 60] and 255) < (horizontal[60 * 128 + 60] and 255))
            fixture.send(InkInputEvent.CancelPointer(1L), brush)
            val canceled = fixture.frame("one canceled pointer restores the other")
            assertEquals(horizontal[60 * 128 + 60], canceled[60 * 128 + 60])
            assertEquals(0xffffffff.toInt(), canceled[60 * 128 + 30])
            assertEquals(1L, fixture.retained.backgroundBuildCount)
            fixture.send(InkInputEvent.Finish(sample(60f, 100f, 1020L), 2L), brush)
            val finished = fixture.frame("wet to finished handoff")
            assertEquals(canceled[60 * 128 + 60], finished[60 * 128 + 60], "handoff applies alpha once")
            assertEquals(2L, fixture.retained.backgroundBuildCount)
            assertTrue(fixture.session.liveStrokes.isEmpty())
        }
    }

    @Test
    fun cancelAndRestartBeforeTheNextPaintClearsTheRecycledEngineOldBounds() {
        Fixture().use { fixture ->
            val brush = marker()
            fixture.send(InkInputEvent.Begin(sample(20f, 20f), 7L), brush)
            fixture.send(InkInputEvent.Move(sample(100f, 20f, 1010L), 7L), brush)
            fixture.session.advance(1010L)
            fixture.frame("old engine geometry")
            val reused = fixture.session.liveStrokes.single().stroke
            fixture.send(InkInputEvent.CancelPointer(7L), brush)
            fixture.send(InkInputEvent.Begin(sample(20f, 100f, 2000L), 7L), brush)
            fixture.send(InkInputEvent.Move(sample(100f, 100f, 2010L), 7L), brush)
            fixture.session.advance(2010L)
            assertSame(reused, fixture.session.liveStrokes.single().stroke)
            val restarted = fixture.frame("retired and restarted without an intermediate paint")
            assertEquals(0xffffffff.toInt(), restarted[20 * 128 + 60])
            fixture.send(InkInputEvent.Finish(pointerId = 7L), brush)
            fixture.send(InkInputEvent.Begin(sample(60f, 60f, 3000L), 7L), brush)
            assertSame(reused, fixture.session.liveStrokes.single().stroke)
            fixture.frame("finish and restart before finished background refresh")
        }
    }

    @Test
    fun realPartialEraseUndoAndRedoInvalidateFinishedPixelsWhileWetInkRemains() {
        Fixture().use { fixture ->
            val brush = marker()
            val original = Stroke(brush, inputs(10f to 60f, 110f to 60f))
            fixture.finished.add(InkSceneStroke(original))
            fixture.frame("before partial erase")
            fixture.send(InkInputEvent.Begin(sample(10f, 95f)), brush)
            fixture.send(InkInputEvent.Move(sample(110f, 95f, 1010L)), brush)
            fixture.session.advance(1010L)
            fixture.frame("wet stroke before partial erase")
            val mask = ViveBrushes.eraseMask(inputs(60f to 20f, 60f to 100f), 20f)
            val pieces = listOf(PageStroke("cut", original)).subtract(mask, listOf("cut"))
            fixture.finished.clear()
            fixture.finished.addAll(pieces.map { InkSceneStroke(it.stroke) })
            fixture.retained.invalidateContent()
            val erased = fixture.frame("finished geometry after partial erase")
            assertEquals(0xffffffff.toInt(), erased[60 * 128 + 60])
            assertTrue((erased[95 * 128 + 60] and 255) < 100, "the active stroke survives the background change")
            fixture.finished.clear()
            fixture.finished.add(InkSceneStroke(original))
            fixture.retained.invalidateContent()
            fixture.frame("partial erase undo")
            fixture.finished.clear()
            fixture.finished.addAll(pieces.map { InkSceneStroke(it.stroke) })
            fixture.retained.invalidateContent()
            fixture.frame("partial erase redo")
            assertEquals(4L, fixture.retained.backgroundBuildCount)
        }
    }

    @Test
    fun transformsDensityAndResizePreserveAntialiasingAndViewportClips() {
        Fixture().use { fixture ->
            val brush = marker(0x805040e0.toInt(), 14f)
            fixture.send(InkInputEvent.Begin(sample(-8f, 20f)), brush)
            fixture.send(InkInputEvent.Move(sample(110f, 90f, 1010L)), brush)
            fixture.session.advance(1010L)
            val transforms = listOf(
                AffineTransform.IDENTITY,
                ImmutableAffineTransform(-1f, .35f, 110f, .1f, .8f, -7f),
                ImmutableAffineTransform(.25f, 1.8f, 2f, -.45f, .6f, 35f),
                ImmutableAffineTransform(1.7f, -.3f, -25f, .2f, .65f, 5f),
            )
            for (density in listOf(1f, 1.25f, 2f, 1f)) {
                fixture.scale = density
                fixture.width = (133f * density).toInt()
                fixture.height = (127f * density).toInt()
                transforms.forEachIndexed { index, transform ->
                    fixture.overrideTransform = transform
                    fixture.frame("density $density / transform $index")
                }
            }
            fixture.overrideTransform = AffineTransform.IDENTITY
            fixture.width = 96
            fixture.height = 80
            fixture.frame("smaller viewport")
            fixture.width = 160
            fixture.height = 144
            fixture.frame("larger viewport")
            assertTrue(fixture.retained.retainedPixelBytes > 0L)
        }
    }

    @Test
    fun incrementalMotionUnderShearAndAnisotropyKeepsTransformedAntialiasingExact() {
        val transforms = listOf(
            ImmutableAffineTransform(.3f, 1.3f, 5f, -.15f, .7f, 25f),
            ImmutableAffineTransform(1.8f, -.5f, -15f, .2f, .2f, 20f),
        )
        for (density in listOf(1.25f, 2f)) for ((transformIndex, transform) in transforms.withIndex()) {
            Fixture().use { fixture ->
                fixture.scale = density
                fixture.width = (133f * density).toInt()
                fixture.height = (127f * density).toInt()
                fixture.overrideTransform = transform
                val brush = marker(0x806090d0.toInt(), 14f)
                fixture.send(InkInputEvent.Begin(sample(20f, 20f)), brush)
                fixture.frame("initial skewed stroke at $density / $transformIndex")
                repeat(12) { index ->
                    fixture.send(InkInputEvent.Move(sample(24f + index * 4f, 24f + index * 2f, 1001L + index)), brush)
                    fixture.session.advance(1001L + index)
                    // If another observer consumed the region, the changed version still requires
                    // conservative old/new bounds rather than leaving stale antialiased pixels.
                    if (index == 7) fixture.session.liveStrokes.single().stroke.resetUpdatedRegion()
                    fixture.frame("incremental skewed move $index at $density / $transformIndex")
                }
                assertEquals(1L, fixture.retained.backgroundBuildCount)
                assertTrue(fixture.retained.dirtyRedrawCount >= 12L)
            }
        }
    }

    @Test
    fun lastInputTextureOriginChangesTheExistingPrefixOnAShortMove() {
        Surface.makeRaster(ImageInfo.makeS32(2, 1, ColorAlphaType.PREMUL)).use { texture ->
            Paint().use { paint ->
                paint.color = 0xffff0000.toInt()
                texture.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, 0f, 1f, 1f), paint)
                paint.color = 0xff00ff00.toInt()
                texture.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(1f, 0f, 1f, 1f), paint)
            }
            texture.makeImageSnapshot().use { image ->
                val layer = BrushPaint.TilingTexture("last-input", 20f, 20f,
                    origin = BrushPaint.TilingTexture.Origin.LAST_STROKE_INPUT)
                val brush = Brush.createWithColorIntArgb(BrushFamily(paint = BrushPaint(listOf(layer))),
                    0xffffffff.toInt(), 20f, .25f)
                Fixture(renderer = InkMeshRenderer(InkTextureStore { image }),
                    referenceRenderer = InkMeshRenderer(InkTextureStore { image })).use { fixture ->
                    fixture.send(InkInputEvent.Begin(sample(20f, 60f)), brush)
                    fixture.send(InkInputEvent.Move(sample(80f, 60f, 1010L)), brush)
                    fixture.session.advance(1010L)
                    val before = fixture.frame("texture anchored to the old last input")
                    fixture.send(InkInputEvent.Move(sample(85f, 60f, 1011L)), brush)
                    fixture.session.advance(1011L)
                    val after = fixture.frame("short move shifts the complete textured prefix")
                    assertTrue((24..40).any { x -> before[60 * 128 + x] != after[60 * 128 + x] },
                        "the control changes pixels far before the updated geometric tail")
                    assertEquals(1L, fixture.retained.backgroundBuildCount, "global wet paint changes keep finished pixels")
                    assertTrue(fixture.retained.dirtyRedrawCount > 0L)
                }
            }
        }
    }

    @Test
    fun translucentClearAndFinishedContentStayExactAndRendererChangesRefreshThem() {
        Fixture().use { fixture ->
            fixture.clearColor = 0x40204080
            fixture.overlay = { canvas, _, _ ->
                Paint().use { paint ->
                    paint.color = 0x90a040e0.toInt()
                    paint.isAntiAlias = true
                    canvas.skiaCanvas.drawRect(org.jetbrains.skia.Rect.makeLTRB(11.5f, 7.25f, 94.5f, 80.75f), paint)
                }
            }
            val brush = marker(0x806040c0.toInt())
            fixture.send(InkInputEvent.Begin(sample(10f, 60f)), brush)
            fixture.frame("translucent clear and background")
            fixture.send(InkInputEvent.Move(sample(110f, 60f, 1010L)), brush)
            fixture.session.advance(1010L)
            fixture.frame("translucent foreground over retained premultiplied background")
            fixture.clearColor = 0
            fixture.frame("clear color invalidates both rasters")
            assertEquals(2L, fixture.retained.backgroundBuildCount)
            fixture.renderer.clearCache()
            fixture.referenceRenderer.clearCache()
            fixture.frame("renderer version invalidates finished and live rendering")
            assertEquals(3L, fixture.retained.backgroundBuildCount)
        }
    }

    @Test
    fun timedOpacityChangesRestoreBackgroundWithoutNewPointerPositions() {
        val family = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(
            TargetNode.Target.OPACITY_MULTIPLIER, 1f, 0f,
            SourceNode(SourceNode.Source.TIME_SINCE_INPUT_IN_SECONDS, 0f, 1f),
        )))))
        val brush = Brush.createWithColorIntArgb(family, 0xffb03070.toInt(), 20f, .25f)
        Fixture().use { fixture ->
            fixture.send(InkInputEvent.Begin(sample(60f, 60f)), brush)
            val initial = fixture.frame("fresh timed-opacity stroke")
            fixture.session.advance(1500L)
            val faded = fixture.frame("same positions with faded opacity")
            assertFalse(initial.contentEquals(faded), "the timed brush control changes visible pixels")
            fixture.session.advance(2000L)
            fixture.frame("timed stroke becomes invisible")
            assertEquals(1L, fixture.retained.backgroundBuildCount)
            assertTrue(fixture.retained.dirtyRedrawCount >= 2L)
        }
    }

    @Test
    fun animatedTextureVersionRefreshesFinishedContentAndWetInk() {
        Surface.makeRaster(ImageInfo.makeS32(2, 1, ColorAlphaType.PREMUL)).use { atlas ->
            Paint().use { paint ->
                paint.color = 0xffff0000.toInt()
                atlas.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(0f, 0f, 1f, 1f), paint)
                paint.color = 0xff00ff00.toInt()
                atlas.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(1f, 0f, 1f, 1f), paint)
            }
            atlas.makeImageSnapshot().use { image ->
                val layer = BrushPaint.StampingTexture("atlas", animationFrames = 2, animationRows = 1,
                    animationColumns = 2, animationDurationMillis = 1000L)
                val family = BrushFamily(BrushTip(particleGapDistanceScale = 2f), BrushPaint(listOf(layer)))
                val brush = Brush.createWithColorIntArgb(family, 0xffffffff.toInt(), 20f, .25f)
                Fixture(renderer = InkMeshRenderer(InkTextureStore { image }),
                    referenceRenderer = InkMeshRenderer(InkTextureStore { image })).use { fixture ->
                    fixture.finished.add(InkSceneStroke(Stroke(brush, inputs(20f to 30f, 100f to 30f))))
                    fixture.send(InkInputEvent.Begin(sample(20f, 90f)), brush)
                    fixture.send(InkInputEvent.Move(sample(100f, 90f, 1010L)), brush)
                    fixture.session.advance(1010L)
                    val red = fixture.frame("first atlas frame")
                    fixture.renderer.animationTimeMillis = 750L
                    fixture.referenceRenderer.animationTimeMillis = 750L
                    val green = fixture.frame("renderer atlas clock changed")
                    assertFalse(red.contentEquals(green), "both finished and wet atlas controls visibly animate")
                    assertEquals(2L, fixture.retained.backgroundBuildCount)
                }
                assertFalse(image.isClosed, "the supplied texture remains caller-owned")
            }
        }
    }

    @Test
    fun budgetFallbackAndCustomRenderersKeepTheFullRedrawResultWithoutRetainedBytes() {
        for (budget in listOf(0L, 1024L)) {
            Fixture(budget = budget).use { fixture ->
                val brush = marker(0x806060c0.toInt())
                fixture.send(InkInputEvent.Begin(sample(20f, 60f)), brush)
                fixture.frame("budget $budget / first frame")
                fixture.send(InkInputEvent.Move(sample(100f, 60f, 1010L)), brush)
                fixture.session.advance(1010L)
                fixture.frame("budget $budget / full redraw")
                assertEquals(0L, fixture.retained.retainedPixelBytes)
                assertEquals(2, fixture.contentCalls)
            }
        }
        Fixture(routeRenderer = { delegate -> object : InkRenderer by delegate {} }).use { fixture ->
            val brush = marker()
            fixture.send(InkInputEvent.Begin(sample(20f, 60f)), brush)
            fixture.frame("custom renderer fallback")
            fixture.frame("custom renderer redraw on exposure")
            assertEquals(0L, fixture.retained.retainedPixelBytes)
            assertEquals(2, fixture.contentCalls, "an unknown renderer cannot promise conservative mesh damage")
        }
    }

    @Test
    fun failingBackgroundPaintCanRetryAndCloseReleasesEveryRetainedRaster() {
        val fixture = Fixture()
        try {
            val brush = marker()
            fixture.send(InkInputEvent.Begin(sample(20f, 60f)), brush)
            fixture.frame("before callback failure")
            fixture.send(InkInputEvent.Move(sample(100f, 60f, 1010L)), brush)
            fixture.session.advance(1010L)
            fixture.overlay = { _, _, _ -> throw IllegalStateException("planned background failure") }
            fixture.retained.invalidateContent()
            assertFailsWith<IllegalStateException> { fixture.drawRetained() }
            fixture.overlay = { _, _, _ -> }
            fixture.frame("successful retry retains all pending engine damage")
            assertTrue(fixture.retained.retainedPixelBytes > 0L)
        } finally {
            fixture.close()
        }
        fixture.retained.close()
        assertEquals(0L, fixture.retained.retainedPixelBytes)
        assertFailsWith<IllegalStateException> { fixture.drawRetained() }
    }

    @Test
    fun scopedSnapshotsReleaseBeforeTheNextWriteWithoutReplacingRasterStorage() {
        Fixture().use { fixture ->
            val brush = marker()
            fixture.send(InkInputEvent.Begin(sample(10f, 60f)), brush)
            fixture.frame("initial owned raster storage")
            fun raster(name: String): Surface = InkRetainedAuthoringRaster::class.java
                .getDeclaredField(name).also { it.isAccessible = true }.get(fixture.retained) as Surface
            fun address(surface: Surface): Long = Pixmap().use { pixmap ->
                assertTrue(surface.peekPixels(pixmap))
                pixmap.addr
            }
            val background = raster("background")
            val frame = raster("frame")
            val backgroundAddress = address(background)
            val frameAddress = address(frame)
            assertTrue(backgroundAddress != 0L && frameAddress != 0L)
            repeat(16) { index ->
                fixture.send(InkInputEvent.Move(sample(14f + index * 5f, 60f, 1001L + index)), brush)
                fixture.session.advance(1001L + index)
                if (index % 3 == 0) fixture.retained.invalidateContent()
                fixture.frame("write after released snapshots $index")
                assertSame(background, raster("background"))
                assertSame(frame, raster("frame"))
                assertEquals(backgroundAddress, address(background), "finished snapshot does not trigger a raster copy")
                assertEquals(frameAddress, address(frame), "presentation snapshot does not trigger a raster copy")
            }
        }
    }

    @Test
    fun pathRendererRetainsExactTransformedFractionalScalePixelsThroughPredictionAndCancel() {
        for (density in listOf(1.25f, 2f)) for (meshHighlighter in listOf(false, true)) {
            val paths = InkPathRenderer()
            val referencePaths = InkPathRenderer()
            try {
                Fixture(routeRenderer = { if (meshHighlighter) it else paths },
                    routeReferenceRenderer = { if (meshHighlighter) it else referencePaths }).use { fixture ->
                    fixture.scale = density
                    fixture.width = (133f * density).toInt()
                    fixture.height = (127f * density).toInt()
                    val transform = ImmutableAffineTransform(.75f, .2f, 10f, -.15f, .85f, 12f)
                    fixture.overrideTransform = transform
                    fixture.finished.add(InkSceneStroke(Stroke(marker(0x806050d0.toInt()), inputs(10f to 105f, 120f to 105f))))
                    val mode = if (meshHighlighter) "mesh highlighter" else "path renderer"
                    val brush = if (meshHighlighter) ViveBrushes.brush(ViveBrushes.HIGHLIGHTER, 0, 0x80000000.toInt(), 14f)
                        else marker(0x80000000.toInt(), 14f)
                    fixture.send(InkInputEvent.Begin(sample(20f, 60f), 1L), brush, transform)
                    fixture.frame("$mode first dot at $density")
                    repeat(6) { index ->
                        fixture.send(InkInputEvent.Move(sample(32f + index * 8f, 60f, 1001L + index), 1L), brush)
                        fixture.session.advance(1001L + index)
                        fixture.frame("$mode incremental motion $index at $density")
                    }
                    fixture.send(InkInputEvent.Predict(listOf(sample(110f, 60f, 1020L)), 1L), brush)
                    fixture.session.advance(1006L)
                    val predicted = fixture.frame("$mode predicted extension at $density")
                    fixture.send(InkInputEvent.Begin(sample(60f, 20f), 2L), brush, transform)
                    fixture.send(InkInputEvent.Move(sample(60f, 100f, 1006L), 2L), brush)
                    fixture.session.advance(1006L)
                    val overlap = fixture.frame("$mode translucent crossing at $density")
                    assertFalse(predicted.contentEquals(overlap), "the second path adds visible overlapping ink")
                    fixture.send(InkInputEvent.Predict(listOf(sample(75f, 50f, 1020L)), 1L), brush)
                    fixture.session.advance(1006L)
                    fixture.frame("$mode prediction turns and shrinks at $density")
                    fixture.session.advance(1020L)
                    assertEquals(0, fixture.session.liveStrokes.first().stroke.getPredictedInputCount())
                    fixture.frame("$mode prediction expires at $density")
                    fixture.send(InkInputEvent.CancelPointer(2L), brush)
                    fixture.frame("$mode cancel restores the underlying translucent path at $density")
                    assertEquals(1L, fixture.retained.backgroundBuildCount)
                    assertEquals(1, fixture.contentCalls, "the eligible path renderer keeps finished content")
                    assertTrue(fixture.retained.retainedPixelBytes > 0L, "the path renderer uses retention")
                    assertTrue(fixture.retained.dirtyRedrawCount >= 6L)
                    fixture.send(InkInputEvent.Finish(sample(100f, 60f, 1030L), 1L), brush)
                    fixture.frame("$mode canonical finished handoff at $density")
                    assertEquals(2L, fixture.retained.backgroundBuildCount)
                }
            } finally {
                paths.clearCache()
                referencePaths.clearCache()
            }
        }
    }

    companion object {
        // Native Wayland's pinned software painter supplies tagged sRGB N32 premultiplied pixels.
        private fun rasterPixels(width: Int, height: Int, draw: (org.jetbrains.skia.Canvas) -> Unit): IntArray =
            Surface.makeRaster(ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL)).use { surface ->
                surface.canvas.clear(0)
                draw(surface.canvas)
                surface.makeImageSnapshot().use { image ->
                    Bitmap.makeFromImage(image).use { bitmap ->
                        IntArray(width * height) { index -> bitmap.getColor(index % width, index / width) }
                    }
                }
            }
    }
}
