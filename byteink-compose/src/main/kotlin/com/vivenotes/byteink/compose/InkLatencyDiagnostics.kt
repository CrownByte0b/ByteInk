package com.vivenotes.byteink.compose

import java.awt.EventQueue
import java.util.List.copyOf
import kotlin.math.ceil

/** Drawing in a software Swing raster, or recording through SkiaLayer's selected backend. */
public enum class InkLatencyRenderPath { SWING_SOFTWARE, SKIA_LAYER }

/** Start of the input-age measurement. MIXED means a frame contains both kinds of input. */
public enum class InkLatencyInputOrigin { NATIVE_QUEUE, LISTENER, MIXED }

/** One delivered packet group. Counts describe supplied input, including ignored observations. */
public data class InkLatencyInput(
    public val id: Long,
    public val origin: InkLatencyInputOrigin,
    public val packetCount: Int,
    public val eventCount: Int,
    public val realSampleCount: Int,
    public val oldestQueueWaitNanos: Long?,
    public val newestQueueWaitNanos: Long?,
    public val normalizationNanos: Long?,
    public val handlingNanos: Long,
)

/**
 * One Skia delegate invocation. Input ages end at drawing/recording completion or synchronous
 * request return. Neither boundary establishes deferred submission, compositor or display timing.
 * Null means unavailable, not zero. Durations overlap and must not be added into an end-to-end sum.
 * [engineAdvanceNanos] includes scheduled advances since the previous delegate, including timers.
 * [swingTransferAndDrawNanos] covers the post-delegate Skiko copies, Java2D drawing and cleanup
 * until SkiaSwingLayer.paint returns; ancestor-buffer work is included only in [frameRequestNanos].
 */
public data class InkLatencyFrame(
    public val id: Long,
    public val path: InkLatencyRenderPath,
    public val inputEventCount: Int,
    public val realSampleCount: Int,
    public val inputOrigin: InkLatencyInputOrigin?,
    public val renderQueueNanos: Long?,
    public val engineAdvanceNanos: Long,
    public val drawNanos: Long,
    public val swingTransferAndDrawNanos: Long?,
    public val frameRequestNanos: Long?,
    public val oldestInputToDrawNanos: Long?,
    public val newestInputToDrawNanos: Long?,
    public val oldestInputToRequestReturnNanos: Long?,
    public val newestInputToRequestReturnNanos: Long?,
    public val completed: Boolean,
)

/**
 * Opt-in, bounded software diagnostics for one direct authoring panel. Construct, attach, snapshot
 * and clear on AWT EDT. Each ring retains at most [capacity] records (1..65,536); overwrites are
 * counted. No input contents, native handles, callbacks, logging or I/O are retained. Use a distinct
 * collector per panel. Recording adds clock reads/allocation; use disabled controls for benchmarks.
 */
public class InkLatencyDiagnostics internal constructor(
    public val capacity: Int,
    internal val nanoTime: () -> Long,
) {
    public constructor(capacity: Int = 512) : this(capacity, System::nanoTime)

    init {
        checkLatencyThread()
        require(capacity in 1..65_536) { "Diagnostic capacity must be within 1..65,536" }
    }
    @Volatile internal var epoch: Long = 0L
        private set
    private val inputs = ArrayDeque<InkLatencyInput>()
    private val frames = ArrayDeque<InkLatencyFrame>()
    private var inputId = 0L
    private var frameId = 0L
    private var overwrittenInputs = 0L
    private var overwrittenFrames = 0L

    /** Copies both rings in chronological order. The immutable result may be exported off EDT. */
    public fun snapshot(): InkLatencySnapshot {
        checkLatencyThread()
        return InkLatencySnapshot(capacity, epoch, copyOf(inputs), copyOf(frames), overwrittenInputs, overwrittenFrames)
    }

    /** Clears observations and invalidates timestamps already captured by a native queue or frame. */
    public fun clear() {
        checkLatencyThread()
        epoch++
        inputs.clear(); frames.clear()
        inputId = 0L; frameId = 0L
        overwrittenInputs = 0L; overwrittenFrames = 0L
    }

    internal fun recordInput(input: InkLatencyInput) {
        checkLatencyThread()
        if (inputs.size == capacity) { inputs.removeFirst(); overwrittenInputs++ }
        inputs.addLast(input.copy(id = ++inputId))
    }

    internal fun recordFrame(frame: InkLatencyFrame) {
        checkLatencyThread()
        if (frames.size == capacity) { frames.removeFirst(); overwrittenFrames++ }
        frames.addLast(frame.copy(id = ++frameId))
    }
}

