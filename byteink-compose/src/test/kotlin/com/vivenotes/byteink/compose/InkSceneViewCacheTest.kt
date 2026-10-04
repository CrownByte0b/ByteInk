package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkSceneViewCacheTest {
    @Test
    fun revisitedPanAndZoomViewsReuseExactPixelsAndCopiedTransformValues() {
        val scene = scene()
        val renderer = InkPathRenderer(1)
        val transform = MutableAffineTransform()
        val views = listOf(
            ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 0f),
            ImmutableAffineTransform(1f, 0f, -7.5f, 0f, 1f, -9.25f),
            ImmutableAffineTransform(1.5f, 0.2f, -32f, -0.1f, 1.25f, -24f),
        )
        InkSceneRasterCache(4, 4 * VIEW_BYTES).use { cache ->
            val expected = views.map { view -> InkSceneRasterCache().use { direct -> pixels(direct, scene, renderer, view) } }
            views.forEachIndexed { at, view ->
                transform.setValues(view.m00, view.m10, view.m20, view.m01, view.m11, view.m21)
                assertEquals(expected[at], pixels(cache, scene, renderer, transform))
            }
            val builds = renderer.pathBuildCount
            repeat(4) { views.indices.reversed().forEach { at ->
                assertEquals(expected[at], pixels(cache, scene, renderer, views[at]))
            } }
            assertEquals(3L, cache.rasterBuildCount)
            assertEquals(builds, renderer.pathBuildCount)
            assertEquals(3 * VIEW_BYTES, cache.retainedPixelBytes)
        }
        renderer.clearCache()
    }

    @Test
    fun pixelBudgetEvictsTheLeastRecentlyUsedImageBeforeReplacement() {
        val scene = scene()
        val renderer = InkPathRenderer()
        val views = List(3) { i -> ImmutableAffineTransform(1f, 0f, -i * 8f, 0f, 1f, 0f) }
        InkSceneRasterCache(8, 2 * VIEW_BYTES).use { cache ->
            pixels(cache, scene, renderer, views[0]); val first = images(cache).single()
            pixels(cache, scene, renderer, views[1]); val second = images(cache).last()
            pixels(cache, scene, renderer, views[0]) // Touch first; second is now the oldest.
            pixels(cache, scene, renderer, views[2])
            assertTrue(second.isClosed)
            assertFalse(first.isClosed)
            assertEquals(2, cache.cachedViewCount)
            assertEquals(2 * VIEW_BYTES, cache.retainedPixelBytes)
            assertEquals(1L, cache.rasterEvictionCount)
            val remaining = images(cache)
            cache.clearCache()
            assertTrue(remaining.all { it.isClosed })
            assertEquals(0, cache.cachedViewCount)
            assertEquals(0L, cache.retainedPixelBytes)
        }
        renderer.clearCache()
    }

    @Test
    fun entryLimitSceneReplacementAndCloseRetireAllOwnedImages() {
        val scene = scene()
        val renderer = InkPathRenderer()
        val cache = InkSceneRasterCache(1, 8 * VIEW_BYTES)
        pixels(cache, scene, renderer); val original = images(cache).single()
        pixels(cache, scene, renderer, ImmutableAffineTransform(2f, 0f, 0f, 0f, 2f, 0f))
        assertTrue(original.isClosed)
        assertEquals(1L, cache.rasterEvictionCount)
        val before = images(cache).single()
        pixels(cache, InkScene(scene.strokes), renderer)
        assertTrue(before.isClosed)
        val last = images(cache).single()
        cache.close(); cache.close()
        assertTrue(last.isClosed)
        assertEquals(0L, cache.retainedPixelBytes)
        assertFailsWith<IllegalStateException> { pixels(cache, scene, renderer) }
        renderer.clearCache()
    }

    @Test
    fun zeroAndInsufficientBudgetsDrawIdenticalPixelsWithoutRetainingImages() {
        val scene = scene()
        val renderer = InkPathRenderer()
        val expected = InkSceneRasterCache().use { pixels(it, scene, renderer) }
        for ((capacity, bytes) in listOf(4 to 0L, 0 to VIEW_BYTES, 4 to VIEW_BYTES - 1)) {
            InkSceneRasterCache(capacity, bytes).use { cache ->
                repeat(2) { assertEquals(expected, pixels(cache, scene, renderer)) }
                assertEquals(2L, cache.rasterBuildCount)
                assertEquals(0, cache.cachedViewCount)
                assertEquals(0L, cache.retainedPixelBytes)
            }
        }
        assertFailsWith<IllegalArgumentException> { InkSceneRasterCache(-1, VIEW_BYTES) }
        assertFailsWith<IllegalArgumentException> { InkSceneRasterCache(1, -1) }
        renderer.clearCache()
    }

    @Test
    fun changedOccurrenceExclusionsDiscardEveryOlderView() {
        val scene = scene()
        val renderer = InkPathRenderer()
        InkSceneRasterCache(4, 4 * VIEW_BYTES).use { cache ->
            pixels(cache, scene, renderer)
            pixels(cache, scene, renderer, ImmutableAffineTransform(1.5f, 0f, 0f, 0f, 1.5f, 0f))
            val old = images(cache)
            Surface.makeRasterN32Premul(128, 128).use { surface ->
                assertEquals(scene.strokes.size - 1, cache.draw(surface.canvas.asComposeCanvas(), scene,
                    renderer, Rect(0f, 0f, 128f, 128f), excludedStrokes = setOf(scene.strokes.first())))
            }
            assertTrue(old.all { it.isClosed })
            assertEquals(1, cache.cachedViewCount)
        }
        renderer.clearCache()
    }

    private fun pixels(cache: InkSceneRasterCache, scene: InkScene, renderer: InkPathRenderer,
        transform: AffineTransform = AffineTransform.IDENTITY): List<Int> =
        Surface.makeRasterN32Premul(128, 128).use { surface ->
            surface.canvas.clear(0)
            cache.draw(surface.canvas.asComposeCanvas(), scene, renderer, 128, 128, transform)
            surface.makeImageSnapshot().use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                List(128 * 128) { i -> bitmap.getColor(i % 128, i / 128) }
            } }
        }

    // Observe actual native ownership, including image close on eviction, rather than counters alone.
    private fun images(cache: InkSceneRasterCache): List<Image> {
        val field = InkSceneRasterCache::class.java.getDeclaredField("views").apply { isAccessible = true }
        return (field.get(cache) as Map<*, *>).values.map { raster ->
            requireNotNull(raster).javaClass.getDeclaredField("image").apply { isAccessible = true }.get(raster) as Image
        }
    }

    private fun scene(): InkScene = InkScene(List(3) { index ->
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 4f, 20f + index * 30f, 0L)
            add(InputToolType.MOUSE, 120f, 70f + index * 10f, 100L)
        }
        InkSceneStroke(Stroke(ViveBrushes.highlighter(0x80ff0033.toInt(), 9f), inputs.toImmutable()))
    })

    private companion object { const val VIEW_BYTES = 128L * 128 * 4 }
}
