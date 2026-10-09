package com.vivenotes.byteink.compose

import java.awt.EventQueue
import java.util.concurrent.FutureTask
import kotlin.test.*

class InkLatencyDiagnosticsTest {
    private fun <T> ui(action: () -> T): T {
        val task = FutureTask(action)
        EventQueue.invokeAndWait(task)
        return task.get()
    }

    @Test fun mergedNativePacketsRetainArrivalRangeAndAllRealHistory() = ui {
        var now = -100L // nanoTime has an arbitrary, possibly negative origin.
        val collector = InkLatencyDiagnostics(8) { now }
        val posted = ArrayDeque<() -> Unit>()
        val arrivals = mutableListOf<InkLatencyArrival?>()
        val frames = mutableListOf<NativePenBridge.Frame>()
        lateinit var queue: NativeInkPacketQueue
        queue = NativeInkPacketQueue({ packet ->
            arrivals += queue.deliveryArrival
            frames += packet as NativePenBridge.Frame
        }, { posted.addLast(it) }, { 0L }, { collector })
        repeat(3) { index ->
            queue.enqueue(NativePenBridge.Frame(9L, NativePenBridge.MOVE, NativePenBridge.PEN,
                listOf(NativePenBridge.Point(index.toDouble(), 20.0, index.toLong(), .5f, 0f, 0f, NativePenBridge.PRESSURE))))
            now += 10L
        }
        posted.removeFirst()()
        assertEquals(listOf(0L, 1L, 2L), frames.single().points().map { it.ticks() })
        val timing = assertNotNull(arrivals.single())
        assertEquals(-100L, timing.oldestNanos)
        assertEquals(-80L, timing.newestNanos)
        assertEquals(3, timing.packetCount)
        assertNull(queue.deliveryArrival, "A following control packet cannot reuse an old delivery's timing")
        queue.close()
    }

    @Test fun clearAndReplacementDoNotMergeDifferentDiagnosticEpochs() = ui {
        var now = 0L
        var collector: InkLatencyDiagnostics? = InkLatencyDiagnostics(8) { now }
        val first = collector!!
        val posted = ArrayDeque<() -> Unit>()
        val arrivals = mutableListOf<InkLatencyArrival?>()
        lateinit var queue: NativeInkPacketQueue
        queue = NativeInkPacketQueue({ arrivals += queue.deliveryArrival }, { posted.addLast(it) }, { 0L }, { collector })
        fun move() = queue.enqueue(InkInputEvent.Move(InkPointerSample(20f, 30f, 1000L)))
        move()
        first.clear(); now++
        move()
        collector = InkLatencyDiagnostics(8) { now }; now++
        move()
        collector = null
        move()
        while (posted.isNotEmpty()) posted.removeFirst()()
        assertEquals(4, arrivals.size)
        assertFalse(arrivals[0]!!.isCurrent(first))
        assertTrue(arrivals[1]!!.isCurrent(first))
        assertNotSame(first, arrivals[2]!!.diagnostics)
        assertNull(arrivals[3])
        queue.close()
    }

    @Test fun disabledQueueKeepsUntimedHistory() = ui {
        val posted = ArrayDeque<() -> Unit>()
        val delivered = mutableListOf<Any>()
        lateinit var queue: NativeInkPacketQueue
        queue = NativeInkPacketQueue({ packet ->
            assertNull(queue.deliveryArrival)
            delivered += packet
        }, { posted.addLast(it) }, { 0L })
        repeat(4) { queue.enqueue(InkInputEvent.Move(InkPointerSample(it.toFloat(), 0f, 1000L + it))) }
        posted.removeFirst()()
        assertEquals(4, (delivered.single() as InkInputEvent.Batch).samples.size)
        queue.close()
    }

