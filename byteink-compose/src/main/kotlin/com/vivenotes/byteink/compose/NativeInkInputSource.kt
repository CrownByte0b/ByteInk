package com.vivenotes.byteink.compose

import androidx.ink.brush.InputToolType
import java.awt.Component
import java.awt.EventQueue
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.event.HierarchyEvent
import java.awt.event.HierarchyListener
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.tan

/** The native protocol actually used by an attached desktop source. */
public enum class InkNativeBackend { WINDOWS_POINTER, LINUX_XINPUT2, LINUX_WAYLAND_TABLET }

/**
 * Captures real pen, touch and primary mouse input from a live Skia/AWT native window. Windows uses
 * a thread-local message hook and WM_POINTER history. Linux uses XInput2 on an owned X connection,
 * including XWayland. Native Wayland uses tablet-v2 and wl_touch on JBR's connection, plus AWT mouse
 * input. No elevated permissions or tablet-device-file access are needed.
 *
 * [windowHandle] must be the HWND/XID of [component]'s drawing area, obtained after addNotify (for
 * SkiaLayer use windowHandle and canvas). [pixelsPerLocalUnit] converts native pixels to drawing
 * coordinates. [centimetersPerNativePixel] must be calibrated; logical OS DPI is not physical DPI.
 * Subscribe and close on the AWT event thread. Delivery is ordered on that thread. On native Wayland,
 * pass the top-level wl_surface after showing the window, and enable
 * --add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED on JBR. Surface coordinates are mapped to [component].
 * Hide/show replaces the native surface; close and recreate the source (the built-in panel does this).
 */
