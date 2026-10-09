package com.vivenotes.byteink.compose

import java.awt.EventQueue

/** Lossless FIFO delivery that yields between bursts so a queued paint can run on the EDT. */
internal class NativeInkPacketQueue(
    private val consume: (Any) -> Unit,
    private val post: (() -> Unit) -> Unit = { EventQueue.invokeLater(it) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val diagnostics: () -> InkLatencyDiagnostics? = { null },
) : AutoCloseable {
    private class TimedPacket(val packet: Any, val arrival: InkLatencyArrival)
    private val packets = ArrayDeque<Any>()
    private var attached = true
    private var scheduled = false
    /** Valid only during consume, on EDT. Disabled delivery keeps the original raw-packet path. */
    var deliveryArrival: InkLatencyArrival? = null
        private set

    fun enqueue(packet: Any) {
        val collector = diagnostics()
        val queued = if (collector == null) packet else {
            val epoch = collector.epoch
            val now = collector.nanoTime()
            TimedPacket(packet, InkLatencyArrival(collector, epoch, now, now))
        }
        synchronized(packets) {
            if (!attached) return
            packets.addLast(queued)
            if (!scheduled) {
                scheduled = true
                post(::drain)
            }
        }
    }

    private fun drain() {
        check(EventQueue.isDispatchThread())
        val start = nanoTime()
        var samples = 0
        var delivered = 0
        try {
            while (samples < MAX_SAMPLES && delivered < MAX_PACKETS) {
                val packet = synchronized(packets) {
                    if (!attached || packets.isEmpty()) null else take(MAX_SAMPLES - samples)
                } ?: break
                deliveryArrival = (packet as? TimedPacket)?.arrival
                val value = value(packet)
                try { consume(value) } finally { deliveryArrival = null }
                samples += sampleCount(value)
                delivered++
                if (nanoTime() - start >= MAX_DRAIN_NANOS) break
            }
        } catch (failure: Throwable) {
            close()
            throw failure
        }
        synchronized(packets) {
            if (attached && packets.isNotEmpty()) post(::drain)
            else scheduled = false
        }
    }

    /** Called with the queue lock. Never merge across pointer/tool or lifecycle barriers. */
    private fun take(remaining: Int): Any {
        val queued = packets.removeFirst()
        val first = value(queued)
        var arrival = (queued as? TimedPacket)?.arrival
        if (first is NativePenBridge.Frame && first.phase() == NativePenBridge.MOVE) {
            var points: MutableList<NativePenBridge.Point>? = null
            var count = first.points().size
            var merged = 1
            while (packets.isNotEmpty() && merged < MAX_PACKETS) {
                val nextQueued = packets.first()
                if (!compatible(arrival, nextQueued)) break
                val next = value(nextQueued) as? NativePenBridge.Frame ?: break
                if (next.phase() != NativePenBridge.MOVE || next.pointerId() != first.pointerId() ||
                    next.tool() != first.tool() || next.points().size > remaining - count) break
                if (points == null) points = ArrayList<NativePenBridge.Point>(minOf(remaining, count + next.points().size)).also { it.addAll(first.points()) }
                points.addAll(next.points())
                count += next.points().size
                merged++
                arrival = mergeTiming(arrival, nextQueued)
                packets.removeFirst()
            }
            return timed(if (points == null) first else NativePenBridge.Frame(first.pointerId(), first.phase(), first.tool(), points), arrival)
        }
        if (first is InkInputEvent.Move) {
            var points: MutableList<InkPointerSample>? = null
            var count = 1
            while (count < remaining && packets.isNotEmpty()) {
                val nextQueued = packets.first()
                if (!compatible(arrival, nextQueued)) break
                val next = value(nextQueued) as? InkInputEvent.Move ?: break
                if (next.pointerId != first.pointerId || next.sample.toolType != first.sample.toolType) break
                if (points == null) points = ArrayList<InkPointerSample>().also { it.add(first.sample) }
                points.add(next.sample)
                count++
                arrival = mergeTiming(arrival, nextQueued)
                packets.removeFirst()
            }
            return timed(if (points == null) first else InkInputEvent.Batch(points, pointerId = first.pointerId), arrival)
        }
        return queued
    }

    private fun value(packet: Any): Any = (packet as? TimedPacket)?.packet ?: packet

    private fun compatible(arrival: InkLatencyArrival?, packet: Any): Boolean {
        val next = (packet as? TimedPacket)?.arrival
        return if (arrival == null) next == null
        else next != null && next.diagnostics === arrival.diagnostics && next.epoch == arrival.epoch
    }

    private fun mergeTiming(arrival: InkLatencyArrival?, packet: Any): InkLatencyArrival? {
        val next = (packet as? TimedPacket)?.arrival ?: return null
        return arrival!!.copy(
            oldestNanos = if (next.oldestNanos - arrival.oldestNanos < 0L) next.oldestNanos else arrival.oldestNanos,
            newestNanos = if (next.newestNanos - arrival.newestNanos > 0L) next.newestNanos else arrival.newestNanos,
            packetCount = arrival.packetCount + next.packetCount)
    }

    private fun timed(packet: Any, arrival: InkLatencyArrival?): Any = arrival?.let { TimedPacket(packet, it) } ?: packet

    override fun close() {
        synchronized(packets) { attached = false; packets.clear(); scheduled = false }
    }

    private fun sampleCount(packet: Any): Int = when (packet) {
        is NativePenBridge.Frame -> maxOf(1, packet.points().size)
        is InkInputEvent.Batch -> maxOf(1, packet.samples.size)
        else -> 1
    }

    private companion object {
        const val MAX_SAMPLES = 512
        const val MAX_PACKETS = 256
        const val MAX_DRAIN_NANOS = 2_000_000L
    }
}
