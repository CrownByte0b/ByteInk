package com.vivenotes.byteink.kit

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Public call shapes and semantics from the Android app's ink/InkCodec.kt. */
class AndroidCodecApiTest {
    @Test
    fun presetDefaultsAndCodecVersionMatchAndroid() {
        val pen = PenPreset()
        assertEquals(PenKind.Fountain, pen.kind)
        assertEquals(LineType.Solid, pen.lineType)
        assertEquals(0xFF000000.toInt(), pen.colorArgb)
        assertEquals(false, pen.colorFollowsTheme)
        assertEquals(1.5f, pen.thickness)
        assertEquals(3, pen.pressure)
        assertEquals(1, pen.stabilization)
        assertEquals(true, pen.holdForStraightLine)
        assertEquals(HighlighterSettings(0x66FFEB3B, 18), HighlighterSettings())
        assertEquals(1, ViveInkCodec.BRUSH_VERSION)
        assertEquals(ViveBrushes.BRUSH_VERSION, ViveInkCodec.BRUSH_VERSION)
    }

    @Test
    fun eraseMaskPreservesUnquantizedInputsAndUsesTheAndroidMarkerBrush() {
        val inputs = inputs()
        val mask: Stroke = ViveInkCodec.eraseMask(inputs = inputs, sizeDp = 23f)

        assertEquals(StockBrushes.marker(StockBrushes.MarkerVersion.V1), mask.brush.family)
        assertEquals(0xFF000000.toInt(), mask.brush.colorIntArgb)
        assertEquals(23f, mask.brush.size)
        assertEquals(0.25f, mask.brush.epsilon)
        assertEquals(inputs.size, mask.inputs.size)
        repeat(inputs.size) { assertEquals(inputs[it], mask.inputs[it]) }
        val storedMask = assertNotNull(ViveInkCodec.reloadedEraseMask(inputs, 23f))
        assertTrue((0 until inputs.size).any { inputs[it].x != storedMask.inputs[it].x },
            "Fixture must distinguish direct construction from a quantized storage round trip")
    }

    @Test
    fun eraseMaskSupportsEmptyInputsAndRejectsInvalidSizesDirectly() {
        val empty = ViveInkCodec.eraseMask(MutableStrokeInputBatch(), 12f)
        assertEquals(0, empty.inputs.size)
        assertNull(empty.shape.computeBoundingBox())
        for (size in listOf(-1f, 0f, 0.1f, Float.NaN, Float.POSITIVE_INFINITY)) {
            assertFailsWith<IllegalArgumentException>("size=$size") {
                ViveInkCodec.eraseMask(inputs(), size)
            }
        }
    }

    @Test
    fun penEncodingAndBrushSelectionMatchAndroidForEveryKindAndLineType() {
        for (kind in PenKind.entries) for (lineType in LineType.entries) {
            for (pressure in listOf(-1, 0, 3, 5, 9)) {
                val pen = PenPreset(kind, lineType, 0xFF1E88E5.toInt(), true, 4.5f, pressure, 4)
                val family = when {
                    lineType != LineType.Solid -> "dashed-line"
                    kind == PenKind.Fountain -> "marker"
                    else -> "calligraphy-v1-p${pressure.coerceIn(0, 5)}"
                }
                val stroke = Stroke(ViveInkCodec.brushFor(pen), inputs())
                val row = ViveInkCodec.encode(stroke = stroke, pageId = "page", seq = 7, pen = pen, now = 42L)

                assertEquals(family, row.brushFamily)
                assertEquals(ViveInkCodec.encodeStroke(stroke, row.id, "page", 7, family, 4, true, 42L), row)
                assertEquals(ViveBrushes.brush(family, 4, pen.colorArgb, pen.thickness), stroke.brush)
                assertEquals(stroke.brush, assertNotNull(ViveInkCodec.decode(row)).brush)
                assertNull(row.groupId)
                assertNull(row.deletedAt)
            }
        }
    }

