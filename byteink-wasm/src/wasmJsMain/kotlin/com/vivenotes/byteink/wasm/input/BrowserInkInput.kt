@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.vivenotes.byteink.wasm.input

import com.vivenotes.byteink.wasm.InkSample
import com.vivenotes.byteink.wasm.InkToolType
import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.math.*

/** Invertible page-to-surface affine mapping. Surface dimensions use CSS-independent drawing units. */
public data class BrowserInkViewport(
    public val width: Float, public val height: Float,
    public val a: Float = 1f, public val b: Float = 0f, public val c: Float = 0f,
    public val d: Float = 0f, public val e: Float = 1f, public val f: Float = 0f,
    /** Calibrated centimeters per CSS pixel; CSS's nominal 96 dpi is not physical calibration. */
    public val centimetersPerCssPixel: Float? = null,
) {
    init {
        require(listOf(width, height, a, b, c, d, e, f).all { it.isFinite() } && width > 0 && height > 0 &&
            (a.toDouble() * e - b.toDouble() * d).let { it.isFinite() && it != 0.0 }) { "Invalid drawing viewport" }
        require(centimetersPerCssPixel == null || centimetersPerCssPixel.isFinite() && centimetersPerCssPixel > 0)
    }
}

/** Real browser observations in fractions of the captured element rectangle; time keeps sub-ms precision. */
public data class BrowserInkObservation(
    public val x: Double, public val y: Double, public val timeMillis: Double,
    public val pressure: Float? = null, public val tiltRadians: Float? = null,
    public val orientationRadians: Float? = null,
)

public sealed interface BrowserInkEvent {
    public val pointerId: Int
    /** Configuration and axis availability are captured independently for each pointer at Begin. */
    public data class Begin(override val pointerId: Int, public val sample: InkSample,
        public val eraser: Boolean, public val timeOriginMillis: Double) : BrowserInkEvent
    /** Ordered real history and replacement prediction. An empty prediction retracts the old tail. */
    public data class Batch(override val pointerId: Int, public val real: List<InkSample>,
        public val predicted: List<InkSample>) : BrowserInkEvent
    public data class Finish(override val pointerId: Int) : BrowserInkEvent
    public data class Cancel(override val pointerId: Int) : BrowserInkEvent
}

/** Per-pointer normalization. Predictions never update real axes/time or suppress a later real sample.
 * A scalar physical unit is omitted for anisotropy/shear. Out-of-order real history is ignored;
 * distinct positions at equal timestamps are retained, with no artificial one-millisecond steps.
 */
public class BrowserInkNormalizer {
    private class Gesture(val viewport: BrowserInkViewport, val tool: InkToolType, val start: Double,
        val cssWidth: Double, val cssHeight: Double, val first: BrowserInkObservation) {
        var last: InkSample? = null
        var realAxes = first
    }
    private val gestures = mutableMapOf<Int, Gesture>()
    public val activePointerCount: Int get() = gestures.size

    public fun begin(pointerId: Int, tool: InkToolType, observation: BrowserInkObservation, viewport: BrowserInkViewport,
        cssWidth: Double, cssHeight: Double, eraser: Boolean = false): BrowserInkEvent.Begin {
        require(cssWidth.isFinite() && cssHeight.isFinite() && cssWidth > 0 && cssHeight > 0)
        require(observation.timeMillis.isFinite() && observation.timeMillis >= 0)
        val gesture = Gesture(viewport, tool, observation.timeMillis, cssWidth, cssHeight, observation)
        val sample = normalize(gesture, observation, false) ?: throw IllegalArgumentException("Invalid initial pointer observation")
        gesture.last = sample
        gestures[pointerId] = gesture
        return BrowserInkEvent.Begin(pointerId, sample, eraser, observation.timeMillis)
    }

    public fun batch(pointerId: Int, real: List<BrowserInkObservation>, predicted: List<BrowserInkObservation>): BrowserInkEvent.Batch? {
        val gesture = gestures[pointerId] ?: return null
        val samples = mutableListOf<InkSample>()
        for (observation in real) {
            if (!observation.timeMillis.isFinite() || observation.timeMillis < gesture.start + (gesture.last?.elapsedTimeMillis ?: 0.0)) continue
            val sample = normalize(gesture, observation, false) ?: continue
            if (sample == gesture.last) continue
            samples += sample; gesture.last = sample
            gesture.realAxes = observation.copy(pressure = sample.pressure,
                tiltRadians = observation.tiltRadians?.takeIf { it.isFinite() && it in 0f..(PI / 2).toFloat() } ?: gesture.realAxes.tiltRadians,
                orientationRadians = observation.orientationRadians?.takeIf { it.isFinite() && it >= 0f && it < (2 * PI).toFloat() } ?: gesture.realAxes.orientationRadians)
        }
        var after = gesture.last?.elapsedTimeMillis ?: 0.0
        val tail = predicted.mapNotNull { observation ->
            val sample = normalize(gesture, observation, true)
            if (sample == null || sample.elapsedTimeMillis <= after || sample.elapsedTimeMillis > (gesture.last?.elapsedTimeMillis ?: 0.0) + 100.0) null
            else sample.also { after = it.elapsedTimeMillis }
        }
        return BrowserInkEvent.Batch(pointerId, samples, tail)
    }