public class NativeInkInputSource(
    private val component: Component,
    private val windowHandle: Long,
    private val pixelsPerLocalUnit: () -> Float = { 1f },
    private val centimetersPerNativePixel: Float? = null,
    private val onFailure: (Throwable) -> Unit = { throw IllegalStateException("Native ink capture failed", it) },
) : InkInputSource {
    public val backend: InkNativeBackend = when {
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> InkNativeBackend.WINDOWS_POINTER
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) && WaylandRuntime.isWayland() -> InkNativeBackend.LINUX_WAYLAND_TABLET
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> InkNativeBackend.LINUX_XINPUT2
        else -> throw UnsupportedOperationException("Native ink supports Linux x86_64 and Windows x86_64")
    }
    private var subscribed = false
    init {
        require(windowHandle != 0L) { "A live native drawing-area handle is required" }
        require(centimetersPerNativePixel == null || centimetersPerNativePixel.isFinite() && centimetersPerNativePixel > 0f)
        require(System.getProperty("os.arch").lowercase() in setOf("amd64", "x86_64")) { "Native ink requires x86_64" }
    }

    override public fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable {
        check(EventQueue.isDispatchThread()) { "Subscribe on the AWT event thread" }
        check(!subscribed) { "Native input source already has a subscriber" }
        check(component.isDisplayable) { "Attach the drawing component before subscribing" }
        val wayland = if (backend == InkNativeBackend.LINUX_WAYLAND_TABLET) WaylandRuntime.windowSurface(component) else null
        require(wayland == null || wayland.surface == windowHandle) { "The handle must be this component's live top-level wl_surface" }
        val normalizer = NativePenNormalizer(pixelsPerLocalUnit, centimetersPerNativePixel)
        val accepted = mutableSetOf<Long>()
        val hostWindow = SwingUtilities.getWindowAncestor(component)
        var mouseDown = false
        fun localFrame(frame: NativePenBridge.Frame): NativePenBridge.Frame? {
            if (wayland == null) return frame
            if (frame.phase() == NativePenBridge.CANCEL_ALL) { accepted.clear(); mouseDown = false; return frame }
            if (frame.phase() == NativePenBridge.CANCEL) { accepted.remove(frame.pointerId()); return frame }
            val scale = pixelsPerLocalUnit().also { require(it.isFinite() && it > 0f) }
            val offset = SwingUtilities.convertPoint(component, 0, 0, wayland.window)
            val units = wayland.surfaceUnitsPerLocalUnit()
            val points = frame.points().map { point ->
                NativePenBridge.Point((point.x() / units - offset.x) * scale, (point.y() / units - offset.y) * scale,
                    point.ticks(), point.pressure(), point.tiltX(), point.tiltY(), point.axes())
            }
            if (frame.phase() == NativePenBridge.BEGIN) {
                val first = points.firstOrNull() ?: return null
                if (first.x() < 0 || first.y() < 0 || first.x() / scale >= component.width || first.y() / scale >= component.height) return null
                val x = (first.x() / scale).toInt(); val y = (first.y() / scale).toInt()
                if (!component.contains(x, y)) return null
                if (hostWindow != null) {
                    val location = SwingUtilities.convertPoint(component, x, y, hostWindow)
                    val hit = SwingUtilities.getDeepestComponentAt(hostWindow, location.x, location.y)
                    if (hit !== component && (hit == null || !SwingUtilities.isDescendingFrom(hit, component))) return null
                }
                accepted.add(frame.pointerId())
            } else if (frame.pointerId() !in accepted) return null
            if (frame.phase() == NativePenBridge.FINISH) accepted.remove(frame.pointerId())
            return NativePenBridge.Frame(frame.pointerId(), frame.phase(), frame.tool(), points)
        }
        val queue = ArrayDeque<Any>()
        var attached = true
        var scheduled = false
        var bridge: NativePenBridge? = null
        lateinit var subscription: AutoCloseable
        fun drain() {
            check(EventQueue.isDispatchThread())
            val packets = synchronized(queue) {
                scheduled = false
                queue.toList().also { queue.clear() }
            }
            if (!attached) return
            try {
                var i = 0
                while (i < packets.size && attached) {
                    val packet = packets[i++]
                    if (packet is Throwable) {
                        normalizer.clear()
                        try { listener(InkInputEvent.Cancel) } finally { subscription.close() }
                        onFailure(packet)
                        return
                    }
                    if (packet is InkInputEvent) { listener(packet); continue }
                    var frame = packet as NativePenBridge.Frame
                    if (frame.phase() == NativePenBridge.MOVE) {
                        val points = frame.points().toMutableList()
                        while (i < packets.size) {
                            val next = packets[i] as? NativePenBridge.Frame ?: break
                            if (next.phase() != NativePenBridge.MOVE || next.pointerId() != frame.pointerId() || next.tool() != frame.tool()) break
                            points.addAll(next.points()); i++
                        }
                        frame = NativePenBridge.Frame(frame.pointerId(), frame.phase(), frame.tool(), points)
                    }
                    localFrame(frame)?.let { local -> normalizer.events(local).forEach { if (attached) listener(it) } }
                }
            } catch (failure: Throwable) {
                try { subscription.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                throw failure
            }
        }
        fun enqueue(packet: Any) {
            synchronized(queue) {
                if (!attached) return
                queue.addLast(packet)
                if (!scheduled) { scheduled = true; EventQueue.invokeLater(::drain) }
            }
        }
        val mouse = object : MouseAdapter() {
            fun sample(event: MouseEvent): InkPointerSample {
                val age = (System.currentTimeMillis() - event.`when`).coerceAtLeast(0L)
                val scale = pixelsPerLocalUnit()
                return InkPointerSample(event.x.toFloat(), event.y.toFloat(),
                    (NativeMonotonicClock.uptimeMillis() - age).coerceAtLeast(0L), InputToolType.MOUSE,
                    strokeUnitLengthCm = centimetersPerNativePixel?.times(scale))
            }
            override fun mousePressed(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && attached) { mouseDown = true; enqueue(InkInputEvent.Begin(sample(event), 0)) }
            }
            override fun mouseDragged(event: MouseEvent) { if (mouseDown && attached) enqueue(InkInputEvent.Move(sample(event), 0)) }
            override fun mouseReleased(event: MouseEvent) {
                if (event.button == MouseEvent.BUTTON1 && mouseDown && attached) { mouseDown = false; enqueue(InkInputEvent.Finish(sample(event), 0)) }
            }
        }
        val focus = object : FocusAdapter() {
            override fun focusLost(event: FocusEvent) {
                enqueue(NativePenBridge.Frame(0, NativePenBridge.CANCEL_ALL, NativePenBridge.MOUSE, emptyList()))
            }
        }
        val windowFocus = object : WindowAdapter() {
            override fun windowLostFocus(event: WindowEvent) {
                enqueue(NativePenBridge.Frame(0, NativePenBridge.CANCEL_ALL, NativePenBridge.MOUSE, emptyList()))
            }
            override fun windowIconified(event: WindowEvent) {
                enqueue(NativePenBridge.Frame(0, NativePenBridge.CANCEL_ALL, NativePenBridge.MOUSE, emptyList()))
            }
        }
        val hierarchy = HierarchyListener { event ->
            if (attached && (event.changeFlags and HierarchyEvent.DISPLAYABILITY_CHANGED.toLong() != 0L && !component.isDisplayable ||
                        wayland != null && event.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && !component.isShowing)) {
                try { listener(InkInputEvent.Cancel) } finally { subscription.close() }
            }
        }
        subscription = AutoCloseable {
            check(EventQueue.isDispatchThread()) { "Close native input on the AWT event thread" }
            if (attached) {
                synchronized(queue) { attached = false; queue.clear() }
                component.removeFocusListener(focus)
                component.removeHierarchyListener(hierarchy)
                component.removeMouseListener(mouse)
                component.removeMouseMotionListener(mouse)
                hostWindow?.removeWindowFocusListener(windowFocus)
                hostWindow?.removeWindowListener(windowFocus)
                normalizer.clear()
                accepted.clear(); mouseDown = false
                try { bridge?.close() } finally { subscribed = false }
            }
        }
        try {
            subscribed = true
            bridge = when (backend) {
                InkNativeBackend.WINDOWS_POINTER -> WindowsPenBridge(windowHandle, ::enqueue, ::enqueue)
                InkNativeBackend.LINUX_XINPUT2 -> X11PenBridge(windowHandle, ::enqueue, ::enqueue)
                InkNativeBackend.LINUX_WAYLAND_TABLET -> WaylandPenBridge(wayland!!.display, wayland.surface, ::enqueue, ::enqueue)
            }
            component.addFocusListener(focus)
            component.addHierarchyListener(hierarchy)
            if (wayland != null) { component.addMouseListener(mouse); component.addMouseMotionListener(mouse) }
            hostWindow?.addWindowFocusListener(windowFocus)
            hostWindow?.addWindowListener(windowFocus)
        } catch (failure: Throwable) {
            try { subscription.close() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
            throw failure
        }
        return subscription
    }
}