    @Test
    fun encodeRecordsPresetIntentAndActualStrokeStyleAsAndroidDoes() {
        val pen = PenPreset(kind = PenKind.Calligraphy, pressure = 4, stabilization = 3, colorFollowsTheme = true)
        // Android records the stroke's actual size/color/epsilon, even if the supplied preset differs.
        val brush = Brush.createWithColorIntArgb(ViveBrushes.family("calligraphy-v1-p4", 3), 0xFF123456.toInt(), 8f, 0.125f)
        val stroke = Stroke(brush, inputs())
        val row = ViveInkCodec.encode(stroke, "page", 9, pen, 84L)
        val bounds = assertNotNull(stroke.shape.computeBoundingBox())

        assertEquals("calligraphy-v1-p4", row.brushFamily)
        assertEquals(3, row.stabilization)
        assertEquals(true, row.colorFollowsTheme)
        assertEquals(8f, row.sizeDp)
        assertEquals(0xFF123456.toInt(), row.colorArgb)
        assertEquals(0.125f, row.epsilon)
        assertEquals(listOf(bounds.xMin, bounds.yMin, bounds.xMax, bounds.yMax),
            listOf(row.minX, row.minY, row.maxX, row.maxY))
    }

    @Test
    fun stabilizationIsClampedForDrawingAndPreservedInStorage() {
        for (level in listOf(-2, 0, 1, 5, 12)) {
            val pen = PenPreset(stabilization = level)
            val stroke = Stroke(ViveInkCodec.brushFor(pen), inputs())
            val row = ViveInkCodec.encode(stroke, "page", 0, pen, 1L)
            assertEquals(level, row.stabilization)
            assertEquals(ViveBrushes.inputModelFor(level.coerceIn(0, 5)), stroke.brush.family.inputModel)
            assertEquals(stroke.brush, assertNotNull(ViveInkCodec.decode(row)).brush)
        }
    }

    @Test
    fun encodeDefaultsTheClockAndGeneratesDistinctAndroidUuidV7Ids() {
        val pen = PenPreset()
        val stroke = Stroke(ViveInkCodec.brushFor(pen), inputs())
        val before = System.currentTimeMillis()
        val row = ViveInkCodec.encode(stroke, "page", 2, pen)
        val another = ViveInkCodec.encode(stroke, "page", 3, pen, now = 42L)
        val after = System.currentTimeMillis()

        assertTrue(row.createdAt in before..after)
        assertEquals(42L, another.createdAt)
        assertEquals(2, setOf(row.id, another.id).size)
        for (id in listOf(row.id, another.id)) {
            val uuid = UUID.fromString(id)
            assertEquals(7, uuid.version())
            assertEquals(2, uuid.variant())
            assertTrue((uuid.mostSignificantBits ushr 16) in before..after)
        }
    }

    @Test
    fun emptyPenStrokeEncodesWithZeroBounds() {
        val pen = PenPreset()
        val stroke = Stroke(ViveInkCodec.brushFor(pen), MutableStrokeInputBatch())
        val row = ViveInkCodec.encode(stroke, "page", 0, pen, 1L)

        assertEquals(listOf(0f, 0f, 0f, 0f), listOf(row.minX, row.minY, row.maxX, row.maxY))
        assertEquals(0, assertNotNull(ViveInkCodec.decode(row)).inputs.size)
    }

    @Test
    fun highlighterOverloadPreservesAlphaAndUsesFixedMetadata() {
        val highlighter = HighlighterSettings(colorArgb = 0x6676FF03, thickness = 24)
        val stroke = Stroke(ViveInkCodec.brushFor(highlighter = highlighter), inputs())
        val row = ViveInkCodec.encode(stroke = stroke, pageId = "page", seq = 4, highlighter = highlighter, now = 42L)

        assertEquals(0x6676FF03, stroke.brush.colorIntArgb)
        assertEquals(24f, stroke.brush.size)
        assertEquals(SelfOverlap.DISCARD, stroke.brush.family.coats.single().paintPreferences.first().selfOverlap)
        assertEquals("highlighter", row.brushFamily)
        assertEquals(0, row.stabilization)
        assertEquals(false, row.colorFollowsTheme)
        assertEquals(ViveInkCodec.encodeHighlighter(stroke, row.id, "page", 4, 42L), row)
        assertEquals(stroke.brush, assertNotNull(ViveInkCodec.decode(row)).brush)
        val before = System.currentTimeMillis()
        val defaultClock = ViveInkCodec.encode(stroke, "page", 5, highlighter)
        assertTrue(defaultClock.createdAt in before..System.currentTimeMillis())
    }

    private fun inputs() = MutableStrokeInputBatch().apply {
        add(InputToolType.STYLUS, 0.1234567f, 10.234567f, 0L, pressure = 0.31f)
        add(InputToolType.STYLUS, 10.765432f, 15.123456f, 10L, pressure = 0.67f)
        add(InputToolType.STYLUS, 100.4321f, 20.876543f, 20L, pressure = 0.89f)
    }.toImmutable()
}
