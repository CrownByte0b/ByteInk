package com.vivenotes.byteink.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.Stroke

/** Remembers one reusable controller for a drawing surface. */
@Composable
public fun rememberInkAuthoringController(): InkAuthoringController = remember { InkAuthoringController() }

/**
 * Draws [drawContent], then the current real Ink stroke, and authors one stroke per primary-pointer
 * gesture. Coordinates and brush size are in stroke units; [strokeToView] maps them to local pixels.
 * The brush, transform and completion callback are captured at pointer down, so changing a tool or
 * zoom during a gesture cannot change its stored metadata. Completed strokes belong to the caller;
 * add them to [drawContent] in [onStrokeFinished] to keep them visible after pointer up.
 *
 * Mouse and touch input have no pressure. Stylus input uses the pressure Compose reports. A native
 * adapter may provide [inputSource] instead of Compose pointer events; its callbacks must run on
 * the UI thread. Disabling or removing the surface cancels the gesture and closes its subscription.
 * One controller may be attached to only one surface at a time.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
public fun InkDrawingSurface(
    controller: InkAuthoringController,
    brush: Brush,
    modifier: Modifier = Modifier,
    strokeToView: AffineTransform = AffineTransform.IDENTITY,
    renderer: InkPathRenderer = remember { InkPathRenderer() },
    enabled: Boolean = true,
    inputSource: InkInputSource? = null,
    onStrokeFinished: (Stroke) -> Unit,
    drawContent: DrawScope.() -> Unit = {},
) {
    val currentBrush by rememberUpdatedState(brush)
    val currentTransform by rememberUpdatedState(strokeToView)
    val currentCallback by rememberUpdatedState(onStrokeFinished)
    val timing = remember(controller) { FrameTiming() }

    DisposableEffect(controller, inputSource, enabled) {
        var callback: ((Stroke) -> Unit)? = null
        var attached = true
        val subscription = try {
            if (enabled) inputSource?.subscribe { event ->
                if (attached) when (event) {
                    is InkInputEvent.Begin -> {
                        callback = currentCallback
                        controller.begin(currentBrush, event.sample, currentTransform)
                        timing.begin(event.sample.uptimeMillis)
                    }
                    is InkInputEvent.Move -> controller.append(event.sample)
                    is InkInputEvent.Finish -> {
                        val completed = controller.finish(event.sample)
                        val notify = callback
                        callback = null
                        if (completed != null) notify?.invoke(completed)
                    }
                    InkInputEvent.Cancel -> { controller.cancel(); callback = null }
                }
            } else null
        } catch (failure: Throwable) {
            // Acquisition can deliver input before failing, before an onDispose hook exists.
            // Ignore a failed source's retained listener and recover the reusable controller.
            attached = false
            try { controller.cancel() } catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
            throw failure
        }
        onDispose {
            attached = false
            try { subscription?.close() } finally { controller.cancel() }
        }
    }

    LaunchedEffect(controller) {
        snapshotFlow { controller.isDrawing to controller.hasPendingInputs }.collect {
            while (controller.isUpdateNeeded()) {
                withFrameNanos { frameNanos -> controller.advance(timing.uptimeMillis(frameNanos)) }
            }
        }
    }

    val inputModifier = if (enabled && inputSource == null) Modifier.pointerInput(controller, enabled) {
        try {
            awaitEachGesture {
                val down = awaitFirstDown()
                if (down.type == PointerType.Mouse && !currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                val callback = currentCallback
                controller.begin(currentBrush, down.inkSample(), currentTransform)
                timing.begin(down.uptimeMillis)
                down.consume()
                try {
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || change.isConsumed) { controller.cancel(); break }
                        change.historical.forEach { historical ->
                            controller.append(change.inkSample(historical.position.x, historical.position.y,
                                historical.uptimeMillis, includePressure = false))
                        }
                        if (change.changedToUpIgnoreConsumed() ||
                            (down.type == PointerType.Mouse && !event.buttons.isPrimaryPressed)) {
                            val stroke = controller.finish(change.inkSample())
                            change.consume()
                            if (stroke != null) callback(stroke)
                            break
                        }
                        controller.append(change.inkSample())
                        change.consume()
                    }
                } finally { controller.cancel() }
            }
        } finally { controller.cancel() }
    } else Modifier

    Canvas(modifier.then(inputModifier)) {
        drawContent()
        // Read snapshot state in the draw phase, avoiding a composition for every pointer sample.
        @Suppress("UNUSED_VARIABLE") val revision = controller.revision
        controller.liveStroke?.let { drawInk(renderer, it, controller.strokeToView) }
    }
}

private fun PointerInputChange.inkSample(
    x: Float = position.x,
    y: Float = position.y,
    uptime: Long = uptimeMillis,
    includePressure: Boolean = true,
): InkPointerSample {
    val tool = when (type) {
        PointerType.Mouse -> InputToolType.MOUSE
        PointerType.Touch -> InputToolType.TOUCH
        PointerType.Stylus, PointerType.Eraser -> InputToolType.STYLUS
        else -> InputToolType.UNKNOWN
    }
    // Compose's mouse/touch constructors synthesize 1.0; that is not measured pen pressure.
    val measuredPressure = if (includePressure && tool == InputToolType.STYLUS && pressure.isFinite() && pressure in 0f..1f) pressure else null
    return InkPointerSample(x, y, uptime, tool, measuredPressure)
}

private class FrameTiming {
    private var eventUptime: Long = 0L
    private var startNanos: Long = 0L
    private var firstFrameNanos: Long? = null
    private var firstFrameUptime: Long = 0L
    fun begin(uptime: Long) {
        eventUptime = uptime
        startNanos = System.nanoTime()
        firstFrameNanos = null
    }
    fun uptimeMillis(frameNanos: Long): Long {
        if (firstFrameNanos == null) {
            firstFrameNanos = frameNanos
            firstFrameUptime = eventUptime + (System.nanoTime() - startNanos) / 1_000_000L
        }
        return firstFrameUptime + ((frameNanos - requireNotNull(firstFrameNanos)) / 1_000_000L).coerceAtLeast(0L)
    }
}
