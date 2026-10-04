package com.vivenotes.byteink.consumer

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkAuthoringController
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.nativeloader.LoadedInkLibrary
import com.vivenotes.byteink.kit.InkPageIndex
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import java.io.File
import java.util.Properties
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/** A separate application using only public APIs from ByteInk's dependency coordinates. */
fun main(args: Array<String>) {
    val reportDirectory = File(args.single()).apply { mkdirs() }
    val kitPackage = StoredInkStroke::class.java.packageName
    check(kitPackage == "com.vivenotes.byteink.kit") { "Wrong public kit namespace: $kitPackage" }
    val loaded = InkRuntime.load()
    check(loaded.origin == LoadedInkLibrary.Origin.BUNDLED) { "Expected the published native bundle: $loaded" }
    check(loaded.sha256.matches(Regex("[0-9a-f]{64}")))
    val expectedNativeName = if (System.getProperty("os.name").startsWith("Windows")) "ink.dll" else "libink.so"
    check(loaded.path.fileName.toString() == expectedNativeName) { "Wrong platform library: $loaded" }

    val renderer = InkPathRenderer()
    try {
        InkAuthoringController().use { controller ->
            val markerBrush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 12f)
            controller.begin(markerBrush, InkPointerSample(20f, 32f, 1000L))
            check(controller.append(InkPointerSample(64f, 32f, 1100L)))
            controller.advance(1100L)
            val livePixels = raster(reportDirectory.resolve("live.png")) { surface ->
                check(renderer.draw(surface.canvas.asComposeCanvas(), checkNotNull(controller.liveStroke)))
            }
            check(livePixels[32 * WIDTH + 48] == 0xff000000.toInt()) { "Published authoring/renderer drew no live marker" }
            val marker = checkNotNull(controller.finish(InkPointerSample(108f, 32f, 1200L)))
            val markerRow = ViveInkCodec.encodeStroke(
                marker, "marker", "page", 0, ViveBrushes.MARKER, 0, true, 1234L,
            )
            val restoredMarker = restore(markerRow, marker)
            val meshes = InkMeshes.triangles(restoredMarker.shape, 0)
            check(meshes.sumOf { it.triangleCount } > 0) { "Published engine generated no marker triangles" }

            controller.begin(ViveBrushes.highlighter(0x80ff0000.toInt(), 14f), InkPointerSample(20f, 64f, 2000L))
            controller.append(InkPointerSample(108f, 112f, 2100L))
            controller.append(InkPointerSample(20f, 112f, 2200L))
            controller.append(InkPointerSample(108f, 64f, 2300L))
            val highlighter = checkNotNull(controller.finish())
            val highlighterRow = ViveInkCodec.encodeHighlighter(highlighter, "highlighter", "page", 1, 1235L)
            val restoredHighlighter = restore(highlighterRow, highlighter)

            val page = listOf(PageStroke("marker", restoredMarker), PageStroke("highlighter", restoredHighlighter))
            val index = InkPageIndex(page)
            check(index.at(InkPoint(64f, 32f), 1f).map { it.id } == listOf("marker"))
            check(index.at(InkPoint(64f, 88f), 1f).map { it.id } == listOf("highlighter"))
            check(index.at(InkPoint(4f, 4f), 1f).isEmpty())

            val finished = raster(reportDirectory.resolve("finished.png")) { surface ->
                val canvas = surface.canvas.asComposeCanvas()
                check(renderer.draw(canvas, restoredMarker))
                check(renderer.draw(canvas, restoredHighlighter))
            }
            check(finished[32 * WIDTH + 64] == 0xff000000.toInt())
            check(finished[88 * WIDTH + 64] ushr 24 == 128) { "A self-crossing highlighter must fill once" }
            check(finished[4 * WIDTH + 4] == 0)
            val visible = finished.count { it ushr 24 > 0 }
            check(visible > 1000) { "Published Skia renderer generated too little visible ink: $visible pixels" }

            val report = Properties().apply {
                setProperty("status", "passed")
                setProperty("api.kit.package", kitPackage)
                setProperty("java.home", File(System.getProperty("java.home")).canonicalPath)
                setProperty("java.version", System.getProperty("java.version"))
                setProperty("java.vendor", System.getProperty("java.vendor"))
                setProperty("os.name", System.getProperty("os.name"))
                setProperty("native.name", expectedNativeName)
                setProperty("native.sha256", loaded.sha256)
                setProperty("native.origin", loaded.origin.name)
                setProperty("visible.pixels", visible.toString())
                setProperty("marker.triangles", meshes.sumOf { it.triangleCount }.toString())
                setProperty("codec.rows", "2")
                setProperty("hit.test.results", "marker,highlighter,empty")
                setProperty("loader.source", LoadedInkLibrary::class.java.protectionDomain.codeSource.location.toString())
            }
            reportDirectory.resolve("smoke.properties").outputStream().use { report.store(it, "ByteInk consumer smoke") }
            println("byteink-consumer-smoke: passed; ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}; $loaded; $visible pixels")
        }
    } finally {
        renderer.clearCache()
    }
}

private fun restore(row: StoredInkStroke, original: Stroke): Stroke {
    check(row.enc == ViveInkCodec.ENCODING && row.points.isNotEmpty())
    val restored = checkNotNull(ViveInkCodec.decode(row)) { "Published Vive codec could not decode its own row" }
    check(restored.inputs.size == original.inputs.size)
    check(restored.shape.computeBoundingBox() == original.shape.computeBoundingBox())
    return restored
}

private fun raster(output: File, draw: (Surface) -> Unit): List<Int> =
    Surface.makeRasterN32Premul(WIDTH, HEIGHT).use { surface ->
        surface.canvas.clear(0)
        draw(surface)
        surface.makeImageSnapshot().use { image ->
            image.encodeToData(EncodedImageFormat.PNG)!!.use { output.writeBytes(it.bytes) }
            Bitmap.makeFromImage(image).use { bitmap ->
                List(WIDTH * HEIGHT) { bitmap.getColor(it % WIDTH, it / WIDTH) }
            }
        }
    }

private const val WIDTH = 128
private const val HEIGHT = 128
