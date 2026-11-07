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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.StoredInkErase
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.AuthoredViveStroke
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

    /** The stored row of a black horizontal pen line, 12 units wide, at [y]. */
    private fun penRow(id: String, y: Float): StoredInkStroke {
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.MOUSE, 10f, y, 0L)
            add(InputToolType.MOUSE, 110f, y, 100L)
        }
        val tool = ViveInkTool(sizeDp = 12f, colorArgb = 0xff000000.toInt())
        return tool.complete(Stroke(tool.brush, inputs.toImmutable()), id, "page", 0, 1L).row
    }

    private fun ink(vararg rows: StoredInkStroke, erases: List<StoredInkErase> = emptyList()) =
        ViveInkPage.load(rows.toList(), erases, emptyList()).strokes

    @Test
    fun partialEraserCutsOnlyWhatItCrossesAndAReloadShowsTheSame() = runDesktopComposeUiTest(width = 128, height = 128) {
        val row = penRow("ink", 60f)
        val strokes = mutableStateOf(ink(row))
        val erases = mutableListOf<StoredInkErase>()
        setContent {
            InkPreview(strokes.value, 1f, Modifier.size(128.dp).testTag("ink"), pageId = "page",
                eraser = InkEraseMode.Normal, onEraseFinished = { erases += it })
        }
        val node = onNodeWithTag("ink")
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[64, 21])
        node.performTouchInput { swipe(Offset(64f, 2f), Offset(64f, 60f), durationMillis = 200) }
        val cut = node.captureToImage().toPixelMap()
        assertEquals(Color.White, cut[64, 21], "the crossed part is gone")
        assertEquals(Color.Black, cut[30, 21], "the left end stays where it was")
        assertEquals(Color.Black, cut[100, 21], "the right end stays where it was")
        val erase = runOnIdle { erases.single() }
        assertEquals("page", erase.pageId)
        assertEquals(InkEraseMode.Normal.stored, erase.mode)
        assertEquals(listOf("ink"), erase.targetIds)
        assertNotNull(ViveInkCodec.decodeErase(erase))

        runOnIdle { strokes.value = ink(row, erases = erases) }
        val reloaded = node.captureToImage().toPixelMap()
        for (y in 0 until 128) for (x in 0 until 128) assertEquals(cut[x, y], reloaded[x, y], "pixel $x,$y after reload")
    }

    @Test
    fun objectEraserTakesTheWholeStrokeItTouchesWithoutMovingTheOthers() = runDesktopComposeUiTest(width = 128, height = 128) {
        val strokes = ink(penRow("top", 60f), penRow("bottom", 110f))
        val erases = mutableListOf<StoredInkErase>()
        setContent {
            InkPreview(strokes, 1f, Modifier.size(128.dp).testTag("ink"),
                eraser = InkEraseMode.Object, onEraseFinished = { erases += it })
        }
        val node = onNodeWithTag("ink")
        val before = node.captureToImage().toPixelMap()
        assertEquals(Color.Black, before[64, 21])
        assertEquals(Color.Black, before[64, 64])
        node.performTouchInput { swipe(Offset(30f, 10f), Offset(30f, 24f), durationMillis = 100) }
        val after = node.captureToImage().toPixelMap()
        assertEquals(Color.White, after[100, 21], "the touched stroke is gone, far from where it was touched")
        assertEquals(Color.Black, after[64, 64], "the other stroke did not move or refit")
        val erase = runOnIdle { erases.single() }
        assertEquals(InkEraseMode.Object.stored, erase.mode)
        assertEquals(listOf("top"), erase.targetIds)
    }

    @Test
    fun erasingEmptySpaceChangesAndStoresNothing() = runDesktopComposeUiTest(width = 128, height = 128) {
        val strokes = ink(penRow("ink", 60f))
        val erases = mutableListOf<StoredInkErase>()
        setContent {
            InkPreview(strokes, 1f, Modifier.size(128.dp).testTag("ink"),
                eraser = InkEraseMode.Normal, onEraseFinished = { erases += it })
        }
        val node = onNodeWithTag("ink")
        node.performTouchInput { swipe(Offset(20f, 110f), Offset(108f, 110f), durationMillis = 100) }
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[64, 21])
        runOnIdle { assertTrue(erases.isEmpty(), "no ink touched, no operation") }
    }

    @Test
    fun eraserWorksOnABlankPageAndOnInkDrawnThisSession() = runDesktopComposeUiTest(width = 128, height = 128) {
        val eraser = mutableStateOf<InkEraseMode?>(InkEraseMode.Normal)
        val finished = mutableListOf<AuthoredViveStroke>()
        val erases = mutableListOf<StoredInkErase>()
        val tool = ViveInkTool(sizeDp = 10f, colorArgb = 0xff000000.toInt())
        setContent {
            InkPreview(emptyList(), 1f, Modifier.size(128.dp).testTag("ink"), tool, "page", eraser.value,
                onStrokeFinished = { finished += it }, onEraseFinished = { erases += it })
        }
        val node = onNodeWithTag("ink")
        node.performTouchInput { swipe(Offset(64f, 20f), Offset(64f, 100f), durationMillis = 100) }
        runOnIdle { assertTrue(erases.isEmpty(), "a blank page has nothing to erase") }

        runOnIdle { eraser.value = null }
        node.performTouchInput { swipe(Offset(10f, 60f), Offset(118f, 60f), durationMillis = 200) }
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[64, 60])

        runOnIdle { eraser.value = InkEraseMode.Normal }
        node.performTouchInput { swipe(Offset(64f, 20f), Offset(64f, 100f), durationMillis = 200) }
        val cut = node.captureToImage().toPixelMap()
        assertEquals(Color.White, cut[64, 60])
        assertEquals(Color.Black, cut[20, 60])
        assertEquals(Color.Black, cut[108, 60])
        runOnIdle { assertEquals(listOf(finished.single().row.id), erases.single().targetIds) }

        runOnIdle { eraser.value = null }
        node.performTouchInput { swipe(Offset(10f, 115f), Offset(118f, 115f), durationMillis = 200) }
        runOnIdle { assertEquals(listOf(0, 1), finished.map { it.row.seq }, "one row cut into two pieces is still one row") }

        runOnIdle { eraser.value = InkEraseMode.Object }
        node.performTouchInput { swipe(Offset(20f, 50f), Offset(20f, 70f), durationMillis = 100) }
        val left = node.captureToImage().toPixelMap()
        assertEquals(Color.White, left[20, 60], "only the touched piece of the row goes")
        assertEquals(Color.Black, left[108, 60])
        assertEquals(Color.Black, left[20, 115], "a stroke the mask never touched stays")
    }

    @Test
    fun toolbarErasersCutInkWithoutMovingThePage() = runDesktopComposeUiTest(width = 800, height = 600) {
        setContent { NotebookViewer(null, onOpen = {}) }
        val ink = onNodeWithTag("ink")
        ink.performTouchInput { swipe(Offset(50f, 100f), Offset(700f, 100f), durationMillis = 200) }
        val page = ink.fetchSemanticsNode().boundsInRoot

        onNodeWithText("Partial eraser").performClick()
        ink.performTouchInput { swipe(Offset(300f, 50f), Offset(300f, 150f), durationMillis = 200) }
        onNodeWithText("Draw on the blank page · 1 unsaved stroke · 1 unsaved erase").assertExists()
        assertEquals(page, ink.fetchSemanticsNode().boundsInRoot, "a new count must not push the page down")
        val cut = ink.captureToImage().toPixelMap()
        assertEquals(Color.White, cut[300, 100])
        assertTrue(cut[100, 100] != Color.White)
        assertTrue(cut[600, 100] != Color.White)

        onNodeWithText("Object eraser").performClick()
        ink.performTouchInput { swipe(Offset(100f, 90f), Offset(100f, 110f), durationMillis = 100) }
        val left = ink.captureToImage().toPixelMap()
        assertEquals(Color.White, left[200, 100], "all of the touched piece goes, not just where it was touched")
        assertTrue(left[600, 100] != Color.White, "the piece the cut left apart stays")
    }

    @Test
    fun aCutHighlighterStaysVisibleEitherSideOfTheCut() = runDesktopComposeUiTest(width = 128, height = 128) {
        val eraser = mutableStateOf<InkEraseMode?>(null)
        val tool = ViveInkTool(ViveBrushes.HIGHLIGHTER, colorArgb = 0xffffe000.toInt(), sizeDp = 18f)
        setContent { InkPreview(emptyList(), 1f, Modifier.size(128.dp).testTag("ink"), tool, "page", eraser.value) }
        val node = onNodeWithTag("ink")
        node.performTouchInput { swipe(Offset(10f, 60f), Offset(118f, 60f), durationMillis = 200) }
        runOnIdle { eraser.value = InkEraseMode.Normal }
        node.performTouchInput { swipe(Offset(64f, 20f), Offset(64f, 100f), durationMillis = 200) }
        val cut = node.captureToImage().toPixelMap()
        assertEquals(Color.White, cut[64, 60])
        assertTrue(cut[20, 60] != Color.White, "a highlighter is drawn from its outlines, which a cut must keep")
        assertTrue(cut[108, 60] != Color.White)
    }
}
