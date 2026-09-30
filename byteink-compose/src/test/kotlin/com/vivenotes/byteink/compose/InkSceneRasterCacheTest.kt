package com.vivenotes.byteink.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.vive.ViveBrushes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InkSceneRasterCacheTest {
    @Test
    fun unchangedFramesReuseTheRasterWhenThePageExceedsThePathCache() {
        val scene = InkScene(List(12) { index -> InkSceneStroke(stroke(
            color = 0xff000000.toInt(), size = 3f, points = arrayOf(10f to index * 8f + 10f, 110f to index * 8f + 10f),
        )) })
        val renderer = InkPathRenderer(cacheCapacity = 2)
        InkSceneRasterCache().use { cache ->
            raster { canvas -> assertEquals(12, cache.draw(canvas, scene, renderer, 128, 128)) }
            val paths = renderer.pathBuildCount
            assertEquals(12L, paths)
            assertEquals(2, renderer.cachedShapeCount)
            assertEquals(1L, cache.rasterBuildCount)
            assertEquals(128L * 128L * 4L, cache.retainedPixelBytes)
            repeat(10) {
                raster { canvas -> assertEquals(12, cache.draw(canvas, scene, renderer, 128, 128)) }
            }
            assertEquals(paths, renderer.pathBuildCount, "held frames never visit evicted paths")
            assertEquals(1L, cache.rasterBuildCount, "held frames reuse one native-resolution image")
        }
    }

    @Test
    fun sceneIdentityViewValuesAndViewportSizeInvalidateTheRaster() {
        val original = stroke(points = arrayOf(10f to 20f, 60f to 20f))
        val scene = InkScene(listOf(InkSceneStroke(original)))
        val renderer = InkPathRenderer()
        val transform = MutableAffineTransform()
        InkSceneRasterCache().use { cache ->
            fun draw(snapshot: InkScene = scene, width: Int = 128, height: Int = 128, view: AffineTransform = transform) =
                raster { canvas -> cache.draw(canvas, snapshot, renderer, width, height, view) }
            val first = draw()
            assertEquals(first, draw(view = ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f)))
            assertEquals(1L, cache.rasterBuildCount, "equal immutable view values reuse the raster")
            transform.m20 = 20f
            val translated = draw()
            assertTrue(first != translated, "mutating the same transform changes the visible ink")
            assertEquals(2L, cache.rasterBuildCount)
            assertEquals(translated, draw())
            assertEquals(2L, cache.rasterBuildCount)
            transform.setValues(1.5f, 0f, 0f, 0f, 1.5f, 0f)
            draw()
            assertEquals(3L, cache.rasterBuildCount)
            draw(width = 96)
            assertEquals(4L, cache.rasterBuildCount)
            assertEquals(96L * 128L * 4L, cache.retainedPixelBytes)
            draw(height = 96)
            assertEquals(5L, cache.rasterBuildCount)
            draw(snapshot = InkScene(scene.strokes))
            assertEquals(6L, cache.rasterBuildCount, "different scene identity invalidates even equivalent contents")
            val recolored = InkScene(listOf(InkSceneStroke(original, colorArgb = 0xffff0000.toInt())))
            val red = draw(snapshot = recolored)
            assertEquals(7L, cache.rasterBuildCount)
            assertTrue(red.any { it and 0x00ffffff == 0x00ff0000 }, "a changed scene's colour reaches the raster")
        }
    }

    @Test
    fun transparentCachedScenesPreserveOrderAndAlphaOverDifferentBackgrounds() {
        val scene = InkScene(listOf(
            InkSceneStroke(stroke(size = 8f, points = arrayOf(12f to 60f, 116f to 60f))),
            InkSceneStroke(stroke(ViveBrushes.HIGHLIGHTER, 0x80ff0000.toInt(), 16f,
                arrayOf(20f to 20f, 100f to 100f, 20f to 100f, 100f to 20f))),
            InkSceneStroke(stroke(color = 0x800000ff.toInt(), size = 12f, points = arrayOf(60f to 12f, 60f to 116f))),
            InkSceneStroke(stroke(color = 0xff20aa70.toInt(), size = 4f, points = arrayOf(12f to 16f, 42f to 16f))),
        ))
        val renderer = InkPathRenderer()
        val measured = mutableListOf<String>()
        InkSceneRasterCache().use { cache ->
            val views = listOf(
                AffineTransform.IDENTITY,
                ImmutableAffineTransform(1.1f, 0.2f, -8f, 0.1f, 0.9f, 4f),
                ImmutableAffineTransform(0f, -1f, 128f, 1f, 0f, 0f),
            )
            for ((viewIndex, view) in views.withIndex()) for (background in listOf(0, 0xffffffff.toInt(), 0xff142638.toInt(), 0x80304050.toInt())) {
                val direct = raster(background) { canvas ->
                    scene.draw(canvas, renderer, view, Rect(0f, 0f, 128f, 128f))
                }
                val cached = raster(background) { canvas -> cache.draw(canvas, scene, renderer, 128, 128, view) }
                var maxDelta = 0
                var differing = 0
                direct.indices.forEach { index ->
                    if (direct[index] != cached[index]) differing++
                    repeat(4) { channel ->
                        maxDelta = maxOf(maxDelta, abs(((direct[index] ushr (channel * 8)) and 255) -
                            ((cached[index] ushr (channel * 8)) and 255)))
                    }
                }
                measured += "{\"view\":$viewIndex,\"backgroundArgb\":$background,\"differingPixels\":$differing,\"maxChannelDelta\":$maxDelta}"
                assertTrue(maxDelta <= 2, "Transparent raster compositing exceeds the existing two-channel renderer bound: ${measured.last()}")
                if (background == 0) assertEquals(direct, cached, "transparent destinations preserve the layer exactly")
            }
            assertEquals(3L, cache.rasterBuildCount, "backgrounds are composited at draw time and do not invalidate the transparent image")
        }
        val report = """
            {
              "scope": "Skia N32 premultiplied viewport layer versus direct paths, four opaque/translucent strokes including DISCARD self-crossing, three affine views, four destination backgrounds",
              "maxAcceptedChannelDelta": 2,
              "toleranceSource": "Existing uniform path renderer channel bound; unchanged",
              "cases": [${measured.joinToString(",")}]
            }
        """.trimIndent() + "\n"
        System.getProperty("byteink.test.interactionReport")?.let { destination ->
            val path = Path.of(destination).resolveSibling("raster-cache-fidelity.json")
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, report)
        }
        println(report)
    }

    @Test
    fun clearCloseAndEmptyViewportsReleaseRetainedPixels() {
        val scene = InkScene(listOf(InkSceneStroke(stroke(points = arrayOf(10f to 20f, 60f to 20f)))))
        val renderer = InkPathRenderer()
        val cache = InkSceneRasterCache()
        fun draw(width: Int = 128, height: Int = 128) = raster { canvas -> cache.draw(canvas, scene, renderer, width, height) }
        try {
            draw()
            assertTrue(cache.retainedPixelBytes > 0L)
            cache.clearCache()
            assertEquals(0L, cache.retainedPixelBytes)
            assertEquals(1L, cache.rasterBuildCount)
            draw()
            assertEquals(2L, cache.rasterBuildCount)
            assertTrue(draw(width = 0).all { it == 0 })
            assertEquals(0L, cache.retainedPixelBytes)
            assertEquals(2L, cache.rasterBuildCount)
            draw()
            cache.close()
            cache.close()
            cache.clearCache()
            assertEquals(0L, cache.retainedPixelBytes)
            assertFailsWith<IllegalStateException> { draw() }
            assertFailsWith<IllegalStateException> { draw(width = 0) }
        } finally { cache.close() }
    }

    @Test
    fun invalidViewsFailBeforeReplacingTheUsableRaster() {
        val scene = InkScene(listOf(InkSceneStroke(stroke(points = arrayOf(10f to 20f, 60f to 20f)))))
        val renderer = InkPathRenderer()
        InkSceneRasterCache().use { cache ->
            raster { canvas ->
                cache.draw(canvas, scene, renderer, 128, 128)
                assertFailsWith<IllegalArgumentException> { cache.draw(canvas, scene, renderer, -1, 128) }
                assertFailsWith<IllegalArgumentException> {
                    cache.draw(canvas, scene, renderer, 128, 128, ImmutableAffineTransform(0f, 0f, 0f, 0f, 0f, 0f))
                }
                assertFailsWith<IllegalArgumentException> {
                    cache.draw(canvas, scene, renderer, 128, 128, ImmutableAffineTransform(Float.NaN, 0f, 0f, 0f, 1f, 0f))
                }
            }
            assertEquals(1L, cache.rasterBuildCount)
            assertEquals(128L * 128L * 4L, cache.retainedPixelBytes)
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rememberedCacheClosesWhenItsCompositionIsRemoved() = runDesktopComposeUiTest(width = 128, height = 128) {
        val present = mutableStateOf(true)
        var retained: InkSceneRasterCache? = null
        val scene = InkScene(listOf(InkSceneStroke(stroke(points = arrayOf(10f to 20f, 60f to 20f)))))
        val renderer = InkPathRenderer()
        setContent {
            if (present.value) {
                val cache = rememberInkSceneRasterCache()
                retained = cache
                Canvas(Modifier.size(128.dp)) { drawCachedInkScene(cache, scene, renderer) }
            }
        }
        waitForIdle()
        runOnIdle {
            assertTrue(assertNotNull(retained).retainedPixelBytes > 0L)
            present.value = false
        }
        waitForIdle()
        runOnIdle {
            val cache = assertNotNull(retained)
            assertEquals(0L, cache.retainedPixelBytes)
            raster { canvas -> assertFailsWith<IllegalStateException> { cache.draw(canvas, scene, renderer, 128, 128) } }
        }
    }

    private fun stroke(
        family: String = ViveBrushes.MARKER,
        color: Int = 0xff000000.toInt(),
        size: Float = 6f,
        points: Array<Pair<Float, Float>>,
    ): Stroke = Stroke(ViveBrushes.brush(family, 0, color, size), MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.MOUSE, x, y, index * 100L) }
    }.toImmutable())

    private fun raster(background: Int = 0, draw: (androidx.compose.ui.graphics.Canvas) -> Unit): List<Int> =
        Surface.makeRasterN32Premul(128, 128).use { surface ->
            surface.canvas.clear(background)
            draw(surface.canvas.asComposeCanvas())
            surface.makeImageSnapshot().use { image ->
                Bitmap.makeFromImage(image).use { bitmap -> List(128 * 128) { i -> bitmap.getColor(i % 128, i / 128) } }
            }
        }
}
