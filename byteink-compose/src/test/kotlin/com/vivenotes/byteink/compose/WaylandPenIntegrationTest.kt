package com.vivenotes.byteink.compose

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.image.BufferedImage
import java.util.concurrent.FutureTask
import javax.swing.BorderFactory
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.test.*

class WaylandPenIntegrationTest {
    private fun <T> ui(action: () -> T): T {
        val task = FutureTask(action)
        if (EventQueue.isDispatchThread()) task.run() else EventQueue.invokeAndWait(task)
        return task.get()
    }
    private fun await(message: String, check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) { if (ui(check)) return; Thread.sleep(10) }
        fail(message)
    }
    private class Window(val frame: JFrame, val panel: InkLowLatencyPanel, val finished: MutableList<Stroke>) : AutoCloseable {
        override fun close() { panel.close(); frame.dispose() }
    }
    private fun window(): Window {
        assertTrue(ui { WaylandRuntime.isWayland() }, "Tests must use JBR's native Wayland toolkit")
        val readers = Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "byteink-wayland-pen" }
        val window = ui {
            val finished = mutableListOf<Stroke>()
            val frame = JFrame("Isolated Wayland pen verification").also { it.isUndecorated = true }
            val panel = InkLowLatencyPanel(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 8f),
                { _, stroke -> finished.add(stroke) })
            panel.preferredSize = Dimension(192, 128)
            val host = JPanel(java.awt.BorderLayout()).also {
                it.border = BorderFactory.createEmptyBorder(8, 12, 4, 6); it.add(panel)
            }
            frame.contentPane.add(host); frame.pack(); frame.isVisible = true
            Window(frame, panel, finished)
        }
        await("configured Wayland surface, native capture and first Skia frame") {
            window.panel.renderedFrameCount > 0 && Thread.getAllStackTraces().keys.count { it.isAlive && it.name == "byteink-wayland-pen" } == readers + 1
        }
        return window
    }
    private fun injector(window: Window): WaylandInputInjection = ui {
        WaylandInputInjection(WaylandRuntime.windowSurface(window.panel).display)
    }
    private fun send(window: Window, input: WaylandInputInjection, phase: Int, x: Double = 20.0,
        tool: Int = 0, pressure: Int = 16384, time: Int = 1000) = ui {
        val surface = WaylandRuntime.windowSurface(window.panel)
        val offset = SwingUtilities.convertPoint(window.panel, 0, 0, surface.window)
        val units = surface.surfaceUnitsPerLocalUnit()
        input.send(surface.surface, tool, phase, (x + offset.x) * units, (60.0 + offset.y) * units,
            pressure, 45.0, 0.0, time)
    }
    private fun inkPixel(window: Window): Int = ui {
        val image = BufferedImage(window.panel.width, window.panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try { window.panel.paintAll(graphics) } finally { graphics.dispose() }
        image.getRGB(60, 60).also { assertEquals(255, it ushr 24, "the actual painter filled the panel") }
    }

    @Test fun actualTabletFramesPreservePressureTiltHistoryAndCanonicalHandoff() {
        val window = window()
        try {
            var renderedWidth = 0
            ui { window.panel.drawContent = { canvas, width, _ ->
                renderedWidth = width
                window.finished.forEach { window.panel.renderer.render(canvas, it) }
            } }
            injector(window).use { input ->
                send(window, input, 0)
                await("tablet down") { window.panel.session.activePointerIds.size == 1 }
                ui { repeat(32) { send(window, input, 1, 23.0 + it * 3, pressure = 49151, time = 1001 + it) } }
                await("all native history reached Skia") { window.panel.session.liveStrokes.single().stroke.getRealInputCount() == 33 }
                ui {
                    assertTrue(window.panel.lastInputToRenderNanos > 0)
                    assertEquals(window.panel.width, renderedWidth, "physical backing pixels retain logical drawing coordinates")
                    assertTrue(window.panel.session.liveStrokes.single().stroke.getPredictedInputCount() > 0)
                }
                assertTrue(inkPixel(window) and 0xff < 200, "live mesh ink is visible through the actual Swing/Skia painter")
                send(window, input, 2, 140.0, pressure = 32768, time = 1040)
                await("canonical stroke handoff") { window.finished.size == 1 && window.panel.session.liveStrokes.isEmpty() }
                ui {
                    val inputs = window.finished.single().inputs
                    assertEquals(34, inputs.size, "predictions were excluded from the saved stroke")
                    assertEquals(InputToolType.STYLUS, inputs.getToolType())
                    assertEquals(20f, inputs[0].x, .02f); assertEquals(60f, inputs[0].y, .02f)
                    assertEquals(16384 / 65535f, inputs[0].pressure, .000001f)
                    assertEquals(49151 / 65535f, inputs[1].pressure, .000001f)
                    assertEquals((PI / 4).toFloat(), inputs[0].tiltRadians, .000001f)
                    assertEquals(0f, inputs[0].orientationRadians, .000001f)
                    assertTrue((1 until inputs.size).all { inputs[it].elapsedTimeMillis >= inputs[it - 1].elapsedTimeMillis })
                }
                assertTrue(inkPixel(window) and 0xff < 200, "finished scene retains visible ink after wet handoff")
            }
        } finally { ui { window.close() } }
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "byteink-wayland-pen" })
    }

    @Test fun separateWindowsShareTheToolkitConnectionWithoutCrossCaptureOrClosingIt() {
        val first = window(); val second = window()
        try {
            injector(first).use { input ->
                send(first, input, 0)
                await("first window down") { first.panel.session.activePointerIds.size == 1 }
                ui { assertTrue(second.panel.session.activePointerIds.isEmpty()) }
                send(first, input, 2, 100.0, time = 1010)
                await("first window finish") { first.finished.size == 1 }
                ui { first.close() }
                send(second, input, 0, time = 1020)
                await("second window survives closing first queue") { second.panel.session.activePointerIds.size == 1 }
                send(second, input, 2, 120.0, time = 1030)
                await("second window finish") { second.finished.size == 1 }
            }
        } finally { ui { first.close(); second.close() } }
    }

    @Test fun distinctToolsAndTouchContactsKeepTheirOwnOptionalAxesAndCancellation() {
        val window = window()
        try {
            injector(window).use { input ->
                send(window, input, 0)
                send(window, input, 0, 30.0, tool = 1, time = 1001)
                await("two tablet tools") { window.panel.session.activePointerIds.size == 2 }
                send(window, input, 2, 80.0, tool = 1, time = 1010)
                await("axis-free tool finish") { window.finished.size == 1 }
                ui { assertFalse(window.finished.single().inputs.hasPressure()); assertFalse(window.finished.single().inputs.hasTilt()) }
                send(window, input, 7, 40.0, tool = 41, time = 1011)
                send(window, input, 7, 50.0, tool = 42, time = 1012)
                await("pen plus two touch contacts") { window.panel.session.activePointerIds.size == 3 }
                send(window, input, 8, 100.0, tool = 41, time = 1013)
                send(window, input, 9, 100.0, tool = 41, time = 1014)
                await("touch completion") { window.finished.size == 2 }
                ui { assertEquals(InputToolType.TOUCH, window.finished.last().inputs.getToolType()); assertFalse(window.finished.last().inputs.hasPressure()) }
                send(window, input, 10, time = 1015)
                await("touch cancel keeps pen") { window.panel.session.activePointerIds.size == 1 }
                send(window, input, 3, time = 1016)
                await("proximity out cancels pen") { window.panel.session.activePointerIds.isEmpty() }
                ui { assertEquals(2, window.finished.size) }
            }
        } finally { ui { window.close() } }
    }

    @Test fun toolAndTabletRemovalCancelWithoutPublishingAndHideShowReconnects() {
        val window = window()
        try {
            injector(window).use { input ->
                for (phase in listOf(4, 5)) {
                    send(window, input, 0)
                    await("contact before removal") { window.panel.session.activePointerIds.size == 1 }
                    send(window, input, phase)
                    await("device removal cancels") { window.panel.session.activePointerIds.isEmpty() }
                }
                ui { window.frame.isVisible = false }
                assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "byteink-wayland-pen" })
                ui { window.frame.isVisible = true }
                await("Wayland reattach") { Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "byteink-wayland-pen" } }
                send(window, input, 0)
                await("new surface contact") { window.panel.session.activePointerIds.size == 1 }
                send(window, input, 2, 110.0, time = 1020)
                await("new surface finish") { window.finished.size == 1 }
                ui { window.panel.authoringEnabled = false }
                send(window, input, 0); send(window, input, 2)
                Thread.sleep(100)
                ui { assertEquals(1, window.finished.size); window.panel.authoringEnabled = true }
                send(window, input, 6, time = 1030)
                await("same-frame down/up") { window.finished.size == 2 }
            }
        } finally { ui { window.close() } }
    }

    @Test fun outsideContactsAreFilteredAndCapturedContactMayLeaveThePanel() {
        val window = window()
        try {
            injector(window).use { input ->
                send(window, input, 0, -.25)
                send(window, input, 1, 20.0, time = 1001)
                send(window, input, 2, 30.0, time = 1002)
                Thread.sleep(100)
                ui { assertTrue(window.finished.isEmpty()); assertTrue(window.panel.session.activePointerIds.isEmpty()) }
                send(window, input, 0, 20.0, time = 1010)
                send(window, input, 1, 250.0, time = 1011)
                send(window, input, 2, 300.0, time = 1012)
                await("captured contact outside bounds") { window.finished.size == 1 }
                ui { assertEquals(300f, window.finished.single().inputs[2].x, .02f) }
            }
        } finally { ui { window.close() } }
    }

    @Test fun nativeWaylandMouseReachesAwtWithoutSyntheticPenAxes() {
        val window = window()
        try {
            injector(window).use { input ->
                send(window, input, 11)
                await("native mouse down") { window.panel.session.activePointerIds.size == 1 }
                send(window, input, 12, 80.0, time = 1010)
                send(window, input, 13, 140.0, time = 1020)
                await("native mouse stroke") { window.finished.size == 1 }
            }
            ui {
                assertEquals(InputToolType.MOUSE, window.finished.single().inputs.getToolType())
                assertFalse(window.finished.single().inputs.hasPressure())
                assertEquals(20f, window.finished.single().inputs[0].x, .02f)
            }
        } finally { ui { window.close() } }
    }

    @Test fun hotplugDuringDetachLeavesNoCallbacksOnTheSharedToolkitConnection() {
        val window = window()
        try {
            injector(window).use { input ->
                repeat(20) {
                    ui {
                        send(window, input, 4)
                        send(window, input, 0)
                        window.panel.authoringEnabled = false
                        window.panel.authoringEnabled = true
                    }
                }
                send(window, input, 0, time = 1100)
                await("pen survives hotplug during repeated detach") { window.panel.session.activePointerIds.size == 1 }
                send(window, input, 2, 120.0, time = 1110)
                await("finish after hotplug/detach") { window.finished.size == 1 }
            }
        } finally { ui { window.close() } }
        assertFalse(Thread.getAllStackTraces().keys.any { it.isAlive && it.name == "byteink-wayland-pen" })
    }
}
