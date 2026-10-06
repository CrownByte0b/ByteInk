package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertTrue

class InkMeshGpuTest {
    @Test
    fun runtimeMeshShaderCompilesAndRendersOnOpenGl() {
        assumeTrue("Run Linux Xvfb/GLX verification with :byteink-compose:meshGpuTest", java.lang.Boolean.getBoolean("byteink.test.gpu"))
        HeadlessGlContext().use { gl -> DirectContext.makeGL().use { context ->
            println("Mesh shader OpenGL renderer: ${gl.renderer()}")
            Surface.makeRasterN32Premul(2, 1).use { source ->
                source.canvas.clear(0x90ff8040.toInt())
                source.makeImageSnapshot().use { image -> InkMeshRenderer(InkTextureStore { image }).use { renderer ->
                    val behavior = BrushBehavior(TargetNode(TargetNode.Target.OPACITY_MULTIPLIER, .2f, 1f,
                        SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)))
                    val family = BrushFamily(BrushTip(behaviors = listOf(behavior)),
                        BrushPaint(listOf(BrushPaint.TilingTexture("test", 20f, 20f))))
                    val brush = Brush.createWithColorIntArgb(family, 0xff4070a0.toInt(), 20f, .25f)
                    val stroke = Stroke(brush, MutableStrokeInputBatch().apply {
                        repeat(33) { i -> add(InputToolType.STYLUS, 20f + i * 2.75f, 60f, i * 8L, pressure = i / 32f) }
                    })
                    val info = ImageInfo(128, 128, ColorType.N32, ColorAlphaType.PREMUL, ColorSpace.sRGB)
                    Surface.makeRenderTarget(context, false, info).use { gpu -> Surface.makeRaster(info).use { cpu ->
                        gpu.canvas.clear(0); cpu.canvas.clear(0)
                        renderer.draw(gpu.canvas.asComposeCanvas(), stroke)
                        renderer.draw(cpu.canvas.asComposeCanvas(), stroke)
                        gpu.flushAndSubmit(true)
                        gpu.makeImageSnapshot().use { actual -> cpu.makeImageSnapshot().use { expected ->
                            Bitmap.makeFromImage(actual, context).use { a -> Bitmap.makeFromImage(expected).use { b ->
                                for (x in 24..104) {
                                    val ac = a.getColor(x, 60); val bc = b.getColor(x, 60)
                                    assertTrue(ac ushr 24 > 0, "GPU mesh missing at $x")
                                    // getColor unpremultiplies: compare stored channels so low opacity
                                    // does not amplify the GPU's half-float/8-bit rounding.
                                    for (shift in listOf(24, 16, 8, 0)) {
                                        val av = if (shift == 24) ac ushr 24 else ((ac ushr shift) and 255) * (ac ushr 24) / 255
                                        val bv = if (shift == 24) bc ushr 24 else ((bc ushr shift) and 255) * (bc ushr 24) / 255
                                        assertTrue(kotlin.math.abs(av - bv) <= 2,
                                            "GPU/CPU channel $shift at $x: ${ac.toUInt().toString(16)} vs ${bc.toUInt().toString(16)}")
                                    }
                                }
                            } }
                        } }
                    } }
                } }
            }
        } }
    }
}
