package com.vivenotes.byteink.compose

/** One panel's EDT-owned in-flight attribution; the collector owns only completed observations. */
internal class InkLatencyTracker(val diagnostics: InkLatencyDiagnostics, private val path: InkLatencyRenderPath) {
    private var epoch = diagnostics.epoch
    private var generation = 0L
    private var oldestInput: Long? = null
    private var newestInput: Long? = null
    private var inputOrigin: InkLatencyInputOrigin? = null
    private var events = 0
    private var samples = 0
    private var advanceNanos = 0L
    private var request: Request? = null
    private var paint: Paint? = null
    private var drawing: Frame? = null

    class Ticket(val tracker: InkLatencyTracker, val generation: Long, val epoch: Long, val queuedNanos: Long)
    class Request(val started: Long, val queued: Long?, val generation: Long, val frames: ArrayDeque<Frame> = ArrayDeque())
    class Paint(val generation: Long, var frame: Frame? = null)
    class Input(val epoch: Long, val generation: Long, val start: Long, val events: Int, val samples: Int,
        val delivery: InkLatencyDelivery?, val arrival: InkLatencyArrival?)
    class Frame(
        val epoch: Long, val generation: Long, val oldest: Long?, val newest: Long?,
        val origin: InkLatencyInputOrigin?, val events: Int, val samples: Int,
        val renderQueue: Long?, var advance: Long, var drawStart: Long? = null,
        var draw: Long = 0L, var drawEnd: Long = 0L, var transfer: Long? = null,
        var delegateReturn: Long? = null,
        var completed: Boolean = false,
    )

    private fun refresh() {
        if (epoch != diagnostics.epoch) { reset(); epoch = diagnostics.epoch }
    }

    fun reset() {
        generation++
        oldestInput = null; newestInput = null; inputOrigin = null
        events = 0; samples = 0; advanceNanos = 0L
        request = null; paint = null; drawing = null
    }

    fun beginInput(eventCount: Int, realSamples: Int, delivery: InkLatencyDelivery?): Input {
        refresh()
        val start = diagnostics.nanoTime()
        val currentDelivery = delivery?.takeIf { it.isCurrent(diagnostics) }
        val arrival = currentDelivery?.arrival?.takeIf { it.isCurrent(diagnostics) }
        val origin = if (arrival == null) InkLatencyInputOrigin.LISTENER else InkLatencyInputOrigin.NATIVE_QUEUE
        val first = arrival?.oldestNanos ?: start
        val last = arrival?.newestNanos ?: start
        oldestInput = oldestInput?.let { if (start - first > start - it) first else it } ?: first
        newestInput = newestInput?.let { if (start - last < start - it) last else it } ?: last
        inputOrigin = if (inputOrigin == null || inputOrigin == origin) origin else InkLatencyInputOrigin.MIXED
        events += eventCount; samples += realSamples
        return Input(epoch, generation, start, eventCount, realSamples, currentDelivery, arrival)
    }

    fun endInput(input: Input) {
        if (input.epoch != diagnostics.epoch || input.generation != generation) return
        val arrival = input.arrival
        diagnostics.recordInput(InkLatencyInput(0L,
            if (arrival == null) InkLatencyInputOrigin.LISTENER else InkLatencyInputOrigin.NATIVE_QUEUE,
            arrival?.packetCount ?: 1, input.events, input.samples,
            arrival?.let { elapsed(input.delivery!!.dequeuedNanos, it.oldestNanos) },
            arrival?.let { elapsed(input.delivery!!.dequeuedNanos, it.newestNanos) },
            input.delivery?.normalizationNanos, elapsed(diagnostics.nanoTime(), input.start)))
    }

    fun queued(): Ticket { refresh(); return Ticket(this, generation, epoch, diagnostics.nanoTime()) }

    fun beginRequest(ticket: Ticket?): Request {
        refresh()
        return Request(diagnostics.nanoTime(), ticket?.takeIf {
            it.tracker === this && it.generation == generation && it.epoch == epoch
        }?.queuedNanos, generation).also { request = it }
    }

    fun endRequest(scope: Request, success: Boolean) {
        val end = diagnostics.nanoTime()
        if (request !== scope) return
        request = null
        scope.frames.forEach { publish(it, end, elapsed(end, scope.started), success) }
    }

    fun beginPaint(): Paint { refresh(); return Paint(generation).also { paint = it } }

    fun endPaint(scope: Paint, success: Boolean) {
        val end = diagnostics.nanoTime()
        if (paint !== scope) return
        paint = null
        scope.frame?.let { frame ->
            frame.completed = frame.completed && frame.delegateReturn != null
            if (frame.completed) frame.transfer = elapsed(end, frame.delegateReturn!!)
            complete(frame, success)
        }
    }

    fun beginFrame(): Frame {
        refresh()
        val start = diagnostics.nanoTime()
        return Frame(epoch, generation, oldestInput, newestInput, inputOrigin, events, samples,
            request?.queued?.let { elapsed(start, it) }, advanceNanos).also {
            oldestInput = null; newestInput = null; inputOrigin = null
            events = 0; samples = 0; advanceNanos = 0L
            drawing = it
            paint?.frame = it
        }
    }

    fun <T> advance(block: () -> T): T {
        refresh()
        val startedEpoch = epoch
        val startedGeneration = generation
        val start = diagnostics.nanoTime()
        try { return block() } finally {
            val end = diagnostics.nanoTime()
            if (startedEpoch == diagnostics.epoch && startedGeneration == generation) advanced(elapsed(end, start))
        }
    }

    fun advanced(nanos: Long) {
        refresh()
        drawing?.let { it.advance += nanos } ?: run { advanceNanos += nanos }
    }

    fun beginDraw(frame: Frame) { frame.drawStart = diagnostics.nanoTime() }

    fun endFrame(frame: Frame, success: Boolean) {
        val end = diagnostics.nanoTime()
        if (drawing !== frame) return
        drawing = null
        frame.drawEnd = end
        frame.draw = frame.drawStart?.let { elapsed(end, it) } ?: 0L
        frame.completed = success
        if (paint == null) complete(frame, success)
    }

    /** Marks the end of delegate bookkeeping, immediately before returning to Skiko's copier. */
    fun delegateReturned(frame: Frame) { frame.delegateReturn = diagnostics.nanoTime() }

    private fun complete(frame: Frame, success: Boolean) {
        frame.completed = frame.completed && success
        val activeRequest = request
        if (activeRequest == null) publish(frame, null, null, success)
        else {
            // Normally one delegate runs per request. Bound storage even under reentrant host paints.
            if (activeRequest.frames.size == diagnostics.capacity) publish(activeRequest.frames.removeFirst(), null, null, false)
            activeRequest.frames.addLast(frame)
        }
    }

    private fun publish(frame: Frame, returned: Long?, requestNanos: Long?, success: Boolean) {
        if (frame.epoch != diagnostics.epoch || frame.generation != generation) return
        diagnostics.recordFrame(InkLatencyFrame(0L, path, frame.events, frame.samples, frame.origin,
            frame.renderQueue, frame.advance, frame.draw, frame.transfer, requestNanos,
            frame.oldest?.let { elapsed(frame.drawEnd, it) }, frame.newest?.let { elapsed(frame.drawEnd, it) },
            returned?.let { end -> frame.oldest?.let { elapsed(end, it) } },
            returned?.let { end -> frame.newest?.let { elapsed(end, it) } }, frame.completed && success))
    }
}

internal fun elapsed(end: Long, start: Long): Long = (end - start).coerceAtLeast(0L)
