@file:OptIn(androidx.ink.brush.ExperimentalInkCustomBrushApi::class,
    androidx.ink.nativeloader.InkInternalOnlyApi::class)

package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.asComposeCanvas
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
import androidx.ink.brush.color.Color as InkColor
import androidx.ink.brush.color.colorspace.ColorSpaces
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.core.StrokeMesh
import com.vivenotes.byteink.kit.ViveBrushes
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout
import java.nio.file.Files
import java.nio.file.Path
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorSpace
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.DirectContext
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Pixmap
import org.jetbrains.skia.Surface
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Explicit opt-in: a missing EGL driver is a failed investigation, never an assumed/skipped pass. */
class InkMeshEglTest {
    private var rendererIdentity = ""
    private fun requireEgl() {
        assertTrue(java.lang.Boolean.getBoolean("byteink.test.egl"), "Run :byteink-compose:meshEglTest")
        assertTrue(System.getenv("DISPLAY").isNullOrEmpty(), "The EGL suite must not have an X display")
    }

    private fun describe(egl: HeadlessEglContext) {
        val identity = listOf(egl.renderer(), egl.vendor(), egl.version(), egl.eglVendor(), egl.eglVersion())
        rendererIdentity = identity[0]
        assertTrue(identity.all { it.isNotBlank() && it != "unknown" }, "EGL and GL must report their actual identities")
        println("Surfaceless EGL: renderer=${identity[0]}, vendor=${identity[1]}, GL=${identity[2]}, " +
            "EGL vendor=${identity[3]}, EGL=${identity[4]}, software=${egl.isSoftwareRenderer()}")
        if (java.lang.Boolean.getBoolean("byteink.test.eglHardware")) {
            assertFalse(egl.isSoftwareRenderer(), "This run requires a hardware renderer; software Mesa is not hardware evidence")
        }
    }

