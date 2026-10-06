package com.vivenotes.byteink.compose

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.StrokeInput
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import kotlin.math.PI
import kotlin.test.*

class InkPenPipelineTest {
    private val brush = Brush.createWithColorIntArgb(BrushFamily(), 0xff000000.toInt(), 8f, .25f)
    private fun pen(x: Float, time: Long, pressure: Float? = .4f, tilt: Float? = .3f, orientation: Float? = .5f) =
        InkPointerSample(x, 30f, time, InputToolType.STYLUS, pressure, tilt, orientation, .01f)

    @Test fun penAttributesFreezeAvailabilityAndHoldMissingObservations() {
        InkAuthoringController().use { controller ->
            controller.begin(brush, pen(10f, 1000))
            controller.append(pen(20f, 1010, .8f, .7f, 1f))
            controller.append(pen(30f, 1020, null, null, null).copy(strokeUnitLengthCm = null))
            val inputs = assertNotNull(controller.finish()).inputs
            val last = inputs[2]
            assertEquals(.8f, last.pressure); assertEquals(.7f, last.tiltRadians)
            assertEquals(1f, last.orientationRadians, .000001f)
            assertEquals(.01f, last.strokeUnitLengthCm)
        }
        InkAuthoringController().use { controller ->
            controller.begin(brush, pen(10f, 1000, null, null, null).copy(strokeUnitLengthCm = null))
            val inputs = assertNotNull(controller.finish(pen(20f, 1010))).inputs
            for (i in 0 until inputs.size) {
                assertFalse(inputs[i].hasPressure); assertFalse(inputs[i].hasTilt)
                assertFalse(inputs[i].hasOrientation)
                assertEquals(StrokeInput.NO_STROKE_UNIT_LENGTH, inputs[i].strokeUnitLengthCm)
            }
        }
    }

    @Test fun rotatedScaledViewMapsShaftOrientationAndPhysicalStrokeUnits() {
        val transform = ImmutableAffineTransform(0f, -2f, 80f, 2f, 0f, 0f)
        InkAuthoringController().use { controller ->
            controller.begin(brush, pen(20f, 1000, orientation = 0f), transform)
            val first = assertNotNull(controller.finish()).inputs[0]
            assertEquals(15f, first.x); assertEquals(30f, first.y)
            assertEquals((1.5 * PI).toFloat(), first.orientationRadians, .000001f)
            assertEquals(.02f, first.strokeUnitLengthCm)
            assertEquals(.3f, first.tiltRadians)
        }
    }

    @Test fun reflectedViewMapsOrientationAndAnisotropicViewOmitsUndefinedPhysicalScale() {
        InkAuthoringController().use { controller ->
            controller.begin(brush, pen(10f, 1000, orientation = 0f), ImmutableAffineTransform(-1f, 0f, 0f, 0f, 1f, 0f))
            assertEquals(PI.toFloat(), assertNotNull(controller.finish()).inputs[0].orientationRadians, .000001f)
            controller.begin(brush, pen(10f, 2000), ImmutableAffineTransform(2f, 0f, 0f, 0f, 1f, 0f))
            assertEquals(0f, assertNotNull(controller.finish()).inputs[0].strokeUnitLengthCm)
        }
    }

    @Test fun predictionsReplaceRetractAndNeverReachTheSavedInputs() {
        InkAuthoringController().use { controller ->
            controller.begin(brush, pen(10f, 1000))
            controller.append(pen(20f, 1010))
            controller.setPredictedInputs(listOf(pen(80f, 1020), pen(120f, 1030)))
            controller.advance(1011)
            val live = assertNotNull(controller.liveStroke)
            assertEquals(2, live.getRealInputCount()); assertEquals(2, live.getPredictedInputCount())
            assertEquals(120f, live.populateInput(StrokeInput(), 3).x)
            controller.setPredictedInputs(listOf(pen(35f, 1020)))
            controller.advance(1012)
            assertEquals(1, live.getPredictedInputCount())
            assertEquals(35f, live.populateInput(StrokeInput(), 2).x)
            controller.append(pen(23f, 1013))
            controller.advance(1013)
            assertEquals(0, live.getPredictedInputCount())
            controller.setPredictedInputs(listOf(pen(150f, 1050)))
            val finished = assertNotNull(controller.finish(pen(30f, 1020)))
            assertEquals(4, finished.inputs.size)
            assertEquals(listOf(10f, 20f, 23f, 30f), (0 until 4).map { finished.inputs[it].x })
            assertFalse(controller.isDrawing)
            controller.begin(brush, pen(40f, 2000))
            assertEquals(0, assertNotNull(controller.liveStroke).getPredictedInputCount())
        }
    }