    @Test fun softwareStagesAndOldestNewestAgesHaveExactBoundaries() = ui {
        var now = 100L
        val collector = InkLatencyDiagnostics(8) { now }
        val tracker = InkLatencyTracker(collector, InkLatencyRenderPath.SWING_SOFTWARE)
        val delivery = InkLatencyDelivery(collector, collector.epoch,
            InkLatencyArrival(collector, collector.epoch, 10L, 20L, 3), 80L, 20L)
        val input = tracker.beginInput(2, 4, delivery)
        now = 130L; tracker.endInput(input)
        val ticket = tracker.queued()
        now = 150L
        val request = tracker.beginRequest(ticket)
        val paint = tracker.beginPaint()
        now = 160L
        val frame = tracker.beginFrame()
        tracker.advance { now = 180L }
        tracker.beginDraw(frame)
        now = 250L; tracker.endFrame(frame, true)
        now = 260L; tracker.delegateReturned(frame)
        now = 280L; tracker.endPaint(paint, true)
        assertTrue(collector.snapshot().frames.isEmpty(), "Full immediate request has not returned")
        now = 300L; tracker.endRequest(request, true)
        val snapshot = collector.snapshot()
        assertEquals(InkLatencyInput(1L, InkLatencyInputOrigin.NATIVE_QUEUE, 3, 2, 4, 70L, 60L, 20L, 30L), snapshot.inputs.single())
        assertEquals(InkLatencyFrame(1L, InkLatencyRenderPath.SWING_SOFTWARE, 2, 4, InkLatencyInputOrigin.NATIVE_QUEUE,
            30L, 20L, 70L, 20L, 150L, 240L, 230L, 290L, 280L, true), snapshot.frames.single())
    }

    @Test fun listenerOnlyAndIncidentalRecordingLeaveUnavailableStagesNull() = ui {
        var now = 0L
        val collector = InkLatencyDiagnostics(8) { now }
        val tracker = InkLatencyTracker(collector, InkLatencyRenderPath.SKIA_LAYER)
        val input = tracker.beginInput(1, 1, null)
        now = 10L; tracker.endInput(input)
        tracker.advance { now = 30L }
        val frame = tracker.beginFrame()
        tracker.beginDraw(frame)
        now = 50L; tracker.endFrame(frame, true)
        val snapshot = collector.snapshot()
        assertNull(snapshot.inputs.single().oldestQueueWaitNanos)
        assertNull(snapshot.inputs.single().normalizationNanos)
        val recorded = snapshot.frames.single()
        assertEquals(InkLatencyInputOrigin.LISTENER, recorded.inputOrigin)
        assertEquals(20L, recorded.engineAdvanceNanos)
        assertNull(recorded.renderQueueNanos)
        assertNull(recorded.swingTransferAndDrawNanos)
        assertNull(recorded.frameRequestNanos)
        assertNull(recorded.oldestInputToRequestReturnNanos)
        assertEquals(50L, recorded.oldestInputToDrawNanos)
        val incidental = tracker.beginFrame()
        tracker.beginDraw(incidental)
        now = 60L; tracker.endFrame(incidental, true)
        assertNull(collector.snapshot().frames.last().oldestInputToDrawNanos)
        assertEquals(0, collector.snapshot().frames.last().inputEventCount)
    }

    @Test fun clearDisconnectAndFailedPaintDoNotReportStaleOrSuccessfulPresentation() = ui {
        var now = 0L
        val collector = InkLatencyDiagnostics(8) { now }
        val tracker = InkLatencyTracker(collector, InkLatencyRenderPath.SWING_SOFTWARE)
        val input = tracker.beginInput(1, 1, null)
        val ticket = tracker.queued()
        val request = tracker.beginRequest(ticket)
        val paint = tracker.beginPaint()
        val frame = tracker.beginFrame()
        tracker.beginDraw(frame)
        now = 10L; tracker.endFrame(frame, true)
        collector.clear()
        tracker.endInput(input); tracker.endPaint(paint, true); tracker.endRequest(request, true)
        assertTrue(collector.snapshot().inputs.isEmpty())
        assertTrue(collector.snapshot().frames.isEmpty())

        tracker.beginInput(1, 1, null)
        val oldTicket = tracker.queued()
        tracker.reset()
        val followingRequest = tracker.beginRequest(oldTicket)
        val followingPaint = tracker.beginPaint()
        val failed = tracker.beginFrame()
        tracker.beginDraw(failed)
        now = 20L; tracker.endFrame(failed, false)
        tracker.endPaint(followingPaint, false); tracker.endRequest(followingRequest, false)
        val observation = collector.snapshot().frames.single()
        assertFalse(observation.completed)
        assertNull(observation.renderQueueNanos)
        assertNull(observation.oldestInputToDrawNanos)
        assertNull(observation.swingTransferAndDrawNanos)
        assertTrue(collector.snapshot().toJson().contains("\"frames.drawNanos\":null"), "Failed frames are excluded from percentiles")
    }

