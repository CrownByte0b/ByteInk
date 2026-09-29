package com.vivenotes.byteink.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.vive.PageStroke
import com.vivenotes.byteink.vive.AUTOMATIC_DARK
import com.vivenotes.byteink.vive.automaticColorOr
import java.io.File
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.math.ceil

/** Offscreen ink-only page images for notebook proofs and renderer comparisons. */
public object NotebookInkImages {
    /**
     * Writes white paper with all [strokes] in draw order, fitted to their combined bounds with
     * 16 pixels of padding. [maxDimension] limits both image dimensions; empty pages give 32×32 PNGs.
     * Automatic ink resolves to black on this paper, using ViveNotes' stored theme flag; deliberate
     * colours keep their alpha. Coordinates remain page units until the one scale into image pixels. Callers provide Skiko's
     * native runtime, normally through Compose Desktop's currentOs dependency.
     */
    public fun writePng(
        strokes: List<PageStroke>,
        file: File,
        maxDimension: Int = 2048,
        renderer: InkPathRenderer = InkPathRenderer(),
    ): RasterPage {
        require(maxDimension > 32)
        val bounds = strokes.mapNotNull { it.pageBounds }
        val left = bounds.minOfOrNull { it.left } ?: 0f
        val top = bounds.minOfOrNull { it.top } ?: 0f
        val right = bounds.maxOfOrNull { it.right } ?: left
        val bottom = bounds.maxOfOrNull { it.bottom } ?: top
        val scale = minOf(1f, (maxDimension - 32) / maxOf(1f, right - left, bottom - top))
        val width = ceil((right - left) * scale).toInt() + 32
        val height = ceil((bottom - top) * scale).toInt() + 32
        var drawn = 0
        val start = System.nanoTime()
        Surface.makeRasterN32Premul(width, height).use { surface ->
            surface.canvas.clear(0xffffffff.toInt())
            val canvas = surface.canvas.asComposeCanvas()
            val viewport = Rect(0f, 0f, width.toFloat(), height.toFloat())
            strokes.forEach { pageStroke ->
                val transform = ImmutableAffineTransform(
                    scale * pageStroke.scaleX, 0f, 16f + scale * (pageStroke.offsetX - left),
                    0f, scale * pageStroke.scaleY, 16f + scale * (pageStroke.offsetY - top),
                )
                val color = automaticColorOr(pageStroke.stroke.brush.colorIntArgb, pageStroke.colorFollowsTheme, AUTOMATIC_DARK)
                if (renderer.draw(canvas, pageStroke.stroke, transform, viewport, color)) drawn++
            }
            surface.makeImageSnapshot().use { image ->
                requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                    file.absoluteFile.parentFile.mkdirs()
                    file.writeBytes(data.bytes)
                }
            }
        }
        return RasterPage(width, height, drawn, (System.nanoTime() - start) / 1e6)
    }
}

/** The image's dimensions, number of rendered projections, and elapsed rendering/PNG encoding time. */
public data class RasterPage(val width: Int, val height: Int, val drawn: Int, val elapsedMillis: Double)
