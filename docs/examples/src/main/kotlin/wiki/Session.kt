package wiki

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.StrokeInput
import com.vivenotes.byteink.compose.InkAuthoringSession
import com.vivenotes.byteink.compose.InkInputEvent
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.kit.AuthoredViveStroke
import com.vivenotes.byteink.kit.ViveInkTool

/** Device-packet example; no window or renderer is needed to author and save real inputs. */
fun simultaneousPointerStrokes(): Map<Long, AuthoredViveStroke> {
    val tool = ViveInkTool(sizeDp = 3f)
    val completed = linkedMapOf<Long, AuthoredViveStroke>()
    val pageToSurfacePixels = ImmutableAffineTransform(2f, 0f, 0f, 0f, 2f, 0f)
    fun pen(x: Float, time: Long) = InkPointerSample(
        x, 40f, time, InputToolType.STYLUS, pressure = .5f,
        tiltRadians = .3f, orientationRadians = .5f,
    )
    fun touch(x: Float, time: Long) = InkPointerSample(x, 100f, time, InputToolType.TOUCH)

    // Disable automatic forecasting here so each replacement comes from the sample device stream.
    InkAuthoringSession(predictorFactory = null).use { session ->
        fun deliver(event: InkInputEvent) = session.handle(
            event = event,
            brush = tool.brush,
            strokeToView = pageToSurfacePixels,
            onStrokeFinished = { pointerId, realStroke ->
                completed[pointerId] = tool.complete(
                    stroke = realStroke,
                    id = "pointer-$pointerId", // Demo IDs/order; the application's repository allocates these.
                    pageId = "page-1",
                    seq = completed.size + 1,
                    createdAt = 42L,
                )
            },
        )

        deliver(InkInputEvent.Begin(pen(20f, 1_000L), pointerId = 11L))
        deliver(InkInputEvent.Begin(touch(20f, 1_000L), pointerId = 22L))
        deliver(InkInputEvent.Batch(
            samples = listOf(pen(40f, 1_008L), pen(60f, 1_016L)),
            predictedSamples = listOf(pen(200f, 1_028L)),
            pointerId = 11L,
        ))
        deliver(InkInputEvent.Batch(
            samples = listOf(touch(40f, 1_008L)),
            predictedSamples = emptyList(), // Explicitly retract predictions for this pointer.
            pointerId = 22L,
        ))
        session.advance(uptimeMillis = 1_016L)
        check(session.activePointerIds == setOf(11L, 22L)) { "Both pointers must remain active independently" }
        val wetPen = session.liveStrokes.single { it.pointerId == 11L }.stroke
        check(wetPen.getRealInputCount() == 3 && wetPen.getPredictedInputCount() == 1) {
            "Initial wet pen: expected 3 real/1 predicted, got ${wetPen.getRealInputCount()}/${wetPen.getPredictedInputCount()}"
        }

        // A replacement changes the wet tail without becoming a real observation.
        deliver(InkInputEvent.Predict(listOf(pen(80f, 1_024L)), pointerId = 11L))
        session.advance(uptimeMillis = 1_017L)
        check(wetPen.getPredictedInputCount() == 1) { "Prediction replacement must retain exactly one speculative sample" }
        val replacementX = wetPen.populateInput(StrokeInput(), 3).x
        check(replacementX == 40f) { "Replacement prediction: expected stroke-space x=40, got $replacementX" }

        deliver(InkInputEvent.Batch(
            samples = listOf(pen(70f, 1_018L)), predictedSamples = emptyList(), pointerId = 11L,
        ))
        session.advance(uptimeMillis = 1_018L)
        check(wetPen.getPredictedInputCount() == 0) { "Empty prediction must retract the wet tail" }
        deliver(InkInputEvent.Predict(listOf(pen(200f, 1_030L)), pointerId = 11L))
        session.advance(uptimeMillis = 1_019L)
        check(wetPen.getPredictedInputCount() == 1) { "Finish fixture must contain a visible prediction" }

        // Finish removes even a visible prediction before producing the saved canonical stroke.
        deliver(InkInputEvent.Finish(pen(90f, 1_020L), pointerId = 11L))
        check(session.activePointerIds == setOf(22L)) { "Finishing the pen must preserve the active touch" }
        deliver(InkInputEvent.Finish(touch(60f, 1_020L), pointerId = 22L))
        check(session.activePointerIds.isEmpty()) { "Both finished pointers must be retired" }
    }
    return completed.toMap()
}
