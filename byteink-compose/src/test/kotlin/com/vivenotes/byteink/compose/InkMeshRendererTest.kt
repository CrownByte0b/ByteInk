@file:OptIn(androidx.ink.brush.ExperimentalInkAnimationApi::class, androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.alphaMultiplier
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.subtract
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkMeshRendererTest {
    private fun inputs(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { i, (x, y) -> add(InputToolType.STYLUS, x, y, i * 100L, pressure = i.toFloat() / maxOf(1, points.size - 1)) }
    }
    private fun brush(family: BrushFamily, color: Int = 0xff000000.toInt(), size: Float = 12f) =
        Brush.createWithColorIntArgb(family, color, size, .25f)
    private fun raster(draw: (Canvas) -> Unit): Bitmap = Surface.makeRasterN32Premul(128, 128).use { surface ->
        surface.canvas.clear(0)
        draw(surface.canvas.asComposeCanvas())
        surface.makeImageSnapshot().use(Bitmap::makeFromImage)
    }
    private fun pixels(image: Bitmap) = List(image.width * image.height) { image.getColor(it % image.width, it / image.width) }
    private fun texture(vararg colors: Int): Image = Surface.makeRasterN32Premul(colors.size, 1).use { surface ->
        colors.forEachIndexed { i, color -> org.jetbrains.skia.Paint().use { paint ->
            paint.color = color
            surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(i.toFloat(), 0f, 1f, 1f), paint)
        } }
        surface.makeImageSnapshot()
    }

    @Test
    fun everyViveBrushDrawsWithTheFullRenderer() {
        InkMeshRenderer().use { renderer ->
            val ids = listOf(ViveBrushes.MARKER, ViveBrushes.PRESSURE_PEN, ViveBrushes.DASHED_LINE, ViveBrushes.HIGHLIGHTER) + (0..5).map(ViveBrushes::calligraphy)
            ids.forEach { id ->
                val stroke = Stroke(ViveBrushes.brush(id, 0, 0xff000000.toInt(), 12f), inputs(20f to 40f, 100f to 40f))
                assertTrue(renderer.canDraw(stroke), id)
                raster { assertTrue(renderer.draw(it, stroke), id) }.use { image ->
                    assertTrue(pixels(image).count { it ushr 24 > 0 } > 30, id)
                }
            }
        }
    }

    @Test
    fun perVertexOpacityInterpolatesAlongTheRealMesh() {
        val family = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(
            TargetNode.Target.OPACITY_MULTIPLIER, .1f, 1f,
            SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f),
        )))))
        val stroke = Stroke(brush(family), inputs(16f to 60f, 112f to 60f))
        InkMeshRenderer().use { renderer -> raster { renderer.draw(it, stroke) }.use { image ->
            val left = image.getColor(24, 60) ushr 24
            val middle = image.getColor(64, 60) ushr 24
            val right = image.getColor(104, 60) ushr 24
            assertTrue(left < middle && middle < right, "$left < $middle < $right")
            assertTrue(right > 200)
        } }
    }

    @Test
    fun accumulateOverlapAndDiscardFollowInkPaintSemantics() {
        for (overlap in listOf(SelfOverlap.ANY, SelfOverlap.ACCUMULATE, SelfOverlap.DISCARD)) {
            val stroke = Stroke(brush(BrushFamily(paint = BrushPaint(selfOverlap = overlap)), 0x80ff0000.toInt()),
                inputs(20f to 20f, 100f to 100f, 20f to 100f, 100f to 20f))
            InkMeshRenderer().use { renderer -> raster { renderer.draw(it, stroke) }.use { image ->
                val alpha = image.getColor(60, 60) ushr 24
                if (overlap == SelfOverlap.DISCARD) assertEquals(128, alpha) else assertTrue(alpha >= 190, "$overlap alpha=$alpha")
            } }
        }
    }

    @Test
    fun tilingTexturesRenderWithTheMeshAndDiscardPath() {
        texture(0xffff0000.toInt(), 0xff00ff00.toInt()).use { image ->
            for (overlap in listOf(SelfOverlap.ANY, SelfOverlap.DISCARD)) {
                val paint = BrushPaint(listOf(BrushPaint.TilingTexture("stripes", 20f, 20f)), selfOverlap = overlap)
                val stroke = Stroke(brush(BrushFamily(paint = paint), 0xffffffff.toInt(), 16f), inputs(10f to 60f, 110f to 60f))
                InkMeshRenderer(InkTextureStore { image }).use { renderer ->
                    assertTrue(renderer.canDraw(stroke))
                    raster { renderer.draw(it, stroke) }.use { actual ->
                        assertEquals(0xffff0000.toInt(), actual.getColor(24, 60), "$overlap red")
                        assertEquals(0xff00ff00.toInt(), actual.getColor(34, 60), "$overlap green")
                    }
                    renderer.clearCache()
                    assertFalse(image.isClosed)
                }
            }
        }
    }

    @Test
    fun missingTexturesSelectTheNextPaintAndFailureDrawsNoCoat() {
        val missing = BrushPaint(listOf(BrushPaint.TilingTexture("missing", 10f, 10f)))
        val fallback = BrushPaint(colorFunctions = listOf(BrushPaint.ColorFunction.ReplaceColor.withColorIntArgb(0xffff0000.toInt())))
        val family = BrushFamily(listOf(BrushCoat(BrushTip(), listOf(missing, fallback))))
        val stroke = Stroke(brush(family), inputs(20f to 60f, 100f to 60f))
        InkMeshRenderer().use { renderer ->
            assertTrue(renderer.canDraw(stroke))
            raster { renderer.draw(it, stroke) }.use {
                val pixel = it.getColor(60, 60)
                assertEquals(255, pixel ushr 24)
                assertTrue(((pixel ushr 16) and 255) >= 253 && ((pixel ushr 8) and 255) <= 2 && (pixel and 255) <= 2)
            }
            val bad = Stroke(brush(BrushFamily(listOf(BrushCoat(BrushTip()), BrushCoat(BrushTip(), missing)))), stroke.inputs)
            assertFalse(renderer.canDraw(bad))
            raster { assertFailsWith<IllegalArgumentException> { renderer.draw(it, bad) } }.use { assertTrue(pixels(it).all { p -> p == 0 }) }
        }
    }

    @Test
    fun geometryReuseCullingAndCacheBudgetsWorkForMeshes() {
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 12f), inputs(20f to 60f, 100f to 60f))
        InkMeshRenderer(cacheCapacity = 1).use { renderer ->
            raster { canvas ->
                assertFalse(renderer.draw(canvas, stroke, viewport = Rect(0f, 0f, 10f, 10f)))
                assertEquals(0, renderer.meshBuildCount)
                renderer.draw(canvas, stroke)
                val builds = renderer.meshBuildCount
                renderer.draw(canvas, stroke, ImmutableAffineTransform(1f, 0f, 2f, 0f, 1f, 3f))
                assertEquals(builds, renderer.meshBuildCount)
                renderer.draw(canvas, stroke, colorArgb = 0xffff0000.toInt())
                assertEquals(builds + 1, renderer.meshBuildCount)
                assertEquals(1, renderer.cachedShapeCount)
                assertTrue(renderer.cachedGeometryBytes in 1..renderer.cacheByteBudget)
                renderer.clearCache()
                assertEquals(0, renderer.cachedGeometryBytes)
            }.close()
        }
        InkMeshRenderer(cacheByteBudget = 1).use { renderer ->
            raster { renderer.draw(it, stroke) }.close()
            assertEquals(0, renderer.cachedShapeCount)
        }
    }

    @Test
    fun allTextureBlendModesMatchSkiasPremultipliedBlending() {
        val ink = listOf(BrushPaint.TextureLayer.BlendMode.MODULATE, BrushPaint.TextureLayer.BlendMode.DST_IN,
            BrushPaint.TextureLayer.BlendMode.DST_OUT, BrushPaint.TextureLayer.BlendMode.SRC_ATOP, BrushPaint.TextureLayer.BlendMode.SRC_IN,
            BrushPaint.TextureLayer.BlendMode.SRC_OVER, BrushPaint.TextureLayer.BlendMode.DST_OVER, BrushPaint.TextureLayer.BlendMode.SRC,
            BrushPaint.TextureLayer.BlendMode.DST, BrushPaint.TextureLayer.BlendMode.SRC_OUT, BrushPaint.TextureLayer.BlendMode.DST_ATOP,
            BrushPaint.TextureLayer.BlendMode.XOR)
        val skia = listOf(org.jetbrains.skia.BlendMode.MODULATE, org.jetbrains.skia.BlendMode.DST_IN, org.jetbrains.skia.BlendMode.DST_OUT,
            org.jetbrains.skia.BlendMode.SRC_ATOP, org.jetbrains.skia.BlendMode.SRC_IN, org.jetbrains.skia.BlendMode.SRC_OVER,
            org.jetbrains.skia.BlendMode.DST_OVER, org.jetbrains.skia.BlendMode.SRC, org.jetbrains.skia.BlendMode.DST,
            org.jetbrains.skia.BlendMode.SRC_OUT, org.jetbrains.skia.BlendMode.DST_ATOP, org.jetbrains.skia.BlendMode.XOR)
        texture(0x9060d040.toInt()).use { image ->
            for (overlap in listOf(SelfOverlap.ANY, SelfOverlap.DISCARD)) for (mode in ink.indices) {
                val family = BrushFamily(paint = BrushPaint(listOf(BrushPaint.TilingTexture("color", 20f, 20f, blendMode = ink[mode])), selfOverlap = overlap))
                val stroke = Stroke(brush(family, 0x806040c0.toInt()), inputs(10f to 60f, 110f to 60f))
                val expected = org.jetbrains.skia.Surface.makeRasterN32Premul(1, 1).use { surface ->
                    org.jetbrains.skia.Shader.makeColor(stroke.brush.colorIntArgb).use { color -> image.makeShader().use { src ->
                        org.jetbrains.skia.Shader.makeBlend(skia[mode], color, src).use { shader -> org.jetbrains.skia.Paint().use { paint ->
                            paint.shader = shader
                            surface.canvas.drawRect(org.jetbrains.skia.Rect.makeWH(1f, 1f), paint)
                        } }
                    } }
                    surface.makeImageSnapshot().use { Bitmap.makeFromImage(it).use { it.getColor(0, 0) } }
                }
                InkMeshRenderer(InkTextureStore { image }).use { renderer -> raster { renderer.draw(it, stroke) }.use { actual ->
                    val pixel = actual.getColor(60, 60)
                    // Integer colors unpremultiply low-alpha pixels; compare stored premultiplied channels.
                    for (shift in listOf(24, 16, 8, 0)) {
                        val ea = expected ushr 24; val aa = pixel ushr 24
                        val e = if (shift == 24) ea else ((expected ushr shift) and 255) * ea / 255
                        val a = if (shift == 24) aa else ((pixel ushr shift) and 255) * aa / 255
                        assertTrue(kotlin.math.abs(a - e) <= 2, "$overlap ${ink[mode]} expected=${expected.toUInt().toString(16)}, actual=${pixel.toUInt().toString(16)}")
                    }
                } }
            }
        }
    }

    @Test
    fun textureLayersBlendInOrderBeforeTheFinalBrushBlend() {
        texture(0x90e03050.toInt()).use { first -> texture(0xb040d090.toInt()).use { second ->
            val layers = listOf(
                BrushPaint.TilingTexture("first", 20f, 20f, blendMode = BrushPaint.TextureLayer.BlendMode.SRC_ATOP),
                BrushPaint.TilingTexture("second", 20f, 20f, blendMode = BrushPaint.TextureLayer.BlendMode.DST_OVER),
            )
            val expected = Surface.makeRasterN32Premul(1, 1).use { surface ->
                first.makeShader().use { a -> second.makeShader().use { b ->
                    org.jetbrains.skia.Shader.makeBlend(org.jetbrains.skia.BlendMode.SRC_ATOP, b, a).use { combined ->
                        org.jetbrains.skia.Shader.makeColor(0x805070c0.toInt()).use { color ->
                            org.jetbrains.skia.Shader.makeBlend(org.jetbrains.skia.BlendMode.DST_OVER, color, combined).use { shader ->
                                org.jetbrains.skia.Paint().use { paint ->
                                    paint.shader = shader
                                    surface.canvas.drawRect(org.jetbrains.skia.Rect.makeWH(1f, 1f), paint)
                                }
                            }
                        }
                    }
                } }
                surface.makeImageSnapshot().use { Bitmap.makeFromImage(it).use { it.getColor(0, 0) } }
            }
            InkMeshRenderer(InkTextureStore { if (it == "first") first else second }, textureCacheCapacity = 1).use { renderer ->
                for (overlap in listOf(SelfOverlap.ANY, SelfOverlap.DISCARD)) {
                    val stroke = Stroke(brush(BrushFamily(paint = BrushPaint(layers, selfOverlap = overlap)), 0x805070c0.toInt()),
                        inputs(10f to 60f, 110f to 60f))
                    raster { renderer.draw(it, stroke) }.use { bitmap ->
                        val actual = bitmap.getColor(60, 60)
                        for (shift in listOf(24, 16, 8, 0)) {
                            val a = if (shift == 24) actual ushr 24 else ((actual ushr shift) and 255) * (actual ushr 24) / 255
                            val e = if (shift == 24) expected ushr 24 else ((expected ushr shift) and 255) * (expected ushr 24) / 255
                            assertTrue(kotlin.math.abs(a - e) <= 2, "$overlap texture order: ${actual.toUInt().toString(16)} vs ${expected.toUInt().toString(16)}")
                        }
                        assertEquals(1, renderer.cachedTextureCount)
                    }
                }
            }
            assertFalse(first.isClosed); assertFalse(second.isClosed)
        } }
    }

    @Test
    fun tilingUsesRotationOffsetOriginAndWrapModes() {
        texture(0xffff0000.toInt(), 0xff00ff00.toInt()).use { image ->
            val base = BrushPaint.TilingTexture("stripes", 20f, 20f)
            val layers = listOf(base, base.copy(offsetX = .5f), base.copy(origin = BrushPaint.TilingTexture.Origin.FIRST_STROKE_INPUT),
                base.copy(wrapX = BrushPaint.TextureLayer.Wrap.CLAMP), base.copy(wrapX = BrushPaint.TextureLayer.Wrap.MIRROR))
            val expected = listOf(listOf(0xffff0000.toInt(), 0xff00ff00.toInt()), listOf(0xff00ff00.toInt(), 0xffff0000.toInt()),
                listOf(0xff00ff00.toInt(), 0xffff0000.toInt()), listOf(0xff00ff00.toInt(), 0xff00ff00.toInt()),
                listOf(0xff00ff00.toInt(), 0xffff0000.toInt()))
            InkMeshRenderer(InkTextureStore { image }).use { renderer ->
                layers.forEachIndexed { i, layer ->
                    val stroke = Stroke(brush(BrushFamily(paint = BrushPaint(listOf(layer))), 0xffffffff.toInt(), 20f), inputs(10f to 60f, 110f to 60f))
                    raster { renderer.draw(it, stroke) }.use { pixels ->
                        assertEquals(expected[i][0], pixels.getColor(24, 60), "$layer at 24")
                        assertEquals(expected[i][1], pixels.getColor(34, 60), "$layer at 34")
                    }
                }
                val rotated = Stroke(brush(BrushFamily(paint = BrushPaint(listOf(base.copy(rotationDegrees = 90f)))), 0xffffffff.toInt(), 20f), inputs(60f to 10f, 60f to 110f))
                raster { renderer.draw(it, rotated) }.use {
                    assertEquals(0xffff0000.toInt(), it.getColor(60, 24))
                    assertEquals(0xff00ff00.toInt(), it.getColor(60, 34))
                }
            }
        }
    }

    @Test
    fun stampingAndAtlasAnimationUseParticleUvsAndInvalidateSceneRasters() {
        texture(0xffff0000.toInt(), 0xff00ff00.toInt()).use { image ->
            val layer = BrushPaint.StampingTexture("atlas", animationFrames = 2, animationRows = 1, animationColumns = 2, animationDurationMillis = 1000L)
            val family = BrushFamily(BrushTip(particleGapDistanceScale = 2f), BrushPaint(listOf(layer)))
            val stroke = Stroke(brush(family, 0xffffffff.toInt(), 20f), inputs(20f to 60f, 100f to 60f))
            InkMeshRenderer(InkTextureStore { image }).use { renderer ->
                assertTrue(renderer.canDraw(stroke))
                val scene = InkScene(listOf(InkSceneStroke(stroke)))
                InkSceneRasterCache().use { cache ->
                    raster { cache.draw(it, scene, renderer, 128, 128) }.use { assertEquals(0xffff0000.toInt(), it.getColor(20, 60)) }
                    raster { cache.draw(it, scene, renderer, 128, 128) }.close()
                    assertEquals(1, cache.rasterBuildCount)
                    renderer.animationTimeMillis = 750L
                    raster { cache.draw(it, scene, renderer, 128, 128) }.use { assertEquals(0xff00ff00.toInt(), it.getColor(20, 60)) }
                    assertEquals(2, cache.rasterBuildCount)
                    renderer.animationTimeMillis = 1000L
                    raster { cache.draw(it, scene, renderer, 128, 128) }.use { assertEquals(0xffff0000.toInt(), it.getColor(20, 60)) }
                    assertEquals(3, cache.rasterBuildCount)
                    renderer.clearCache()
                    raster { cache.draw(it, scene, renderer, 128, 128) }.close()
                    assertEquals(4, cache.rasterBuildCount)
                }
            }
        }
    }

    @Test
    fun stampingUsesAtlasRowsReverseLoopsAndPerParticleOffsetsWithNoClock() {
        val colors = listOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), 0xffffffff.toInt())
        Surface.makeRasterN32Premul(2, 2).use { atlas ->
            colors.forEachIndexed { i, color -> org.jetbrains.skia.Paint().use { paint ->
                paint.color = color
                atlas.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH((i % 2).toFloat(), (i / 2).toFloat(), 1f, 1f), paint)
            } }
            atlas.makeImageSnapshot().use { image ->
                val layer = BrushPaint.StampingTexture("atlas", animationFrames = 4, animationRows = 2,
                    animationColumns = 2, animationDurationMillis = 1000L,
                    animationRepeatMode = BrushPaint.TextureLayer.AnimationRepeatMode.REVERSE)
                val stroke = Stroke(brush(BrushFamily(BrushTip(particleGapDistanceScale = 2f), BrushPaint(listOf(layer))),
                    0xffffffff.toInt(), 20f), inputs(20f to 60f, 100f to 60f))
                InkMeshRenderer(InkTextureStore { image }).use { renderer ->
                    for ((time, frame) in listOf(0L to 0, 300L to 1, 600L to 2, 900L to 3, 1500L to 2, 1800L to 0)) {
                        renderer.animationTimeMillis = time
                        raster { renderer.draw(it, stroke) }.use { assertEquals(colors[frame], it.getColor(20, 60), "time=$time") }
                    }
                    // Finished meshes quantize particle offsets. Keep this test away from
                    // frame boundaries so it checks offset use rather than packing rounding.
                    val offset = BrushBehavior(TargetNode(TargetNode.Target.TEXTURE_ANIMATION_PROGRESS_OFFSET, .6f, .7f,
                        SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)))
                    val offsetStroke = Stroke(brush(BrushFamily(BrushTip(particleGapDistanceScale = 2f, behaviors = listOf(offset)),
                        BrushPaint(listOf(layer.copy(animationDurationMillis = 0L)))), 0xffffffff.toInt(), 20f), inputs(20f to 60f, 100f to 60f))
                    for (time in listOf(0L, 750L, 2000L)) {
                        renderer.animationTimeMillis = time
                        raster { renderer.draw(it, offsetStroke) }.use { assertEquals(colors[2], it.getColor(20, 60), "offset with time=$time") }
                    }
                }
            }
        }
    }

    @Test
    fun predictionOpacityAndTimedEffectsUpdateWithoutNewRealInputs() {
        val predicted = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(
            TargetNode.Target.OPACITY_MULTIPLIER, 1f, 0f,
            SourceNode(SourceNode.Source.PREDICTED_DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 6f),
        )))))
        InkMeshRenderer().use { renderer ->
            val live = InProgressStroke()
            try {
                live.start(brush(predicted))
                live.enqueueInputs(inputs(20f to 60f, 40f to 60f), MutableStrokeInputBatch().apply {
                    add(InputToolType.STYLUS, 112f, 60f, 200L, pressure = 1f)
                })
                live.updateShape(100L)
                raster { renderer.draw(it, live) }.use { image ->
                    assertTrue(image.getColor(28, 60) ushr 24 > 240)
                    assertTrue(image.getColor(100, 60) ushr 24 < 100)
                }
                live.enqueueInputs(MutableStrokeInputBatch(), MutableStrokeInputBatch().apply {
                    add(InputToolType.STYLUS, 40f, 110f, 200L, pressure = 1f)
                })
                live.updateShape(100L)
                raster { renderer.draw(it, live) }.use { assertEquals(0, it.getColor(100, 60)) }
            } finally { live.clear() }
            val timed = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(
                TargetNode.Target.OPACITY_MULTIPLIER, 1f, 0f,
                SourceNode(SourceNode.Source.TIME_SINCE_INPUT_IN_SECONDS, 0f, 1f),
            )))))
            try {
                live.start(brush(timed, size = 20f))
                live.enqueueInputs(inputs(60f to 60f), MutableStrokeInputBatch())
                live.updateShape(0L)
                val a = raster { renderer.draw(it, live) }.use { it.getColor(60, 60) ushr 24 }
                live.updateShape(500L)
                val b = raster { renderer.draw(it, live) }.use { it.getColor(60, 60) ushr 24 }
                assertTrue(a > 240 && b in 120..135, "timed opacity $a -> $b")
                live.clear()
                raster { assertFalse(renderer.draw(it, live)) }.use { assertTrue(pixels(it).all { p -> p == 0 }) }
            } finally { live.clear() }
        }
    }

    @Test
    fun hueSaturationAndLuminosityBehaviorsChangeVertexColors() {
        for (target in listOf(TargetNode.Target.HUE_OFFSET_IN_RADIANS, TargetNode.Target.SATURATION_MULTIPLIER, TargetNode.Target.LUMINOSITY_OFFSET)) {
            val range = when (target) {
                TargetNode.Target.HUE_OFFSET_IN_RADIANS -> 0f to 1.8f
                TargetNode.Target.SATURATION_MULTIPLIER -> .2f to 1.8f
                else -> -.1f to .3f
            }
            val family = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(target, range.first, range.second,
                SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f))))))
            val stroke = Stroke(brush(family, 0xff508090.toInt()), inputs(16f to 60f, 112f to 60f))
            InkMeshRenderer().use { renderer -> raster { renderer.draw(it, stroke) }.use { image ->
                val left = image.getColor(24, 60); val right = image.getColor(104, 60)
                assertEquals(255, left ushr 24)
                assertEquals(255, right ushr 24)
                assertTrue(left != right, "$target must affect color")
            } }
        }
    }

    @Test
    fun splitMeshesRenderErasedGapsAndRestoreTransforms() {
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x80000000.toInt(), 12f), inputs(10f to 60f, 110f to 60f))
        val mask = ViveBrushes.eraseMask(inputs(60f to 20f, 60f to 100f), 20f)
        val pieces = listOf(PageStroke("cut", stroke)).subtract(mask, listOf("cut"))
        InkMeshRenderer().use { renderer -> raster { canvas -> pieces.forEach { renderer.draw(canvas, it.stroke) } }.use { image ->
            assertEquals(0, image.getColor(60, 60))
            for (x in 15..40) assertEquals(128, image.getColor(x, 60) ushr 24)
            for (x in 80..105) assertEquals(128, image.getColor(x, 60) ushr 24)
        } }
    }

    @Test
    fun colorManagementMatchesSkiaOnUntaggedSrgbLinearAndP3Surfaces() {
        val color = 0x80173b83.toInt()
        val stroke = Stroke(brush(BrushFamily(), color, 20f), inputs(20f to 60f, 100f to 60f))
        InkMeshRenderer().use { renderer ->
            for (space in listOf(null, org.jetbrains.skia.ColorSpace.sRGB, org.jetbrains.skia.ColorSpace.sRGBLinear, org.jetbrains.skia.ColorSpace.displayP3)) {
                val info = org.jetbrains.skia.ImageInfo(128, 128, org.jetbrains.skia.ColorType.N32, org.jetbrains.skia.ColorAlphaType.PREMUL, space)
                Surface.makeRaster(info).use { surface ->
                    surface.canvas.clear(0)
                    renderer.draw(surface.canvas.asComposeCanvas(), stroke)
                    org.jetbrains.skia.Paint().use { paint ->
                        paint.color = color
                        surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH(20f, 10f, 80f, 20f), paint)
                    }
                    surface.makeImageSnapshot().use { image -> Bitmap.makeFromImage(image).use { pixels ->
                        val actual = pixels.getColor(60, 60); val expected = pixels.getColor(60, 20)
                        for (shift in listOf(24, 16, 8, 0)) assertTrue(kotlin.math.abs(((actual ushr shift) and 255) - ((expected ushr shift) and 255)) <= 2,
                            "$space expected=${expected.toUInt().toString(16)}, actual=${actual.toUInt().toString(16)}")
                    } }
                }
            }
        }
    }

    @Suppress("DEPRECATION_ERROR") // Exercise the alpha multiplier used internally by Compose layers.
    @OptIn(androidx.compose.runtime.InternalComposeApi::class)
    @Test
    fun composeAlphaClippingAndExistingCanvasTransformsApplyToMeshes() {
        val stroke = Stroke(brush(BrushFamily(), 0xffff0000.toInt(), 20f), inputs(20f to 60f, 100f to 60f))
        InkMeshRenderer().use { renderer -> raster { canvas ->
            canvas.alphaMultiplier = .5f
            canvas.clipRect(0f, 0f, 64f, 128f)
            canvas.translate(0f, 20f)
            renderer.draw(canvas, stroke, ImmutableAffineTransform(-1f, 0f, 120f, 0f, 1f, 0f))
        }.use { image ->
            assertTrue(image.getColor(40, 80) ushr 24 in 127..128)
            assertEquals(0, image.getColor(80, 80))
            assertEquals(0, image.getColor(40, 60))
        } }
    }

    @Test
    fun liveFinishedRecolorAndClearRetireNativeResources() {
        val renderer = InkMeshRenderer(cacheCapacity = 1)
        val live = InProgressStroke()
        try {
            live.start(brush(BrushFamily(), 0xff4080d0.toInt(), 20f))
            live.enqueueInputs(inputs(20f to 60f, 100f to 60f), MutableStrokeInputBatch())
            live.finishInput(); live.updateShape()
            raster { renderer.draw(it, live) }.use { wet ->
                val count = renderer.meshBuildCount
                raster { renderer.draw(it, live) }.close()
                assertEquals(count, renderer.meshBuildCount)
                raster { renderer.draw(it, live.toImmutable()) }.use { dry ->
                    val a = wet.getColor(60, 60); val b = dry.getColor(60, 60)
                    for (shift in listOf(24, 16, 8, 0)) assertTrue(kotlin.math.abs(((a ushr shift) and 255) - ((b ushr shift) and 255)) <= 2)
                }
            }
            renderer.close(); renderer.close()
            assertEquals(0, renderer.cachedGeometryBytes)
            assertEquals(0, renderer.cachedShapeCount)
            assertEquals(0, renderer.cachedTextureCount)
            raster { assertFailsWith<IllegalStateException> { renderer.draw(it, live) } }.close()
            assertFailsWith<IllegalStateException> { renderer.canDraw(live.toImmutable()) }
        } finally { renderer.close(); live.clear() }
    }
}
