package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.ink.brush.InputToolType
import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.vivenotes.byteink.kit.ViveBrushes
import org.junit.Assume.assumeTrue
import java.awt.Dimension
import java.awt.EventQueue
import java.util.concurrent.FutureTask
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFrame
import kotlin.math.PI
import kotlin.test.*

class DesktopPenIntegrationTest {
    private val brush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 8f)
    private val windows = System.getProperty("os.name").startsWith("Windows")
    private fun <T> ui(action: () -> T): T {
        val task = FutureTask(action)
        if (EventQueue.isDispatchThread()) task.run() else EventQueue.invokeAndWait(task)
        return task.get()
    }
    private fun await(message: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (ui(check)) return
            Thread.sleep(10)
        }
        fail(message)
    }
    private class Source : InkInputSource {
        var listener: ((InkInputEvent) -> Unit)? = null
        var oldListener: ((InkInputEvent) -> Unit)? = null
        var closes = 0
        override fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable {
            check(this.listener == null)
            this.listener = listener
            return AutoCloseable { oldListener = this.listener; this.listener = null; closes++ }
        }
        fun send(event: InkInputEvent) = assertNotNull(listener)(event)
    }
    private class Renderer : InkRenderer {
        private val delegate = InkMeshRenderer()
        var wetCount = 0
        var dryCount = 0
        var lastRealInputs = 0
        override fun canDraw(stroke: Stroke) = delegate.canDraw(stroke)
        override fun canDraw(stroke: InProgressStroke) = delegate.canDraw(stroke)
        override fun render(canvas: Canvas, stroke: Stroke, strokeToCanvas: AffineTransform, viewport: Rect?, colorArgb: Int?): Boolean {
            dryCount++
            return delegate.render(canvas, stroke, strokeToCanvas, viewport, colorArgb)
        }
        override fun render(canvas: Canvas, stroke: InProgressStroke, strokeToCanvas: AffineTransform, viewport: Rect?, colorArgb: Int?): Boolean {
            wetCount++; lastRealInputs = stroke.getRealInputCount()
            return delegate.render(canvas, stroke, strokeToCanvas, viewport, colorArgb)
        }
        override fun clearCache() = delegate.clearCache()
    }
    private class Window(val frame: JFrame, val panel: InkLowLatencyPanel) : AutoCloseable {
        override fun close() { panel.close(); frame.dispose() }
    }
    private fun window(source: InkInputSource? = null, renderer: InkRenderer? = null,
        onFinish: (Long, Stroke) -> Unit = { _, _ -> }): Window = ui {
        val frame = JFrame("ByteInk native pen verification")
        frame.isUndecorated = true
        val panel = InkLowLatencyPanel(brush, onFinish, renderer = renderer, inputSource = source, predictorFactory = null)
        panel.preferredSize = Dimension(192, 128)
        frame.contentPane.add(panel); frame.pack(); frame.setLocation(120, 100)
        frame.isVisible = true
        panel.requestInkRender()
        Window(frame, panel)
    }

    @Test fun realNativeMouseCaptureAuthorsWithoutSyntheticPressureAndStopsOnDisable() {
        assumeTrue(java.lang.Boolean.getBoolean("byteink.test.nativePen"))
        val completed = mutableListOf<Stroke>()
        val window = window(onFinish = { _, stroke -> completed.add(stroke) })
        try {
            await("initial direct frame") { window.panel.renderedFrameCount > 0 }
            DesktopInputInjection().use { injection ->
                val origin = ui { window.panel.locationOnScreen }
                val hwnd = ui { window.panel.nativeWindowHandle }
                fun mouse(phase: Int, x: Int) {
                    injection.mouse(hwnd, phase, if (windows) x else origin.x + x, if (windows) 60 else origin.y + 60)
                }
                mouse(0, 20)
                await("native primary down was not captured") { window.panel.session.activePointerIds.isNotEmpty() }
                mouse(1, 80)
                await("native motion did not reach the engine") { window.panel.session.liveStrokes.single().stroke.getRealInputCount() >= 2 }
                mouse(2, 140)
                await("native release did not complete") { completed.size == 1 }
                ui {
                    val stroke = completed.single()
                    assertEquals(InputToolType.MOUSE, stroke.inputs.getToolType())
                    assertTrue(stroke.inputs.size >= 3)
                    assertFalse(stroke.inputs.hasPressure())
                    window.panel.authoringEnabled = false
                }
                mouse(0, 30); mouse(1, 100); mouse(2, 140)
                // Queue barriers plus native reader polling allow any stale packets to be delivered.
                Thread.sleep(150)
                ui { assertEquals(1, completed.size); assertTrue(window.panel.session.activePointerIds.isEmpty()) }
                ui { window.panel.authoringEnabled = true }
                mouse(0, 30); mouse(2, 100)
                await("native source did not resubscribe") { completed.size == 2 }
            }
        } finally { ui { window.close() } }
        if (!windows) assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "byteink-xinput2" })
    }

    @Test fun oneImmediateFrameConsumesABurstAndHandsFinishedInkToTheSceneWithoutAComposeClock() {
        assumeTrue(java.lang.Boolean.getBoolean("byteink.test.nativePen"))
        val source = Source(); val renderer = Renderer(); val finished = mutableListOf<Stroke>()
        val window = window(source, renderer) { _, stroke -> finished.add(stroke) }
        try {
            ui { window.panel.drawContent = { canvas, _, _ -> finished.forEach { renderer.render(canvas, it) } } }
            await("initial direct frame") { window.panel.renderedFrameCount > 0 }
            val diagnostics = ui { InkLatencyDiagnostics(8).also { window.panel.latencyDiagnostics = it } }
            ui { source.send(InkInputEvent.Begin(InkPointerSample(10f, 60f, 1000), 1)) }
            await("first dot was not rendered") { renderer.wetCount > 0 }
            val before = ui { window.panel.session.revision }
            ui {
                repeat(32) { source.send(InkInputEvent.Move(InkPointerSample(12f + it * 3, 60f, 1001L + it), 1)) }
            }
            await("burst did not render all observations") { renderer.lastRealInputs == 33 }
            ui {
                assertEquals(before + 1, window.panel.session.revision, "one engine update for the entire queued burst")
                assertTrue(window.panel.lastInputToRenderNanos in 1L..1_000_000_000L)
                val snapshot = diagnostics.snapshot()
                assertTrue(snapshot.inputs.all { it.oldestQueueWaitNanos == null && it.normalizationNanos == null })
                assertTrue(snapshot.overwrittenInputs > 0L, "Burst diagnostics stay bounded")
                val burst = snapshot.frames.first { it.inputEventCount == 32 }
                assertEquals(32, burst.realSampleCount)
                assertEquals(InkLatencyInputOrigin.LISTENER, burst.inputOrigin)
                assertEquals(InkLatencyRenderPath.SKIA_LAYER, burst.path)
                assertNull(burst.swingTransferAndDrawNanos)
                assertNotNull(burst.frameRequestNanos)
                source.send(InkInputEvent.Finish(InkPointerSample(120f, 60f, 1040), 1))
            }
            await("canonical finish did not reach the finished scene") { renderer.dryCount > 0 }
            val snapshot = ui { diagnostics.snapshot() }
            val directory = Path.of(System.getProperty("byteink.test.latencyReports", "build/reports/software-latency"))
            Files.createDirectories(directory)
            Files.writeString(directory.resolve("desktop-synthetic-listener.json"),
                "{\"workload\":\"32-event listener burst\",\"syntheticInput\":true,\"physicalInputDevice\":false," +
                    "\"diagnostics\":${snapshot.toJson()}}")
            ui { assertEquals(34, finished.single().inputs.size); assertTrue(window.panel.session.activePointerIds.isEmpty()) }
            ui { window.panel.authoringEnabled = false }
            assertEquals(1, ui { source.closes })
            ui { assertNotNull(source.oldListener)(InkInputEvent.Begin(InkPointerSample(20f, 60f, 2000))) }
            ui { assertTrue(window.panel.session.activePointerIds.isEmpty()) }
        } finally { ui { window.close(); renderer.clearCache() } }
    }

    @Test fun windowsSyntheticPenReachesTheActualPointerApiWithPressureAndShaftAngles() {
        assumeTrue(java.lang.Boolean.getBoolean("byteink.test.nativePen") && windows)
        val completed = mutableListOf<Stroke>()
        val window = window(onFinish = { _, stroke -> completed.add(stroke) })
        try {
            await("initial direct frame") { window.panel.renderedFrameCount > 0 }
            DesktopInputInjection().use { injection ->
                val hwnd = ui { window.panel.nativeWindowHandle }
                val scale = ui { window.panel.graphicsConfiguration.defaultTransform.scaleX }
                fun pen(phase: Int, x: Int, pressure: Int) = injection.penAt(hwnd, phase,
                    (x * scale).toInt(), (60 * scale).toInt(), pressure, 45, 0)
                pen(0, 20, 256)
                await("WM_POINTERDOWN was not captured") { window.panel.session.activePointerIds.isNotEmpty() }
                pen(1, 80, 768)
                await("WM_POINTERUPDATE was not captured") { window.panel.session.liveStrokes.single().stroke.getRealInputCount() >= 2 }
                pen(2, 140, 512)
                await("WM_POINTERUP was not captured") { completed.size == 1 }
                ui {
                    val inputs = completed.single().inputs
                    assertEquals(InputToolType.STYLUS, inputs.getToolType())
                    assertEquals(.25f, inputs[0].pressure)
                    assertTrue((0 until inputs.size).any { inputs[it].pressure == .75f })
                    assertEquals((PI / 4).toFloat(), inputs[0].tiltRadians, .000001f)
                    assertEquals(0f, inputs[0].orientationRadians)
                    assertEquals(20f, inputs[0].x, 1f)
                    assertEquals(0, window.panel.session.liveStrokes.size)
                }
                pen(0, 30, 256)
                await("second native pen down was not captured") { window.panel.session.activePointerIds.isNotEmpty() }
                injection.cancelInput(hwnd)
                await("sent cancel-mode notification did not cancel the pen") { window.panel.session.activePointerIds.isEmpty() }
                pen(2, 100, 256)
                Thread.sleep(100)
                ui { assertEquals(1, completed.size, "cancelled native pen was never published: ${completed.map { it.inputs.getToolType() }}") }
            }
        } finally { ui { window.close() } }
    }
}
