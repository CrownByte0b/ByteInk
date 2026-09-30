package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.vive.ViveBrushes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InkSceneTest {
    private val viewport = Rect(0f, 0f, 128f, 128f)
    private val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x80000000.toInt(), 6f),
        MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 20f, 0L)
            add(InputToolType.MOUSE, 30f, 20f, 10L)
        }.toImmutable())

    @Test
    fun indexedDrawingMatchesTheScanWithAffineTransformsAndAlphaOrder() {
        val items = List(200) { i ->
            InkSceneStroke(stroke, ImmutableAffineTransform(
                1f, if (i % 3 == 0) 0.2f else 0f, (i % 20) * 20f - 60f,
                if (i % 4 == 0) 0.25f else 0f, 1f, (i / 20) * 20f - 40f,
            ), if (i % 2 == 0) 0x80ff0000.toInt() else 0x800000ff.toInt())
        }
        val scene = InkScene(items)
        listOf(
            AffineTransform.IDENTITY,
            ImmutableAffineTransform(1.5f, 0f, -20f, 0f, 1.5f, -10f),
            ImmutableAffineTransform(0f, -0.8f, 100f, 1.2f, 0f, 5f),
            ImmutableAffineTransform(-1f, 0.2f, 115f, -0.1f, 0.6f, 10f),
        ).forEach { transform ->
            val renderer = InkPathRenderer()
            val indexed = raster { canvas -> scene.draw(canvas, renderer, transform, viewport) }
            val scanned = raster { canvas ->
                val composed = MutableAffineTransform()
                items.forEach { item ->
                    AffineTransform.multiply(transform, item.strokeToScene, composed)
                    renderer.draw(canvas, item.stroke, composed, viewport, item.colorArgb)
                }
            }
            assertEquals(scanned, indexed, "viewport culling preserves every visible pixel and stroke order for $transform")
        }
    }

    @Test
    fun anOffscreenPageAvoidsPathBuildsAndSmallViewportVisitsOnlyNearbyStrokes() {
        val items = List(40_000) { i -> InkSceneStroke(stroke,
            ImmutableAffineTransform(1f, 0f, (i % 200) * 100f, 0f, 1f, (i / 200) * 100f)) }
        val scene = InkScene(items)
        val renderer = InkPathRenderer()
        raster { canvas ->
            assertEquals(0, scene.draw(canvas, renderer, viewport = Rect(-1000f, -1000f, -500f, -500f)))
            assertEquals(0, renderer.pathBuildCount)
            assertTrue(scene.visibleStrokes(viewport).size <= 4)
            assertTrue(scene.draw(canvas, renderer, viewport = viewport) > 0)
            assertEquals(1, renderer.pathBuildCount, "shared geometry has one path regardless of projections")
        }
    }

    @Test
    fun mutableTransformsAreSnapshottedAndInvalidViewportsAreRefused() {
        val transform = MutableAffineTransform()
        val scene = InkScene(listOf(InkSceneStroke(stroke, transform)))
        transform.setValues(1f, 0f, 1000f, 0f, 1f, 1000f)
        assertEquals(1, scene.visibleStrokes(viewport).size)
        assertEquals(emptyList(), scene.visibleStrokes(Rect.Zero))
        assertFailsWith<IllegalArgumentException> { scene.visibleStrokes(Rect(Float.NaN, 0f, 1f, 1f)) }
        assertFailsWith<IllegalArgumentException> {
            scene.visibleStrokes(viewport, ImmutableAffineTransform(0f, 0f, 0f, 0f, 0f, 0f))
        }
    }

    @org.junit.Test(timeout = 5_000)
    fun tinyFiniteZoomStillFindsCandidatesWithoutCellCountOverflow() {
        val scene = InkScene(listOf(InkSceneStroke(stroke)))
        assertEquals(1, scene.visibleStrokes(Rect(-128f, -128f, 128f, 128f),
            ImmutableAffineTransform(1e-18f, 0f, 0f, 0f, 1e-18f, 0f)).size)
    }

    private fun raster(draw: (androidx.compose.ui.graphics.Canvas) -> Unit): List<Int> =
        Surface.makeRasterN32Premul(128, 128).use { surface ->
            surface.canvas.clear(0)
            draw(surface.canvas.asComposeCanvas())
            surface.makeImageSnapshot().use { image ->
                Bitmap.makeFromImage(image).use { bitmap -> List(128 * 128) { i -> bitmap.getColor(i % 128, i / 128) } }
            }
        }
}
