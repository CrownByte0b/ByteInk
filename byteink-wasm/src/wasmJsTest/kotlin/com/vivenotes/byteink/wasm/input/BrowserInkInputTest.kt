@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.vivenotes.byteink.wasm.input

import com.vivenotes.byteink.wasm.*
import kotlinx.coroutines.test.runTest
import kotlin.math.PI
import kotlin.test.*

private fun engineUrl(): String = js("process.env.BYTEINK_WASM_ENGINE")

class BrowserInkInputTest {
    private val viewport = BrowserInkViewport(1000f, 500f)
    private fun point(x: Double, time: Double, pressure: Float? = null, tilt: Float? = null, angle: Float? = null) =
        BrowserInkObservation(x, .5, time, pressure, tilt, angle)

    @Test fun fractionalTimeAndDistinctEqualTimeObservationsSurvive() {
        val normalizer = BrowserInkNormalizer()
        val begin = normalizer.begin(1, InkToolType.MOUSE, point(.1, 100.25), viewport, 1000.0, 500.0)
        assertEquals(0.0, begin.sample.elapsedTimeMillis)
        val first = normalizer.batch(1, listOf(point(.2, 100.5), point(.3, 100.75), point(.4, 100.75)), emptyList())!!
        assertEquals(listOf(.25, .5, .5), first.real.map { it.elapsedTimeMillis })
        assertEquals(listOf(200f, 300f, 400f), first.real.map { it.x })
        assertTrue(normalizer.batch(1, listOf(point(.4, 100.75), point(.2, 100.5)), emptyList())!!.real.isEmpty())
    }

    @Test fun axesFreezeAndPredictionCannotAdvanceTheRealClockOrAxes() {
        val normalizer = BrowserInkNormalizer()
        normalizer.begin(1, InkToolType.STYLUS, point(.1, 10.0, .2f, .3f, .4f), viewport, 1000.0, 500.0)
        val batch = normalizer.batch(1, listOf(point(.2, 10.25, .7f)), listOf(point(.9, 20.0, 1f, 1f, 1f)))!!
        assertEquals(.7f, batch.predicted.single().pressure)
        assertEquals(.3f, batch.predicted.single().tiltRadians)
        val actual = normalizer.batch(1, listOf(point(.3, 10.5)), emptyList())!!.real.single()
        assertEquals(.5, actual.elapsedTimeMillis)
        assertEquals(.7f, actual.pressure)
        assertEquals(.3f, actual.tiltRadians)
        normalizer.begin(2, InkToolType.STYLUS, point(.1, 10.0), viewport, 1000.0, 500.0)
        assertNull(normalizer.batch(2, listOf(point(.3, 11.0, .9f)), emptyList())!!.real.single().pressure)
        for (tool in listOf(InkToolType.MOUSE, InkToolType.TOUCH)) {
            assertNull(normalizer.begin(3, tool, point(.1, 10.0, .5f, .2f, .3f), viewport, 1000.0, 500.0).sample.pressure)
        }
    }

    @Test fun rotatedCoordinatesShaftOrientationAndPhysicalCalibration() {
        val normalizer = BrowserInkNormalizer()
        val rotated = BrowserInkViewport(100f, 100f, a = 0f, b = -2f, d = 2f, e = 0f, centimetersPerCssPixel = .02f)
        val sample = normalizer.begin(1, InkToolType.STYLUS, point(.5, 1.0, .5f, .2f, 0f), rotated, 100.0, 100.0).sample
        assertEquals(25f, sample.x); assertEquals(-25f, sample.y)
        assertEquals((PI * 1.5).toFloat(), sample.orientationRadians!!, 1e-6f)
        assertEquals(.04f, sample.strokeUnitLengthCm!!, 1e-6f)
        val anisotropic = rotated.copy(a = 2f, b = 0f, d = 0f, e = 3f)
        assertNull(normalizer.begin(2, InkToolType.STYLUS, point(.5, 2.0), anisotropic, 100.0, 100.0).sample.strokeUnitLengthCm)
        assertNull(normalizer.begin(3, InkToolType.STYLUS, point(.5, 2.0), rotated.copy(b = 1f, a = 2f), 100.0, 100.0).sample.strokeUnitLengthCm)
    }

    @Test fun invalidHistoryBoundedPredictionAndIndependentContacts() {
        val normalizer = BrowserInkNormalizer()
        normalizer.begin(1, InkToolType.MOUSE, point(.1, 10.0), viewport, 1000.0, 500.0)
        normalizer.begin(2, InkToolType.TOUCH, point(.2, 20.0), viewport, 1000.0, 500.0)
        val batch = normalizer.batch(1, listOf(point(Double.NaN, 11.0), point(.3, 9.0), point(.4, 11.0)),
            listOf(point(.6, 10.0), point(.6, 11.5), point(.7, 111.5), point(.8, 12.0)))!!
        assertEquals(1, batch.real.size)
        assertEquals(listOf(1.5, 2.0), batch.predicted.map { it.elapsedTimeMillis })
        assertNotNull(normalizer.cancel(1)); assertNull(normalizer.cancel(1))
        assertEquals(1, normalizer.activePointerCount)
        assertNotNull(normalizer.finish(2)); assertEquals(0, normalizer.activePointerCount)
        assertFailsWith<IllegalArgumentException> { BrowserInkViewport(1f, 1f, a = 0f) }
    }

