package com.vivenotes.byteink.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput

/**
 * Builds live strokes with the real Ink engine. Use one controller on the Compose UI thread.
 *
 * A gesture freezes its brush, tool type, pressure availability and view transform at [begin].
 * Input is buffered by [append] and enqueued together by [advance], normally once per frame. No
 * prediction is fabricated. The [revision] and [isDrawing] properties are Compose snapshot state,
 * so reading [revision] in a draw block invalidates that block when the wet geometry changes.
 *
 * Out-of-order observations and repeated x/y/time triplets are discarded. Pressure is omitted
 * throughout a gesture when absent at its start; if it was reported at the start, a later missing
 * observation holds the last reported pressure. A tool change is discarded rather than joining
 * two different device streams. The native stroke and input buffers are reused across gestures.
 */
public class InkAuthoringController : AutoCloseable {
    public var revision: Long by mutableLongStateOf(0L)
        private set

    public var isDrawing: Boolean by mutableStateOf(false)
        private set

    /** Scheduling state for frame loops; reading this in a draw block is unnecessary. */
    public var hasPendingInputs: Boolean by mutableStateOf(false)
        private set

    /** The active wet stroke, or null when no gesture is active. Do not mutate it directly. */
    public val liveStroke: InProgressStroke?
        get() = if (isDrawing) engine else null

    /** Immutable snapshot of the page-to-surface transform used for the current gesture. */
    public var strokeToView: AffineTransform = AffineTransform.IDENTITY
        private set

    private var engine: InProgressStroke? = null
    private var incrementalInputs: MutableStrokeInputBatch? = null
    private var emptyPrediction: MutableStrokeInputBatch? = null
    private var viewToStroke: AffineTransform = AffineTransform.IDENTITY
    private var closed: Boolean = false
    private var startUptime: Long = 0L
    private var lastUptime: Long = 0L
    private var lastInputElapsed: Long = 0L
    private var lastNativeTimeSeconds: Float = 0f
    private var lastShapeElapsed: Long = 0L
    private var toolType: InputToolType = InputToolType.MOUSE
    private var reportedPressure: Float? = null
    private var lastX: Float = 0f
    private var lastY: Float = 0f

    /**
     * Starts a gesture and makes its first dot visible immediately. Coordinates are mapped through
     * the inverse of [strokeToView]; a mutable transform supplied by the caller is copied.
     *
     * @throws IllegalStateException if already drawing or closed.
     * @throws IllegalArgumentException if the transform is non-finite, singular, or its inverse or
     * transformed sample cannot be represented by finite floats.
     */
    public fun begin(
        brush: Brush,
        sample: InkPointerSample,
        strokeToView: AffineTransform = AffineTransform.IDENTITY,
    ) {
        check(!closed) { "InkAuthoringController is closed" }
        check(!isDrawing) { "Finish or cancel the active gesture before beginning another" }
        val transform = strokeToView.toImmutable()
        val inverse = checkedInverse(transform)
        val x = mapX(inverse, sample)
        val y = mapY(inverse, sample)
        val stroke = engine ?: InProgressStroke().also { engine = it }
        stroke.start(brush)
        this.strokeToView = transform
        viewToStroke = inverse
        startUptime = sample.uptimeMillis
        lastUptime = sample.uptimeMillis
        lastInputElapsed = 0L
        lastNativeTimeSeconds = 0f
        lastShapeElapsed = 0L
        toolType = sample.toolType
        reportedPressure = sample.pressure
        lastX = x
        lastY = y
        buffer(x, y, 0L)
        enqueuePending(stroke)
        stroke.updateShape(0L)
        isDrawing = true
        revision++
    }

    /** Queues a real observation. Returns false when idle or when its ordering/tool is invalid. */
    public fun append(sample: InkPointerSample): Boolean {
        if (!isDrawing) return false
        if (sample.toolType != toolType || sample.uptimeMillis < lastUptime) return false
        val x = mapX(viewToStroke, sample)
        val y = mapY(viewToStroke, sample)
        val elapsed = sample.uptimeMillis - startUptime
        // Ink stores time as float seconds. At long durations two distinct millisecond timestamps
        // can represent the same native x/y/time triplet, which must also be discarded safely.
        val nativeTimeSeconds = elapsed.toFloat() * 0.001f
        if (x == lastX && y == lastY && nativeTimeSeconds == lastNativeTimeSeconds) return false
        if (reportedPressure != null && sample.pressure != null) reportedPressure = sample.pressure
        buffer(x, y, elapsed)
        lastX = x
        lastY = y
        lastUptime = sample.uptimeMillis
        lastInputElapsed = elapsed
        lastNativeTimeSeconds = nativeTimeSeconds
        return true
    }

    /** Whether a frame must consume buffered observations or advance timed brush behavior. */
    public fun isUpdateNeeded(): Boolean = isDrawing &&
        (hasPendingInputs || requireNotNull(engine).isUpdateNeeded())

