package com.vivenotes.byteink.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.Stroke
import org.jetbrains.skiko.FrameBuffering
import org.jetbrains.skiko.SkiaLayer
import org.jetbrains.skiko.SkiaLayerProperties
import org.jetbrains.skiko.SkikoRenderDelegate
import java.awt.BorderLayout
import java.awt.EventQueue
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Dedicated desktop Ink surface. Native packets enqueue real history and one immediate Skia redraw
 * on the AWT event queue, without a Compose frame-clock wait. The layer disables vsync throttling
 * and requests two buffers rather than a third queued frame. The compositor still controls scanout;
 * this is the desktop presentation path, not Android's front-buffer API.
 *
 * Construct, update and close on the AWT event thread. [drawContent] draws the finished scene first,
 * in local pixels, then all wet strokes are drawn with the full mesh renderer by default. Add a
 * completed stroke to that scene synchronously in [onStrokeFinished] for continuous handoff.
 * Brush, transform and completion callback are frozen independently at each pointer down.
 * [inputSource] overrides built-in native capture for a host protocol or a test harness.
 */
public class InkLowLatencyPanel(
    public var brush: Brush,
    public var onStrokeFinished: (Long, Stroke) -> Unit,
    public var strokeToView: AffineTransform = AffineTransform.IDENTITY,
    renderer: InkRenderer? = null,
    private val inputSource: InkInputSource? = null,
    predictorFactory: (() -> InkInputPredictor)? = { InkLinearPredictor() },
    public var drawContent: (Canvas, Int, Int) -> Unit = { _, _, _ -> },
    private val centimetersPerNativePixel: Float? = null,
) : JPanel(BorderLayout()), AutoCloseable {
    public val session: InkAuthoringSession = InkAuthoringSession(predictorFactory)
    public val renderer: InkRenderer = renderer ?: InkMeshRenderer()
    public var clearColorArgb: Int = 0xffffffff.toInt()
    /** Software packet-handler to Skia recording time, excluding hardware/compositor/scanout. */
    public var lastInputToRenderNanos: Long = 0L
        private set
    public var renderedFrameCount: Long = 0L
        private set
    public var processedPacketCount: Long = 0L
        private set
    /** Live HWND/XID, available only while the panel is attached to an AWT window. */
    public val nativeWindowHandle: Long get() { check(isDisplayable); return layer.windowHandle }
    private val ownsRenderer = renderer == null
    private var closed = false
    private var subscription: AutoCloseable? = null
    private var renderQueued = false
    private var pendingInputNanos: Long? = null
    private val layer = SkiaLayer(properties = SkiaLayerProperties(
        isVsyncEnabled = false,
        isVsyncFramelimitFallbackEnabled = false,
        frameBuffering = FrameBuffering.DOUBLE,
    ))
    private val timer = Timer(4) { requestInkRender() }.also { it.isRepeats = true }

    public var authoringEnabled: Boolean = true
        set(value) {
            checkUiThread()
            if (field == value) return
            field = value
            if (value && isDisplayable && !closed) connect() else disconnect()
            requestInkRender()
        }

    init {
        checkUiThread()
        add(layer, BorderLayout.CENTER)
        layer.renderDelegate = SkikoRenderDelegate { canvas, width, height, nanoTime ->
            if (!closed) {
                session.advanceNow(nanoTime)
                canvas.clear(clearColorArgb)
                val scale = layer.contentScale
                canvas.save()
                try {
                    canvas.scale(scale, scale)
                    val logicalWidth = (width / scale).toInt()
                    val logicalHeight = (height / scale).toInt()
                    val composeCanvas = canvas.asComposeCanvas()
                    drawContent(composeCanvas, logicalWidth, logicalHeight)
                    val viewport = Rect(0f, 0f, logicalWidth.toFloat(), logicalHeight.toFloat())
                    session.liveStrokes.forEach { live -> this.renderer.render(composeCanvas, live.stroke, live.strokeToView, viewport) }
                } finally { canvas.restore() }
                renderedFrameCount++
                pendingInputNanos?.let { lastInputToRenderNanos = (System.nanoTime() - it).coerceAtLeast(0L) }
                pendingInputNanos = null
                if (session.needsAnimationTick()) timer.start() else timer.stop()
            }
        }
    }

    override public fun addNotify() {
        checkUiThread()
        check(!closed) { "InkLowLatencyPanel is closed" }
        super.addNotify()
        try { if (authoringEnabled) connect() }
        catch (failure: Throwable) { disconnect(); throw failure }
    }

    override public fun removeNotify() {
        disconnect()
        super.removeNotify()
    }

    /** Requests a direct render after updating a finished scene or view; multiple requests coalesce. */
    public fun requestInkRender() {
        checkUiThread()
        if (closed || !isDisplayable || renderQueued) return
        renderQueued = true
        EventQueue.invokeLater {
            renderQueued = false
            if (!closed && isDisplayable) layer.renderImmediately()
        }
    }

    override public fun close() {
        checkUiThread()
        if (closed) return
        disconnect()
        closed = true
        session.close()
        layer.dispose()
        if (ownsRenderer) this.renderer.clearCache()
    }

    private fun connect() {
        if (subscription != null) return
        var attached = true
        val source = inputSource ?: NativeInkInputSource(layer.canvas, layer.windowHandle, { layer.contentScale }, centimetersPerNativePixel)
        try {
            val acquired = source.subscribe { event ->
                if (attached && authoringEnabled && !closed) {
                    checkUiThread()
                    pendingInputNanos = pendingInputNanos ?: System.nanoTime()
                    session.handle(event, brush, strokeToView, onStrokeFinished)
                    processedPacketCount++
                    requestInkRender()
                }
            }
            if (closed || !authoringEnabled || !isDisplayable) {
                attached = false
                acquired.close()
                session.cancelAll()
            } else subscription = AutoCloseable { attached = false; acquired.close() }
        } catch (failure: Throwable) {
            attached = false
            session.cancelAll()
            throw failure
        }
    }
    private fun disconnect() {
        checkUiThread()
        timer.stop()
        val old = subscription
        subscription = null
        try { old?.close() } finally { session.cancelAll(); pendingInputNanos = null }
    }
    private fun checkUiThread() { check(EventQueue.isDispatchThread()) { "Use InkLowLatencyPanel on the AWT event thread" } }
}

