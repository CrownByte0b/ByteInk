package com.vivenotes.byteink.compose

import androidx.ink.brush.InputToolType
import kotlin.math.PI

/**
 * One real pointer observation in the drawing surface's pixel coordinates.
 *
 * [uptimeMillis] is a monotonic event timestamp, in the same clock as other samples and frame
 * updates. [pressure] is null when the device does not report pressure; a platform's synthetic
 * mouse/touch pressure must not be passed as if it were a measured value.
 */
public data class InkPointerSample(
    public val x: Float,
    public val y: Float,
    public val uptimeMillis: Long,
    public val toolType: InputToolType = InputToolType.MOUSE,
    public val pressure: Float? = null,
    /** Angle from the surface normal, in [0, pi/2]. */
    public val tiltRadians: Float? = null,
    /** Shaft azimuth from local +x toward local +y, in [0, 2*pi); independent of barrel twist. */
    public val orientationRadians: Float? = null,
    /** Calibrated centimeters per surface pixel; null when the physical scale is unknown. */
    public val strokeUnitLengthCm: Float? = null,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Pointer coordinates must be finite" }
        require(uptimeMillis >= 0L) { "Pointer uptime must be nonnegative" }
        require(pressure == null || (pressure.isFinite() && pressure in 0f..1f)) {
            "Reported pressure must be finite and within [0, 1]"
        }
        require(tiltRadians == null || (tiltRadians.isFinite() && tiltRadians in 0f..(PI / 2).toFloat())) {
            "Tilt must be within [0, pi/2]"
        }
        require(orientationRadians == null || (orientationRadians.isFinite() && orientationRadians >= 0f && orientationRadians < (2 * PI).toFloat())) {
            "Orientation must be within [0, 2*pi)"
        }
        require(strokeUnitLengthCm == null || (strokeUnitLengthCm.isFinite() && strokeUnitLengthCm > 0f)) {
            "Physical unit length must be finite and positive"
        }
    }
}

/** A gesture supplied by an input adapter, using the same surface coordinates as Compose input. */
public sealed interface InkInputEvent {
    public data class Begin(public val sample: InkPointerSample, public val pointerId: Long = 0L) : InkInputEvent
    public data class Move(public val sample: InkPointerSample, public val pointerId: Long = 0L) : InkInputEvent
    /** Ordered real history followed by a replacement prediction. Predictions are never persisted. */
    public data class Batch(
        public val samples: List<InkPointerSample>,
        /** Null lets the session forecast; an empty list explicitly removes prediction. */
        public val predictedSamples: List<InkPointerSample>? = null,
        public val pointerId: Long = 0L,
    ) : InkInputEvent
    public data class Predict(public val samples: List<InkPointerSample>, public val pointerId: Long = 0L) : InkInputEvent
    public data class Finish(public val sample: InkPointerSample? = null, public val pointerId: Long = 0L) : InkInputEvent
    public data class CancelPointer(public val pointerId: Long) : InkInputEvent
    public data object Cancel : InkInputEvent
}

/**
 * Input adapter seam for native pen APIs such as Windows WM_POINTER or Linux tablet input.
 *
 * Deliver callbacks serially on the Compose UI thread. Timestamps, tool type and pressure must
 * describe the original device observations. Closing the returned subscription releases adapter
 * resources and prevents subsequent callbacks. Pointer IDs distinguish concurrent gestures.
 */
public interface InkInputSource {
    public fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable
}
