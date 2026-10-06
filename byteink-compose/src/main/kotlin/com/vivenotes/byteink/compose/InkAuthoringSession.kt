package com.vivenotes.byteink.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.Brush
import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke

/** A borrowed active engine and its frozen transform; only read on the session's UI thread. */
public data class InkLiveStroke(
    public val pointerId: Long,
    public val stroke: InProgressStroke,
    public val strokeToView: AffineTransform,
)

/**
 * Simultaneous desktop authoring. Each down captures its brush, transform and callback. Native
 * history is buffered without losing samples; [advance] updates each active engine once. Forecasts
 * are optional, replaceable and removed before canonical completion. Use on one UI thread.
 */
public class InkAuthoringSession(
    private val predictorFactory: (() -> InkInputPredictor)? = { InkLinearPredictor() },
    public val predictionMillis: Long = 12L,
) : AutoCloseable {
    init { require(predictionMillis in 0L..100L) }
    public var revision: Long by mutableLongStateOf(0L)
        private set
    public val activePointerIds: Set<Long> get() = active.keys.toSet()
    public val liveStrokes: List<InkLiveStroke> get() = active.map { (id, gesture) ->
        InkLiveStroke(id, requireNotNull(gesture.controller.liveStroke), gesture.controller.strokeToView)
    }
    private class Gesture(
        val controller: InkAuthoringController,
        val predictor: InkInputPredictor?,
        val callback: (Long, Stroke) -> Unit,
        val startUptime: Long,
        val startNanos: Long,
        var lastUptime: Long,
        var predictionExpiry: Long? = null,
    )
    private val active = linkedMapOf<Long, Gesture>()
    private val idle = ArrayDeque<InkAuthoringController>()
    private var closed = false

    /** Processes one packet; settings are captured only by Begin. Unknown pointer updates are ignored. */
    public fun handle(
        event: InkInputEvent,
        brush: Brush,
        strokeToView: AffineTransform = AffineTransform.IDENTITY,
        onStrokeFinished: (Long, Stroke) -> Unit,
    ) {
        check(!closed) { "InkAuthoringSession is closed" }
        when (event) {
            is InkInputEvent.Begin -> {
                cancel(event.pointerId)
                val controller = if (idle.isEmpty()) InkAuthoringController() else idle.removeFirst()
                try {
                    controller.begin(brush, event.sample, strokeToView)
                    val predictor = predictorFactory?.invoke()
                    predictor?.record(event.sample)
                    active[event.pointerId] = Gesture(controller, predictor, onStrokeFinished,
                        event.sample.uptimeMillis, System.nanoTime(), event.sample.uptimeMillis)
                } catch (failure: Throwable) { controller.close(); throw failure }
                revision++
            }
            is InkInputEvent.Move -> active[event.pointerId]?.let { gesture ->
                record(gesture, event.sample)
                forecast(gesture)
            }
            is InkInputEvent.Batch -> active[event.pointerId]?.let { gesture ->
                event.samples.forEach { record(gesture, it) }
                val supplied = event.predictedSamples
                if (supplied != null) {
                    gesture.controller.setPredictedInputs(supplied)
                    gesture.predictionExpiry = supplied.lastOrNull()?.uptimeMillis
                } else forecast(gesture)
            }
            is InkInputEvent.Predict -> active[event.pointerId]?.let { gesture ->
                gesture.controller.setPredictedInputs(event.samples)
                gesture.predictionExpiry = event.samples.lastOrNull()?.uptimeMillis
            }
            is InkInputEvent.Finish -> {
                val gesture = active.remove(event.pointerId) ?: return
                try {
                    val stroke = gesture.controller.finish(event.sample)
                    revision++
                    if (stroke != null) gesture.callback(event.pointerId, stroke)
                } finally { recycle(gesture) }
            }
            is InkInputEvent.CancelPointer -> cancel(event.pointerId)
            InkInputEvent.Cancel -> cancelAll()
        }
    }

    /** Advances using an explicit event-clock time, useful to hosts with a calibrated frame clock. */
    public fun advance(uptimeMillis: Long): Boolean {
        var changed = false
        active.values.forEach {
            expireForecast(it, uptimeMillis)
            if (it.controller.advance(uptimeMillis)) changed = true
        }
        if (changed) revision++
        return changed
    }

    /** Advances immediately from elapsed monotonic wall time since each down was delivered. */
    public fun advanceNow(nanoTime: Long = System.nanoTime()): Boolean {
        var changed = false
        active.values.forEach { gesture ->
            val elapsed = ((nanoTime - gesture.startNanos) / 1_000_000L).coerceAtLeast(0L)
            val uptime = maxOf(gesture.lastUptime, gesture.startUptime + elapsed)
            // Retire forecasts after their bounded horizon, including when a device stops moving.
            expireForecast(gesture, uptime)
            if (gesture.controller.advance(uptime)) changed = true
        }
        if (changed) revision++
        return changed
    }

    public fun isUpdateNeeded(): Boolean = active.values.any { it.controller.isUpdateNeeded() }

    /** Includes a pending forecast expiry, so a stopped device cannot leave a speculative tail. */
    public fun needsAnimationTick(): Boolean = isUpdateNeeded() || active.values.any { it.predictionExpiry != null }

    public fun cancel(pointerId: Long) {
        active.remove(pointerId)?.let { recycle(it); revision++ }
    }

    public fun cancelAll() {
        active.values.forEach(::recycle)
        if (active.isNotEmpty()) revision++
        active.clear()
    }

    override public fun close() {
        cancelAll()
        idle.forEach(InkAuthoringController::close)
        idle.clear()
        closed = true
    }

    private fun record(gesture: Gesture, sample: InkPointerSample) {
        if (gesture.controller.append(sample)) {
            gesture.lastUptime = sample.uptimeMillis
            gesture.predictor?.record(sample)
        }
    }
    private fun expireForecast(gesture: Gesture, uptime: Long) {
        if (gesture.predictionExpiry?.let { uptime >= it } == true) {
            gesture.controller.setPredictedInputs(emptyList())
            gesture.predictionExpiry = null
        }
    }
    private fun forecast(gesture: Gesture) {
        val samples = gesture.predictor?.predict(
            (gesture.lastUptime + predictionMillis).coerceAtLeast(gesture.lastUptime)) ?: emptyList()
        gesture.controller.setPredictedInputs(samples)
        gesture.predictionExpiry = samples.lastOrNull()?.uptimeMillis
    }
    private fun recycle(gesture: Gesture) {
        gesture.controller.cancel()
        gesture.predictor?.reset()
        // Bound retained native engines when a device supplies many transient contact IDs.
        if (idle.size < 16) idle.addLast(gesture.controller) else gesture.controller.close()
    }
}