    public fun finish(pointerId: Int): BrowserInkEvent.Finish? = gestures.remove(pointerId)?.let { BrowserInkEvent.Finish(pointerId) }
    public fun cancel(pointerId: Int): BrowserInkEvent.Cancel? = gestures.remove(pointerId)?.let { BrowserInkEvent.Cancel(pointerId) }
    public fun cancelAll(): List<BrowserInkEvent.Cancel> = gestures.keys.toList().mapNotNull(::cancel)

    private fun normalize(g: Gesture, observation: BrowserInkObservation, predicted: Boolean): InkSample? {
        if (!observation.x.isFinite() || !observation.y.isFinite() || !observation.timeMillis.isFinite() || observation.timeMillis < g.start) return null
        val v = g.viewport
        val det = v.a.toDouble() * v.e - v.b.toDouble() * v.d
        val sx = observation.x * v.width - v.c; val sy = observation.y * v.height - v.f
        val x = ((v.e * sx - v.b * sy) / det).toFloat()
        val y = ((-v.d * sx + v.a * sy) / det).toFloat()
        if (!x.isFinite() || !y.isFinite()) return null
        val pen = g.tool == InkToolType.STYLUS
        fun axis(first: Float?, value: Float?, held: Float?, min: Float, max: Float): Float? =
            if (!pen || first == null) null else (if (predicted) held else value)?.takeIf { it.isFinite() && it in min..max } ?: held ?: first
        val pressure = axis(g.first.pressure, observation.pressure, g.realAxes.pressure, 0f, 1f)
        val tilt = axis(g.first.tiltRadians, observation.tiltRadians, g.realAxes.tiltRadians, 0f, (PI / 2).toFloat())
        val orientation = axis(g.first.orientationRadians, observation.orientationRadians, g.realAxes.orientationRadians, 0f, (2 * PI).toFloat())?.let { angle ->
            // First map from CSS axes to drawing surface axes, then through the page inverse.
            val dx = cos(angle.toDouble()) * v.width / g.cssWidth
            val dy = sin(angle.toDouble()) * v.height / g.cssHeight
            val px = (v.e * dx - v.b * dy) / det
            val py = (-v.d * dx + v.a * dy) / det
            ((atan2(py, px) + 2 * PI) % (2 * PI)).toFloat().let { if (it >= (2 * PI).toFloat()) 0f else it }
        }
        val ux = v.e * v.width / g.cssWidth / det; val uy = -v.d * v.width / g.cssWidth / det
        val vx = -v.b * v.height / g.cssHeight / det; val vy = v.a * v.height / g.cssHeight / det
        val l1 = hypot(ux, uy); val l2 = hypot(vx, vy)
        val cm = v.centimetersPerCssPixel?.takeIf { abs(l1 - l2) <= max(l1, l2) * 1e-5 && abs(ux * vx + uy * vy) <= l1 * l2 * 1e-5 }
            ?.let { (it / l1).toFloat() }?.takeIf { it.isFinite() && it > 0f }
        return InkSample(x, y, observation.timeMillis - g.start, g.tool, pressure, tilt, orientation, cm)
    }
}

/** Attach to a drawing element by ID. Uses pointermove coalescing (not duplicate rawupdate), browser
 * predictions where available, capture, multi-contact input, cancel/lost capture/blur/pagehide and
 * resize/scroll cancellation. close removes listeners, releases capture and restores touch-action.
 * The viewport callback runs at pointerdown; pressure and shaft angles are recorded only for pens.
 */
public fun attachBrowserInkInput(elementId: String, viewport: () -> BrowserInkViewport,
    listener: (BrowserInkEvent) -> Unit): AutoCloseable {
    val normalizer = BrowserInkNormalizer()
    val subscription = attach(elementId) { kind, id, tool, eraser, width, height, real, predicted ->
        fun observations(values: JsArray<RawPoint>): List<BrowserInkObservation> = List(values.length) { i ->
            val p = values[i]!!
            BrowserInkObservation(p.x, p.y, p.time, p.pressure.toFloat().takeIf { it.isFinite() && it in 0f..1f },
                p.tilt.toFloat().takeIf { it.isFinite() && it in 0f..(PI / 2).toFloat() },
                p.orientation.takeIf { it.isFinite() && it >= 0 && it < 2 * PI }?.toFloat()?.let {
                    if (it >= (2 * PI).toFloat()) 0f else it
                })
        }
        when (kind) {
            0 -> {
                normalizer.cancel(id)?.let(listener)
                listener(normalizer.begin(id, InkToolType.entries[tool], observations(real).first(), viewport(), width, height, eraser))
            }
            1 -> normalizer.batch(id, observations(real), observations(predicted))?.let(listener)
            2 -> {
                normalizer.batch(id, observations(real), emptyList())?.let(listener)
                normalizer.finish(id)?.let(listener)
            }
            3 -> normalizer.cancel(id)?.let(listener)
        }
    }
    return object : AutoCloseable { override fun close() { subscription.close() } }
}

