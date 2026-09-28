package com.vivenotes.byteink.core

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The pinned AndroidX Ink JVM artifacts run in this build, unchanged, on the native library
 * byteink's loader bundles for this platform.
 */
class UpstreamInkSmokeTest {

    @Test
    fun buildsAStrokeMeshWithTheNativeEngine() {
        val stroke = Stroke(brush = marker(size = 4f), inputs = horizontalLine())

        val bounds = assertNotNull(stroke.shape.computeBoundingBox())
        assertTrue(bounds.xMin <= 0f && bounds.xMax >= 30f, "x extent of $bounds")
        assertTrue(bounds.yMin < 10f && bounds.yMax > 10f, "y extent of $bounds")
        assertTrue(bounds.yMax - bounds.yMin in 2f..6f, "a 4-unit marker drew ${bounds.yMax - bounds.yMin} wide")
    }

    @Test
    fun storageRoundTripsAnInputBatchWithinItsQuantization() {
        val inputs = horizontalLine()

        val decoded = StrokeInputBatch.decode(inputs.encode())

        // The encoding is lossy by design: each axis is quantized to 4096 steps across the stroke's
        // extent (30 units here), and time to microseconds.
        val step = 30f / 4096
        assertEquals(inputs.size, decoded.size)
        repeat(inputs.size) { index ->
            val expected = inputs[index]
            val actual = decoded[index]
            assertEquals(expected.x, actual.x, absoluteTolerance = step)
            assertEquals(expected.y, actual.y, absoluteTolerance = step)
            assertEquals(expected.elapsedTimeMillis, actual.elapsedTimeMillis)
            assertEquals(expected.toolType, actual.toolType)
        }
    }

    private fun marker(size: Float): Brush = Brush.createWithColorIntArgb(
        family = StockBrushes.marker(StockBrushes.MarkerVersion.V1),
        colorIntArgb = 0xFF000000.toInt(),
        size = size,
        epsilon = 0.25f,
    )

    private fun horizontalLine(): StrokeInputBatch = MutableStrokeInputBatch().apply {
        for (step in 0..3) add(InputToolType.MOUSE, x = step * 10f, y = 10f, elapsedTimeMillis = step * 16L)
    }.toImmutable()
}