private class InkPanelHolder { var panel: InkLowLatencyPanel? = null }

/** Embeds the dedicated native/Skia authoring panel in a Compose Desktop layout. */
@Composable
public fun InkLowLatencySurface(
    brush: Brush,
    modifier: Modifier = Modifier,
    strokeToView: AffineTransform = AffineTransform.IDENTITY,
    renderer: InkRenderer? = null,
    enabled: Boolean = true,
    inputSource: InkInputSource? = null,
    centimetersPerNativePixel: Float? = null,
    onStrokeFinished: (Long, Stroke) -> Unit,
    drawContent: (Canvas, Int, Int) -> Unit = { _, _, _ -> },
) {
    val holder = remember(renderer, inputSource, centimetersPerNativePixel) { InkPanelHolder() }
    DisposableEffect(holder) { onDispose {
        holder.panel?.let { panel ->
            if (EventQueue.isDispatchThread()) panel.close() else EventQueue.invokeLater { panel.close() }
        }
    } }
    key(holder) {
        SwingPanel(modifier = modifier, factory = {
            InkLowLatencyPanel(brush, onStrokeFinished, strokeToView, renderer, inputSource,
                drawContent = drawContent, centimetersPerNativePixel = centimetersPerNativePixel).also {
                it.authoringEnabled = enabled
                holder.panel = it
            }
        }, update = { panel ->
            panel.brush = brush
            panel.strokeToView = strokeToView
            panel.onStrokeFinished = onStrokeFinished
            panel.drawContent = drawContent
            panel.authoringEnabled = enabled
            panel.requestInkRender()
        })
    }
}