    @Test fun engineSessionRetractsPredictionFinishesRealOnlyAndRetiresEveryOwner() = runTest {
        val engine = InkWasm.load(engineUrl())
        val normalizer = BrowserInkNormalizer()
        val completed = mutableListOf<InkStroke>()
        val retired = mutableListOf<InkLiveStroke>()
        val session = BrowserInkAuthoringSession(engine, { InkBrush(size = if (it.pointerId == 1) 4f else 8f) },
            { _, stroke -> completed += stroke }, onRetire = { retired += it })
        try {
            session.handle(normalizer.begin(1, InkToolType.STYLUS, point(.1, 100.125, .3f), viewport, 1000.0, 500.0))
            session.handle(normalizer.begin(2, InkToolType.TOUCH, point(.3, 100.5), viewport, 1000.0, 500.0))
            session.handle(normalizer.batch(1, listOf(point(.2, 100.375, .6f)), listOf(point(.8, 110.0)))!!)
            val live = session.liveStrokes.first().stroke
            assertEquals(1, live.predictedInputCount)
            session.advance(180.0)
            assertEquals(0, live.predictedInputCount)
            session.handle(normalizer.batch(1, listOf(point(.3, 100.625, .8f)), listOf(point(.9, 115.0)))!!)
            session.handle(normalizer.finish(1)!!)
            val saved = completed.single()
            assertEquals(3, saved.inputs().size)
            assertEquals(.5, saved.inputs().last().elapsedTimeMillis, 1e-5)
            assertEquals(300f, saved.inputs().last().x)
            assertEquals(4f, saved.brush.size)
            assertTrue(retired.single().isClosed)
            assertEquals(1, session.liveStrokes.size)
            session.cancelAll(); assertEquals(2, retired.size)
            assertTrue(retired.all { it.isClosed }); assertEquals(1, completed.size)
        } finally { session.close(); completed.forEach { it.close() } }
    }

    @Test fun failingCompletionClosesDeliveredStrokeAndLiveEngine() = runTest {
        val engine = InkWasm.load(engineUrl())
        var delivered: InkStroke? = null
        var retired: InkLiveStroke? = null
        val session = BrowserInkAuthoringSession(engine, { InkBrush() }, { _, stroke -> delivered = stroke; error("callback") },
            onRetire = { retired = it })
        val normalizer = BrowserInkNormalizer()
        session.handle(normalizer.begin(1, InkToolType.MOUSE, point(.1, 10.0), viewport, 1000.0, 500.0))
        assertFailsWith<IllegalStateException> { session.handle(normalizer.finish(1)!!) }
        assertTrue(delivered!!.isClosed); assertTrue(retired!!.isClosed)
        assertTrue(session.liveStrokes.isEmpty()); session.close()
        val allRetired = mutableListOf<InkLiveStroke>()
        val failingRetirement = BrowserInkAuthoringSession(engine, { InkBrush() }, { _, stroke -> stroke.close() },
            onRetire = { allRetired += it; error("retirement") })
        for (id in listOf(2, 3)) failingRetirement.handle(normalizer.begin(id, InkToolType.MOUSE, point(.2, 20.0), viewport, 1000.0, 500.0))
        assertFailsWith<IllegalStateException> { failingRetirement.cancelAll() }
        assertEquals(2, allRetired.size); assertTrue(allRetired.all { it.isClosed })
        assertTrue(failingRetirement.liveStrokes.isEmpty()); failingRetirement.close()
    }

    @Test fun canonicalRenderingSnapshotsAreOwnedAndVersioned() = runTest {
        val engine = InkWasm.load(engineUrl())
        engine.startStroke(InkBrush(InkStockBrush.PRESSURE_PEN)).use { live ->
            live.enqueue(listOf(InkSample(20f, 30f, 0.0, InkToolType.STYLUS, .4f), InkSample(70f, 30f, .25, InkToolType.STYLUS, .6f)))
            live.update(.25)
            val snapshot = live.rendering()
            val version = live.version
            val vertices = snapshot.groups.first().meshes.first().vertices.copyOf()
            live.enqueue(listOf(InkSample(90f, 30f, .5, InkToolType.STYLUS, .9f))); live.update(.5)
            assertTrue(live.version > version)
            assertContentEquals(vertices, snapshot.groups.first().meshes.first().vertices)
            live.finish(.5).use { stroke ->
                val rendering = stroke.rendering()
                assertEquals(1, rendering.groups.size)
                assertEquals(InkSelfOverlap.ANY, rendering.groups.single().paints.single().selfOverlap)
                assertTrue(rendering.groups.single().meshes.all { it.vertices.size == it.vertexCount * 15 && it.attributeMask and 1 != 0 })
            }
        }
    }
}
