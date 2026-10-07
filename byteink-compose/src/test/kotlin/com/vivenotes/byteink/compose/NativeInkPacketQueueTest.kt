package com.vivenotes.byteink.compose

import androidx.ink.brush.InputToolType
import java.awt.EventQueue
import kotlin.test.*

class NativeInkPacketQueueTest {
    private fun sample(i: Int, tool: InputToolType = InputToolType.MOUSE) =
        InkPointerSample(i.toFloat(), 20f, 1000L + i, tool)
    private fun frame(i: Int, phase: Int = NativePenBridge.MOVE, id: Long = 1L) =
        NativePenBridge.Frame(id, phase, NativePenBridge.PEN,
            listOf(NativePenBridge.Point(i.toDouble(), 20.0, i.toLong(), .5f, 10f, 20f, NativePenBridge.PRESSURE)))

    @Test fun longNativeHistoryYieldsToPaintWithoutDroppingOrReorderingSamples() = EventQueue.invokeAndWait {
        val posted = ArrayDeque<() -> Unit>()
        val points = ArrayList<NativePenBridge.Point>()
        val phases = ArrayList<Int>()
        var paintAt = -1
        val queue = NativeInkPacketQueue(consume = { packet ->
            val native = packet as NativePenBridge.Frame
            phases.add(native.phase())
            points.addAll(native.points())
            if (native.phase() == NativePenBridge.MOVE && paintAt < 0) {
                posted.addLast { paintAt = points.size }
            }
        }, post = { posted.addLast(it) }, nanoTime = { 0L })
        queue.enqueue(frame(0, NativePenBridge.BEGIN))
        repeat(8192) { queue.enqueue(frame(it + 1)) }
        queue.enqueue(frame(8193, NativePenBridge.FINISH))
        assertEquals(1, posted.size, "Only one delivery may be pending")
        posted.removeFirst().invoke()
        assertTrue(points.size in 2..512)
        posted.removeFirst().invoke()
        assertEquals(points.size, paintAt, "A paint posted during delivery runs before the next drain")
        while (posted.isNotEmpty()) posted.removeFirst().invoke()
        assertEquals((0L..8193L).toList(), points.map { it.ticks() })
        assertTrue(points.all { it.pressure() == .5f && it.tiltX() == 10f && it.tiltY() == 20f })
        assertEquals(NativePenBridge.BEGIN, phases.first())
        assertEquals(NativePenBridge.FINISH, phases.last())
        assertTrue(phases.drop(1).dropLast(1).all { it == NativePenBridge.MOVE })
        queue.close()
    }

    @Test fun mouseCoalescingPreservesToolsPointersAndLifecycleBarriers() = EventQueue.invokeAndWait {
        val posted = ArrayDeque<() -> Unit>()
        val delivered = ArrayList<Any>()
        val queue = NativeInkPacketQueue(delivered::add, { posted.addLast(it) }, { 0L })
        val error = IllegalStateException("native failure")
        val packets = listOf(
            InkInputEvent.Begin(sample(0), 1),
            InkInputEvent.Move(sample(1), 1), InkInputEvent.Move(sample(2), 1),
            InkInputEvent.Move(sample(3), 2), InkInputEvent.Move(sample(4, InputToolType.STYLUS), 2),
            InkInputEvent.CancelPointer(2), InkInputEvent.Move(sample(5), 1),
            InkInputEvent.Finish(sample(6), 1), error,
        )
        packets.forEach(queue::enqueue)
        while (posted.isNotEmpty()) posted.removeFirst().invoke()
        assertEquals(listOf(sample(1), sample(2)), (delivered[1] as InkInputEvent.Batch).samples)
        val expanded = delivered.flatMap { packet ->
            if (packet is InkInputEvent.Batch) packet.samples.map { InkInputEvent.Move(it, packet.pointerId) }
            else listOf(packet)
        }
        assertEquals(packets, expanded)
        queue.close()
    }

    @Test fun timeBudgetAndReentrantEnqueueKeepOneContinuationAndFifoOrder() = EventQueue.invokeAndWait {
        val posted = ArrayDeque<() -> Unit>()
        val delivered = ArrayList<Any>()
        var now = 0L
        lateinit var queue: NativeInkPacketQueue
        queue = NativeInkPacketQueue({ packet ->
            delivered.add(packet)
            if (delivered.size == 1) queue.enqueue(InkInputEvent.CancelPointer(3))
            now += 3_000_000L
        }, { posted.addLast(it) }, { now })
        queue.enqueue(InkInputEvent.CancelPointer(1))
        queue.enqueue(InkInputEvent.CancelPointer(2))
        posted.removeFirst().invoke()
        assertEquals<List<Any>>(listOf(InkInputEvent.CancelPointer(1)), delivered)
        assertEquals(1, posted.size)
        while (posted.isNotEmpty()) posted.removeFirst().invoke()
        assertEquals<List<Any>>((1L..3L).map { InkInputEvent.CancelPointer(it) }, delivered)
        queue.close()
    }

    @Test fun closeAndFailurePreventRemainingAndLateCallbacks() = EventQueue.invokeAndWait {
        val posted = ArrayDeque<() -> Unit>()
        val delivered = ArrayList<Any>()
        lateinit var queue: NativeInkPacketQueue
        queue = NativeInkPacketQueue({ packet -> delivered.add(packet); queue.close() }, { posted.addLast(it) }, { 0L })
        queue.enqueue(InkInputEvent.CancelPointer(1))
        queue.enqueue(InkInputEvent.CancelPointer(2))
        posted.removeFirst().invoke()
        queue.enqueue(InkInputEvent.CancelPointer(3))
        assertEquals<List<Any>>(listOf(InkInputEvent.CancelPointer(1)), delivered)
        assertTrue(posted.isEmpty())

        val error = IllegalStateException("delivery failed")
        val failing = NativeInkPacketQueue({ throw error }, { posted.addLast(it) }, { 0L })
        failing.enqueue(InkInputEvent.CancelPointer(1))
        failing.enqueue(InkInputEvent.CancelPointer(2))
        assertSame(error, assertFailsWith<IllegalStateException> { posted.removeFirst().invoke() })
        failing.enqueue(InkInputEvent.CancelPointer(3))
        assertTrue(posted.isEmpty())
    }
}
