package com.vivenotes.byteink.compose

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkCodec
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real Compose Desktop pointer dispatch and drawing; no window or hardware pen is required. */
@OptIn(ExperimentalTestApi::class)
class InkDrawingSurfaceTest {
    private val marker = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 10f)
    private val surfaceModifier = Modifier.size(128.dp).testTag("ink")

    @Test
    fun mouseDragProducesOneStrokeWithTheSelectedBrushAndEventTimes() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val finished = mutableListOf<Stroke>()
        setContent {
            InkDrawingSurface(controller, marker, surfaceModifier, onStrokeFinished = { finished += it }) {
                drawRect(Color.White)
            }
        }
        onNodeWithTag("ink").performMouseInput {
            updatePointerTo(Offset(12f, 60f))
            press()
            moveTo(Offset(52f, 60f), delayMillis = 24)
            moveTo(Offset(108f, 60f), delayMillis = 32)
            release()
        }
        runOnIdle {
            val stroke = finished.single()
            assertEquals(marker, stroke.brush)
            assertEquals(12f, stroke.inputs[0].x)
            assertEquals(60f, stroke.inputs[0].y)
            assertEquals(0L, stroke.inputs[0].elapsedTimeMillis)
            val last = stroke.inputs[stroke.inputs.size - 1]
            assertEquals(108f, last.x)
            assertEquals(60f, last.y)
            assertTrue(last.elapsedTimeMillis in 56L..57L, "timestamps retain event uptime offsets")
            assertTrue((0 until stroke.inputs.size).all { stroke.inputs[it].toolType == InputToolType.MOUSE })
            assertTrue((0 until stroke.inputs.size).all { stroke.inputs[it].pressure == StrokeInput.NO_PRESSURE })
            assertNull(controller.liveStroke)
        }
    }

    @Test
    fun wetStrokeIsVisibleBeforeTouchReleaseAndMapsTouchToolWithoutSyntheticPressure() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val finished = mutableListOf<Stroke>()
        setContent {
            InkDrawingSurface(controller, marker, surfaceModifier, onStrokeFinished = { finished += it }) {
                drawRect(Color.White)
            }
        }
        val node = onNodeWithTag("ink")
        node.performTouchInput {
            down(Offset(12f, 60f))
            moveTo(Offset(108f, 60f), delayMillis = 64)
        }
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[60, 60])
        runOnIdle {
            assertTrue(finished.isEmpty())
            assertNotNull(controller.liveStroke)
        }
        node.performTouchInput { up() }
        runOnIdle {
            val stroke = finished.single()
            assertTrue((0 until stroke.inputs.size).all { stroke.inputs[it].toolType == InputToolType.TOUCH })
            assertTrue((0 until stroke.inputs.size).all { stroke.inputs[it].pressure == StrokeInput.NO_PRESSURE })
        }
    }

    @Test
    fun aWetHighlighterCrossingHasOneLayerOfOpacity() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val brush = ViveBrushes.highlighter(0x80ff0000.toInt(), 14f)
        setContent {
            InkDrawingSurface(controller, brush, surfaceModifier, onStrokeFinished = {}) { drawRect(Color.White) }
        }
        val node = onNodeWithTag("ink")
        node.performMouseInput {
            updatePointerTo(Offset(20f, 20f))
            press()
            moveTo(Offset(100f, 100f), delayMillis = 100)
            moveTo(Offset(20f, 100f), delayMillis = 100)
            moveTo(Offset(100f, 20f), delayMillis = 100)
        }
        val pixels = node.captureToImage().toPixelMap()
        assertTrue(pixels[60, 60].green in 0.49f..0.51f, "the crossing retains the brush alpha")
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) {
            assertTrue(pixels[x, y].green >= 0.49f, "one wet stroke must not darken at ($x, $y)")
        }
        node.performMouseInput { release() }
    }

    @Test
    fun cancellingDiscardsWetInkAndDoesNotPublishAStroke() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val finished = mutableListOf<Stroke>()
        setContent {
            InkDrawingSurface(controller, marker, surfaceModifier, onStrokeFinished = { finished += it }) {
                drawRect(Color.White)
            }
        }
        val node = onNodeWithTag("ink")
        node.performMouseInput {
            updatePointerTo(Offset(12f, 60f))
            press()
            moveTo(Offset(108f, 60f), delayMillis = 64)
        }
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[60, 60])
        // Compose Desktop's test dispatcher does not emit platform cancellation events.
        runOnIdle { controller.cancel() }
        assertEquals(Color.White, node.captureToImage().toPixelMap()[60, 60])
        node.performMouseInput { release() }
        runOnIdle {
            assertTrue(finished.isEmpty())
            assertNull(controller.liveStroke)
        }
    }

    @Test
    fun rapidStrokesEachPublishOnceAndKeepTheirOwnInputs() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val finished = mutableListOf<Stroke>()
        setContent { InkDrawingSurface(controller, marker, surfaceModifier, onStrokeFinished = { finished += it }) }
        onNodeWithTag("ink").performMouseInput {
            repeat(20) { index ->
                val y = 10f + index * 5f
                updatePointerTo(Offset(12f, y))
                press()
                moveTo(Offset(108f, y), delayMillis = 1)
                release()
                advanceEventTime(1)
            }
        }
        runOnIdle {
            assertEquals(20, finished.size)
            finished.forEachIndexed { index, stroke ->
                assertEquals(12f, stroke.inputs[0].x)
                assertEquals(10f + index * 5f, stroke.inputs[0].y)
                assertEquals(108f, stroke.inputs[stroke.inputs.size - 1].x)
                assertEquals(0L, stroke.inputs[0].elapsedTimeMillis)
            }
            assertFalse(controller.isDrawing)
        }
    }

    @Test
    fun zoomAndTranslationMapViewCoordinatesBackToStrokeCoordinates() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val finished = mutableListOf<Stroke>()
        val transform = ImmutableAffineTransform(2f, 0f, 10f, 0f, 3f, 12f)
        setContent {
            InkDrawingSurface(controller, marker, surfaceModifier, transform, onStrokeFinished = { finished += it }) {
                drawRect(Color.White)
            }
        }
        val node = onNodeWithTag("ink")
        node.performMouseInput {
            updatePointerTo(Offset(22f, 42f))
            press()
            moveTo(Offset(110f, 102f), delayMillis = 64)
        }
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[66, 72])
        node.performMouseInput { release() }
        runOnIdle {
            val stroke = finished.single()
            assertEquals(6f, stroke.inputs[0].x)
            assertEquals(10f, stroke.inputs[0].y)
            assertEquals(50f, stroke.inputs[stroke.inputs.size - 1].x)
            assertEquals(30f, stroke.inputs[stroke.inputs.size - 1].y)
        }
    }

    @Test
    fun anActiveGestureKeepsItsBrushTransformAndCompletionCallbackWhenSettingsChange() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val brush = mutableStateOf(marker)
        val transform = mutableStateOf<AffineTransform>(ImmutableAffineTransform(2f, 0f, 0f, 0f, 2f, 0f))
        val useNewCallback = mutableStateOf(false)
        val originalFinished = mutableListOf<Stroke>()
        val newFinished = mutableListOf<Stroke>()
        setContent {
            val target = if (useNewCallback.value) newFinished else originalFinished
            InkDrawingSurface(controller, brush.value, surfaceModifier, transform.value, onStrokeFinished = { target += it }) {
                drawRect(Color.White)
            }
        }
        val node = onNodeWithTag("ink")
        node.performMouseInput {
            updatePointerTo(Offset(20f, 60f))
            press()
            moveTo(Offset(60f, 60f), delayMillis = 32)
        }
        runOnIdle {
            brush.value = marker.copyWithColorIntArgb(0xffff0000.toInt())
            transform.value = ImmutableAffineTransform(4f, 0f, 0f, 0f, 4f, 0f)
            useNewCallback.value = true
        }
        node.performMouseInput { moveTo(Offset(100f, 60f), delayMillis = 32) }
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[80, 60])
        node.performMouseInput { release() }
        runOnIdle {
            val stroke = originalFinished.single()
            assertTrue(newFinished.isEmpty())
            assertEquals(marker, stroke.brush)
            assertEquals(10f, stroke.inputs[0].x)
            assertEquals(30f, stroke.inputs[0].y)
            assertEquals(50f, stroke.inputs[stroke.inputs.size - 1].x)
            assertEquals(30f, stroke.inputs[stroke.inputs.size - 1].y)
        }
        node.performMouseInput {
            updatePointerTo(Offset(20f, 80f))
            press()
            moveTo(Offset(100f, 80f), delayMillis = 32)
            release()
        }
        runOnIdle {
            val stroke = newFinished.single()
            assertEquals(0xffff0000.toInt(), stroke.brush.colorIntArgb)
            assertEquals(5f, stroke.inputs[0].x)
            assertEquals(20f, stroke.inputs[0].y)
            assertEquals(25f, stroke.inputs[stroke.inputs.size - 1].x)
        }
    }

    @Test
    fun savingAndRebuildingTheFinishedRowPreservesTheWetImage() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val renderer = InkPathRenderer()
        val saved = mutableStateListOf<Stroke>()
        var completed: Stroke? = null
        setContent {
            InkDrawingSurface(controller, marker, surfaceModifier, renderer = renderer, onStrokeFinished = { stroke ->
                completed = stroke
                val row = ViveInkCodec.encodeStroke(
                    stroke, id = "new-stroke", pageId = "page", seq = 0,
                    brushFamily = ViveBrushes.MARKER, stabilization = 0,
                    colorFollowsTheme = false, createdAt = 123L,
                )
                saved += assertNotNull(ViveInkCodec.decode(row))
            }) {
                drawRect(Color.White)
                saved.forEach { renderer.draw(drawContext.canvas, it) }
            }
        }
        val node = onNodeWithTag("ink")
        node.performMouseInput {
            updatePointerTo(Offset(12f, 60f))
            press()
            moveTo(Offset(60f, 60f), delayMillis = 48)
            moveTo(Offset(108f, 60f), delayMillis = 48)
        }
        val wetImage = node.captureToImage()
        val wet = pixels(wetImage)
        var actualWetOutlines = emptyList<FloatArray>()
        runOnIdle {
            val live = assertNotNull(controller.liveStroke)
            actualWetOutlines = InkMeshes.outlines(live, 0)
        }
        node.performMouseInput { release() }
        val rebuiltImage = node.captureToImage()
        var packingDelta = 0f
        runOnIdle {
            val finishedGeometry = assertNotNull(completed)
            val reloadedGeometry = saved.single()
            val finishedOutlines = InkMeshes.outlines(finishedGeometry.shape, 0)
            packingDelta = outlineDifference(actualWetOutlines, finishedOutlines)
            assertTrue(packingDelta <= marker.epsilon, "Ink's live-to-packed outline delta=$packingDelta page units")
            assertEquals(0f, outlineDifference(finishedOutlines, InkMeshes.outlines(reloadedGeometry.shape, 0)),
                "encoding and rebuilding preserve the immutable geometry exactly")
            saved[0] = finishedGeometry
        }
        val finishedImage = node.captureToImage()
        val finishedPixels = pixels(finishedImage)
        val reloadedDifference = pixelDifference(finishedPixels, pixels(rebuiltImage))
        assertTrue(reloadedDifference.indices.isEmpty(), "saved row and finished stroke raster differ: $reloadedDifference")
        val packingDifference = pixelDifference(wet, finishedPixels)
        // Ink packs the immutable mesh positions. Measured packing moves the cap outline by
        // 0.012878418 page units, changing 7 antialiased pixels by at most 2/255 on this fixture.
        // The finished-vs-row assertions above stay exact; this bound applies only to wet packing.
        assertTrue(packingDifference.maxChannelDelta <= 2f / 255f + 0.0000001f &&
            packingDifference.indices.size.toDouble() / wet.size <= 0.001,
            "wet-to-packed raster regressed: $packingDifference")
        System.getProperty("byteink.test.interactionReport")?.let { destination ->
            val report = """
                {
                  "scope": "Passthrough marker at size 10 and identity view transform; upstream Ink immutable mesh packing",
                  "liveToImmutableMaxOutlineDelta": $packingDelta,
                  "wetToFinishedDifferingPixels": ${packingDifference.indices.size},
                  "wetToFinishedMaxChannelDelta": ${packingDifference.maxChannelDelta},
                  "finishedToReloadedDifferingPixels": ${reloadedDifference.indices.size},
                  "finishedToReloadedMaxChannelDelta": ${reloadedDifference.maxChannelDelta},
                  "maxOutlineDeltaCeiling": ${marker.epsilon},
                  "maxChannelDeltaCeiling": ${2f / 255f},
                  "differingPixelFractionCeiling": 0.001
                }
            """.trimIndent() + "\n"
            val path = Path.of(destination).resolveSibling("wet-dry-fidelity.json")
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, report)
        }
        runOnIdle { assertEquals(1, saved.size) }
    }

    @Test
    fun disablingTheSurfaceCancelsAnActiveGesture() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val enabled = mutableStateOf(true)
        val finished = mutableListOf<Stroke>()
        setContent {
            InkDrawingSurface(controller, marker, surfaceModifier, enabled = enabled.value, onStrokeFinished = { finished += it }) {
                drawRect(Color.White)
            }
        }
        val node = onNodeWithTag("ink")
        node.performTouchInput {
            down(Offset(12f, 60f))
            moveTo(Offset(108f, 60f), delayMillis = 64)
        }
        runOnIdle { enabled.value = false }
        assertEquals(Color.White, node.captureToImage().toPixelMap()[60, 60])
        node.performTouchInput { up() }
        runOnIdle {
            assertNull(controller.liveStroke)
            assertTrue(finished.isEmpty())
        }
    }

    @Test
    fun removingTheSurfaceCancelsWithoutPublishing() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val present = mutableStateOf(true)
        val finished = mutableListOf<Stroke>()
        setContent {
            if (present.value) InkDrawingSurface(controller, marker, surfaceModifier, onStrokeFinished = { finished += it })
        }
        onNodeWithTag("ink").performTouchInput {
            down(Offset(12f, 60f))
            moveTo(Offset(108f, 60f), delayMillis = 64)
        }
        runOnIdle { present.value = false }
        waitForIdle()
        runOnIdle {
            assertNull(controller.liveStroke)
            assertFalse(controller.isDrawing)
            assertTrue(finished.isEmpty())
        }
    }

    @Test
    fun secondaryMouseButtonDoesNotCreateInk() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val finished = mutableListOf<Stroke>()
        setContent { InkDrawingSurface(controller, marker, surfaceModifier, onStrokeFinished = { finished += it }) }
        onNodeWithTag("ink").performMouseInput {
            updatePointerTo(Offset(12f, 60f))
            press(MouseButton.Secondary)
            moveTo(Offset(108f, 60f), delayMillis = 64)
            release(MouseButton.Secondary)
        }
        runOnIdle {
            assertTrue(finished.isEmpty())
            assertFalse(controller.isDrawing)
        }
    }

    @Test
    fun releasingPrimaryFinishesTheStrokeEvenWhileASecondaryButtonIsHeld() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val finished = mutableListOf<Stroke>()
        setContent { InkDrawingSurface(controller, marker, surfaceModifier, onStrokeFinished = { finished += it }) }
        onNodeWithTag("ink").performMouseInput {
            updatePointerTo(Offset(12f, 60f))
            press()
            moveTo(Offset(52f, 60f), delayMillis = 24)
            press(MouseButton.Secondary)
            release(MouseButton.Primary)
            moveTo(Offset(108f, 60f), delayMillis = 24)
            release(MouseButton.Secondary)
        }
        runOnIdle {
            val stroke = finished.single()
            assertEquals(12f, stroke.inputs[0].x)
            assertEquals(52f, stroke.inputs[stroke.inputs.size - 1].x, "secondary-button movement is outside this gesture")
            assertFalse(controller.isDrawing)
        }
    }

    @Test
    fun anInputAdapterDrawsMeasuredStylusInputAndCapturesItsCompletionCallbackAtDown() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val source = TestInputSource()
        val useNewCallback = mutableStateOf(false)
        val originalFinished = mutableListOf<Stroke>()
        val newFinished = mutableListOf<Stroke>()
        setContent {
            val target = if (useNewCallback.value) newFinished else originalFinished
            InkDrawingSurface(controller, marker, surfaceModifier, inputSource = source, onStrokeFinished = { target += it }) {
                drawRect(Color.White)
            }
        }
        val node = onNodeWithTag("ink")
        // Choosing a native input source prevents the same gesture arriving through Compose too.
        node.performMouseInput {
            updatePointerTo(Offset(12f, 60f))
            press()
            moveTo(Offset(108f, 60f), delayMillis = 64)
            release()
        }
        runOnIdle {
            assertFalse(controller.isDrawing)
            assertTrue(originalFinished.isEmpty())
            source.emit(InkInputEvent.Begin(InkPointerSample(12f, 60f, 1_000L, InputToolType.STYLUS, 0.2f)))
            source.emit(InkInputEvent.Move(InkPointerSample(52f, 60f, 1_050L, InputToolType.STYLUS, 0.6f)))
        }
        assertEquals(Color.Black, node.captureToImage().toPixelMap()[30, 60])
        runOnIdle { useNewCallback.value = true }
        waitForIdle()
        runOnIdle {
            source.emit(InkInputEvent.Finish(InkPointerSample(108f, 60f, 1_100L, InputToolType.STYLUS, 0.8f)))
            val stroke = originalFinished.single()
            assertTrue(newFinished.isEmpty())
            assertEquals(3, stroke.inputs.size)
            assertEquals(listOf(0L, 50L, 100L), (0 until stroke.inputs.size).map { stroke.inputs[it].elapsedTimeMillis })
            assertEquals(listOf(0.2f, 0.6f, 0.8f), (0 until stroke.inputs.size).map { stroke.inputs[it].pressure })
            assertTrue((0 until stroke.inputs.size).all { stroke.inputs[it].toolType == InputToolType.STYLUS })
            assertNull(controller.liveStroke)
        }
    }

    @Test
    fun inputAdapterCancellationDiscardsInkAndAllowsTheNextGesture() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val source = TestInputSource()
        val finished = mutableListOf<Stroke>()
        setContent {
            InkDrawingSurface(controller, marker, surfaceModifier, inputSource = source, onStrokeFinished = { finished += it }) {
                drawRect(Color.White)
            }
        }
        runOnIdle {
            source.emit(InkInputEvent.Begin(InkPointerSample(12f, 60f, 1_000L)))
            source.emit(InkInputEvent.Move(InkPointerSample(108f, 60f, 1_100L)))
        }
        assertEquals(Color.Black, onNodeWithTag("ink").captureToImage().toPixelMap()[60, 60])
        runOnIdle {
            source.emit(InkInputEvent.Cancel)
            source.emit(InkInputEvent.Finish())
            assertTrue(finished.isEmpty())
        }
        assertEquals(Color.White, onNodeWithTag("ink").captureToImage().toPixelMap()[60, 60])
        runOnIdle {
            source.emit(InkInputEvent.Begin(InkPointerSample(12f, 80f, 2_000L)))
            source.emit(InkInputEvent.Finish(InkPointerSample(108f, 80f, 2_100L)))
            assertEquals(80f, finished.single().inputs[0].y)
        }
    }

    @Test
    fun disablingAndRemovingTheSurfaceCloseTheAdapterAndIgnoreLateCallbacks() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val source = TestInputSource()
        val enabled = mutableStateOf(true)
        val present = mutableStateOf(true)
        val finished = mutableListOf<Stroke>()
        setContent {
            if (present.value) InkDrawingSurface(
                controller, marker, surfaceModifier, enabled = enabled.value,
                inputSource = source, onStrokeFinished = { finished += it },
            ) { drawRect(Color.White) }
        }
        runOnIdle {
            assertEquals(1, source.subscriptionCount)
            source.emit(InkInputEvent.Begin(InkPointerSample(12f, 60f, 1_000L)))
            enabled.value = false
        }
        waitForIdle()
        runOnIdle {
            assertEquals(1, source.closeCount)
            assertNull(controller.liveStroke)
            source.emitAfterClose(InkInputEvent.Begin(InkPointerSample(12f, 60f, 2_000L)))
            assertFalse(controller.isDrawing)
            enabled.value = true
        }
        waitForIdle()
        runOnIdle {
            assertEquals(2, source.subscriptionCount)
            source.emit(InkInputEvent.Begin(InkPointerSample(12f, 60f, 3_000L)))
            present.value = false
        }
        waitForIdle()
        runOnIdle {
            assertEquals(2, source.closeCount)
            assertNull(controller.liveStroke)
            source.emitAfterClose(InkInputEvent.Finish(InkPointerSample(108f, 60f, 3_100L)))
            assertTrue(finished.isEmpty())
        }
    }

    @Test
    fun inputAdapterAcquisitionFailureCancelsInputAndIgnoresItsRetainedListener() {
        val controller = InkAuthoringController()
        val failure = IllegalStateException("Native device acquisition failed")
        var retainedListener: ((InkInputEvent) -> Unit)? = null
        val source = object : InkInputSource {
            override fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable {
                retainedListener = listener
                listener(InkInputEvent.Begin(InkPointerSample(12f, 60f, 1_000L)))
                throw failure
            }
        }
        val thrown = assertFailsWith<IllegalStateException> {
            runDesktopComposeUiTest(width = 128, height = 128) {
                setContent {
                    InkDrawingSurface(controller, marker, surfaceModifier, inputSource = source, onStrokeFinished = {})
                }
                waitForIdle()
            }
        }
        // Coroutine stacktrace recovery may wrap a copy around the original exception.
        assertEquals(failure.javaClass, thrown.javaClass, "the acquisition failure type is propagated")
        assertEquals(failure.message, thrown.message, "the acquisition failure message is propagated")
        assertTrue(generateSequence<Throwable>(thrown) { it.cause }.any { it === failure },
            "the propagated failure retains the original exception")
        assertFalse(controller.isDrawing)
        assertNull(controller.liveStroke)
        assertNotNull(retainedListener)(InkInputEvent.Begin(InkPointerSample(20f, 80f, 2_000L)))
        assertFalse(controller.isDrawing, "a failed source cannot restart authoring")
        controller.begin(marker, InkPointerSample(20f, 80f, 3_000L))
        assertNotNull(controller.finish(), "the controller remains reusable")
        controller.close()
    }

    @Test
    fun inputHandlerToHeadlessComposeRasterFrameHasABoundedLatency() = runDesktopComposeUiTest(width = 128, height = 128) {
        val controller = InkAuthoringController()
        val source = TestInputSource()
        var renderedRevision = -1L
        var drawStartedNanos = 0L
        setContent {
            InkDrawingSurface(controller, marker, surfaceModifier, inputSource = source, onStrokeFinished = {}) {
                drawRect(Color.White)
                renderedRevision = controller.revision
                drawStartedNanos = System.nanoTime()
            }
        }
        runOnIdle { source.emit(InkInputEvent.Begin(InkPointerSample(12f, 60f, 1_000L))) }
        val node = onNodeWithTag("ink")
        node.captureToImage()
        val handlerToDrawMillis = mutableListOf<Double>()
        val handlerToRasterMillis = mutableListOf<Double>()
        repeat(35) { index ->
            var handlerStartedNanos = 0L
            var queuedRevision = 0L
            runOnIdle {
                handlerStartedNanos = System.nanoTime()
                source.emit(InkInputEvent.Move(InkPointerSample(
                    x = 12f + (index % 10) * 10f,
                    y = 40f + (index % 3) * 10f,
                    uptimeMillis = 1_008L + index * 8L,
                )))
                queuedRevision = controller.revision
            }
            mainClock.advanceTimeByFrame()
            node.captureToImage()
            val rasterCompletedNanos = System.nanoTime()
            runOnIdle {
                assertTrue(renderedRevision >= queuedRevision, "the raster includes this input observation")
                assertTrue(drawStartedNanos >= handlerStartedNanos, "the observed frame follows the handler")
                if (index >= 5) {
                    handlerToDrawMillis += (drawStartedNanos - handlerStartedNanos) / 1_000_000.0
                    handlerToRasterMillis += (rasterCompletedNanos - handlerStartedNanos) / 1_000_000.0
                }
            }
        }
        runOnIdle { source.emit(InkInputEvent.Cancel) }
        val report = """
            {
              "scope": "Headless Compose Desktop test scheduler and Skia raster; excludes hardware input and display latency",
              "warmupSamples": 5,
              "measuredSamples": 30,
              "handlerToDrawMillis": ${latencyDistribution(handlerToDrawMillis)},
              "handlerToCompletedRasterMillis": ${latencyDistribution(handlerToRasterMillis)},
              "regressionCeilingP95Millis": 2500
            }
        """.trimIndent() + "\n"
        System.getProperty("byteink.test.interactionReport")?.let { destination ->
            val path = Path.of(destination)
            path.parent?.let { Files.createDirectories(it) }
            Files.writeString(path, report)
        }
        assertTrue(percentile(handlerToRasterMillis, 95) < 2_500.0, "headless raster p95 regressed: $report")
    }

    private fun pixels(image: ImageBitmap): List<Color> = image.toPixelMap().let { pixels ->
        List(pixels.width * pixels.height) { i -> pixels[i % pixels.width, i / pixels.width] }
    }

    private fun outlineDifference(firstOutlines: List<FloatArray>, secondOutlines: List<FloatArray>): Float {
        assertEquals(firstOutlines.size, secondOutlines.size, "outline counts")
        firstOutlines.indices.forEach { outline ->
            assertEquals(firstOutlines[outline].size, secondOutlines[outline].size, "vertex count for outline $outline")
        }
        return firstOutlines.indices.maxOfOrNull { outline ->
            firstOutlines[outline].indices.maxOfOrNull { vertex ->
                kotlin.math.abs(firstOutlines[outline][vertex] - secondOutlines[outline][vertex])
            } ?: 0f
        } ?: 0f
    }

    private fun pixelDifference(first: List<Color>, second: List<Color>): PixelDifference {
        assertEquals(first.size, second.size, "image dimensions")
        val mismatches = first.indices.filter { first[it] != second[it] }
        val maxDelta = mismatches.maxOfOrNull { index ->
            maxOf(kotlin.math.abs(first[index].red - second[index].red),
                kotlin.math.abs(first[index].green - second[index].green),
                kotlin.math.abs(first[index].blue - second[index].blue),
                kotlin.math.abs(first[index].alpha - second[index].alpha))
        } ?: 0f
        return PixelDifference(mismatches, maxDelta)
    }

    private class PixelDifference(val indices: List<Int>, val maxChannelDelta: Float) {
        override fun toString(): String = "${indices.size} differing pixels, max channel delta=$maxChannelDelta, " +
            "first pixel indices=${indices.take(10)}"
    }

    private fun percentile(samples: List<Double>, percentile: Int): Double = samples.sorted()[
        ((samples.size * percentile + 99) / 100 - 1).coerceAtLeast(0)
    ]

    private fun latencyDistribution(samples: List<Double>): String =
        String.format(Locale.ROOT, "{\"p50\": %.3f, \"p95\": %.3f, \"max\": %.3f}",
            percentile(samples, 50), percentile(samples, 95), samples.max())

    private class TestInputSource : InkInputSource {
        var subscriptionCount = 0
            private set
        var closeCount = 0
            private set
        private var listener: ((InkInputEvent) -> Unit)? = null
        private var closedListener: ((InkInputEvent) -> Unit)? = null

        override fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable {
            assertNull(this.listener, "one active adapter subscription")
            this.listener = listener
            subscriptionCount++
            return AutoCloseable {
                closeCount++
                closedListener = listener
                this.listener = null
            }
        }

        fun emit(event: InkInputEvent) = assertNotNull(listener, "adapter is subscribed")(event)
        fun emitAfterClose(event: InkInputEvent) = assertNotNull(closedListener, "adapter was closed")(event)
    }
}