    @Test fun boundedImmutableSnapshotsExportNullsAndNearestRankPercentilesOffEdt() {
        val snapshot = ui {
            val collector = InkLatencyDiagnostics(3)
            repeat(5) { index -> collector.recordInput(InkLatencyInput(0, InkLatencyInputOrigin.LISTENER, 1, 1, 1, null, null, null, index.toLong())) }
            val saved = collector.snapshot()
            assertEquals(listOf(3L, 4L, 5L), saved.inputs.map { it.id })
            assertEquals(2L, saved.overwrittenInputs)
            assertFailsWith<UnsupportedOperationException> { (saved.inputs as MutableList<InkLatencyInput>).clear() }
            collector.clear()
            assertEquals(3, saved.inputs.size)
            assertTrue(collector.snapshot().inputs.isEmpty())
            saved
        }
        val json = snapshot.toJson()
        assertTrue(json.contains("\"physicalPenToPhotonNanos\":null"))
        assertTrue(json.contains("\"presentationCompletionNanos\":null"))
        assertTrue(json.contains("\"inputs.oldestQueueWaitNanos\":null"))
        assertTrue(json.contains("\"inputs.handlingNanos\":{\"count\":3,\"minNanos\":2,\"medianNanos\":3,\"p95Nanos\":4,\"p99Nanos\":4,\"maxNanos\":4}"))
    }

    @Test fun diagnosticsEnforceCapacityAndUiThreadOwnership() {
        assertFailsWith<IllegalStateException> { InkLatencyDiagnostics() }
        val collector = ui {
            assertFailsWith<IllegalArgumentException> { InkLatencyDiagnostics(0) }
            assertFailsWith<IllegalArgumentException> { InkLatencyDiagnostics(65_537) }
            InkLatencyDiagnostics()
        }
        assertFailsWith<IllegalStateException> { collector.clear() }
        assertFailsWith<IllegalStateException> { collector.snapshot() }
    }

    @Test fun staleNormalizationAndAdvancesCannotCrossClear() = ui {
        var now = 0L
        val collector = InkLatencyDiagnostics(8) { now }
        val tracker = InkLatencyTracker(collector, InkLatencyRenderPath.SKIA_LAYER)
        val stale = InkLatencyDelivery(collector, collector.epoch, null, 0L, 30L)
        collector.clear()
        val input = tracker.beginInput(1, 1, stale)
        now = 10L; tracker.endInput(input)
        assertNull(collector.snapshot().inputs.single().normalizationNanos)
        tracker.advance { collector.clear(); now = 100L }
        val frame = tracker.beginFrame()
        tracker.beginDraw(frame)
        now = 110L; tracker.endFrame(frame, true)
        assertEquals(0L, collector.snapshot().frames.single().engineAdvanceNanos)
        assertNull(collector.snapshot().frames.single().oldestInputToDrawNanos)
    }

    @Test fun observationCountsExcludeForecastsAndAbsentReleaseSamples() {
        val sample = InkPointerSample(0f, 0f, 1000L)
        assertEquals(1, InkInputEvent.Begin(sample).realSampleCount())
        assertEquals(1, InkInputEvent.Move(sample).realSampleCount())
        assertEquals(2, InkInputEvent.Batch(listOf(sample, sample), listOf(sample, sample, sample)).realSampleCount())
        assertEquals(0, InkInputEvent.Predict(listOf(sample)).realSampleCount())
        assertEquals(0, InkInputEvent.Finish().realSampleCount())
        assertEquals(1, InkInputEvent.Finish(sample).realSampleCount())
        assertEquals(0, InkInputEvent.Cancel.realSampleCount())
        assertEquals(0, InkInputEvent.CancelPointer(1L).realSampleCount())
    }
}