private external interface RawPoint : JsAny {
    val x: Double; val y: Double; val time: Double; val pressure: Double; val tilt: Double; val orientation: Double
}
private external interface PointerSubscription : JsAny { fun close() }
private fun attach(id: String, callback: (Int, Int, Int, Boolean, Double, Double, JsArray<RawPoint>, JsArray<RawPoint>) -> Unit): PointerSubscription = js("""{
    const element = document.getElementById(id);
    if (!element) throw new Error('Drawing element not found: ' + id);
    const active = new Map(), previousTouchAction = element.style.touchAction;
    element.style.touchAction = 'none';
    let closed = false;
    const point = (event, rect) => {
        let tilt = NaN, orientation = NaN;
        if (event.pointerType === 'pen') {
            if (Number.isFinite(event.altitudeAngle) && Number.isFinite(event.azimuthAngle)) {
                tilt = Math.PI / 2 - event.altitudeAngle; orientation = event.azimuthAngle;
            } else if (Number.isFinite(event.tiltX) && Number.isFinite(event.tiltY)) {
                const x = Math.tan(event.tiltX * Math.PI / 180), y = Math.tan(event.tiltY * Math.PI / 180);
                tilt = Math.atan(Math.hypot(x, y)); orientation = (Math.atan2(y, x) + Math.PI * 2) % (Math.PI * 2);
            }
        }
        return { x: (event.clientX - rect.left) / rect.width, y: (event.clientY - rect.top) / rect.height,
            time: event.timeStamp, pressure: event.pointerType === 'pen' ? event.pressure : NaN, tilt, orientation };
    };
    const release = pointer => { try { if (element.hasPointerCapture(pointer)) element.releasePointerCapture(pointer); } catch (_) {} };
    const cancel = pointer => { if (!active.delete(pointer)) return; release(pointer); callback(3, pointer, 0, false, 0, 0, [], []); };
    const cancelAll = () => {
        let failure;
        for (const pointer of Array.from(active.keys())) { try { cancel(pointer); } catch (error) { failure ??= error; } }
        if (failure) throw failure;
    };
    const down = event => {
        if (closed || event.button !== 0 && event.button !== 5) return;
        const rect = element.getBoundingClientRect();
        if (!(rect.width > 0 && rect.height > 0)) return;
        cancel(event.pointerId);
        const state = { rect, tool: event.pointerType === 'pen' ? 3 : event.pointerType === 'touch' ? 2 : event.pointerType === 'mouse' ? 1 : 0 };
        active.set(event.pointerId, state);
        try {
            element.setPointerCapture(event.pointerId); event.preventDefault();
            callback(0, event.pointerId, state.tool, event.button === 5, rect.width, rect.height, [point(event, rect)], []);
        } catch (error) { cancel(event.pointerId); throw error; }
    };
    const update = (event, finish) => {
        const state = active.get(event.pointerId); if (!state) return;
        if (!finish && event.buttons === 0) { cancel(event.pointerId); return; }
        event.preventDefault();
        const coalesced = !finish && event.getCoalescedEvents ? event.getCoalescedEvents() : [];
        const history = coalesced.length ? coalesced : [event];
        const predicted = !finish && event.getPredictedEvents ? event.getPredictedEvents() : [];
        if (finish) { active.delete(event.pointerId); release(event.pointerId); }
        try { callback(finish ? 2 : 1, event.pointerId, state.tool, false, state.rect.width, state.rect.height,
            history.map(item => point(item, state.rect)), predicted.map(item => point(item, state.rect))); }
        catch (error) { cancel(event.pointerId); throw error; }
    };
    const move = event => update(event, false), up = event => update(event, true), cancelled = event => cancel(event.pointerId);
    const hidden = () => { if (document.hidden) cancelAll(); };
    const options = { capture: true, passive: false };
    const handlers = [['pointerdown', down], ['pointermove', move], ['pointerup', up], ['pointercancel', cancelled], ['lostpointercapture', cancelled]];
    for (const [type, handler] of handlers) element.addEventListener(type, handler, options);
    window.addEventListener('blur', cancelAll); window.addEventListener('pagehide', cancelAll);
    window.addEventListener('scroll', cancelAll, true);
    document.addEventListener('visibilitychange', hidden);
    const resize = new ResizeObserver(() => {
        const rect = element.getBoundingClientRect();
        if (Array.from(active.values()).some(state => state.rect.width !== rect.width || state.rect.height !== rect.height)) cancelAll();
    });
    resize.observe(element);
    return { close() {
        if (closed) return; closed = true;
        for (const [type, handler] of handlers) element.removeEventListener(type, handler, options);
        window.removeEventListener('blur', cancelAll); window.removeEventListener('pagehide', cancelAll);
        window.removeEventListener('scroll', cancelAll, true);
        document.removeEventListener('visibilitychange', hidden); resize.disconnect();
        try { cancelAll(); } finally { element.style.touchAction = previousTouchAction; }
    }};
}""")
