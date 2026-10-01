package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.vive.ViveBrushes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.lang.reflect.Modifier
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkSceneExclusionsTest {
    private val viewport = Rect(0f, 0f, 128f, 128f)
    private val red = 0x80ff0000.toInt()
    private val blue = 0x800000ff.toInt()
    private val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x80000000.toInt(), 6f),
        inputs(10f to 20f, 30f to 20f))

    @Test
    fun legacyDrawMethodsAndKotlinDefaultBridgesRemainBinaryCompatible() {
        val legacy = InkScene::class.java.getMethod("draw", Canvas::class.java, InkPathRenderer::class.java,
            AffineTransform::class.java, Rect::class.java)
        assertEquals(Int::class.javaPrimitiveType, legacy.returnType)
        val legacyDefault = InkScene::class.java.getMethod("draw\$default", InkScene::class.java,
            Canvas::class.java, InkPathRenderer::class.java, AffineTransform::class.java, Rect::class.java,
            Int::class.javaPrimitiveType, Any::class.java)
        assertTrue(Modifier.isStatic(legacyDefault.modifiers))
        assertEquals(Int::class.javaPrimitiveType, legacyDefault.returnType)

        val helpers = Class.forName("com.vivenotes.byteink.compose.InkSceneKt")
        val extension = helpers.getMethod("drawInkScene", DrawScope::class.java, InkScene::class.java,
            InkPathRenderer::class.java, AffineTransform::class.java)
        assertTrue(Modifier.isStatic(extension.modifiers))
        assertEquals(Int::class.javaPrimitiveType, extension.returnType)
        val extensionDefault = helpers.getMethod("drawInkScene\$default", DrawScope::class.java,
            InkScene::class.java, InkPathRenderer::class.java, AffineTransform::class.java,
            Int::class.javaPrimitiveType, Any::class.java)
        assertTrue(Modifier.isStatic(extensionDefault.modifiers))
        assertEquals(Int::class.javaPrimitiveType, extensionDefault.returnType)
    }

    @Test
    fun growingExclusionsMatchReconstructedScenesAcrossAffineTransforms() {
        val scene = InkScene(List(200) { i -> InkSceneStroke(stroke, ImmutableAffineTransform(
            1f, if (i % 3 == 0) 0.2f else 0f, (i % 20) * 20f - 60f,
            if (i % 4 == 0) 0.25f else 0f, 1f, (i / 20) * 20f - 40f,
        ), if (i % 2 == 0) red else blue) })
        val snapshot = scene.strokes
        val renderer = InkPathRenderer()
        val transforms = listOf(
            AffineTransform.IDENTITY,
            ImmutableAffineTransform(1.5f, 0f, -20f, 0f, 1.5f, -10f),
            ImmutableAffineTransform(0f, -0.8f, 100f, 1.2f, 0f, 5f),
            ImmutableAffineTransform(-1f, 0.2f, 115f, -0.1f, 0.6f, 10f),
        )
        transforms.forEach { transform ->
            val candidates = scene.visibleStrokes(viewport, transform)
            listOf(0, 1, 17, 100, 200).forEach { count ->
                val excluded = identitySet(scene.strokes.take(count))
                val rebuilt = InkScene(scene.strokes.filter { it !in excluded })
                val expected = raster { rebuilt.draw(it, InkPathRenderer(), transform, viewport) }
                val actual = raster { scene.draw(it, renderer, transform, viewport, excluded) }
                assertEquals(expected, actual, "exclusions preserve order and pixels at count=$count, transform=$transform")
                assertSame(snapshot, scene.strokes)
                val unchanged = scene.visibleStrokes(viewport, transform)
                assertEquals(candidates.size, unchanged.size)
                candidates.indices.forEach { assertSame(candidates[it], unchanged[it]) }
            }
        }
        assertEquals(1L, renderer.pathBuildCount, "all exclusions, transforms and recolours reuse the shared mesh path")
    }

    @Test
    fun projectionsSharingGeometryKeepIndependentTransformsAndColours() {
        val scene = InkScene(listOf(
            InkSceneStroke(stroke, colorArgb = red),
            InkSceneStroke(stroke, ImmutableAffineTransform(1f, 0f, 0f, 0f, 1f, 30f), red),
            InkSceneStroke(stroke, colorArgb = blue),
            InkSceneStroke(stroke, ImmutableAffineTransform(1f, 0f, 30f, 0f, 1f, 0f)),
        ))
        val renderer = InkPathRenderer()
        val pixels = raster {
            assertEquals(3, scene.draw(it, renderer, viewport = viewport, excludedStrokes = setOf(scene.strokes[0])))
        }
        assertEquals(blue, pixels[20 * 128 + 20])
        assertEquals(red, pixels[50 * 128 + 20])
        assertEquals(0x80000000.toInt(), pixels[20 * 128 + 50])
        assertEquals(1L, renderer.pathBuildCount)
    }

    @Test
    fun equalOccurrencesAreExcludedByIdentityAndRetainSeparateAlpha() {
        val supplied = InkSceneStroke(stroke)
        val scene = InkScene(listOf(supplied, supplied, supplied))
        assertEquals(scene.strokes[0], scene.strokes[1])
        assertNotSame(scene.strokes[0], scene.strokes[1])
        val renderer = InkPathRenderer()
        listOf(
            setOf(supplied) to (3 to 224),
            setOf(scene.strokes[1]) to (2 to 192),
            identitySet(listOf(scene.strokes[0], scene.strokes[2])) to (1 to 128),
            identitySet(scene.strokes) to (0 to 0),
        ).forEach { (excluded, expected) ->
            val pixels = raster {
                assertEquals(expected.first, scene.draw(it, renderer, viewport = viewport, excludedStrokes = excluded))
            }
            assertEquals(expected.second, pixels[20 * 128 + 20] ushr 24)
        }
        assertEquals(1L, renderer.pathBuildCount)
    }

    @Test
    fun exclusionKeysRemainStableWhenInputTransformsAreMutatedAndViewsStayValidated() {
        val transform = MutableAffineTransform()
        val scene = InkScene(listOf(InkSceneStroke(stroke, transform)))
        val key = scene.strokes.single()
        val excluded = setOf(key)
        val hash = key.hashCode()
        transform.setValues(1f, 0f, 1000f, 0f, 1f, 1000f)
        assertEquals(hash, key.hashCode())
        assertTrue(key in excluded)
        assertSame(key, scene.visibleStrokes(viewport).single())
        val renderer = InkPathRenderer()
        raster { canvas ->
            assertEquals(0, scene.draw(canvas, renderer, viewport = viewport, excludedStrokes = excluded))
            assertEquals(0L, renderer.pathBuildCount)
            assertEquals(1, scene.draw(canvas, renderer, viewport = viewport))
            assertFailsWith<IllegalArgumentException> {
                scene.draw(canvas, renderer, viewport = Rect(Float.NaN, 0f, 1f, 1f), excludedStrokes = excluded)
            }
            assertFailsWith<IllegalArgumentException> {
                scene.draw(canvas, renderer, ImmutableAffineTransform(0f, 0f, 0f, 0f, 0f, 0f), viewport, excluded)
            }
        }
    }

    @OptIn(ExperimentalInkEraserApi::class)
    @Test
    fun excludingSplitPiecesPreservesTriangleUnionAlphaAndRemainingDrawOrder() {
        val original = Stroke(ViveBrushes.highlighter(red, 12f), inputs(10f to 60f, 110f to 60f))
        val mask = ViveBrushes.eraseMask(inputs(60f to 20f, 60f to 100f), 20f)
        // Page replay retains DISCARD highlighters as one projection. Explicit Ink splitting here
        // exercises the renderer's no-outline triangle fallback with the real highlighter brush.
        val pieces = original.subtract(mask.shape, AffineTransform.IDENTITY, AffineTransform.IDENTITY)
            .split(AffineTransform.IDENTITY, 0f)
        assertEquals(2, pieces.size)
        assertTrue(pieces.all { it.shape.getOutlineCount(0) == 0 })
        val ordered = pieces.sortedBy { it.shape.computeBoundingBox()!!.xMin }
        val scene = InkScene(listOf(
            InkSceneStroke(ordered[0]),
            InkSceneStroke(original, colorArgb = blue),
            InkSceneStroke(ordered[1]),
        ))
        val excluded = setOf(scene.strokes[0])
        val remaining = scene.strokes.drop(1)
        val renderer = InkPathRenderer()
        val actual = raster { scene.draw(it, renderer, viewport = viewport, excludedStrokes = excluded) }
        val expected = raster { InkScene(remaining).draw(it, InkPathRenderer(), viewport = viewport) }
        val reversed = raster { InkScene(remaining.reversed()).draw(it, InkPathRenderer(), viewport = viewport) }
        assertEquals(expected, actual)
        assertNotEquals(reversed, actual, "overlapping red and blue retain source order after exclusion")
        for (x in 15..45) assertEquals(128, actual[60 * 128 + x] ushr 24, "left piece is removed at $x")
        for (x in 75..105) assertEquals(192, actual[60 * 128 + x] ushr 24, "right piece draws with one alpha at $x")
        assertTrue(actual.all { it ushr 24 <= 192 }, "triangle seams do not add repeated alpha")
        raster { scene.draw(it, renderer, viewport = viewport, excludedStrokes = emptySet()) }
        assertEquals(3L, renderer.pathBuildCount, "restoring a piece adds only its path and retains the others")
    }

    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { i, (x, y) -> add(InputToolType.MOUSE, x, y, i * 100L) }
    }.toImmutable()

    private fun identitySet(items: List<InkSceneStroke>): Set<InkSceneStroke> =
        Collections.newSetFromMap(IdentityHashMap<InkSceneStroke, Boolean>()).apply { addAll(items) }

    private fun raster(draw: (Canvas) -> Unit): List<Int> = Surface.makeRasterN32Premul(128, 128).use { surface ->
        surface.canvas.clear(0)
        draw(surface.canvas.asComposeCanvas())
        surface.makeImageSnapshot().use { image ->
            Bitmap.makeFromImage(image).use { bitmap -> List(128 * 128) { i -> bitmap.getColor(i % 128, i / 128) } }
        }
    }
}