    private fun newEgl(): HeadlessEglContext {
        val egl = HeadlessEglContext()
        try { describe(egl); return egl }
        catch (failure: Throwable) {
            try { egl.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun info(width: Int, height: Int) = ImageInfo(width, height, ColorType.N32, ColorAlphaType.PREMUL, ColorSpace.sRGB)
    private fun raw(surface: Surface): ByteArray = Bitmap().use { bitmap ->
        assertTrue(bitmap.allocPixels(surface.imageInfo))
        assertTrue(surface.readPixels(bitmap, 0, 0), "Read actual render-target pixels")
        requireNotNull(bitmap.readPixels(bitmap.imageInfo, bitmap.rowBytes, 0, 0))
    }

    private data class Frame(val gpu: ByteArray, val cpu: ByteArray, val width: Int, val height: Int)

    private fun pair(context: DirectContext, width: Int = 256, height: Int = 192,
        scale: Float = 1f, label: String, draw: (Surface) -> Unit): Frame {
        val imageInfo = info(width, height)
        return Surface.makeRenderTarget(context, false, imageInfo).use { gpu ->
            Surface.makeRaster(imageInfo).use { cpu ->
                // Skiko 0.150.1 wraps the borrowed recordingContext in an owning DirectContext;
                // its cleaner can free the live context. Probe direct pixel access instead.
                Pixmap().use { pixels ->
                    assertFalse(gpu.peekPixels(pixels), "The EGL control draws into a GPU surface without direct CPU pixels")
                    assertTrue(cpu.peekPixels(pixels), "The paired reference exposes its software raster pixels")
                }
                listOf(gpu, cpu).forEach { surface ->
                    surface.canvas.clear(0)
                    surface.canvas.scale(scale, scale)
                    draw(surface)
                }
                gpu.flushAndSubmit(true)
                Frame(raw(gpu), raw(cpu), width, height).also { compare(it, label) }
            }
        }
    }

    private fun value(bytes: ByteArray, pixel: Int, channel: Int) = bytes[pixel * 4 + channel].toInt() and 255

    private fun flat(bytes: ByteArray, pixel: Int, width: Int, height: Int): Boolean {
        val x = pixel % width
        val y = pixel / width
        return value(bytes, pixel, 3) > 4 && x in 1 until width - 1 && y in 1 until height - 1 &&
            (y - 1..y + 1).all { ny -> (x - 1..x + 1).all { nx ->
                (0..3).all { channel -> abs(value(bytes, ny * width + nx, channel) - value(bytes, pixel, channel)) <= 1 }
            } }
    }

    private fun artifacts(frame: Frame, label: String, metrics: String) {
        val directory = Path.of(System.getProperty("byteink.test.eglArtifacts"))
        Files.createDirectories(directory)
        val name = label.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        Files.writeString(directory.resolve("$name.json"), metrics)
        for ((kind, bytes) in listOf("actual" to frame.gpu, "reference" to frame.cpu)) {
            Files.write(directory.resolve("$name-$kind.n32"), bytes)
            Bitmap().use { bitmap ->
                assertTrue(bitmap.installPixels(info(frame.width, frame.height), bytes, frame.width * 4))
                Image.makeFromBitmap(bitmap).use { image ->
                    requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                        Files.write(directory.resolve("$name-$kind.png"), data.bytes)
                    }
                }
            }
        }
    }

    private fun json(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun isolatedZeros(bytes: ByteArray, width: Int, height: Int): List<Int> {
        val result = mutableListOf<Int>()
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val pixel = y * width + x
            if (value(bytes, pixel, 3) != 0) continue
            if (value(bytes, (y - 1) * width + x - 1, 3) <= 4) continue
            val neighbors = (y - 1..y + 1).flatMap { ny -> (x - 1..x + 1).map { nx -> ny * width + nx } }.filter { it != pixel }
            if (neighbors.all { value(bytes, it, 3) > 4 } && (0..3).all { channel ->
                    neighbors.maxOf { value(bytes, it, channel) } - neighbors.minOf { value(bytes, it, channel) } <= 1
                }) result.add(pixel)
        }
        return result
    }

    /** Owned native/prepared snapshots support an offline investigation of the isolated pixel. */
    private fun meshProbe(stroke: Stroke) {
        val probeX = 176.5
        val probeY = 77.5
        val linearColor = InkColor(stroke.brush.colorLong.toULong()).convert(ColorSpaces.LinearExtendedSrgb)
        val color = MeshColor(linearColor.red, linearColor.green, linearColor.blue, linearColor.alpha)
        val partitions = InkMeshes.rendering(stroke.shape, 0).mapIndexed { partition, mesh ->
            val chunks = prepareMesh(mesh, MeshLinearTransform(1f, 0f, 0f, 1f), color, null)
            try {
                val prepared = chunks.mapIndexed { chunkIndex, chunk ->
                    val positions = chunk.vertices.positions
                    val nearby = (0 until positions.size / 6).mapNotNull { triangle ->
                        val start = triangle * 6
                        val xy = DoubleArray(6) { positions[start + it].toDouble() }
                        if (probeX < minOf(xy[0], xy[2], xy[4]) - 1 || probeX > maxOf(xy[0], xy[2], xy[4]) + 1 ||
                            probeY < minOf(xy[1], xy[3], xy[5]) - 1 || probeY > maxOf(xy[1], xy[3], xy[5]) + 1) null
                        else {
                            val area = (xy[2] - xy[0]) * (xy[5] - xy[1]) - (xy[3] - xy[1]) * (xy[4] - xy[0])
                            val weights = if (area == 0.0) null else {
                                val b = ((probeX - xy[0]) * (xy[5] - xy[1]) - (probeY - xy[1]) * (xy[4] - xy[0])) / area
                                val c = ((xy[2] - xy[0]) * (probeY - xy[1]) - (xy[3] - xy[1]) * (probeX - xy[0])) / area
                                doubleArrayOf(1.0 - b - c, b, c)
                            }
                            val triangleIndex = chunkIndex * MESH_TRIANGLES_PER_DRAW + triangle
                            val nativeIndices = mesh.triangles.sliceArray(triangleIndex * 3 until triangleIndex * 3 + 3)
                            val nativePositions = nativeIndices.flatMap { index ->
                                listOf(mesh.vertices[index * StrokeMesh.VERTEX_STRIDE], mesh.vertices[index * StrokeMesh.VERTEX_STRIDE + 1])
                            }
                            "{\"triangle\":$triangleIndex,\"native_indices\":[${nativeIndices.joinToString(",")}]," +
                                "\"native_xy\":[${nativePositions.joinToString(",")}],\"prepared_xy\":[${xy.joinToString(",")}]," +
                                "\"signed_area\":$area,\"barycentric_weights_double\":${weights?.joinToString(",", "[", "]") ?: "null"}," +
                                "\"inside_prepared_triangle\":${weights?.all { it >= 0.0 } ?: false}}"
                        }
                    }
                    "{\"chunk\":$chunkIndex,\"triangle_count\":${positions.size / 6}," +
                        "\"position_float_bits\":[${positions.joinToString(",") { it.toRawBits().toString() }}]," +
                        "\"varying_float_bits\":[${chunk.varyings.joinToString(",") { it.toRawBits().toString() }}]," +
                        "\"triangles_near_probe\":[${nearby.joinToString(",")}]}"
                }
                "{\"partition\":$partition,\"attribute_mask\":${mesh.attributeMask},\"vertex_stride\":${StrokeMesh.VERTEX_STRIDE}," +
                    "\"native_vertex_float_bits\":[${mesh.vertices.joinToString(",") { it.toRawBits().toString() }}]," +
                    "\"native_triangle_indices\":[${mesh.triangles.joinToString(",")}],\"prepared_chunks\":[${prepared.joinToString(",")}] }"
            } finally { chunks.forEach { it.close() } }
        }
        val directory = Path.of(System.getProperty("byteink.test.eglArtifacts"))
        Files.createDirectories(directory)
        Files.writeString(directory.resolve("textured-mesh-probe-geometry.json"),
            "{\"probe_pixel_center\":[$probeX,$probeY],\"linear_transform\":[1,0,0,1]," +
                "\"mesh_color_linear_rgba\":[${color.r},${color.g},${color.b},${color.a}],\"partitions\":[${partitions.joinToString(",")}]}")
    }

    private fun compare(frame: Frame, label: String, agreementRequired: Boolean = true) {
        val (gpu, cpu, width, height) = frame
        assertEquals(width * height * 4, gpu.size, label)
        assertEquals(cpu.size, gpu.size, label)
        var visible = 0
        var flatPixels = 0
        var sum = 0L
        var maximum = 0
        var maximumPixel = -1
        var maximumChannel = -1
        var changedPixels = 0
        var overTwoPixels = 0
        var flatMaximum = 0
        var flatViolations = 0
        var actualFlatPixels = 0
        var actualFlatMaximum = 0
        var actualFlatViolations = 0
        var haloViolations = 0
        var premulViolations = 0
        val histogram = IntArray(256)
        val largest = mutableListOf<Triple<Int, Int, Int>>()
        fun nearInk(bytes: ByteArray, x: Int, y: Int): Boolean =
            (maxOf(0, y - 1)..minOf(height - 1, y + 1)).any { ny ->
                (maxOf(0, x - 1)..minOf(width - 1, x + 1)).any { nx -> value(bytes, ny * width + nx, 3) > 0 }
            }
        for (pixel in 0 until width * height) {
            val x = pixel % width
            val y = pixel / width
            val aa = value(gpu, pixel, 3)
            val ba = value(cpu, pixel, 3)
            for (channel in 0..2) {
                if (value(gpu, pixel, channel) > aa + 1) premulViolations++
            }
            if (aa == 0 && ba == 0) continue
            visible++
            if (aa > 4 && !nearInk(cpu, x, y)) haloViolations++
            if (ba > 4 && !nearInk(gpu, x, y)) haloViolations++
            val flatCpu = flat(cpu, pixel, width, height)
            if (flatCpu) flatPixels++
            val flatGpu = flat(gpu, pixel, width, height)
            if (flatGpu) actualFlatPixels++
            var pixelMaximum = 0
            for (channel in 0..3) {
                val difference = abs(value(gpu, pixel, channel) - value(cpu, pixel, channel))
                sum += difference
                histogram[difference]++
                pixelMaximum = maxOf(pixelMaximum, difference)
                if (difference > maximum) {
                    maximum = difference
                    maximumPixel = pixel
                    maximumChannel = channel
                }
                if (difference > 2) largest.add(Triple(difference, pixel, channel))
                // This is the established GPU tolerance for stored (premultiplied) interior channels.
                if (flatCpu) {
                    flatMaximum = maxOf(flatMaximum, difference)
                    if (difference > 2) flatViolations++
                }
                if (flatGpu) {
                    actualFlatMaximum = maxOf(actualFlatMaximum, difference)
                    if (difference > 2) actualFlatViolations++
                }
            }
            if (pixelMaximum > 0) changedPixels++
            if (pixelMaximum > 2) overTwoPixels++
        }
        // Diagnose the entire image, including AA and isolated disagreements surrounded by paint.
        // A local flat-reference detector excludes reference holes; report both directions so it
        // cannot be mistaken for a proof of agreement over every geometric stroke interior.
        // The touched-pixel average is not diluted by blank pixels.
        val mean = sum.toDouble() / (visible * 4)
        val promotion = maximum <= 64
        val symmetricFlat = flatViolations == 0 && actualFlatViolations == 0
        fun zeroCoordinates(bytes: ByteArray) = isolatedZeros(bytes, width, height).joinToString(",") { pixel ->
            "{\"x\":${pixel % width},\"y\":${pixel / width}}"
        }
        val worst = largest.sortedByDescending { it.first }.take(32).joinToString(",") { (delta, pixel, channel) ->
            "{\"x\":${pixel % width},\"y\":${pixel / width},\"channel\":$channel,\"delta\":$delta," +
                "\"actual\":${value(gpu, pixel, channel)},\"reference\":${value(cpu, pixel, channel)}," +
                "\"flat_reference\":${flat(cpu, pixel, width, height)}}"
        }
        artifacts(frame, label, """{
          "label":${json(label)},"renderer":${json(rendererIdentity)},"width":$width,"height":$height,
          "agreement_required":$agreementRequired,"touched_pixels":$visible,"flat_pixels":$flatPixels,
          "changed_pixels":$changedPixels,"pixels_over_2":$overTwoPixels,
          "premul_mae_255":$mean,"max_channel_255":$maximum,
          "maximum_x":${maximumPixel % width},"maximum_y":${maximumPixel / width},"maximum_channel":$maximumChannel,
          "flat_reference_max_channel_255":$flatMaximum,"flat_reference_channel_violations":$flatViolations,
          "flat_actual_pixels":$actualFlatPixels,"flat_actual_max_channel_255":$actualFlatMaximum,
          "flat_actual_channel_violations":$actualFlatViolations,"symmetric_flat_2_pass":$symmetricFlat,
          "one_pixel_halo_violations":$haloViolations,"premul_channel_violations":$premulViolations,
          "candidate_promotion_max_channel_64_pass":$promotion,
          "isolated_zero_reference_pixels":[${zeroCoordinates(cpu)}],"isolated_zero_actual_pixels":[${zeroCoordinates(gpu)}],
          "channel_delta_histogram":[${histogram.joinToString(",")}],"worst_channels":[$worst]
        }
        """)
        println("$label: touched=$visible, flat=$flatPixels, premul MAE=$mean/255, max=$maximum/255, " +
            "pixels>2=$overTwoPixels, candidate whole-image64=$promotion, symmetricFlat2=$symmetricFlat, agreementRequired=$agreementRequired")
        assertTrue(visible > 64 && flatPixels > 16, "$label must exercise visible mesh interiors")
        assertEquals(0, premulViolations, "$label: GPU premultiplied color")
        if (agreementRequired) {
            assertEquals(0, haloViolations, "$label: GPU/CPU support differs beyond the one-pixel contour halo")
            assertEquals(0, flatViolations, "$label: locally flat reference premultiplied pixels exceed the established 2/255 GPU tolerance")
            assertTrue(mean <= 3.0, "$label: touched-pixel premultiplied MAE $mean exceeds 3/255")
            // Keep the original proposed promotion limit observable and opt-in. This investigation
            // suite does not grant production approval when the stricter whole-image gate fails.
            if (java.lang.Boolean.getBoolean("byteink.test.eglPromotionCheck")) {
                assertTrue(promotion, "$label: candidate promotion whole-image channel difference $maximum exceeds 64/255")
                assertTrue(symmetricFlat, "$label: candidate promotion symmetric locally flat agreement exceeds 2/255")
            }
        }
    }

    private fun inputs(count: Int = 80, wave: Boolean = false) = MutableStrokeInputBatch().apply {
        repeat(count) { i -> add(InputToolType.STYLUS, 16f + i * 2.5f,
            64f + (if (wave) 14f * sin(i * .3f) else 0f), i * 4L, pressure = .7f) }
    }

    @Test fun explicitNoXContextRendersTexturedTranslucentMeshAcrossMultipleChunks() {
        requireEgl()
        newEgl().use { egl ->
            egl.makeSkiaContext().use { context ->
                Surface.makeRasterN32Premul(2, 1).use { texture ->
                    texture.canvas.clear(0x90ff8040.toInt())
                    texture.makeImageSnapshot().use { image ->
                        InkMeshRenderer(InkTextureStore { image }).use { renderer ->
                            val behavior = BrushBehavior(TargetNode(TargetNode.Target.OPACITY_MULTIPLIER, .2f, 1f,
                                SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)))
                            val family = BrushFamily(BrushTip(behaviors = listOf(behavior)),
                                BrushPaint(listOf(BrushPaint.TilingTexture("egl-test", 20f, 20f))))
                            val brush = Brush.createWithColorIntArgb(family, 0xb04070a0.toInt(), 20f, .05f)
                            val stroke = Stroke(brush, inputs(wave = true))
                            assertTrue(InkMeshes.rendering(stroke.shape, 0).sumOf { it.triangleCount } > 32,
                                "The fixture must cross several sixteen-triangle shader chunks")
                            meshProbe(stroke)
                            pair(context, label = "textured translucent multi-chunk mesh") {
                                assertTrue(renderer.draw(it.canvas.asComposeCanvas(), stroke))
                            }
                            assertTrue(renderer.shaderBuildCount > 2)
                        }
                    }
                }
            }
        }
    }

    @Test fun overlapModesAndMultipleCoatsKeepPremultipliedAlpha() {
        requireEgl()
        newEgl().use { egl -> egl.makeSkiaContext().use { context ->
            val overlapping = MutableStrokeInputBatch().apply {
                repeat(4) { lap -> repeat(48) { i ->
                    val x = if (lap % 2 == 0) 20f + i * 4f else 208f - i * 4f
                    add(InputToolType.STYLUS, x, 56f + lap * 2f, (lap * 48L + i) * 4L, pressure = .7f)
                } }
            }
            for (overlap in listOf(SelfOverlap.ANY, SelfOverlap.ACCUMULATE, SelfOverlap.DISCARD)) {
                val family = BrushFamily(listOf(
                    BrushCoat(BrushTip(scaleX = 1.2f, scaleY = .8f), listOf(BrushPaint(selfOverlap = overlap))),
                    BrushCoat(BrushTip(scaleX = .7f, scaleY = 1.3f), listOf(BrushPaint(selfOverlap = overlap))),
                ))
                val brush = Brush.createWithColorIntArgb(family, 0x702060c0, 18f, .05f)
                InkMeshRenderer().use { renderer ->
                    val stroke = Stroke(brush, overlapping)
                    pair(context, label = "two translucent coats $overlap") { renderer.draw(it.canvas.asComposeCanvas(), stroke) }
                }
            }
        } }
    }

    @Test fun livePredictionReplacementShrinkAndExpiryRetractOldPixels() {
        requireEgl()
        newEgl().use { egl -> egl.makeSkiaContext().use { context ->
            InkAuthoringSession(predictorFactory = null).use { session -> InkMeshRenderer().use { renderer ->
                val brush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xb02060c0.toInt(), 14f)
                fun sample(x: Float, y: Float, time: Long) = InkPointerSample(x, y, time, InputToolType.STYLUS, .7f)
                fun send(event: InkInputEvent) = session.handle(event, brush) { _, _ -> }
                send(InkInputEvent.Begin(sample(16f, 64f, 1000L)))
                send(InkInputEvent.Batch((1..50).map { sample(16f + it * 2.5f, 64f, 1000L + it) },
                    predictedSamples = listOf(sample(190f, 64f, 1080L), sample(224f, 64f, 1090L))))
                session.advance(1050L)
                val live = session.liveStrokes.single().stroke
                fun draw(label: String) = pair(context, label = label) { renderer.draw(it.canvas.asComposeCanvas(), live) }.gpu
                val horizontal = draw("live horizontal prediction")
                assertTrue((170..220).any { value(horizontal, 64 * 256 + it, 3) > 4 })
                send(InkInputEvent.Predict(listOf(sample(141f, 110f, 1080L), sample(141f, 150f, 1090L))))
                session.advance(1051L)
                val replacement = draw("prediction replacement")
                assertFalse(horizontal.contentEquals(replacement))
                assertTrue((176..232).all { value(replacement, 64 * 256 + it, 3) == 0 }, "Old horizontal prediction must disappear")
                send(InkInputEvent.Predict(listOf(sample(141f, 88f, 1060L))))
                session.advance(1052L)
                val shortened = draw("prediction shrink")
                assertTrue((120..160).all { value(shortened, it * 256 + 141, 3) == 0 }, "Old long vertical prediction must disappear")
                session.advance(1060L)
                assertEquals(0, live.getPredictedInputCount())
                val expired = draw("prediction expiry")
                assertFalse(shortened.contentEquals(expired))
                assertEquals(51, live.getRealInputCount())
                send(InkInputEvent.Cancel)
                renderer.releaseLiveStroke(live)
                assertEquals(0L, renderer.cachedLiveGeometryBytes)
                Surface.makeRenderTarget(context, false, info(256, 192)).use { blank ->
                    blank.canvas.clear(0)
                    assertFalse(renderer.draw(blank.canvas.asComposeCanvas(), live), "Canceled engine cannot redraw stale GPU geometry")
                    blank.flushAndSubmit(true)
                    assertTrue(raw(blank).all { it == 0.toByte() }, "Cancellation restores a transparent destination")
                }
            } }
        } }
    }

