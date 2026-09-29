package com.vivenotes.byteink.testing

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkCodec
import java.io.File
import kotlin.math.sin

/** Basic renderer coverage; all coordinates are generated, with no personal notebook data. */
internal fun androidRenderingFixture(directory: File): File {
    directory.mkdirs()
    val families = listOf(ViveBrushes.MARKER, ViveBrushes.DASHED_LINE, ViveBrushes.HIGHLIGHTER,
        ViveBrushes.PRESSURE_PEN) + (0..5).map(ViveBrushes::calligraphy)
    val base = syntheticNotebook(directory, emptyPages = families)
    val rows = families.flatMap { family ->
        listOf(false, true).mapIndexed { seq, translucent ->
            val inputs = MutableStrokeInputBatch().apply {
                repeat(97) { i ->
                    val angle = i * (2.0 * Math.PI / 96)
                    add(InputToolType.STYLUS, 100f + 70f * sin(angle).toFloat(),
                        70f + seq * 110f + 35f * sin(2 * angle).toFloat(), i * 8L,
                        pressure = 0.15f + 0.8f * i / 96f, tiltRadians = 0.3f, orientationRadians = 0.4f)
                }
            }
            val color = if (translucent) 0x806600ff.toInt() else 0xff000000.toInt()
            val stroke = Stroke(ViveBrushes.brush(family, 0, color, 12f), inputs.toImmutable())
            ViveInkCodec.encodeStroke(stroke, "$family-$seq", family, seq, family, 0, false, 100L)
        }
    }
    return File(directory, "brushes.vive").also { destination ->
        ViveNotebook.open(base).use { it.writeCopyWithStrokes(destination, rows) }
    }
}
