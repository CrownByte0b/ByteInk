package com.vivenotes.byteink.compose

import androidx.ink.brush.InputToolType
import kotlin.math.PI
import kotlin.test.*

class NativePenNormalizationTest {
    private fun point(x: Double, tick: Long, axes: Int = NativePenBridge.PRESSURE or NativePenBridge.TILT) =
        NativePenBridge.Point(x, 40.0, tick, .75f, 45f, 0f, axes)
    private fun frame(phase: Int, points: List<NativePenBridge.Point>, id: Long = 1, tool: Int = NativePenBridge.PEN) =
        NativePenBridge.Frame(id, phase, tool, points)

    @Test fun nativeHistoryPreservesTimeAndMeasuredAxesAndConvertsPhysicalToLocalPixels() {
        val input = NativePenNormalizer({ 2f }, .01f)
        val events = input.events(frame(NativePenBridge.BEGIN, listOf(point(20.0, 1000), point(40.0, 1010))), 100_000)
        val down = (events[0] as InkInputEvent.Begin).sample
        val second = (events[1] as InkInputEvent.Batch).samples.single()
        assertEquals(10f, down.x); assertEquals(20f, down.y)
        assertEquals(99_990L, down.uptimeMillis); assertEquals(100_000L, second.uptimeMillis)
        assertEquals(InputToolType.STYLUS, down.toolType); assertEquals(.75f, down.pressure)
        assertEquals((PI / 4).toFloat(), assertNotNull(down.tiltRadians), .000001f)
        assertEquals(0f, down.orientationRadians); assertEquals(.02f, down.strokeUnitLengthCm)
        val up = input.events(frame(NativePenBridge.FINISH, listOf(point(60.0, 1020), point(80.0, 1030))), 100_030)
        assertEquals(2, up.size)
        assertEquals(100_010L, (up[0] as InkInputEvent.Batch).samples.single().uptimeMillis)
        assertEquals(100_020L, assertNotNull((up[1] as InkInputEvent.Finish).sample).uptimeMillis)
    }

    @Test fun unsignedNativeTicksWrapWithoutAJumpAndOlderHistoryDoesNotMoveTheClockBack() {
        val input = NativePenNormalizer({ 1f }, null)
        input.events(frame(NativePenBridge.BEGIN, listOf(point(10.0, 0xfffffff0))), 10_000)
        val moves = input.events(frame(NativePenBridge.MOVE,
            listOf(point(20.0, 0xfffffff8), point(30.0, 8), point(40.0, 16))), 10_045)
        assertEquals(listOf(10_008L, 10_024L, 10_032L), (moves.single() as InkInputEvent.Batch).samples.map { it.uptimeMillis })
        val clock = NativeTickClock()
        assertEquals(10_000L, clock.map(0, 10_000))
        // Reanchoring allows subscriptions to remain open beyond a 32-bit half-cycle.
        assertEquals(1_296_010_000L, clock.map(1_296_000_000L, 0))
        assertEquals(2_592_010_000L, clock.map(2_592_000_000L, 0))
    }

    @Test fun hoverAndLateMovesAreIgnoredAndPerPointerCancellationIsIndependent() {
        val input = NativePenNormalizer({ 1f }, null)
        assertTrue(input.events(frame(NativePenBridge.MOVE, listOf(point(10.0, 0)))).isEmpty())
        input.events(frame(NativePenBridge.BEGIN, listOf(point(10.0, 0)), 1))
        input.events(frame(NativePenBridge.BEGIN, listOf(point(20.0, 0)), 2))
        assertEquals(listOf(InkInputEvent.CancelPointer(1)), input.events(frame(NativePenBridge.CANCEL, emptyList(), 1)))
        assertTrue(input.events(frame(NativePenBridge.FINISH, listOf(point(30.0, 5)), 1)).isEmpty())
        assertIs<InkInputEvent.Finish>(input.events(frame(NativePenBridge.FINISH, listOf(point(40.0, 10)), 2)).single())
    }

    @Test fun ordinaryMouseAndTouchDoNotAcquireSyntheticPressureOrTilt() {
        for (tool in listOf(NativePenBridge.MOUSE, NativePenBridge.TOUCH)) {
            val input = NativePenNormalizer({ 1f }, null)
            val down = input.events(frame(NativePenBridge.BEGIN, listOf(point(10.0, 1000, axes = 0)), tool = tool), 10_000).single() as InkInputEvent.Begin
            assertNull(down.sample.pressure); assertNull(down.sample.tiltRadians)
            assertNull(down.sample.orientationRadians); assertNull(down.sample.strokeUnitLengthCm)
        }
    }

    @Test fun projectedTiltProducesShaftAzimuthInAllQuadrantsIncludingAFlatPen() {
        assertEquals(0f, penTilt(0f, 0f).first)
        assertEquals((PI / 2).toFloat(), penTilt(0f, 45f).second, .000001f)
        assertEquals(PI.toFloat(), penTilt(-45f, 0f).second, .000001f)
        assertEquals((1.5 * PI).toFloat(), penTilt(0f, -45f).second, .000001f)
        assertEquals((PI / 4).toFloat(), penTilt(45f, 45f).second, .000001f)
        assertEquals((PI / 2).toFloat(), penTilt(90f, 0f).first, .000001f)
    }
}