    @Test fun invalidAndStaleForecastSamplesCannotCorruptARealGesture() {
        InkAuthoringController().use { controller ->
            controller.begin(brush, pen(10f, 1000))
            controller.append(pen(20f, 1010))
            controller.setPredictedInputs(listOf(pen(30f, 1009), pen(30f, 1011).copy(toolType = InputToolType.MOUSE),
                pen(30f, 1020), pen(30f, 1020), pen(40f, 1019), pen(40f, 1030)))
            controller.advance(1011)
            assertEquals(2, assertNotNull(controller.liveStroke).getPredictedInputCount())
            assertEquals(2, assertNotNull(controller.finish()).inputs.size)
        }
    }

    @Test fun everyViveBrushStoresOnlyRealFullAttributeInputsWithPredictionEnabled() {
        for (family in listOf(ViveBrushes.MARKER, ViveBrushes.PRESSURE_PEN, ViveBrushes.calligraphy(3), ViveBrushes.HIGHLIGHTER)) {
            InkAuthoringController().use { controller ->
                controller.begin(ViveBrushes.brush(family, 1, 0xff223344.toInt(), 8f), pen(10f, 1000))
                repeat(10) { i ->
                    controller.append(pen(20f + i * 3, 1010L + i * 10))
                    controller.setPredictedInputs(listOf(pen(180f, 1020L + i * 10)))
                    controller.advance(1010L + i * 10)
                }
                val finished = assertNotNull(controller.finish())
                val inputs = finished.inputs
                assertEquals(11, inputs.size, family)
                // Native serialization is the Android-compatible input codec used for persistence.
                val row = ViveInkCodec.encodeStroke(finished, "pen", "page", 0, family, 1, false, 1L)
                val decoded = assertNotNull(ViveInkCodec.decode(row)).inputs
                assertEquals(inputs.size, decoded.size)
                val last = decoded[10]
                assertEquals(.4f, last.pressure, 1f / 4096)
                assertEquals(.3f, last.tiltRadians, .001f)
                assertEquals(.5f, last.orientationRadians, .002f)
                assertEquals(.01f, last.strokeUnitLengthCm)
                assertTrue(last.x < 80f)
            }
        }
    }

    @Test fun sampleValidationRejectsInvalidAnglesAndPhysicalScale() {
        assertFailsWith<IllegalArgumentException> { pen(1f, 0, tilt = -1f) }
        assertFailsWith<IllegalArgumentException> { pen(1f, 0, tilt = 2f) }
        assertFailsWith<IllegalArgumentException> { pen(1f, 0, orientation = (2 * PI).toFloat()) }
        assertFailsWith<IllegalArgumentException> { pen(1f, 0).copy(strokeUnitLengthCm = 0f) }
        assertFailsWith<IllegalArgumentException> { pen(1f, 0, orientation = Float.NaN) }
    }

    @Test fun conservativeForecastHoldsAttributesBoundsDistanceAndStopsOnReversalOrStaleInput() {
        val predictor = InkLinearPredictor(maxPredictionDistance = 4f)
        predictor.record(pen(10f, 1000)); assertTrue(predictor.predict(1010).isEmpty())
        predictor.record(pen(20f, 1010))
        val predicted = predictor.predict(1022).single()
        assertEquals(24f, predicted.x); assertEquals(.4f, predicted.pressure)
        assertEquals(.3f, predicted.tiltRadians); assertEquals(.5f, predicted.orientationRadians)
        assertTrue(predictor.predict(1035).isEmpty())
        predictor.record(pen(19f, 1020)); assertTrue(predictor.predict(1032).isEmpty())
        predictor.record(pen(19f, 1030)); assertTrue(predictor.predict(1042).isEmpty())
        predictor.reset(); assertTrue(predictor.predict(1050).isEmpty())
    }