/** Immutable, content-free software observations. [toJson] can run off EDT. */
public class InkLatencySnapshot internal constructor(
    public val capacity: Int,
    public val epoch: Long,
    public val inputs: List<InkLatencyInput>,
    public val frames: List<InkLatencyFrame>,
    public val overwrittenInputs: Long,
    public val overwrittenFrames: Long,
) {
    /**
     * Schema 1 raw records and unweighted retained-record statistics, in nanoseconds. Percentiles
     * use nearest rank; an even median rounds down. Failed frames are excluded from statistics.
     * Export after capturing: sorting/serialization and disk I/O do not belong in an input callback.
     */
    public fun toJson(): String = buildString {
        append("{\"schemaVersion\":1,\"measurement\":\"software\",\"clock\":\"System.nanoTime\",")
        append("\"presentationCompletionNanos\":null,\"physicalPenToPhotonNanos\":null,")
        append("\"capacity\":$capacity,\"epoch\":$epoch,\"overwrittenInputs\":$overwrittenInputs,")
        append("\"overwrittenFrames\":$overwrittenFrames,\"inputs\":[")
        inputs.forEachIndexed { index, input ->
            if (index > 0) append(',')
            append("{\"id\":${input.id},\"origin\":\"${input.origin}\",\"packetCount\":${input.packetCount},")
            append("\"eventCount\":${input.eventCount},\"realSampleCount\":${input.realSampleCount},")
            append("\"oldestQueueWaitNanos\":${input.oldestQueueWaitNanos},\"newestQueueWaitNanos\":${input.newestQueueWaitNanos},")
            append("\"normalizationNanos\":${input.normalizationNanos},\"handlingNanos\":${input.handlingNanos}}")
        }
        append("],\"frames\":[")
        frames.forEachIndexed { index, frame ->
            if (index > 0) append(',')
            append("{\"id\":${frame.id},\"path\":\"${frame.path}\",\"inputEventCount\":${frame.inputEventCount},")
            append("\"realSampleCount\":${frame.realSampleCount},\"inputOrigin\":")
            append(frame.inputOrigin?.let { "\"$it\"" } ?: "null")
            append(",\"renderQueueNanos\":${frame.renderQueueNanos},\"engineAdvanceNanos\":${frame.engineAdvanceNanos},")
            append("\"drawNanos\":${frame.drawNanos},\"swingTransferAndDrawNanos\":${frame.swingTransferAndDrawNanos},")
            append("\"frameRequestNanos\":${frame.frameRequestNanos},\"oldestInputToDrawNanos\":${frame.oldestInputToDrawNanos},")
            append("\"newestInputToDrawNanos\":${frame.newestInputToDrawNanos},")
            append("\"oldestInputToRequestReturnNanos\":${frame.oldestInputToRequestReturnNanos},")
            append("\"newestInputToRequestReturnNanos\":${frame.newestInputToRequestReturnNanos},\"completed\":${frame.completed}}")
        }
        append("],\"statistics\":{")
        val successful = frames.filter { it.completed }
        val metrics = linkedMapOf(
            "inputs.oldestQueueWaitNanos" to inputs.mapNotNull { it.oldestQueueWaitNanos },
            "inputs.newestQueueWaitNanos" to inputs.mapNotNull { it.newestQueueWaitNanos },
            "inputs.normalizationNanos" to inputs.mapNotNull { it.normalizationNanos },
            "inputs.handlingNanos" to inputs.map { it.handlingNanos },
            "frames.renderQueueNanos" to successful.mapNotNull { it.renderQueueNanos },
            "frames.engineAdvanceNanos" to successful.map { it.engineAdvanceNanos },
            "frames.drawNanos" to successful.map { it.drawNanos },
            "frames.swingTransferAndDrawNanos" to successful.mapNotNull { it.swingTransferAndDrawNanos },
            "frames.frameRequestNanos" to successful.mapNotNull { it.frameRequestNanos },
            "frames.oldestInputToDrawNanos" to successful.mapNotNull { it.oldestInputToDrawNanos },
            "frames.newestInputToDrawNanos" to successful.mapNotNull { it.newestInputToDrawNanos },
            "frames.oldestInputToRequestReturnNanos" to successful.mapNotNull { it.oldestInputToRequestReturnNanos },
            "frames.newestInputToRequestReturnNanos" to successful.mapNotNull { it.newestInputToRequestReturnNanos },
        )
        metrics.entries.forEachIndexed { index, (name, values) ->
            if (index > 0) append(',')
            append("\"$name\":")
            if (values.isEmpty()) append("null") else {
                val sorted = values.sorted()
                val upper = sorted.size / 2
                val median = if (sorted.size % 2 == 1) sorted[upper]
                    else sorted[upper - 1] + (sorted[upper] - sorted[upper - 1]) / 2
                fun percentile(p: Double): Long = sorted[ceil(sorted.size * p).toInt() - 1]
                append("{\"count\":${sorted.size},\"minNanos\":${sorted.first()},\"medianNanos\":$median,")
                append("\"p95Nanos\":${percentile(.95)},\"p99Nanos\":${percentile(.99)},\"maxNanos\":${sorted.last()}}")
            }
        }
        append("}}")
    }
}

internal fun checkLatencyThread() {
    check(EventQueue.isDispatchThread()) { "Use ink latency diagnostics on the AWT event thread" }
}

internal data class InkLatencyArrival(
    val diagnostics: InkLatencyDiagnostics,
    val epoch: Long,
    val oldestNanos: Long,
    val newestNanos: Long,
    val packetCount: Int = 1,
) {
    fun isCurrent(collector: InkLatencyDiagnostics): Boolean = diagnostics === collector && epoch == collector.epoch
}

internal data class InkLatencyDelivery(
    val diagnostics: InkLatencyDiagnostics,
    val epoch: Long,
    val arrival: InkLatencyArrival?,
    val dequeuedNanos: Long,
    val normalizationNanos: Long,
) {
    fun isCurrent(collector: InkLatencyDiagnostics): Boolean = diagnostics === collector && epoch == collector.epoch
}

internal fun InkInputEvent.realSampleCount(): Int = when (this) {
    is InkInputEvent.Begin, is InkInputEvent.Move -> 1
    is InkInputEvent.Batch -> samples.size
    is InkInputEvent.Finish -> if (sample == null) 0 else 1
    else -> 0
}
