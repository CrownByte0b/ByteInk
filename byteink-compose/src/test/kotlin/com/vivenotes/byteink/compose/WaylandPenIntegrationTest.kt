package com.vivenotes.byteink.compose

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.subtract
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
    private fun <T> responsiveUi(action: () -> T): T {
        val task = FutureTask(action)
        EventQueue.invokeLater(task)
        return task.get(2, TimeUnit.SECONDS)
    }
    private fun awaitReadersStopped() = await("native Wayland readers release their callbacks off EDT") {
        Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "byteink-wayland-pen" }
    }
    private class Window(val frame: JFrame, val panel: InkLowLatencyPanel, val finished: MutableList<Stroke>) : AutoCloseable {
        override fun close() { panel.close(); frame.dispose() }
    }
    private fun window(predictorFactory: (() -> InkInputPredictor)? = { InkLinearPredictor() }): Window {
        assertTrue(ui { WaylandRuntime.isWayland() }, "Tests must use JBR's native Wayland toolkit")
        val window = ui {
            val finished = mutableListOf<Stroke>()
            val frame = JFrame("Isolated Wayland pen verification").also { it.isUndecorated = true }
            val panel = InkLowLatencyPanel(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 8f),
                { _, stroke -> finished.add(stroke) }, predictorFactory = predictorFactory)
            panel.preferredSize = Dimension(192, 128)
            val host = JPanel(java.awt.BorderLayout()).also {
                it.border = BorderFactory.createEmptyBorder(8, 12, 4, 6); it.add(panel)
            }
            frame.contentPane.add(host); frame.pack(); frame.isVisible = true
            Window(frame, panel, finished)
        }
        await("configured Wayland surface, native capture and first Skia frame") {
            window.panel.renderedFrameCount > 0 && window.panel.isInputReady
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

    private fun painterPixels(window: Window, directlyPaintLayer: Boolean = false): IntArray = ui {
        val image = BufferedImage(window.panel.width, window.panel.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        try {
            if (directlyPaintLayer) window.panel.components.single().paint(graphics) else window.panel.paintAll(graphics)
        } finally { graphics.dispose() }
        image.getRGB(0, 0, image.width, image.height, null, 0, image.width)
    }

    private fun assertActualPainterMatchesFullRedraw(window: Window, label: String) = ui {
        val retained = painterPixels(window)
        val budget = window.panel.retainedPixelBudgetBytes
        try {
            window.panel.retainedPixelBudgetBytes = 0L
            val full = painterPixels(window)
            if (!retained.contentEquals(full)) {
                val at = full.indices.first { full[it] != retained[it] }
                fail("$label: actual retained/full painters differ at (${at % window.panel.width}, " +
                    "${at / window.panel.width}): ${retained[at].toUInt().toString(16)} / ${full[at].toUInt().toString(16)}")
            }
        } finally {
            window.panel.retainedPixelBudgetBytes = budget
            painterPixels(window)
        }
    }

    @Test fun syntheticTabletDiagnosticsMeasureQueueRasterAndPaintReturnWithoutChangingOutput() {
        val window = window(predictorFactory = null)
        try {
            ui { window.panel.drawContent = { canvas, _, _ -> window.finished.forEach { window.panel.renderer.render(canvas, it) } } }
            injector(window).use { input ->
                fun trace(): Stroke {
                    val count = ui { window.finished.size }
                    send(window, input, 0, x = 20.0, time = 4000)
                    await("synthetic diagnostic down is rendered") {
                        window.panel.session.liveStrokes.singleOrNull()?.stroke?.getRealInputCount() == 1
                    }
                    repeat(32) { index ->
                        send(window, input, 1, x = 24.0 + index * 4.0, time = 4001 + index)
                        await("synthetic diagnostic observation ${index + 1} is rendered") {
                            window.panel.session.liveStrokes.singleOrNull()?.stroke?.getRealInputCount() == index + 2
                        }
                    }
                    send(window, input, 2, x = 152.0, time = 4033)
                    await("synthetic diagnostic finish is painted") {
                        window.finished.size == count + 1 && window.panel.session.liveStrokes.isEmpty()
                    }
                    // A queued immediate paint runs before this barrier, without forcing an internal commit.
                    ui { }
                    return ui { window.finished.last() }
                }
                val control = trace()
                val controlPixels = painterPixels(window)
                val diagnostics = ui {
                    window.finished.clear()
                    window.panel.requestInkRender()
                    InkLatencyDiagnostics(512).also { window.panel.latencyDiagnostics = it }
                }
                val measured = trace()
                val measuredPixels = painterPixels(window)
                assertEquals(control.inputs.size, measured.inputs.size)
                assertEquals(34, measured.inputs.size)
                repeat(measured.inputs.size) { index ->
                    assertEquals(control.inputs[index], measured.inputs[index], "diagnostics preserve real observation $index")
                }
                assertContentEquals(controlPixels, measuredPixels, "diagnostics preserve exact finished pixels")
                val snapshot = ui { diagnostics.snapshot() }
                assertEquals(34, snapshot.inputs.sumOf { it.realSampleCount })
                assertTrue(snapshot.inputs.all { it.origin == InkLatencyInputOrigin.NATIVE_QUEUE })
                assertTrue(snapshot.inputs.all { it.oldestQueueWaitNanos!! >= it.newestQueueWaitNanos!! })
                assertTrue(snapshot.inputs.all { it.normalizationNanos != null })
                val timed = snapshot.frames.filter { it.inputEventCount > 0 && it.frameRequestNanos != null }
                assertTrue(timed.isNotEmpty(), "Capture normal immediate requests without a test-only peer commit")
                assertTrue(timed.all { it.path == InkLatencyRenderPath.SWING_SOFTWARE && it.completed })
                assertTrue(timed.all { it.swingTransferAndDrawNanos != null && it.renderQueueNanos != null })
                assertTrue(timed.all { it.oldestInputToRequestReturnNanos!! >= it.oldestInputToDrawNanos!! })
                assertTrue(timed.all { it.oldestInputToDrawNanos!! >= it.newestInputToDrawNanos!! })
                assertEquals(0L, snapshot.overwrittenInputs)
                writeLatencyReport(snapshot, "wayland-synthetic-tablet")
                val oldInputCount = snapshot.inputs.size
                ui { window.panel.authoringEnabled = false; diagnostics.clear() }
                send(window, input, 0, time = 5000)
                Thread.sleep(50)
                ui {
                    assertTrue(diagnostics.snapshot().inputs.isEmpty())
                    assertTrue(diagnostics.snapshot().frames.all { it.inputEventCount == 0 })
                    assertTrue(oldInputCount > 0)
                }
            }
        } finally { ui { window.close() } }
        awaitReadersStopped()
    }

    private fun writeLatencyReport(snapshot: InkLatencySnapshot, name: String) {
        val directory = Path.of(System.getProperty("byteink.test.latencyReports", "build/reports/software-latency"))
        Files.createDirectories(directory)
        val scale = ui { java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX }
        val report = "{\"workload\":\"$name\",\"syntheticInput\":true,\"physicalInputDevice\":false," +
            "\"compositor\":\"private headless Weston\",\"scale\":$scale," +
            "\"prediction\":false,\"runtime\":\"${System.getProperty("java.runtime.version")}\",\"diagnostics\":${snapshot.toJson()}}"
        Files.writeString(directory.resolve("$name-scale$scale.json"), report)
    }

    @Test fun nativeDirtyFramesRetainFinishedContentAndMatchFullPainterThroughEraseAndReconnect() {
        val window = window(predictorFactory = null)
        var contentCalls = 0
        try {
            ui {
                window.panel.brush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0x80000000.toInt(), 12f)
                window.panel.drawContent = { canvas, _, _ ->
                    contentCalls++
                    window.finished.forEach { window.panel.renderer.render(canvas, it) }
                }
                window.panel.requestInkRender()
            }
            await("initial retained finished raster") { contentCalls > 0 && window.panel.retainedPixelBytes > 0L }
            injector(window).use { input ->
                val initialCalls = ui { contentCalls }
                send(window, input, 0)
                await("translucent native contact") { window.panel.session.liveStrokes.size == 1 }
                val beforeMove = ui { window.panel.renderedFrameCount }
                send(window, input, 1, 100.0, time = 1010)
                await("retained native move reached the renderer") {
                    window.panel.session.liveStrokes.single().stroke.getRealInputCount() == 2 &&
                        window.panel.renderedFrameCount > beforeMove
                }
                ui {
                    assertEquals(initialCalls, contentCalls, "native contact frames reuse the finished raster")
                    assertTrue(window.panel.retainedDirtyRedrawCount > 0L)
                    val scale = window.panel.graphicsConfiguration.defaultTransform.scaleX
                    val physicalPixels = (window.panel.width * scale).toLong() * (window.panel.height * scale).toLong()
                    assertTrue(window.panel.retainedLastRedrawnPixelCount in 1L until physicalPixels,
                        "native motion repaints a proper subregion at the current scale")
                }
                val wetPixel = inkPixel(window)
                assertTrue((wetPixel and 255) in 120..135, "one translucent native stroke applies its alpha once")
                assertActualPainterMatchesFullRedraw(window, "native wet stroke")
                val beforeCancel = ui { contentCalls to window.panel.renderedFrameCount }
                send(window, input, 3, time = 1011)
                await("canceled wet pixels repainted") {
                    window.panel.session.liveStrokes.isEmpty() && window.panel.renderedFrameCount > beforeCancel.second
                }
                assertEquals(0xffffffff.toInt(), inkPixel(window), "cancel restores the finished background")
                ui { assertEquals(beforeCancel.first, contentCalls) }
                send(window, input, 0, time = 1020)
                send(window, input, 1, 100.0, time = 1030)
                val beforeFinishCalls = ui { contentCalls }
                send(window, input, 2, 140.0, time = 1040)
                await("finished background rebuilt after synchronous handoff") {
                    window.finished.size == 1 && window.panel.session.liveStrokes.isEmpty() && contentCalls > beforeFinishCalls
                }
                assertEquals(wetPixel, inkPixel(window), "finished handoff retains the original alpha")
                assertActualPainterMatchesFullRedraw(window, "native finished handoff")
                ui {
                    val batch = MutableStrokeInputBatch().apply {
                        add(InputToolType.STYLUS, 60f, 20f, 0L)
                        add(InputToolType.STYLUS, 60f, 100f, 10L)
                    }
                    val mask = ViveBrushes.eraseMask(batch, 24f)
                    val pieces = listOf(PageStroke("native-cut", window.finished.single())).subtract(mask, listOf("native-cut"))
                    window.finished.clear()
                    window.finished.addAll(pieces.map { it.stroke })
                    window.panel.requestInkRender()
                }
                await("partial erase refreshed finished pixels") { inkPixel(window) == 0xffffffff.toInt() }
                assertActualPainterMatchesFullRedraw(window, "native partial erase")
            }
            val configuredBudget = ui { window.panel.retainedPixelBudgetBytes }
            ui {
                val enabledPixels = painterPixels(window)
                window.panel.authoringEnabled = false
                assertEquals(0L, window.panel.retainedPixelBytes, "disable releases both retained rasters")
                val before = window.panel.renderedFrameCount
                val disabledPixels = painterPixels(window, directlyPaintLayer = true)
                assertTrue(window.panel.renderedFrameCount > before, "the disabled explicit paint reached the actual delegate")
                assertTrue(enabledPixels.contentEquals(disabledPixels), "disabled authoring still paints finished content exactly")
                assertEquals(0L, window.panel.retainedPixelBytes, "explicit disabled paint cannot recreate retained rasters")
                assertEquals(configuredBudget, window.panel.retainedPixelBudgetBytes, "disable preserves the configured budget")
                assertTrue(window.panel.retainedLastRedrawnPixelCount > 0L, "fallback still reports its raster work")
            }
            ui {
                assertEquals(0L, window.panel.retainedPixelBytes, "the queued disabled paint also leaves retained bytes at zero")
                window.panel.authoringEnabled = true
            }
            await("reenabling authoring restores retained buffers and native capture") {
                window.panel.retainedPixelBytes > 0L && window.panel.isInputReady
            }
            injector(window).use { input ->
                val before = ui { window.finished.size }
                send(window, input, 6, 160.0, time = 2000)
                await("native input still finishes after reenabling authoring") {
                    window.finished.size == before + 1 && window.panel.session.liveStrokes.isEmpty()
                }
            }
            ui {
                val visiblePixels = painterPixels(window)
                window.frame.isVisible = false
                assertEquals(0L, window.panel.retainedPixelBytes)
                val before = window.panel.renderedFrameCount
                val hiddenPixels = painterPixels(window, directlyPaintLayer = true)
                assertTrue(window.panel.renderedFrameCount > before, "the hidden explicit paint reached the actual delegate")
                assertTrue(visiblePixels.contentEquals(hiddenPixels), "hidden explicit painting retains the full redraw result")
                assertEquals(0L, window.panel.retainedPixelBytes, "hidden paint cannot recreate retained rasters")
                assertEquals(configuredBudget, window.panel.retainedPixelBudgetBytes, "hide preserves the configured budget")
            }
            ui { window.frame.isVisible = true }
            await("retained raster and input reconnect on a fresh Wayland surface") {
                window.panel.retainedPixelBytes > 0L && window.panel.isInputReady
            }
            assertActualPainterMatchesFullRedraw(window, "hide/show reconnect")
        } finally { ui { window.close(); assertEquals(0L, window.panel.retainedPixelBytes) } }
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
        awaitReadersStopped()
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
                awaitReadersStopped()
                ui { window.frame.isVisible = true }
                await("Wayland reattach") { window.panel.isInputReady }
                send(window, input, 0)
                await("new surface contact") { window.panel.session.activePointerIds.size == 1 }
                send(window, input, 2, 110.0, time = 1020)
                await("new surface finish") { window.finished.size == 1 }
                ui { window.panel.authoringEnabled = false }
                send(window, input, 0); send(window, input, 2)
                Thread.sleep(100)
                ui { assertEquals(1, window.finished.size); window.panel.authoringEnabled = true }
                await("capture ready after enabling") { window.panel.isInputReady }
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
                await("current generation ready after hotplug/detach") { window.panel.isInputReady }
                send(window, input, 0, time = 1100)
                await("pen survives hotplug during repeated detach") { window.panel.session.activePointerIds.size == 1 }
                send(window, input, 2, 120.0, time = 1110)
                await("finish after hotplug/detach") { window.finished.size == 1 }
            }
        } finally { ui { window.close() } }
        awaitReadersStopped()
    }

    @Test fun delayedDiscoveryKeepsEdtResponsiveAndRequiresBothSyncReplies() {
        var attachNanos = 0L
        WaylandDiscoveryConnection().use { connection ->
            val ready = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val bridge = responsiveUi {
                val start = System.nanoTime()
                WaylandPenBridge(connection.display(), 1L, { fail("No input devices were advertised") },
                    { ready.incrementAndGet() }, { failure.set(it) }).also { attachNanos = System.nanoTime() - start }
            }
            try {
                val first = connection.nextSync()
                Thread.sleep(150)
                responsiveUi { assertEquals(0, ready.get(), "no readiness before registry discovery") }
                connection.completeSync(first)
                val second = connection.nextSync()
                Thread.sleep(150)
                responsiveUi { assertEquals(0, ready.get(), "globals alone do not establish device readiness") }
                connection.completeSync(second)
                await("asynchronous device discovery completes") { ready.get() == 1 }
                assertNull(failure.get())
            } finally { responsiveUi { bridge.close() }; bridge.termination().get(3, TimeUnit.SECONDS) }
        }
        WaylandDiscoveryConnection().use { reference ->
            val blocked = FutureTask {
                val start = System.nanoTime()
                reference.blockingDiscoveryReference()
                System.nanoTime() - start
            }
            val heartbeat = FutureTask { true }
            EventQueue.invokeLater(blocked)
            try {
                val first = reference.nextSync()
                EventQueue.invokeLater(heartbeat)
                Thread.sleep(150)
                assertFalse(heartbeat.isDone, "the synchronous reference stalls a queued UI task")
                reference.completeSync(first)
                val second = reference.nextSync()
                Thread.sleep(150)
                assertFalse(heartbeat.isDone, "the synchronous reference still stalls during device discovery")
                reference.completeSync(second)
                val blockedNanos = blocked.get(3, TimeUnit.SECONDS)
                assertTrue(heartbeat.get(3, TimeUnit.SECONDS))
                println("Wayland controlled delayed discovery: attachEdtNanos=$attachNanos, " +
                    "blockingReferenceEdtNanos=$blockedNanos, withheldReplyMillis=300")
            } finally {
                if (!blocked.isDone) { reference.disconnectServer(); runCatching { blocked.get(3, TimeUnit.SECONDS) } }
            }
        }
    }

    @Test fun closeDuringEitherDiscoveryBarrierCanReattachOnTheSameDisplay() {
        for (barrier in 1..2) WaylandDiscoveryConnection().use { connection ->
            val ready = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val bridge = responsiveUi { WaylandPenBridge(connection.display(), 1L, {},
                { ready.incrementAndGet() }, { failure.set(it) }) }
            var pending = connection.nextSync()
            if (barrier == 2) { connection.completeSync(pending); pending = connection.nextSync() }
            var closeNanos = 0L
            try {
                responsiveUi { val start = System.nanoTime(); bridge.close(); closeNanos = System.nanoTime() - start }
                bridge.termination().get(3, TimeUnit.SECONDS)
                responsiveUi { assertEquals(0, ready.get()); assertNull(failure.get()) }
                // Deliver the old callback after its queue/upcalls were released. It must neither
                // invoke freed closures nor mark a replacement bridge ready on this shared display.
                connection.completeSync(pending)
                val replacement = responsiveUi { WaylandPenBridge(connection.display(), 1L, {},
                    { ready.incrementAndGet() }, { failure.set(it) }) }
                try {
                    connection.completeSync(connection.nextSync())
                    responsiveUi { assertEquals(0, ready.get()) }
                    connection.completeSync(connection.nextSync())
                    await("replacement capture ready after canceled barrier $barrier") { ready.get() == 1 }
                    assertNull(failure.get())
                } finally { responsiveUi { replacement.close() }; replacement.termination().get(3, TimeUnit.SECONDS) }
                println("Wayland controlled canceled discovery: barrier=$barrier, closeEdtNanos=$closeNanos")
            } finally { bridge.close(); bridge.termination().get(3, TimeUnit.SECONDS) }
        }
    }

    @Test fun disconnectDuringEitherDiscoveryBarrierReportsFailureOnceAndStops() {
        for (barrier in 1..2) WaylandDiscoveryConnection().use { connection ->
            val ready = AtomicInteger()
            val failures = AtomicInteger()
            val bridge = responsiveUi { WaylandPenBridge(connection.display(), 1L, {},
                { ready.incrementAndGet() }, { failures.incrementAndGet() }) }
            try {
                val first = connection.nextSync()
                if (barrier == 2) { connection.completeSync(first); connection.nextSync() }
                connection.disconnectServer()
                assertFailsWith<ExecutionException> { bridge.termination().get(3, TimeUnit.SECONDS) }
                responsiveUi { assertEquals(1, failures.get()); assertEquals(0, ready.get()); bridge.close(); bridge.close() }
            } finally { bridge.close(); runCatching { bridge.termination().get(3, TimeUnit.SECONDS) } }
        }
    }

    @Test fun penAndTouchBeginsBeforeReadinessStayIgnoredWhenTheirFramesArriveAfterIt() {
        WaylandDiscoveryConnection(true).use { connection ->
            val frames = ConcurrentLinkedQueue<NativePenBridge.Frame>()
            val ready = AtomicInteger()
            val failure = AtomicReference<Throwable>()
            val bridge = responsiveUi { WaylandPenBridge(connection.display(), connection.surface(), { frames.add(it) },
                { ready.incrementAndGet() }, { failure.set(it) }) }
            try {
                connection.completeSync(connection.nextSync())
                val devices = connection.nextSync()
                connection.awaitTouchBinding()
                assertEquals(0, ready.get())
                connection.penDownWithoutFrame()
                connection.touchDownWithoutFrame()
                connection.completeSync(devices)
                connection.finishPenContact()
                connection.finishTouchContact()
                // Valid contacts then act as an ordered marker: the last touch finish proves
                // all earlier tablet/touch events, including frames straddling sync, dispatched.
                connection.penDownWithoutFrame()
                connection.finishPenContact()
                connection.touchDownWithoutFrame()
                connection.finishTouchContact()
                await("valid post-readiness contacts finish") {
                    frames.any { it.tool() == NativePenBridge.TOUCH && it.phase() == NativePenBridge.FINISH }
                }
                assertEquals(1, ready.get())
                assertNull(failure.get())
                for (tool in listOf(NativePenBridge.PEN, NativePenBridge.TOUCH)) {
                    assertEquals(listOf(NativePenBridge.BEGIN, NativePenBridge.MOVE, NativePenBridge.FINISH),
                        frames.filter { it.tool() == tool }.map { it.phase() }, "only the contact beginning after readiness is accepted")
                }
                assertTrue(frames.filter { it.tool() == NativePenBridge.PEN }.all { it.points().single().axes() == NativePenBridge.PRESSURE })
                assertTrue(frames.filter { it.tool() == NativePenBridge.TOUCH }.all { it.points().single().axes() == 0 })
            } finally { responsiveUi { bridge.close() }; bridge.termination().get(3, TimeUnit.SECONDS) }
        }
    }

    @Test fun immediateCloseBeforeDiscoveryCanBeRepeatedWithoutReaderOrDescriptorLeaks() {
        val descriptors = Files.list(Path.of("/proc/self/fd")).use { it.count() }
        WaylandDiscoveryConnection().use { connection ->
            val bridges = responsiveUi {
                List(40) { WaylandPenBridge(connection.display(), 1L, {},
                    { fail("Canceled discovery must never publish readiness") },
                    { fail("Canceled discovery failed", it) }).also { it.close(); it.close() } }
            }
            bridges.forEach { it.termination().get(3, TimeUnit.SECONDS) }
        }
        awaitReadersStopped()
        val after = Files.list(Path.of("/proc/self/fd")).use { it.count() }
        assertTrue(after <= descriptors + 2, "canceled discovery leaked descriptors: $descriptors -> $after")
    }

    @Test fun nativeSubscriptionReadinessResetsAndDropsContactsBeginningBeforeDiscovery() {
        val window = window()
        val events = mutableListOf<InkInputEvent>()
        val failures = mutableListOf<Throwable>()
        lateinit var source: NativeInkInputSource
        var subscription: AutoCloseable? = null
        try {
            ui {
                window.panel.authoringEnabled = false
                assertFalse(window.panel.isInputReady)
                val component = window.panel.components.single()
                source = NativeInkInputSource(component, window.panel.nativeWindowHandle, onFailure = { failures.add(it) })
                repeat(20) {
                    source.subscribe { fail("Old subscription delivered input") }.close()
                    assertFalse(source.isReady)
                }
                subscription = source.subscribe { assertTrue(EventQueue.isDispatchThread()); events.add(it) }
                assertFalse(source.isReady, "readiness is published through the EDT queue")
                component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    0, 20, 60, 1, false, MouseEvent.BUTTON1))
            }
            await("current direct native subscription is ready") { source.isReady }
            ui {
                val component = window.panel.components.single()
                component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
                    MouseEvent.BUTTON1_DOWN_MASK, 50, 60, 0, false, MouseEvent.NOBUTTON))
                component.dispatchEvent(MouseEvent(component, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, 80, 60, 1, false, MouseEvent.BUTTON1))
                assertTrue(events.isEmpty(), "the rest of a pre-readiness mouse contact stays ignored")
            }
            injector(window).use { input ->
                send(window, input, 0)
                send(window, input, 2, 100.0, time = 1010)
                await("ready subscription receives a complete real tablet contact") { events.any { it is InkInputEvent.Finish } }
            }
            ui {
                assertEquals(1, events.count { it is InkInputEvent.Begin })
                assertTrue(failures.isEmpty())
                subscription!!.close()
                assertFalse(source.isReady)
            }
        } finally { ui { subscription?.close(); window.close() }; awaitReadersStopped() }
    }

    @Test fun idleNativeQueueWaitsWithoutTimeoutWakeupsAndExplicitShutdownCancelsItsRead() {
        val wire = WaylandWire { throw AssertionError(it) }
        val display = wire.pointer("wl_display_connect", MemorySegment.NULL)
        assertNotEquals(0L, display.address(), "connect to the isolated test compositor")
        val queue = wire.pointer("wl_display_create_queue", display)
        assertNotEquals(0L, queue.address())
        val running = AtomicBoolean(true)
        val failure = AtomicReference<Throwable>()
        val reader = Thread({
            try { wire.readLoop(display, queue) { running.get() } }
            catch (error: Throwable) { failure.set(error) }
        }, "byteink-wayland-idle-test").also { it.isDaemon = true }
        try {
            // This owned test connection has no proxies or requests that can generate events.
            reader.start()
            val deadline = System.nanoTime() + 3_000_000_000L
            while (wire.readPollCount == 0L && reader.isAlive && System.nanoTime() < deadline) Thread.sleep(2)
            assertEquals(1L, wire.readPollCount, "reader entered its first native poll")
            Thread.sleep(150)
            assertTrue(reader.isAlive)
            assertEquals(1L, wire.readPollCount, "an idle queue has no periodic polling wakeups")
            running.set(false)
            wire.wakeReader()
            reader.join(3000)
            assertFalse(reader.isAlive, "the owned eventfd interrupts an indefinite native poll")
            failure.get()?.let { throw AssertionError("native queue reader failed", it) }
            // A roundtrip includes another prepare/read sequence. It would remain blocked if
            // shutdown left the prior reader registered on this display.
            val roundtripFailure = AtomicReference<Throwable>()
            val roundtrip = Thread({
                try { assertTrue(wire.integer("wl_display_roundtrip_queue", display, queue) >= 0) }
                catch (error: Throwable) { roundtripFailure.set(error) }
            }, "byteink-wayland-read-pair-test").also { it.isDaemon = true }
            roundtrip.start()
            roundtrip.join(3000)
            assertFalse(roundtrip.isAlive, "shutdown paired its preparation with cancel_read")
            roundtripFailure.get()?.let { throw AssertionError("display failed after shutdown", it) }
        } finally {
            running.set(false)
            wire.wakeReader()
            reader.join(3000)
            // Keep memory alive if a broken native reader is still using it.
            if (!reader.isAlive && Thread.getAllStackTraces().keys.none { it.isAlive && it.name == "byteink-wayland-read-pair-test" }) {
                wire.procedure("wl_event_queue_destroy", queue)
                wire.procedure("wl_display_disconnect", display)
                wire.close()
            }
        }
    }

    @Test fun capturedErrnoAndOwnedWakeupReleaseDoNotRequireRetiringNativeMetadata() {
        fun eventDescriptors(): Long = Files.list(Path.of("/proc/self/fd")).use { descriptors ->
            descriptors.filter { descriptor ->
                try { Files.readSymbolicLink(descriptor).toString() == "anon_inode:[eventfd]" }
                catch (_: java.io.IOException) { false }
            }.count()
        }
        val before = eventDescriptors()
        repeat(16) {
            WaylandWire { throw AssertionError(it) }.use { wire ->
                Arena.ofConfined().use { local ->
                    val layout = Linker.Option.captureStateLayout()
                    val state = local.allocate(layout)
                    val pollField = WaylandWire::class.java.getDeclaredField("poll").also { it.isAccessible = true }
                    val poll = pollField.get(wire) as MethodHandle
                    // A deliberately impossible nfds count makes the real libc poll return EINVAL.
                    assertEquals(-1, poll.invokeWithArguments(state, MemorySegment.NULL, -1L, 0))
                    val errno = layout.byteOffset(MemoryLayout.PathElement.groupElement("errno"))
                    assertEquals(22, state.get(ValueLayout.JAVA_INT, errno), "errno was captured with the failing call")
                }
                if (it % 2 == 0) {
                    wire.closeWakeup()
                    wire.closeWakeup()
                    // A terminal borrowed display retains protocol metadata while its owned
                    // wake descriptor can already be released.
                    assertNotEquals(0L, wire.protocol.type("zwp_tablet_tool_v2").address())
                }
            }
        }
        assertEquals(before, eventDescriptors(), "all owned wake descriptors were released")
    }
}