    @Test fun settledLiveToFinishedHandoffAndScaleResizeKeepGpuOutput() {
        requireEgl()
        newEgl().use { egl -> egl.makeSkiaContext().use { context -> InkMeshRenderer().use { renderer ->
            val live = InProgressStroke()
            try {
                live.start(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x802060c0.toInt(), 16f))
                live.enqueueInputs(inputs(wave = true), MutableStrokeInputBatch())
                live.finishInput(); live.updateShape(400L)
                val dry = live.toImmutable()
                for ((width, height, scale) in listOf(Triple(256, 192, 1f), Triple(512, 384, 2f), Triple(320, 224, 1f))) {
                    val wet = pair(context, width, height, scale, "settled wet ${width}x$height scale$scale") {
                        renderer.draw(it.canvas.asComposeCanvas(), live)
                    }
                    val finished = pair(context, width, height, scale, "finished ${width}x$height scale$scale") {
                        renderer.draw(it.canvas.asComposeCanvas(), dry)
                    }
                    // Finished mesh attributes are packed by the engine. Characterize that existing
                    // wet/dry difference on both backends; each geometry's GPU/CPU agreement was gated above.
                    compare(Frame(wet.gpu, finished.gpu, width, height), "GPU terminal wet-finished delta ${width}x$height scale$scale",
                        agreementRequired = false)
                    compare(Frame(wet.cpu, finished.cpu, width, height), "CPU terminal wet-finished delta ${width}x$height scale$scale",
                        agreementRequired = false)
                    var terminalInteriors = 0
                    for (pixel in 0 until width * height) {
                        if (flat(wet.cpu, pixel, width, height) && flat(finished.cpu, pixel, width, height)) {
                            terminalInteriors++
                            for (channel in 0..3) {
                                assertTrue(abs(value(wet.gpu, pixel, channel) - value(finished.gpu, pixel, channel)) <= 2,
                                    "Terminal GPU wet/dry interior channel at (${pixel % width},${pixel / width})")
                                assertTrue(abs(value(wet.cpu, pixel, channel) - value(finished.cpu, pixel, channel)) <= 2,
                                    "Terminal CPU wet/dry interior channel at (${pixel % width},${pixel / width})")
                            }
                        }
                    }
                    assertTrue(terminalInteriors > 16)
                }
                renderer.releaseLiveStroke(live)
                assertEquals(0L, renderer.cachedLiveGeometryBytes)
            } finally { live.clear() }
        } } }
    }

    private data class Binding(val api: Int, val context: Long, val display: Long, val draw: Long, val read: Long)
    private fun binding(selectedApi: Int? = null): Binding = Arena.ofConfined().use { arena ->
        val egl = SymbolLookup.libraryLookup("libEGL.so.1", arena)
        val linker = Linker.nativeLinker()
        fun pointer(name: String, selector: Int? = null): Long {
            val descriptor = if (selector == null) FunctionDescriptor.of(ValueLayout.ADDRESS)
                else FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)
            val method = linker.downcallHandle(egl.find(name).orElseThrow(), descriptor)
            return ((if (selector == null) method.invokeWithArguments() else method.invokeWithArguments(selector)) as MemorySegment).address()
        }
        val queryApi = linker.downcallHandle(egl.find("eglQueryAPI").orElseThrow(), FunctionDescriptor.of(ValueLayout.JAVA_INT))
        val bindApi = linker.downcallHandle(egl.find("eglBindAPI").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT))
        val previousApi = queryApi.invokeWithArguments() as Int
        try {
            if (selectedApi != null) assertNotEquals(0, bindApi.invokeWithArguments(selectedApi) as Int)
            Binding(queryApi.invokeWithArguments() as Int, pointer("eglGetCurrentContext"), pointer("eglGetCurrentDisplay"),
                pointer("eglGetCurrentSurface", 0x3059), pointer("eglGetCurrentSurface", 0x305a))
        } finally {
            if (selectedApi != null) assertNotEquals(0, bindApi.invokeWithArguments(previousApi) as Int)
        }
    }

    @Test fun releaseRebindNestedContextsAndRecreationRestoreCallerEglBinding() {
        requireEgl()
        val before = binding()
        val beforeDesktop = binding(0x30a2)
        val beforeEs = binding(0x30a0)
        assertEquals(0L, before.context, "JUnit thread starts with no foreign current EGL context")
        assertEquals(0, HeadlessEglContext.activeDisplayLeaseCount())
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x902060c0.toInt(), 16f), inputs())
        InkMeshRenderer().use { renderer ->
            var reference: ByteArray? = null
            repeat(3) { iteration ->
                val egl = newEgl()
                egl.use {
                    val outer = binding()
                    assertEquals(0x30a2, outer.api, "Desktop GL is selected while rendering")
                    assertNotEquals(0L, outer.context)
                    egl.makeSkiaContext().use { context ->
                        val pixels = pair(context, label = "context recreation $iteration") { renderer.draw(it.canvas.asComposeCanvas(), stroke) }.gpu
                        reference?.let { assertTrue(it.contentEquals(pixels), "Driver output is stable after complete context recreation") }
                        reference = pixels
                        egl.releaseCurrent()
                        assertEquals(before, binding(), "Release restores the API as well as both current surfaces")
                        assertEquals(beforeDesktop, binding(0x30a2), "No context/surface remains current when querying the desktop GL API")
                        assertEquals(beforeEs, binding(0x30a0), "No context/surface remains current when querying the ES API")
                        assertFailsWith<IllegalStateException> { egl.makeSkiaContext() }
                        egl.makeCurrent()
                        assertEquals(outer, binding())
                        newEgl().use { nested ->
                            assertNotEquals(outer.context, binding().context)
                            assertEquals(2, HeadlessEglContext.activeDisplayLeaseCount())
                            val inner = binding()
                            assertFailsWith<IllegalStateException> { egl.close() }
                            assertEquals(inner, binding(), "Rejected non-LIFO close leaves the nested binding intact")
                            assertEquals(2, HeadlessEglContext.activeDisplayLeaseCount(), "Rejected close leaves display leases intact")
                            nested.makeSkiaContext().use { other ->
                                pair(other, label = "nested independent EGL context $iteration") { renderer.draw(it.canvas.asComposeCanvas(), stroke) }
                            }
                        }
                        assertEquals(outer, binding(), "Closing inner context restores the live outer EGL context")
                        assertEquals(1, HeadlessEglContext.activeDisplayLeaseCount())
                        assertTrue(pair(context, label = "outer after nested cleanup $iteration") { renderer.draw(it.canvas.asComposeCanvas(), stroke) }.gpu.contentEquals(pixels))
                    }
                }
                egl.close()
                assertEquals(before, binding(), "Close restores caller API and unbinds desktop GL")
                assertEquals(beforeDesktop, binding(0x30a2))
                assertEquals(beforeEs, binding(0x30a0))
                assertEquals(0, HeadlessEglContext.activeDisplayLeaseCount())
                assertFailsWith<IllegalStateException> { egl.makeCurrent() }
                assertFailsWith<IllegalStateException> { egl.renderer() }
            }
        }
    }

    @Test fun failedNativeContextCreationReleasesLeasesAndLeavesSoftwareFallbackUsable() {
        requireEgl()
        val before = binding()
        val beforeDesktop = binding(0x30a2)
        val beforeEs = binding(0x30a0)
        repeat(4) {
            assertFailsWith<IllegalStateException> { HeadlessEglContext(99, 99) }
            assertEquals(before, binding(), "Failed native creation restores caller EGL API and context")
            assertEquals(beforeDesktop, binding(0x30a2))
            assertEquals(beforeEs, binding(0x30a0))
            assertEquals(0, HeadlessEglContext.activeDisplayLeaseCount(), "Failed creation releases its display lease")
        }
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x802060c0.toInt(), 16f), inputs())
        InkMeshRenderer().use { renderer ->
            Surface.makeRaster(info(256, 192)).use { fallback ->
                fallback.canvas.clear(0)
                assertTrue(renderer.draw(fallback.canvas.asComposeCanvas(), stroke))
                assertTrue(raw(fallback).any { it != 0.toByte() }, "Existing software mesh rendering works after EGL failure")
            }
            newEgl().use { egl -> egl.makeSkiaContext().use { context ->
                pair(context, label = "successful EGL after rejected context") { renderer.draw(it.canvas.asComposeCanvas(), stroke) }
            } }
        }
        assertEquals(before, binding())
        assertEquals(0, HeadlessEglContext.activeDisplayLeaseCount())
    }
}
