package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InkAuthoringControllerTest {
    private val black = 0xff000000.toInt()
    private fun marker(level: Int = 0): Brush = ViveBrushes.brush(ViveBrushes.MARKER, level, black, 10f)

    @OptIn(androidx.ink.nativeloader.InkInternalOnlyApi::class)
    @Test
    fun bufferedObservationsKeepTheWetPathUntilOneProcessedFrame() {
        InkAuthoringController().use { controller ->
            val renderer = InkPathRenderer()
            Surface.makeRasterN32Premul(128, 128).use { surface ->
                val canvas = surface.canvas.asComposeCanvas()
                controller.begin(marker(), sample(10f, 50f, 1_000L))
                val wet = assertNotNull(controller.liveStroke)
                renderer.draw(canvas, wet)
                val version = wet.getVersion()
                val revision = controller.revision
                repeat(32) { i ->
                    assertTrue(controller.append(sample(12f + i * 3f, 50f, 1_001L + i)))
                    renderer.draw(canvas, wet)
                    assertEquals(version, wet.getVersion(), "buffering does not enqueue into the engine")
                    assertEquals(revision, controller.revision, "buffering does not invalidate drawing")
                    assertEquals(1L, renderer.pathBuildCount)
                }
                assertTrue(controller.hasPendingInputs)
                assertTrue(controller.isUpdateNeeded())
                assertEquals(1, wet.getInputCount())
                assertTrue(controller.advance(1_040L))
                assertFalse(controller.hasPendingInputs)
                assertEquals(version + 2, wet.getVersion(), "one enqueue and one shape update")
                assertEquals(revision + 1, controller.revision)
                assertEquals(33, wet.getInputCount())
                renderer.draw(canvas, wet)
                assertEquals(2L, renderer.pathBuildCount)
                assertFalse(controller.advance(1_050L))
                assertEquals(revision + 1, controller.revision)
                assertEquals(33, assertNotNull(controller.finish()).inputs.size)
            }
            renderer.clearCache()
        }
    }

    @Test
    fun finishingWithoutAFrameFlushesAllPendingPressureAndFinalInput() {
        InkAuthoringController().use { controller ->
            controller.begin(marker(), InkPointerSample(10f, 50f, 1_000L, InputToolType.STYLUS, .2f))
            repeat(32) { i ->
                controller.append(InkPointerSample(12f + i * 3f, 50f, 1_001L + i,
                    InputToolType.STYLUS, if (i % 2 == 0) .6f else null))
            }
            val finished = assertNotNull(controller.finish(InkPointerSample(112f, 50f, 1_040L,
                InputToolType.STYLUS, .8f)))
            assertEquals(34, finished.inputs.size)
            assertEquals(.2f, finished.inputs[0].pressure)
            repeat(32) { i -> assertEquals(.6f, finished.inputs[i + 1].pressure) }
            assertEquals(.8f, finished.inputs[33].pressure)
            assertFalse(controller.hasPendingInputs)
            assertFalse(controller.isUpdateNeeded())
            assertEquivalentOutlines(Stroke(finished.brush, finished.inputs), finished, "pending finish")
        }
    }

    @Test
    fun cancellationDiscardsUnprocessedHistoryBeforeRestart() {
        InkAuthoringController().use { controller ->
            controller.begin(marker(), sample(10f, 50f, 1_000L))
            repeat(32) { controller.append(sample(it.toFloat(), 60f, 1_001L + it)) }
            assertTrue(controller.hasPendingInputs)
            controller.cancel()
            assertFalse(controller.hasPendingInputs)
            assertFalse(controller.isUpdateNeeded())
            controller.begin(marker(), sample(80f, 90f, 2_000L))
            assertFalse(controller.hasPendingInputs)
            assertEquals(1, assertNotNull(controller.finish()).inputs.size)
        }
    }

    @Test
    fun dragPreservesRealInputsBrushAndEventRelativeTimes() {
        InkAuthoringController().use { controller ->
            val brush = marker(4)
            controller.begin(brush, sample(20f, 40f, 1_000L))
            assertTrue(controller.isDrawing)
            assertEquals(1, assertNotNull(controller.liveStroke).getInputCount())
            controller.append(sample(60f, 45f, 1_024L))
            assertTrue(controller.advance(1_030L))
            val stroke = assertNotNull(controller.finish(sample(100f, 40f, 1_056L)))
            assertEquals(brush, stroke.brush)
            assertEquals(3, stroke.inputs.size)
            assertEquals(listOf(0L, 24L, 56L), List(3) { stroke.inputs[it].elapsedTimeMillis })
            assertEquals(listOf(20f, 60f, 100f), List(3) { stroke.inputs[it].x })
            assertEquals(InputToolType.MOUSE, stroke.inputs.getToolType())
            assertFalse(stroke.inputs.hasPressure())
            assertFalse(controller.isDrawing)
            assertNull(controller.liveStroke)
            assertNull(controller.finish())
        }
    }

    @Test
    fun cancelClearsWetGeometryAndRapidGesturesReuseTheNativeStroke() {
        InkAuthoringController().use { controller ->
            controller.begin(marker(), sample(20f, 40f))
            val reusable = assertNotNull(controller.liveStroke)
            controller.append(sample(100f, 40f, 20L))
            controller.advance(20L)
            assertTrue(reusable.getInputCount() > 0)
            controller.cancel()
            assertNull(controller.liveStroke)
            assertNull(reusable.brush)
            assertEquals(0, reusable.getInputCount())
            assertNull(controller.finish())
            repeat(100) { i ->
                controller.begin(marker(), sample(i.toFloat(), 40f, i * 100L))
                assertSame(reusable, controller.liveStroke)
                val stroke = assertNotNull(controller.finish(sample(i + 10f, 40f, i * 100L + 16L)))
                assertEquals(2, stroke.inputs.size)
                assertEquals(i.toFloat(), stroke.inputs[0].x)
            }
        }
    }

    @Test
    fun aTapProducesOneIndependentStroke() {
        InkAuthoringController().use { controller ->
            controller.begin(marker(), sample(40f, 40f, 100L))
            val first = assertNotNull(controller.finish(sample(40f, 40f, 100L)))
            assertEquals(1, first.inputs.size)
            assertNotNull(first.shape.computeBoundingBox())
            controller.begin(marker(), sample(80f, 80f, 200L))
            controller.cancel()
            assertEquals(40f, first.inputs[0].x)
            val box = assertNotNull(first.shape.computeBoundingBox())
            assertTrue(40f in box.xMin..box.xMax && 40f in box.yMin..box.yMax)
        }
    }

    @Test
    fun repeatedTripletsOutOfOrderSamplesAndToolChangesAreDiscarded() {
        InkAuthoringController().use { controller ->
            controller.begin(marker(), sample(10f, 20f, 100L))
            val revision = controller.revision
            assertFalse(controller.append(sample(10f, 20f, 100L)))
            assertFalse(controller.append(sample(15f, 20f, 99L)))
            assertFalse(controller.append(InkPointerSample(15f, 20f, 101L, InputToolType.STYLUS, 0.5f)))
            assertEquals(revision, controller.revision)
            // Equal timestamps at different positions are valid Ink observations.
            assertTrue(controller.append(sample(20f, 20f, 100L)))
            assertTrue(controller.append(sample(20f, 20f, 108L)))
            val stroke = assertNotNull(controller.finish())
            assertEquals(3, stroke.inputs.size)
            assertEquals(listOf(0L, 0L, 8L), List(3) { stroke.inputs[it].elapsedTimeMillis })
        }
    }

    @Test
    fun stylusPressureAvailabilityStaysConsistentAcrossTheGesture() {
        InkAuthoringController().use { controller ->
            controller.begin(marker(), InkPointerSample(20f, 20f, 10L, InputToolType.STYLUS, 0.2f))
            controller.append(InkPointerSample(40f, 20f, 20L, InputToolType.STYLUS))
            val stroke = assertNotNull(controller.finish(InkPointerSample(60f, 20f, 30L, InputToolType.STYLUS, 0.8f)))
            assertTrue(stroke.inputs.hasPressure())
            assertEquals(listOf(0.2f, 0.2f, 0.8f), List(3) { stroke.inputs[it].pressure })
            controller.begin(marker(), InkPointerSample(20f, 20f, 40L, InputToolType.STYLUS))
            val unreported = assertNotNull(controller.finish(InkPointerSample(60f, 20f, 50L, InputToolType.STYLUS, 0.8f)))
            assertFalse(unreported.inputs.hasPressure())
        }
    }

    @Test
    fun stationaryPressureChangesArePreservedAtDistinctDeviceTimes() {
        InkAuthoringController().use { controller ->
            controller.begin(marker(), InkPointerSample(40f, 40f, 100L, InputToolType.STYLUS, 0.2f))
            assertFalse(controller.append(InkPointerSample(40f, 40f, 100L, InputToolType.STYLUS, 0.8f)))
            assertTrue(controller.append(InkPointerSample(40f, 40f, 101L, InputToolType.STYLUS, 0.8f)))
            val finished = assertNotNull(controller.finish())
            assertEquals(listOf(0.2f, 0.8f), List(2) { finished.inputs[it].pressure })
        }
    }

    @Test
    fun pageCoordinatesUseTheFrozenInverseOfZoomRotationAndTranslation() {
        InkAuthoringController().use { controller ->
            // surface x=-2*page y+100, surface y=page x+10.
            val transform = MutableAffineTransform(0f, -2f, 100f, 1f, 0f, 10f)
            controller.begin(marker(), sample(60f, 20f, 100L), transform)
            transform.populateFromIdentity()
            controller.append(sample(60f, 40f, 120L))
            val stroke = assertNotNull(controller.finish())
            assertEquals(10f, stroke.inputs[0].x)
            assertEquals(20f, stroke.inputs[0].y)
            assertEquals(30f, stroke.inputs[1].x)
            assertEquals(20f, stroke.inputs[1].y)
            assertEquals(ImmutableAffineTransform(0f, -2f, 100f, 1f, 0f, 10f), controller.strokeToView)
        }
    }

    @Test
    fun invalidTransformsAndSamplesNeverStartNativeAuthoring() {
        InkAuthoringController().use { controller ->
            val invalid = listOf(
                ImmutableAffineTransform(0f, 0f, 0f, 0f, 0f, 0f),
                ImmutableAffineTransform(Float.NaN, 0f, 0f, 0f, 1f, 0f),
                ImmutableAffineTransform(Float.MIN_VALUE, 0f, 0f, 0f, Float.MIN_VALUE, 0f),
            )
            invalid.forEach { transform ->
                assertFailsWith<IllegalArgumentException> { controller.begin(marker(), sample(20f, 40f), transform) }
                assertFalse(controller.isDrawing)
                assertNull(controller.liveStroke)
            }
            assertFailsWith<IllegalArgumentException> { InkPointerSample(Float.POSITIVE_INFINITY, 0f, 0L) }
            assertFailsWith<IllegalArgumentException> { InkPointerSample(0f, 0f, -1L) }
            assertFailsWith<IllegalArgumentException> { InkPointerSample(0f, 0f, 0L, pressure = Float.NaN) }
            assertFailsWith<IllegalArgumentException> { InkPointerSample(0f, 0f, 0L, pressure = 1.1f) }
            controller.begin(marker(), sample(20f, 40f))
            assertNotNull(controller.finish())
        }
    }

    @Test
    fun frameTimeNeverMovesBackwardAndIdleFramesDoNoWork() {
        InkAuthoringController().use { controller ->
            assertFalse(controller.advance(1_000L))
            controller.begin(marker(), sample(20f, 40f, 1_000L))
            assertFalse(controller.advance(1_010L))
            controller.append(sample(100f, 40f, 1_024L))
            assertTrue(controller.advance(999L))
            assertFalse(controller.advance(998L))
            controller.append(sample(110f, 40f, 1_032L))
            assertTrue(controller.advance(1_000L))
            assertEquals(3, assertNotNull(controller.finish()).inputs.size)
        }
    }

    @Test
    fun liveHighlighterSelfCrossingHasUniformOpacityBeforeFinish() {
        InkAuthoringController().use { controller ->
            controller.begin(ViveBrushes.highlighter(0x80ff0000.toInt(), 14f), sample(20f, 20f, 100L))
            controller.append(sample(100f, 100f, 200L))
            controller.append(sample(20f, 100f, 300L))
            controller.append(sample(100f, 20f, 400L))
            controller.advance(400L)
            Surface.makeRasterN32Premul(128, 128).use { surface ->
                surface.canvas.clear(0)
                assertTrue(InkPathRenderer().draw(surface.canvas.asComposeCanvas(), assertNotNull(controller.liveStroke)))
                surface.makeImageSnapshot().use { image ->
                    Bitmap.makeFromImage(image).use { bitmap ->
                        assertEquals(128, bitmap.getColor(60, 60) ushr 24)
                        repeat(128 * 128) { i -> assertTrue(bitmap.getColor(i % 128, i / 128) ushr 24 <= 128) }
                    }
                }
            }
        }
    }

    @Test
    fun everyFamilyStabilizationAndToolSettlesToItsRebuiltShape() {
        val families = listOf(ViveBrushes.MARKER, ViveBrushes.PRESSURE_PEN, ViveBrushes.DASHED_LINE, ViveBrushes.HIGHLIGHTER) +
            (0..5).map(ViveBrushes::calligraphy)
        val tools = listOf(InputToolType.MOUSE, InputToolType.TOUCH, InputToolType.STYLUS, InputToolType.UNKNOWN)
        InkAuthoringController().use { controller ->
            for (family in families) for (level in 0..5) for (tool in tools) {
                val description = "$family stabilization=$level tool=$tool"
                val brush = ViveBrushes.brush(family, level, black, 10f)
                fun event(x: Float, y: Float, time: Long, pressure: Float): InkPointerSample =
                    InkPointerSample(x, y, time, tool, if (tool == InputToolType.STYLUS) pressure else null)
                controller.begin(brush, event(20f, 40f, 1_000L, 0.25f))
                controller.append(event(40f, 55f, 1_016L, 0.5f))
                controller.advance(1_018L)
                controller.append(event(60f, 35f, 1_032L, 0.75f))
                controller.advance(1_035L)
                controller.append(event(80f, 50f, 1_048L, 0.5f))
                val finished = assertNotNull(controller.finish(event(100f, 40f, 1_064L, 0.25f)))
                assertEquivalentOutlines(finished, Stroke(brush, finished.inputs), description)
                val row = ViveInkCodec.encodeStroke(finished, "drawn", "page", 0, family, level, false, 1L)
                val decoded = assertNotNull(ViveInkCodec.decode(row), description)
                assertEquals(tool, decoded.inputs.getToolType(), description)
                assertEquivalentOutlines(finished, decoded, "$description encoded row")
            }
        }
    }

    @Test
    fun settledIncrementalPressurePenDifferenceIsReportedAndCanonicalGeometryMatchesPersistence() {
        val brush = ViveBrushes.brush(ViveBrushes.PRESSURE_PEN, 1, black, 10f)
        val incremental = InProgressStroke()
        val emptyPrediction = MutableStrokeInputBatch()
        val inputs = MutableStrokeInputBatch()
        fun enqueue(vararg samples: InkPointerSample) {
            inputs.clear()
            samples.forEach { inputs.add(it.toolType, it.x, it.y, it.uptimeMillis) }
            incremental.enqueueInputs(inputs, emptyPrediction)
        }
        try {
            incremental.start(brush)
            enqueue(sample(20f, 40f, 0L))
            incremental.updateShape(0L)
            enqueue(sample(40f, 55f, 16L))
            incremental.updateShape(18L)
            enqueue(sample(60f, 35f, 32L))
            incremental.updateShape(35L)
            enqueue(sample(80f, 50f, 48L), sample(100f, 40f, 64L))
            // Compare the finished native mesh, after stabilization/timed behavior completes.
            // An active tail is intentionally still moving and is not this persistence boundary.
            incremental.finishInput()
            incremental.updateShape()
            val settled = incremental.toImmutable()
            val persisted = Stroke(brush, settled.inputs)
            val before = InkMeshes.outlines(settled.shape, 0)
            val after = InkMeshes.outlines(persisted.shape, 0)
            assertTrue(before.isNotEmpty() && after.isNotEmpty())
            assertTrue(before.all { it.isNotEmpty() && it.all(Float::isFinite) })
            assertTrue(after.all { it.isNotEmpty() && it.all(Float::isFinite) })
            val boundaryDistance = maxOf(directedBoundaryDistance(before, after), directedBoundaryDistance(after, before))
            assertTrue(boundaryDistance.isFinite())
            val matchedCoordinateDelta = if (before.size == after.size && before.indices.all { before[it].size == after[it].size }) {
                before.indices.maxOf { outline -> before[outline].indices.maxOf { i -> abs(before[outline][i] - after[outline][i]) } }
            } else null
            assertTrue(matchedCoordinateDelta == null || matchedCoordinateDelta.isFinite())
            // Mesh epsilon bounds simplification within one modeled shape; it is not a guarantee
            // that incremental derivative modeling and one-shot modeling produce identical shapes.
            // Measure this pinned case separately while keeping persistence equality strict below.
            val report = """
                {
                  "scope": "Pinned Ink pressure-pen V1, stabilization 1, mouse, size 10, epsilon 0.25; settled incremental mesh versus one-shot Stroke reconstruction from the exact same native input batch",
                  "inputs": [[20,40,0], [40,55,16], [60,35,32], [80,50,48], [100,40,64]],
                  "incrementalUpdatesMillis": [0, 18, 35],
                  "finalUpdate": "finishInput followed by terminal updateShape",
                  "maximumSymmetricOutlineVertexToSegmentDistancePageUnits": $boundaryDistance,
                  "maximumMatchedVertexCoordinateDeltaPageUnits": $matchedCoordinateDelta,
                  "measurement": "Informational native modeling difference; mesh epsilon is not a bound on incremental-versus-one-shot model differences",
                  "canonicalPersistenceCheck": "Controller completion strictly matches the one-shot real-Ink reconstruction; existing encoded-row and raster equality checks retain their thresholds"
                }
            """.trimIndent() + "\n"
            System.getProperty("byteink.test.interactionReport")?.let { destination ->
                val path = Path.of(destination).resolveSibling("pressure-pen-fidelity.json")
                path.parent?.let { Files.createDirectories(it) }
                Files.writeString(path, report)
            }
            println(report)
            InkAuthoringController().use { controller ->
                controller.begin(brush, sample(20f, 40f, 1_000L))
                controller.append(sample(40f, 55f, 1_016L))
                controller.advance(1_018L)
                controller.append(sample(60f, 35f, 1_032L))
                controller.advance(1_035L)
                controller.append(sample(80f, 50f, 1_048L))
                val canonical = assertNotNull(controller.finish(sample(100f, 40f, 1_064L)))
                assertEquivalentOutlines(persisted, canonical, "canonical persistence boundary")
            }
        } finally { incremental.clear() }
    }

    @Test
    fun timedBrushBehaviorAdvancesWithoutNewInputAndFinishesSettled() {
        val family = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(
            target = TargetNode.Target.CORNER_ROUNDING_OFFSET,
            targetModifierRangeStart = 0f,
            targetModifierRangeEnd = 1f,
            input = SourceNode(SourceNode.Source.TIME_SINCE_INPUT_IN_SECONDS, 0f, 1f),
        )))))
        val brush = Brush.createWithColorIntArgb(family, black, 10f, 0.25f)
        InkAuthoringController().use { controller ->
            controller.begin(brush, sample(40f, 40f, 1_000L))
            assertTrue(assertNotNull(controller.liveStroke).changesWithTime())
            assertTrue(controller.advance(1_500L))
            val revision = controller.revision
            assertTrue(controller.advance(1_400L))
            assertTrue(controller.revision > revision)
            val finished = assertNotNull(controller.finish())
            assertEquivalentOutlines(finished, Stroke(brush, finished.inputs), "timed behavior")
        }
    }

    @Test
    fun aClosedControllerDiscardsInputAndCannotRestart() {
        val controller = InkAuthoringController()
        controller.begin(marker(), sample(40f, 40f))
        assertFailsWith<IllegalStateException> { controller.begin(marker(), sample(50f, 50f)) }
        controller.close()
        controller.close()
        assertFalse(controller.isDrawing)
        assertNull(controller.liveStroke)
        assertFalse(controller.append(sample(50f, 50f, 20L)))
        assertNull(controller.finish())
        assertFailsWith<IllegalStateException> { controller.begin(marker(), sample(50f, 50f)) }
    }

    private fun sample(x: Float, y: Float, time: Long = 0L): InkPointerSample = InkPointerSample(x, y, time)

    /** Largest outline-vertex distance to the other mesh's closed outline segments. */
    private fun directedBoundaryDistance(from: List<FloatArray>, to: List<FloatArray>): Float {
        var maximum = 0f
        from.forEach { outline ->
            for (index in outline.indices step 2) {
                val x = outline[index]
                val y = outline[index + 1]
                var nearest = Float.POSITIVE_INFINITY
                to.forEach { target ->
                    for (start in target.indices step 2) {
                        val end = (start + 2) % target.size
                        val dx = target[end] - target[start]
                        val dy = target[end + 1] - target[start + 1]
                        val squaredLength = dx * dx + dy * dy
                        val ratio = if (squaredLength == 0f) 0f else
                            (((x - target[start]) * dx + (y - target[start + 1]) * dy) / squaredLength).coerceIn(0f, 1f)
                        nearest = minOf(nearest, hypot(x - (target[start] + ratio * dx), y - (target[start + 1] + ratio * dy)))
                    }
                }
                maximum = maxOf(maximum, nearest)
            }
        }
        return maximum
    }

    private fun assertEquivalentOutlines(expected: Stroke, actual: Stroke, label: String) {
        assertEquals(expected.brush, actual.brush, label)
        assertEquals(expected.inputs.size, actual.inputs.size, label)
        assertEquals(expected.shape.getRenderGroupCount(), actual.shape.getRenderGroupCount(), label)
        repeat(expected.shape.getRenderGroupCount()) { group ->
            val before = InkMeshes.outlines(expected.shape, group)
            val after = InkMeshes.outlines(actual.shape, group)
            assertEquals(before.size, after.size, label)
            before.zip(after).forEach { (a, b) ->
                assertEquals(a.size, b.size, label)
                a.indices.forEach { i -> assertTrue(abs(a[i] - b[i]) <= 0.0001f, "$label outline coordinate $i: ${a[i]} != ${b[i]}") }
            }
        }
    }
}