    /**
     * Updates queued geometry and any timed brush behavior. [uptimeMillis] uses the input event
     * clock; a late frame or an older timestamp cannot move the engine's animation clock backward.
     * Returns true when a shape update was needed.
     */
    public fun advance(uptimeMillis: Long): Boolean {
        require(uptimeMillis >= 0L) { "Frame uptime must be nonnegative" }
        if (!isDrawing) return false
        val stroke = requireNotNull(engine)
        enqueuePending(stroke)
        if (!stroke.isUpdateNeeded()) return false
        val elapsed = maxOf(lastShapeElapsed, lastInputElapsed, (uptimeMillis - startUptime).coerceAtLeast(0L))
        stroke.updateShape(elapsed)
        lastShapeElapsed = elapsed
        revision++
        return true
    }

    /**
     * Includes the optional final observation and settles timed behavior. Returns null if no
     * gesture is active. The result is rebuilt by Ink from the real inputs, through the same path
     * used when loading a stored stroke. Ink's incremental mesh can differ slightly from this
     * reconstruction, so finalizing establishes the persisted geometry before handing it off.
     * The resulting stroke is independent of subsequent gestures and ready for encoding.
     */
    public fun finish(sample: InkPointerSample? = null): Stroke? {
        if (!isDrawing) return null
        if (sample != null) append(sample)
        val stroke = requireNotNull(engine)
        enqueuePending(stroke)
        stroke.finishInput()
        // Consume the last inputs and complete timed behavior before taking the real input batch.
        stroke.updateShape()
        // A copied incremental mesh is not always the mesh Stroke(brush, inputs) reconstructs:
        // derivative-dependent brushes can freeze slightly different vertices between batches.
        // ViveNotes stores inputs, so finalize through that same real-Ink reconstruction boundary.
        val inputs = stroke.populateInputs(requireNotNull(incrementalInputs))
        val finished = Stroke(requireNotNull(stroke.brush), inputs)
        stroke.clear()
        incrementalInputs?.clear()
        hasPendingInputs = false
        isDrawing = false
        reportedPressure = null
        revision++
        return finished
    }

    /** Discards the active gesture and clears its native geometry. Safe to call while idle. */
    public fun cancel() {
        if (!isDrawing) return
        engine?.clear()
        incrementalInputs?.clear()
        hasPendingInputs = false
        isDrawing = false
        reportedPressure = null
        revision++
    }

    /** Discards any gesture and releases references to native buffers; this controller cannot restart. */
    override public fun close() {
        cancel()
        engine = null
        incrementalInputs = null
        emptyPrediction = null
        closed = true
    }

    private fun buffer(x: Float, y: Float, elapsed: Long) {
        val inputs = incrementalInputs ?: MutableStrokeInputBatch().also { incrementalInputs = it }
        inputs.add(toolType, x, y, elapsed, pressure = reportedPressure ?: StrokeInput.NO_PRESSURE)
        hasPendingInputs = true
    }

    private fun enqueuePending(stroke: InProgressStroke) {
        if (!hasPendingInputs) return
        val inputs = requireNotNull(incrementalInputs)
        val prediction = emptyPrediction ?: MutableStrokeInputBatch().also { emptyPrediction = it }
        stroke.enqueueInputs(inputs, prediction)
        inputs.clear()
        hasPendingInputs = false
    }

    private fun mapX(transform: AffineTransform, sample: InkPointerSample): Float =
        (transform.m00 * sample.x + transform.m10 * sample.y + transform.m20).also {
            require(it.isFinite()) { "Transformed pointer coordinates must be finite" }
        }

    private fun mapY(transform: AffineTransform, sample: InkPointerSample): Float =
        (transform.m01 * sample.x + transform.m11 * sample.y + transform.m21).also {
            require(it.isFinite()) { "Transformed pointer coordinates must be finite" }
        }

    private fun checkedInverse(transform: AffineTransform): ImmutableAffineTransform {
        require(listOf(transform.m00, transform.m10, transform.m20, transform.m01, transform.m11, transform.m21).all(Float::isFinite)) {
            "strokeToView must be finite"
        }
        // Double intermediates avoid overflowing the determinant of an otherwise usable transform.
        val a = transform.m00.toDouble()
        val b = transform.m10.toDouble()
        val c = transform.m20.toDouble()
        val d = transform.m01.toDouble()
        val e = transform.m11.toDouble()
        val f = transform.m21.toDouble()
        val determinant = a * e - b * d
        require(determinant != 0.0) { "strokeToView must be invertible" }
        val values = floatArrayOf(
            (e / determinant).toFloat(), (-b / determinant).toFloat(), ((b * f - c * e) / determinant).toFloat(),
            (-d / determinant).toFloat(), (a / determinant).toFloat(), ((c * d - a * f) / determinant).toFloat(),
        )
        require(values.all(Float::isFinite)) { "The inverse of strokeToView must be finite" }
        return ImmutableAffineTransform(values[0], values[1], values[2], values[3], values[4], values[5])
    }
}
