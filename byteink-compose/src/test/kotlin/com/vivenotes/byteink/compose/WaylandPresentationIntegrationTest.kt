package com.vivenotes.byteink.compose

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.util.concurrent.FutureTask
import javax.swing.JFrame
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Pinned JBR-only presentation probe; all native writes/commits are still performed by JBR. */
class WaylandPresentationIntegrationTest {
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

    private class Window(val frame: JFrame, val panel: InkLowLatencyPanel, val finished: MutableList<Stroke>) : AutoCloseable {
        override fun close() {
            panel.close()
            frame.dispose()
            assertEquals(0L, panel.retainedPixelBytes, "Close releases retained physical rasters")
        }
    }

    private fun window(): Window {
        assertTrue(System.getenv("DISPLAY").isNullOrEmpty(), "Native Wayland presentation must not use X11")
        assertTrue(ui { WaylandRuntime.isWayland() }, "Run with pinned JBR's WLToolkit")
        val window = ui {
            val finished = mutableListOf<Stroke>()
            val frame = JFrame("Isolated Wayland presentation verification").also { it.isUndecorated = true }
            val panel = InkLowLatencyPanel(
                ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x80000000.toInt(), 18f),
                { _, stroke -> finished.add(stroke) },
                inputSource = object : InkInputSource {
                    override fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable = AutoCloseable { }
                },
                predictorFactory = null,
            )
            panel.drawContent = { canvas, _, _ -> finished.forEach { panel.renderer.render(canvas, it) } }
            panel.preferredSize = Dimension(256, 192)
            frame.contentPane.add(panel)
            frame.pack()
            frame.isVisible = true
            Window(frame, panel, finished)
        }
        await("JBR's configured Wayland surface and initial native SkiaSwingLayer paint") {
            window.panel.renderedFrameCount > 0 && WaylandRuntime.findWindowSurface(window.panel) != null
        }
        ui { assertDestination(window) }
        return window
    }

    private fun destination(graphics: Graphics2D): Any {
        assertEquals("sun.java2d.SunGraphics2D", graphics.javaClass.name)
        return Class.forName("sun.java2d.SunGraphics2D").getField("surfaceData")
            .also { it.isAccessible = true }.get(graphics)
    }

    private fun expectedBackend(): String = System.getProperty("byteink.test.waylandBackend").also {
        assertTrue(it in listOf("shm", "vulkan"), "Explicit expected native backend is required")
    }

    private fun assertDestination(window: Window) {
        val vulkan = expectedBackend() == "vulkan"
        assertEquals(if (vulkan) "sun.java2d.vulkan.WLVKGraphicsConfig" else "sun.awt.wl.WLSMGraphicsConfig",
            window.frame.graphicsConfiguration.javaClass.name, "JBR selected the explicitly expected graphics configuration")
        val graphics = assertNotNull(window.frame.graphics as? Graphics2D)
        try {
            val data = destination(graphics)
            assertEquals(if (vulkan) "sun.java2d.vulkan.WLVKWindowSurfaceData" else "sun.java2d.wl.WLSMSurfaceData",
                data.javaClass.name, "Assert the actual window destination, not merely a requested property")
            val scale = System.getProperty("byteink.test.waylandScale").toDouble()
            assertEquals(scale, window.frame.graphicsConfiguration.defaultTransform.scaleX)
            assertEquals(scale, window.frame.graphicsConfiguration.defaultTransform.scaleY)
            val vkEnv = Class.forName("sun.java2d.vulkan.VKEnv")
            val enabled = vkEnv.getMethod("isPresentationEnabled").also { it.isAccessible = true }.invoke(null)
            assertEquals(vulkan, enabled)
            println("Native Wayland destination: request=${System.getProperty("sun.java2d.vulkan")}, " +
                "expected=${expectedBackend()}, configuration=${window.frame.graphicsConfiguration.javaClass.name}, " +
                "surface=${data.javaClass.name}, scale=$scale, runtime=${System.getProperty("java.runtime.version")}")
            if (vulkan) {
                @Suppress("UNCHECKED_CAST")
                val devices = vkEnv.getMethod("getDevices").also { it.isAccessible = true }.invoke(null) as java.util.stream.Stream<Any>
                devices.use { stream -> stream.forEach { device ->
                    println("JBR Vulkan device: " + device.javaClass.getMethod("getName").also { it.isAccessible = true }.invoke(device))
                } }
            }
        } finally { graphics.dispose() }
    }

    private data class NativeFrame(val pixels: IntArray, val width: Int, val height: Int)

    private fun commit(window: Window) {
        val peers = Class.forName("sun.awt.wl.WLToolkit").getDeclaredField("wlSurfaceToPeerMap")
            .also { it.isAccessible = true }.get(null) as Map<*, *>
        val peer = synchronized(peers) { peers.values.first { candidate ->
            candidate != null && candidate.javaClass.getMethod("getTarget").also { it.isAccessible = true }.invoke(candidate) === window.frame
        } }!!
        peer.javaClass.getMethod("commitToServer").also { it.isAccessible = true }.invoke(peer)
        Toolkit.getDefaultToolkit().sync()
    }

    /** Reads JBR's real window buffer. panel.paintAll(BufferedImage) alone does not prove presentation. */
    private fun actualFrame(window: Window): NativeFrame = ui {
        assertDestination(window)
        val graphics = assertNotNull(window.frame.graphics as? Graphics2D)
        try {
            window.panel.paintAll(graphics)
            commit(window)
            val data = destination(graphics)
            val bounds = data.javaClass.getMethod("getBounds").also { it.isAccessible = true }.invoke(data) as Rectangle
            assertEquals(0, bounds.x)
            assertEquals(0, bounds.y)
            val scale = window.panel.graphicsConfiguration.defaultTransform.scaleX
            assertEquals((window.panel.width * scale).toInt(), bounds.width)
            assertEquals((window.panel.height * scale).toInt(), bounds.height)
            val grabber = Class.forName("sun.java2d.wl.WLPixelGrabberExt")
            assertTrue(grabber.isInstance(data), "The actual destination supports a pinned JBR buffer snapshot")
            val pixels = grabber.getMethod("getRGBPixelsAt", Rectangle::class.java)
                .also { it.isAccessible = true }.invoke(data, bounds) as IntArray
            NativeFrame(pixels, bounds.width, bounds.height)
        } finally { graphics.dispose() }
    }

    private fun assertMatchesSoftware(window: Window, label: String): NativeFrame {
        val actual = actualFrame(window)
        val expected = ui {
            val image = BufferedImage(actual.width, actual.height, BufferedImage.TYPE_INT_ARGB_PRE)
            val graphics = image.createGraphics()
            try {
                val scale = window.panel.graphicsConfiguration.defaultTransform.scaleX
                graphics.scale(scale, scale)
                window.panel.paintAll(graphics)
            } finally { graphics.dispose() }
            image.getRGB(0, 0, actual.width, actual.height, null, 0, actual.width)
        }
        assertEquals(expected.size, actual.pixels.size)
        var differences = 0
        var maxDifference = 0
        for (pixel in expected.indices) {
            if (actual.pixels[pixel] != expected[pixel]) differences++
            assertEquals(255, actual.pixels[pixel] ushr 24, "$label: opaque panel destination at pixel $pixel")
            for (shift in listOf(24, 16, 8, 0)) {
                val difference = abs(((actual.pixels[pixel] ushr shift) and 255) - ((expected[pixel] ushr shift) and 255))
                maxDifference = maxOf(maxDifference, difference)
                assertTrue(difference <= 2, "$label: native destination differs by $difference/255 at " +
                    "(${pixel % actual.width},${pixel / actual.width}), channel$shift")
            }
        }
        println("$label: native/reference ${actual.width}x${actual.height}, differing pixels=$differences, max=$maxDifference/255")
        return actual
    }

    private fun sample(x: Float, time: Long) = InkPointerSample(x, 64f, time, InputToolType.STYLUS, .7f)

    private fun send(window: Window, event: InkInputEvent) = ui {
        window.panel.session.handle(event, window.panel.brush) { _, stroke -> window.finished.add(stroke) }
        window.panel.session.advance(1020L)
        window.panel.requestInkRender()
    }

    private fun center(frame: NativeFrame, x: Int = 80, y: Int = 64): Int {
        val scale = System.getProperty("byteink.test.waylandScale").toInt()
        return frame.pixels[(y * scale) * frame.width + x * scale]
    }

    @Test fun realNativeDestinationPresentsWetAlphaAndFinishedHandoff() {
        val window = window()
        try {
            val blank = assertMatchesSoftware(window, "native blank frame")
            assertEquals(0xffffffff.toInt(), center(blank))
            send(window, InkInputEvent.Begin(sample(20f, 1000L)))
            send(window, InkInputEvent.Move(sample(180f, 1010L)))
            val wet = assertMatchesSoftware(window, "native translucent wet frame")
            assertTrue((center(wet) and 255) in 125..130, "One 50% black stroke applies alpha once over white")
            send(window, InkInputEvent.Finish(sample(180f, 1011L)))
            ui {
                assertTrue(window.panel.session.liveStrokes.isEmpty())
                assertEquals(1, window.finished.size)
                assertEquals(3, window.finished.single().inputs.size, "The canonical stroke contains only the three delivered observations")
            }
            val dry = assertMatchesSoftware(window, "native finished handoff")
            assertEquals(center(wet), center(dry), "Wet-to-dry handoff preserves native alpha")
            send(window, InkInputEvent.Begin(sample(20f, 1012L)))
            send(window, InkInputEvent.Move(sample(180f, 1013L)))
            val overlapping = assertMatchesSoftware(window, "native translucent live over finished")
            assertTrue((center(overlapping) and 255) in 61..66, "Two separate strokes compose in order")
            send(window, InkInputEvent.Cancel)
            val canceled = assertMatchesSoftware(window, "native canceled wet frame")
            assertEquals(center(dry), center(canceled), "Cancellation restores the finished native destination")
        } finally { ui { window.close() } }
    }

    @Test fun nativeHideResizeReconfigureAndDisabledFallbackPreserveFinishedPixels() {
        val window = window()
        try {
            ui {
                val inputs = MutableStrokeInputBatch().apply {
                    add(InputToolType.STYLUS, 20f, 64f, 0L, pressure = .7f)
                    add(InputToolType.STYLUS, 180f, 64f, 10L, pressure = .7f)
                }
                window.finished.add(Stroke(window.panel.brush, inputs))
                window.panel.drawContent = { canvas, _, _ -> window.finished.forEach { window.panel.renderer.render(canvas, it) } }
                window.panel.requestInkRender()
            }
            val initial = assertMatchesSoftware(window, "native finished before resize")
            ui { window.frame.setSize(320, 224) }
            await("configured resized native window") {
                if (window.panel.width != 320 || window.panel.height != 224 || WaylandRuntime.findWindowSurface(window.panel) == null) false
                else {
                    val graphics = window.frame.graphics as? Graphics2D
                    if (graphics == null) false else try {
                        val data = destination(graphics)
                        val bounds = data.javaClass.getMethod("getBounds").also { it.isAccessible = true }.invoke(data) as Rectangle
                        val scale = window.panel.graphicsConfiguration.defaultTransform.scaleX
                        bounds.width == (320 * scale).toInt() && bounds.height == (224 * scale).toInt()
                    } finally { graphics.dispose() }
                }
            }
            val resized = assertMatchesSoftware(window, "native resized destination")
            assertEquals(center(initial), center(resized))
            ui { window.frame.isVisible = false }
            await("hidden panel releases retained pixel buffers") { window.panel.retainedPixelBytes == 0L }
            val beforeShow = ui { window.panel.renderedFrameCount }
            ui { window.frame.isVisible = true }
            await("native surface reconfigured after show") {
                window.panel.renderedFrameCount > beforeShow && WaylandRuntime.findWindowSurface(window.panel) != null
            }
            val restored = assertMatchesSoftware(window, "native hide/show reconfiguration")
            assertEquals(center(resized), center(restored))
            ui { window.panel.authoringEnabled = false; window.panel.requestInkRender() }
            val disabled = assertMatchesSoftware(window, "native authoring-disabled full redraw")
            assertEquals(center(restored), center(disabled))
            ui { assertEquals(0L, window.panel.retainedPixelBytes) }
        } finally { ui { window.close() } }
        ui { assertFailsWith<IllegalStateException> { window.panel.renderer.canDraw(window.finished.single()) } }
    }
}