/** Maps unsigned native 32-bit ticks into one local monotonic clock, retaining historical order. */
internal class NativeTickClock {
    private var anchorTicks: Long? = null
    private var anchorUptime: Long = 0L
    fun map(ticks: Long, now: Long): Long {
        val anchor = anchorTicks
        if (anchor == null) {
            anchorTicks = ticks
            anchorUptime = now
            return now
        }
        val delta = (ticks - anchor).toInt().toLong()
        val mapped = (anchorUptime + delta).coerceAtLeast(0L)
        if (delta > 0L) { anchorTicks = ticks; anchorUptime = mapped }
        return mapped
    }
}

internal class NativePenNormalizer(
    private val pixelsPerLocalUnit: () -> Float,
    private val centimetersPerPixel: Float?,
) {
    private val clock = NativeTickClock()
    private val active = mutableSetOf<Long>()
    fun clear() { active.clear() }
    fun events(frame: NativePenBridge.Frame, now: Long = NativeMonotonicClock.uptimeMillis()): List<InkInputEvent> {
        val id = frame.pointerId()
        if (frame.phase() == NativePenBridge.CANCEL_ALL) { clear(); return listOf(InkInputEvent.Cancel) }
        if (frame.phase() == NativePenBridge.CANCEL) {
            active.remove(id)
            return listOf(InkInputEvent.CancelPointer(id))
        }
        val points = frame.points()
        if (points.isEmpty()) return emptyList()
        // Anchor at the newest observation, so the first historical packet remains in the past.
        clock.map(points.last().ticks(), now)
        val scale = pixelsPerLocalUnit().also { require(it.isFinite() && it > 0f) }
        val tool = when (frame.tool()) {
            NativePenBridge.PEN -> InputToolType.STYLUS
            NativePenBridge.TOUCH -> InputToolType.TOUCH
            else -> InputToolType.MOUSE
        }
        val samples = points.map { point ->
            val tilt = if (point.axes() and NativePenBridge.TILT != 0) penTilt(point.tiltX(), point.tiltY()) else null
            InkPointerSample((point.x() / scale).toFloat(), (point.y() / scale).toFloat(),
                clock.map(point.ticks(), now), tool,
                if (point.axes() and NativePenBridge.PRESSURE != 0) point.pressure() else null,
                tilt?.first, tilt?.second, centimetersPerPixel?.times(scale))
        }
        return when (frame.phase()) {
            NativePenBridge.BEGIN -> {
                val result = mutableListOf<InkInputEvent>()
                if (!active.add(id)) result.add(InkInputEvent.CancelPointer(id))
                result.add(InkInputEvent.Begin(samples.first(), id))
                if (samples.size > 1) result.add(InkInputEvent.Batch(samples.drop(1), pointerId = id))
                result
            }
            NativePenBridge.MOVE -> if (id in active) listOf(InkInputEvent.Batch(samples, pointerId = id)) else emptyList()
            NativePenBridge.FINISH -> if (active.remove(id)) buildList {
                if (samples.size > 1) add(InkInputEvent.Batch(samples.dropLast(1), pointerId = id))
                add(InkInputEvent.Finish(samples.last(), id))
            } else emptyList()
            else -> emptyList()
        }
    }
}

private object NativeMonotonicClock {
    private val origin = System.nanoTime()
    fun uptimeMillis(): Long = 1_000_000L + (System.nanoTime() - origin) / 1_000_000L
}

/** Projected x/y tilt angles describe the shaft; barrel rotation is a different quantity. */
internal fun penTilt(xDegrees: Float, yDegrees: Float): Pair<Float, Float> {
    require(xDegrees.isFinite() && yDegrees.isFinite() && xDegrees in -90f..90f && yDegrees in -90f..90f)
    val x = tan(xDegrees.toDouble().coerceIn(-89.999999, 89.999999) * PI / 180)
    val y = tan(yDegrees.toDouble().coerceIn(-89.999999, 89.999999) * PI / 180)
    val tilt = atan(hypot(x, y)).toFloat()
    val turn = (2 * PI).toFloat()
    val orientation = ((atan2(y, x).toFloat() + turn) % turn)
    return tilt to orientation
}
