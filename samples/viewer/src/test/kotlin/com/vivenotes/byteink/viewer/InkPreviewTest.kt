package com.vivenotes.byteink.viewer

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.vive.PageStroke
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkTool
import com.vivenotes.byteink.vive.ViveInkCodec
import com.vivenotes.byteink.vive.AuthoredViveStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertNotNull

@OptIn(ExperimentalTestApi::class)
class InkPreviewTest {
    @Test
    fun drawingProducesReadableRowsAndEmptyPageIdentityDoesNotLeakInk() = runDesktopComposeUiTest(width = 128, height = 128) {
        val pageId = mutableStateOf("page-a")
        val finished = mutableListOf<AuthoredViveStroke>()
        val tool = ViveInkTool(sizeDp = 10f)
        setContent {
            InkPreview(emptyList(), 1f, Modifier.size(128.dp).testTag("ink"), tool,
                pageId.value, onStrokeFinished = { finished += it })
        }
        val node = onNodeWithTag("ink")
        node.performTouchInput { swipe(Offset(20f, 60f), Offset(108f, 60f), durationMillis = 200) }
        runOnIdle {
            val result = finished.single()
            assertEquals("page-a", result.row.pageId)
            assertEquals(ViveBrushes.MARKER, result.row.brushFamily)
            assertNotNull(ViveInkCodec.decode(result.row))
        }
        assertTrue(node.captureToImage().toPixelMap()[64, 60] != Color.White, "finished ink stays visible")
        runOnIdle { pageId.value = "page-b" }
        assertEquals(Color.White, node.captureToImage().toPixelMap()[64, 60], "another empty page starts blank")
        node.performTouchInput { swipe(Offset(20f, 80f), Offset(108f, 80f), durationMillis = 200) }
        runOnIdle {
            assertEquals("page-b", finished.last().row.pageId)
            assertEquals(0, finished.last().row.seq)
        }
    }

    @Test
    fun draggingPansTheInkAndZoomChangesItsScale() = runDesktopComposeUiTest(width = 128, height = 128) {
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 60f, 0L)
            add(InputToolType.MOUSE, 110f, 60f, 100L)
        }
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 12f), inputs.toImmutable())
        val strokes = listOf(PageStroke("ink", stroke))
        val zoom = mutableFloatStateOf(1f)
        setContent { InkPreview(strokes, zoom.floatValue, Modifier.size(128.dp).testTag("ink")) }
        val node = onNodeWithTag("ink")
        assertEquals(Color.White, node.captureToImage().toPixelMap()[64, 30])
        runOnIdle { zoom.floatValue = 2f }
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[64, 30])
        runOnIdle { zoom.floatValue = 1f }
        node.performTouchInput { swipe(Offset(60f, 30f), Offset(60f, 70f), durationMillis = 200) }
        val pixels = node.captureToImage().toPixelMap()
        assertEquals(Color.White, pixels[64, 21], "the stroke moved away")
        assertTrue((45..70).any { pixels[64, it] == Color.Black }, "the stroke follows the pan")
    }

    @Test
    fun automaticWhiteInkIsVisibleOnTheWhiteViewerPaper() = runDesktopComposeUiTest(width = 128, height = 128) {
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 60f, 0L)
            add(InputToolType.MOUSE, 110f, 60f, 100L)
        }
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xffffffff.toInt(), 12f), inputs.toImmutable())
        setContent { InkPreview(listOf(PageStroke("auto", stroke, colorFollowsTheme = true)), 1f, Modifier.size(128.dp).testTag("ink")) }
        val pixels = onNodeWithTag("ink").captureToImage().toPixelMap()
        assertEquals(Color.Black, pixels[64, 21])
        assertEquals(Color.White, pixels[64, 64])
    }

    @Test
    fun deliberateColourKeepsItsColourAndAlpha() = runDesktopComposeUiTest(width = 128, height = 128) {
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, 60f, 0L)
            add(InputToolType.MOUSE, 110f, 60f, 100L)
        }
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x80ff0000.toInt(), 12f), inputs.toImmutable())
        setContent { InkPreview(listOf(PageStroke("red", stroke, colorFollowsTheme = false)), 1f, Modifier.size(128.dp).testTag("ink")) }
        val pixels = onNodeWithTag("ink").captureToImage().toPixelMap()
        assertEquals(1f, pixels[64, 21].red)
        assertTrue(pixels[64, 21].green in 0.49f..0.51f)
    }
}
