package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class ViveInkToolTest {
    @Test
    fun finishedStrokeKeepsCatalogMetadataAndProducesReadableRows() {
        val families = listOf(ViveBrushes.MARKER, ViveBrushes.PRESSURE_PEN, ViveBrushes.DASHED_LINE) +
            (0..5).map(ViveBrushes::calligraphy)
        for (family in families) for (level in 0..5) {
            val tool = ViveInkTool(family, level, 0xff182f51.toInt(), 8f, true)
            val stroke = stroke(tool)
            val result = tool.complete(stroke, "new", "page", 7, 123L, "group")
            assertSame(stroke, result.stroke)
            assertEquals(family, result.row.brushFamily)
            assertEquals(level, result.row.stabilization)
            assertEquals(true, result.row.colorFollowsTheme)
            assertEquals("group", result.row.groupId)
            assertEquals(7, result.row.seq)
            val rebuilt = assertNotNull(ViveInkCodec.decode(result.row))
            assertEquals(tool.brush, rebuilt.brush)
            assertEquals(stroke.shape.computeBoundingBox(), rebuilt.shape.computeBoundingBox())
        }
    }

    @Test
    fun highlighterMetadataAndBrushAreCoupled() {
        val tool = ViveInkTool(ViveBrushes.HIGHLIGHTER, colorArgb = 0x80ffff00.toInt(), sizeDp = 16f)
        val row = tool.complete(stroke(tool), "h", "page", 0, 1L).row
        assertEquals(0, row.stabilization)
        assertEquals(false, row.colorFollowsTheme)
        assertNotNull(ViveInkCodec.decode(row))
        assertFailsWith<IllegalArgumentException> { ViveInkTool(ViveBrushes.HIGHLIGHTER, stabilization = 1) }
        assertFailsWith<IllegalArgumentException> { ViveInkTool(ViveBrushes.HIGHLIGHTER, colorFollowsTheme = true) }
    }

    @Test
    fun mismatchedToolsAndInvalidAuthoringSettingsAreRejected() {
        val tool = ViveInkTool()
        assertFailsWith<IllegalArgumentException> {
            ViveInkTool(colorArgb = 0xffff0000.toInt()).complete(stroke(tool), "s", "p", 0, 0)
        }
        assertFailsWith<IllegalArgumentException> { ViveInkTool("unknown") }
        assertFailsWith<IllegalArgumentException> { ViveInkTool(stabilization = 6) }
    }

    private fun stroke(tool: ViveInkTool): Stroke = Stroke(tool.brush, MutableStrokeInputBatch().apply {
        add(InputToolType.MOUSE, 10f, 20f, 0)
        add(InputToolType.MOUSE, 40f, 60f, 100)
        add(InputToolType.MOUSE, 90f, 30f, 200)
    }.toImmutable())
}
