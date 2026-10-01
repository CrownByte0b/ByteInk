package com.vivenotes.byteink.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
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
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class InkSceneRasterCacheTest {
    @Test
    fun legacyDrawAndExtensionJvmSignaturesRemainCallable() {
        val types = arrayOf(GraphicsCanvas::class.java, InkScene::class.java, InkPathRenderer::class.java,
            Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!, AffineTransform::class.java)
        val legacy = InkSceneRasterCache::class.java.getMethod("draw", *types)
        val legacyDefault = InkSceneRasterCache::class.java.getMethod("draw\$default",
            InkSceneRasterCache::class.java, *types, Int::class.javaPrimitiveType!!, Any::class.java)
        val scene = InkScene(listOf(InkSceneStroke(stroke(points = arrayOf(10f to 20f, 60f to 20f)))))
        val renderer = InkPathRenderer()
        InkSceneRasterCache().use { cache ->
            val direct = raster { canvas ->
                assertEquals(1, legacy.invoke(cache, canvas, scene, renderer, 128, 128, AffineTransform.IDENTITY))
            }
            assertEquals(direct, raster { canvas ->
                assertEquals(1, legacyDefault.invoke(null, cache, canvas, scene, renderer, 128, 128, null, 32, null))
            })
            assertEquals(1L, cache.rasterBuildCount)
        }
        val helpers = Class.forName("com.vivenotes.byteink.compose.InkSceneRasterCacheKt")
        val extensionTypes = arrayOf(DrawScope::class.java, InkSceneRasterCache::class.java,
            InkScene::class.java, InkPathRenderer::class.java, AffineTransform::class.java)
        assertEquals(Int::class.javaPrimitiveType,
            helpers.getMethod("drawCachedInkScene", *extensionTypes).returnType)
        assertEquals(Int::class.javaPrimitiveType,
            helpers.getMethod("drawCachedInkScene\$default", *extensionTypes,
                Int::class.javaPrimitiveType!!, Any::class.java).returnType)
    }

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

    @Test
    fun fractionalViewportDensityAndAncestorZoomPreservePhysicalPixelsAndAlpha() {
        val scene = InkScene(listOf(
            InkSceneStroke(stroke(points = arrayOf(2f to 12f, 36f to 12f))),
            InkSceneStroke(stroke(ViveBrushes.HIGHLIGHTER, 0x80ffe000.toInt(), 8f,
                arrayOf(4f to 4f, 30f to 30f, 4f to 30f, 30f to 4f))),
            InkSceneStroke(stroke(color = 0x800000ff.toInt(), size = 4f,
                points = arrayOf(16f to 2f, 16f to 38f))),
            InkSceneStroke(stroke(points = arrayOf(2f to 34f, 18f to 34f)), colorArgb = 0xffffffff.toInt()),
        ))
        val viewport = Rect(64.25f, 80.5f, 127.5f, 128f)
        val renderer = InkPathRenderer()
        val measured = mutableListOf<String>()
        InkSceneRasterCache().use { cache ->
            for (density in listOf(1f, 1.75f, 2.5f)) for (zoom in listOf(0.75f, 1f, 1.375f, 2f)) {
                val transforms = listOf(
                    ImmutableAffineTransform(density, 0f, viewport.left + 2.25f,
                        0f, density, viewport.top + 3.5f),
                    ImmutableAffineTransform(density, density * 0.2f, viewport.left + 2.25f,
                        density * 0.1f, density * 0.9f, viewport.top + 3.5f),
                    ImmutableAffineTransform(0f, -density, viewport.right - 2.25f,
                        density, 0f, viewport.top + 3.5f),
                )
                for ((viewIndex, view) in transforms.withIndex()) for (background in listOf(0, 0xffffffff.toInt(), 0xff142638.toInt())) {
                    val direct = physicalRaster(viewport, zoom, background) { canvas ->
                        scene.draw(canvas, renderer, view, viewport)
                    }
                    val cached = physicalRaster(viewport, zoom, background) { canvas ->
                        cache.draw(canvas, scene, renderer, viewport, view, zoom)
                    }
                    val delta = maxChannelDelta(direct, cached)
                    val diffs = direct.indices.filter { direct[it] != cached[it] }
                    assertTrue(delta <= 2, "density=$density zoom=$zoom view=$view background=$background delta=$delta " +
                        "diffs=${diffs.take(12).map { i -> "$i:${direct[i].toUInt().toString(16)}/${cached[i].toUInt().toString(16)}" }}")
                    if (background == 0) assertEquals(direct, cached, "transparent physical destinations retain exact pixels")
                    measured += "{\"density\":$density,\"ancestorZoom\":$zoom,\"affineView\":$viewIndex," +
                        "\"backgroundArgb\":$background,\"differingPixels\":${diffs.size},\"maxChannelDelta\":$delta}"
                    assertTrue(cached.any { it != background }, "the nonzero viewport must contain ink")
                    val paths = renderer.pathBuildCount
                    val rasters = cache.rasterBuildCount
                    assertEquals(cached, physicalRaster(viewport, zoom, background) { canvas ->
                        cache.draw(canvas, scene, renderer, viewport, view, zoom)
                    })
                    assertEquals(paths, renderer.pathBuildCount)
                    assertEquals(rasters, cache.rasterBuildCount)
                    assertEquals(ceil(viewport.width * zoom.toDouble()).toLong() *
                        ceil(viewport.height * zoom.toDouble()).toLong() * 4L, cache.retainedPixelBytes)
                }
            }
        }
        System.getProperty("byteink.test.interactionReport")?.let { destination ->
            val path = Path.of(destination).resolveSibling("raster-cache-physical-fidelity.json")
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, """
                {
                  "scope": "Nonzero fractional local viewport, 3 densities, 4 ancestor zooms, identity/shear/rotation, transparent/light/dark destinations, overlapping translucent strokes and theme override",
                  "localViewport": [64.25, 80.5, 127.5, 128.0],
                  "physicalOrigin": [2.0, 2.0],
                  "transparentDestinationExact": true,
                  "maxAcceptedChannelDelta": 2,
                  "cases": [${measured.joinToString(",")}]
                }
            """.trimIndent() + "\n")
        }
    }

    @Test
    fun physicalRoundingPaddingDoesNotStretchInkOrLeakOutsideTheViewport() {
        val viewport = Rect(10.25f, 12.75f, 31.25f, 33.75f)
        val zoom = 1.25f // 26.25 physical pixels, rounded to a 27-pixel raster.
        val scene = InkScene(listOf(
            InkSceneStroke(stroke(size = 2f, points = arrayOf(8f to 20f, 34f to 20f))),
            InkSceneStroke(stroke(size = 1f, points = arrayOf(20f to 10f, 20f to 38f))),
        ))
        val renderer = InkPathRenderer()
        InkSceneRasterCache().use { cache ->
            val direct = physicalRaster(viewport, zoom) { canvas ->
                scene.draw(canvas, renderer, viewport = viewport)
            }
            val cached = physicalRaster(viewport, zoom) { canvas ->
                cache.draw(canvas, scene, renderer, viewport, rasterScale = zoom)
            }
            assertEquals(direct, cached, "ceil padding must retain physical pixel spacing")
            val width = ceil(viewport.width * zoom.toDouble()).toInt() + 4
            val height = ceil(viewport.height * zoom.toDouble()).toInt() + 4
            for (y in 0 until height) for (x in 0 until width) {
                if (x < 2 || y < 2 || x >= width - 2 || y >= height - 2) {
                    assertEquals(0, cached[y * width + x], "viewport clip must preserve outside pixels")
                }
            }
        }
    }

    @Test
    fun viewportOriginAndScaleInvalidateWithoutAllocatingTheDocumentExtent() {
        val scene = InkScene(listOf(
            InkSceneStroke(stroke(points = arrayOf(1_000_016f to 2_000_020f, 1_000_040f to 2_000_020f))),
            InkSceneStroke(stroke(points = arrayOf(10f to 20f, 30f to 20f))),
        ))
        val renderer = InkPathRenderer(cacheCapacity = 1)
        val viewport = Rect(1_000_000f, 2_000_000f, 1_000_064f, 2_000_048f)
        InkSceneRasterCache().use { cache ->
            fun draw(view: Rect = viewport, zoom: Float = 1f): List<Int> = physicalRaster(view, zoom) { canvas ->
                assertEquals(1, cache.draw(canvas, scene, renderer, view, rasterScale = zoom))
            }
            val first = draw()
            assertTrue(first.any { it != 0 })
            assertEquals(64L * 48L * 4L, cache.retainedPixelBytes)
            assertEquals(first, draw(view = viewport.copy()))
            assertEquals(1L, cache.rasterBuildCount)
            val shiftedX = viewport.translate(4f, 0f)
            assertTrue(first != draw(shiftedX))
            assertEquals(2L, cache.rasterBuildCount)
            val shiftedY = viewport.translate(4f, 8f)
            assertTrue(draw(shiftedX) != draw(shiftedY))
            assertEquals(3L, cache.rasterBuildCount)
            draw(shiftedY, 1.5f)
            assertEquals(4L, cache.rasterBuildCount)
            assertEquals(96L * 72L * 4L, cache.retainedPixelBytes)
            val paths = renderer.pathBuildCount
            repeat(10) { draw(shiftedY, 1.5f) }
            assertEquals(4L, cache.rasterBuildCount)
            assertEquals(paths, renderer.pathBuildCount)
        }
    }

    @Test
    fun copiedOccurrenceExclusionsInvalidateEvenForEqualLookingStrokeInstances() {
        val geometry = stroke(color = 0x800000ff.toInt(), size = 12f,
            points = arrayOf(12f to 60f, 116f to 60f))
        val scene = InkScene(listOf(
            InkSceneStroke(geometry),
            InkSceneStroke(stroke(ViveBrushes.HIGHLIGHTER, 0x80ff0000.toInt(), 12f,
                arrayOf(60f to 12f, 60f to 116f))),
            InkSceneStroke(geometry),
        ))
        assertEquals(scene.strokes[0], scene.strokes[2], "equal occurrences frame a different ordered layer")
        val viewport = Rect(0f, 0f, 128f, 128f)
        val renderer = InkPathRenderer()
        val exclusions = Collections.newSetFromMap(IdentityHashMap<InkSceneStroke, Boolean>())
        InkSceneRasterCache().use { cache ->
            fun draw(): List<Int> = raster { canvas ->
                assertEquals(3 - exclusions.size, cache.draw(canvas, scene, renderer, viewport,
                    excludedStrokes = exclusions))
            }
            val all = draw()
            exclusions += scene.strokes[0]
            val excludeFirst = draw()
            assertEquals(2L, cache.rasterBuildCount, "mutating the caller's set must invalidate its copied key")
            assertTrue(all != excludeFirst)
            exclusions.clear()
            exclusions += scene.strokes[2]
            val excludeLast = draw()
            assertEquals(3L, cache.rasterBuildCount, "equal-looking occurrence keys are distinct")
            assertTrue(excludeFirst != excludeLast, "draw order gives equal occurrences different removal pixels")
            assertEquals(raster { canvas ->
                scene.draw(canvas, renderer, viewport = viewport, excludedStrokes = exclusions)
            }, excludeLast)
            assertEquals(excludeLast, draw())
            assertEquals(3L, cache.rasterBuildCount)
            exclusions += scene.strokes[0]
            val excludeBoth = draw()
            assertEquals(4L, cache.rasterBuildCount)
            assertEquals(raster { canvas ->
                scene.draw(canvas, renderer, viewport = viewport, excludedStrokes = exclusions)
            }, excludeBoth)
            exclusions.clear()
            assertEquals(all, draw())
            assertEquals(5L, cache.rasterBuildCount)
        }
    }

    @Test
    fun invalidPhysicalViewportsFailBeforeReplacingTheUsableRaster() {
        val scene = InkScene(listOf(InkSceneStroke(stroke(points = arrayOf(10f to 20f, 60f to 20f)))))
        val renderer = InkPathRenderer()
        val viewport = Rect(0f, 0f, 128f, 128f)
        InkSceneRasterCache().use { cache ->
            val first = raster { canvas -> cache.draw(canvas, scene, renderer, viewport) }
            raster { canvas ->
                for (scale in listOf(0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE, Float.MAX_VALUE)) {
                    assertFailsWith<IllegalArgumentException>("scale=$scale") {
                        cache.draw(canvas, scene, renderer, viewport, rasterScale = scale)
                    }
                }
                for (invalid in listOf(
                    Rect(Float.NaN, 0f, 128f, 128f),
                    Rect(0f, 0f, Float.POSITIVE_INFINITY, 128f),
                    Rect(128f, 0f, 0f, 128f),
                    Rect(0f, 128f, 128f, 0f),
                    Rect(0f, 0f, Int.MAX_VALUE.toFloat(), 1f),
                    Rect(0f, 0f, 32_768f, 32_768f),
                    Rect(-Float.MAX_VALUE, 0f, Float.MAX_VALUE, 1f),
                )) {
                    assertFailsWith<IllegalArgumentException>("viewport=$invalid") {
                        cache.draw(canvas, scene, renderer, invalid)
                    }
                }
                assertFailsWith<IllegalArgumentException> {
                    cache.draw(canvas, scene, renderer, viewport,
                        ImmutableAffineTransform(1f, 0f, Float.MAX_VALUE, 0f, 1f, 0f), rasterScale = 2f)
                }
                assertFailsWith<IllegalArgumentException> {
                    cache.draw(canvas, scene, renderer, viewport,
                        ImmutableAffineTransform(1e-37f, 0f, 0f, 0f, 1e37f, 0f))
                }
            }
            assertEquals(1L, cache.rasterBuildCount)
            assertEquals(128L * 128L * 4L, cache.retainedPixelBytes)
            assertEquals(first, raster { canvas -> cache.draw(canvas, scene, renderer, viewport) })
            assertEquals(1L, cache.rasterBuildCount)
        }
    }

    @Test
    fun emptyNonzeroViewportsReleaseTheirPhysicalRaster() {
        val scene = InkScene(listOf(InkSceneStroke(stroke(points = arrayOf(10f to 20f, 60f to 20f)))))
        val renderer = InkPathRenderer()
        InkSceneRasterCache().use { cache ->
            raster { canvas ->
                cache.draw(canvas, scene, renderer, Rect(0f, 0f, 64f, 64f), rasterScale = 2f)
                assertEquals(128L * 128L * 4L, cache.retainedPixelBytes)
                assertEquals(0, cache.draw(canvas, scene, renderer, Rect(20f, 30f, 20f, 60f), rasterScale = 2f))
                assertEquals(0L, cache.retainedPixelBytes)
                cache.draw(canvas, scene, renderer, Rect(0f, 0f, 64f, 64f), rasterScale = 2f)
                assertEquals(0, cache.draw(canvas, scene, renderer, Rect(20f, 30f, 40f, 30f), rasterScale = 2f))
                assertEquals(0L, cache.retainedPixelBytes)
            }
            assertEquals(2L, cache.rasterBuildCount)
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

    private fun physicalRaster(
        viewport: Rect,
        zoom: Float,
        background: Int = 0,
        draw: (androidx.compose.ui.graphics.Canvas) -> Unit,
    ): List<Int> = raster(background, ceil(viewport.width * zoom.toDouble()).toInt() + 4,
        ceil(viewport.height * zoom.toDouble()).toInt() + 4) { canvas ->
        canvas.skiaCanvas.apply {
            translate(2f, 2f)
            scale(zoom, zoom)
            translate(-viewport.left, -viewport.top)
            clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom, false)
        }
        draw(canvas)
    }

    private fun maxChannelDelta(first: List<Int>, second: List<Int>): Int {
        assertEquals(first.size, second.size)
        var delta = 0
        first.indices.forEach { index -> repeat(4) { channel ->
            delta = maxOf(delta, abs(((first[index] ushr (channel * 8)) and 255) -
                ((second[index] ushr (channel * 8)) and 255)))
        } }
        return delta
    }

    private fun raster(
        background: Int = 0,
        width: Int = 128,
        height: Int = 128,
        draw: (androidx.compose.ui.graphics.Canvas) -> Unit,
    ): List<Int> = Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(background)
            draw(surface.canvas.asComposeCanvas())
            surface.makeImageSnapshot().use { image ->
                Bitmap.makeFromImage(image).use { bitmap -> List(width * height) { i -> bitmap.getColor(i % width, i / width) } }
            }
        }
}