    @Test fun simultaneousPointersFreezeTheirOwnMetadataAndCallbacksAndFinishIndependently() {
        val first = mutableListOf<Pair<Long, androidx.ink.strokes.Stroke>>()
        val second = mutableListOf<Pair<Long, androidx.ink.strokes.Stroke>>()
        InkAuthoringSession(predictorFactory = null).use { session ->
            session.handle(InkInputEvent.Begin(pen(10f, 1000), 7), brush, onStrokeFinished = { id, stroke -> first.add(id to stroke) })
            val scaled = ImmutableAffineTransform(2f, 0f, 0f, 0f, 2f, 0f)
            session.handle(InkInputEvent.Begin(pen(40f, 1010), 8), brush, scaled, { id, stroke -> second.add(id to stroke) })
            session.handle(InkInputEvent.Batch(listOf(pen(20f, 1015), pen(30f, 1020)), pointerId = 7), brush, scaled, { _, _ -> fail("replaced callback") })
            session.handle(InkInputEvent.Finish(pen(60f, 1030), 8), brush, onStrokeFinished = { _, _ -> fail("replaced callback") })
            assertEquals(setOf(7L), session.activePointerIds)
            session.handle(InkInputEvent.Finish(pointerId = 7), brush, onStrokeFinished = { _, _ -> fail("replaced callback") })
            assertEquals(listOf(7L), first.map { it.first }); assertEquals(listOf(8L), second.map { it.first })
            assertEquals(listOf(10f, 20f, 30f), (0 until 3).map { first.single().second.inputs[it].x })
            assertEquals(listOf(20f, 30f), (0 until 2).map { second.single().second.inputs[it].x })
            assertTrue(session.activePointerIds.isEmpty())
        }
    }

    @Test fun cancellingOnePointerDoesNotCancelAnotherAndLatePacketsDoNotRestartIt() {
        var completed = 0
        InkAuthoringSession().use { session ->
            fun send(event: InkInputEvent) = session.handle(event, brush, onStrokeFinished = { _, _ -> completed++ })
            send(InkInputEvent.Begin(pen(10f, 1000), 1)); send(InkInputEvent.Begin(pen(20f, 1000), 2))
            send(InkInputEvent.CancelPointer(1)); send(InkInputEvent.Move(pen(30f, 1010), 1))
            send(InkInputEvent.Finish(pointerId = 1)); assertEquals(setOf(2L), session.activePointerIds)
            send(InkInputEvent.Finish(pointerId = 2)); assertEquals(1, completed)
        }
    }

    @Test fun aStoppedDeviceForecastExpiresAndDoesNotKeepSchedulingEmptyUpdates() {
        InkAuthoringSession().use { session ->
            fun send(event: InkInputEvent) = session.handle(event, brush, onStrokeFinished = { _, _ -> })
            send(InkInputEvent.Begin(pen(10f, 1000), 1))
            send(InkInputEvent.Move(pen(20f, 1010), 1))
            session.advance(1010)
            assertEquals(1, session.liveStrokes.single().stroke.getPredictedInputCount())
            session.advanceNow(System.nanoTime() + 50_000_000L)
            assertEquals(0, session.liveStrokes.single().stroke.getPredictedInputCount())
            assertFalse(session.needsAnimationTick())
        }
    }

    @Test fun anExplicitEmptyForecastAndAnExplicitFrameClockBothRetractPrediction() {
        InkAuthoringSession().use { session ->
            fun send(event: InkInputEvent) = session.handle(event, brush, onStrokeFinished = { _, _ -> })
            send(InkInputEvent.Begin(pen(10f, 1000), 1))
            send(InkInputEvent.Move(pen(20f, 1010), 1)); session.advance(1010)
            assertEquals(1, session.liveStrokes.single().stroke.getPredictedInputCount())
            send(InkInputEvent.Batch(emptyList(), emptyList(), 1)); session.advance(1011)
            assertEquals(0, session.liveStrokes.single().stroke.getPredictedInputCount())
            send(InkInputEvent.Move(pen(30f, 1020), 1)); session.advance(1020)
            assertEquals(1, session.liveStrokes.single().stroke.getPredictedInputCount())
            session.advance(1035)
            assertEquals(0, session.liveStrokes.single().stroke.getPredictedInputCount())
            assertFalse(session.needsAnimationTick())
        }
    }
}
