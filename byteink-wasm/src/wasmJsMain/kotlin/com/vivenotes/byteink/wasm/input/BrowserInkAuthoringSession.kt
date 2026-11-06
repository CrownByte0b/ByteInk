package com.vivenotes.byteink.wasm.input

import com.vivenotes.byteink.wasm.InkBrush
import com.vivenotes.byteink.wasm.InkLiveStroke
import com.vivenotes.byteink.wasm.InkStroke
import com.vivenotes.byteink.wasm.InkWasm

/** One pointer's borrowed live engine and captured brush. The session owns its lifetime. */
public data class BrowserInkLiveStroke(public val pointerId: Int, public val stroke: InkLiveStroke)

/** Real engine authoring with independent simultaneous contacts, replaceable prediction and timed
 * updates. Finished callbacks receive ownership; throwing closes the delivered stroke. Retirement
 * runs before live close, allowing a renderer to drop its cache. Run on the browser UI thread.
 */
public class BrowserInkAuthoringSession(
    private val engine: InkWasm,
    private val brushFor: (BrowserInkEvent.Begin) -> InkBrush,
    private val onFinished: (Int, InkStroke) -> Unit,
    private val onChanged: () -> Unit = {},
    private val onRetire: (InkLiveStroke) -> Unit = {},
    public val predictionLifetimeMillis: Double = 64.0,
) : AutoCloseable {
    init { require(predictionLifetimeMillis.isFinite() && predictionLifetimeMillis >= 0) }
    private class Gesture(val live: InkLiveStroke, val origin: Double) {
        var elapsed = 0.0
        var realTime = 0.0
        var hasPrediction = false
    }
    private val gestures = linkedMapOf<Int, Gesture>()
    private var closed = false
    public val liveStrokes: List<BrowserInkLiveStroke> get() = gestures.map { BrowserInkLiveStroke(it.key, it.value.live) }
    public val needsAnimationFrame: Boolean get() = gestures.values.any { it.hasPrediction || it.live.needsUpdate }

    public fun handle(event: BrowserInkEvent) {
        check(!closed) { "Authoring session is closed" }
        when (event) {
            is BrowserInkEvent.Begin -> {
                cancel(event.pointerId)
                val live = engine.startStroke(brushFor(event))
                val gesture = Gesture(live, event.timeOriginMillis)
                gestures[event.pointerId] = gesture
                try { live.enqueue(listOf(event.sample)); live.update(0.0) }
                catch (error: Throwable) { cancel(event.pointerId); throw error }
                onChanged()
            }
            is BrowserInkEvent.Batch -> {
                val gesture = gestures[event.pointerId] ?: return
                try {
                    gesture.live.enqueue(event.real, event.predicted)
                    gesture.realTime = event.real.lastOrNull()?.elapsedTimeMillis ?: gesture.realTime
                    gesture.elapsed = maxOf(gesture.elapsed, gesture.realTime)
                    gesture.hasPrediction = event.predicted.isNotEmpty()
                    gesture.live.update(gesture.elapsed)
                } catch (error: Throwable) { cancel(event.pointerId); throw error }
                onChanged()
            }
            is BrowserInkEvent.Finish -> {
                val gesture = gestures.remove(event.pointerId) ?: return
                try {
                    val finished = gesture.live.finish(gesture.elapsed)
                    try { onFinished(event.pointerId, finished) } catch (error: Throwable) { finished.close(); throw error }
                } finally { retire(gesture); onChanged() }
            }
            is BrowserInkEvent.Cancel -> cancel(event.pointerId)
        }
    }

    /** Use the same performance.now() clock as browser event timestamps. Old predictions expire
     * even without another pointermove; no predicted observation is ever sent to onFinished.
     */
    public fun advance(timeMillis: Double) {
        check(!closed) { "Authoring session is closed" }
        require(timeMillis.isFinite() && timeMillis >= 0)
        var changed = false
        for ((id, gesture) in gestures.toList()) {
            val elapsed = maxOf(gesture.elapsed, timeMillis - gesture.origin)
            try {
                if (gesture.hasPrediction && elapsed >= gesture.realTime + predictionLifetimeMillis) {
                    gesture.live.enqueue(emptyList()); gesture.hasPrediction = false
                }
                if (gesture.live.needsUpdate) { gesture.live.update(elapsed); changed = true }
                gesture.elapsed = elapsed
            } catch (error: Throwable) { cancel(id); throw error }
        }
        if (changed) onChanged()
    }
    public fun cancelAll() {
        var failure: Throwable? = null
        for (id in gestures.keys.toList()) try { cancel(id) } catch (error: Throwable) {
            if (failure == null) failure = error else failure.addSuppressed(error)
        }
        failure?.let { throw it }
    }
    private fun cancel(id: Int) { gestures.remove(id)?.let { try { retire(it) } finally { onChanged() } } }
    private fun retire(gesture: Gesture) { try { onRetire(gesture.live) } finally { gesture.live.close() } }
    override fun close() { if (!closed) { closed = true; cancelAll() } }
}
