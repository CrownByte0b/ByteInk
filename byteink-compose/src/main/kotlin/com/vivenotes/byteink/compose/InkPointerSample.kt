package com.vivenotes.byteink.compose

import androidx.ink.brush.InputToolType

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
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Pointer coordinates must be finite" }
        require(uptimeMillis >= 0L) { "Pointer uptime must be nonnegative" }
        require(pressure == null || (pressure.isFinite() && pressure in 0f..1f)) {
            "Reported pressure must be finite and within [0, 1]"
        }
    }
}

/** A gesture supplied by an input adapter, using the same surface coordinates as Compose input. */
public sealed interface InkInputEvent {
    public data class Begin(public val sample: InkPointerSample) : InkInputEvent
    public data class Move(public val sample: InkPointerSample) : InkInputEvent
    public data class Finish(public val sample: InkPointerSample? = null) : InkInputEvent
    public data object Cancel : InkInputEvent
}

/**
 * Input adapter seam for native pen APIs such as Windows WM_POINTER or Linux tablet input.
 *
 * Deliver callbacks serially on the Compose UI thread. Timestamps, tool type and pressure must
 * describe the original device observations. Closing the returned subscription releases adapter
 * resources and prevents subsequent callbacks. A source reports one active gesture at a time.
 */
public interface InkInputSource {
    public fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable
}
