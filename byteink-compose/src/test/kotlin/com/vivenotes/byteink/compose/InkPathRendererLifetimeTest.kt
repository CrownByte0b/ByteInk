package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.asSkiaPath
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.ViveBrushes
import java.lang.ref.WeakReference
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.jetbrains.skia.Path as SkiaPath

class InkPathRendererLifetimeTest {
    @Test
    fun byteBudgetClosesEvictedAndOversizedPathsWithoutChangingDrawing() = raster { canvas ->
        val first = stroke()
        val probe = InkPathRenderer()
        probe.draw(canvas, first)
        val budget = probe.cachedPathBytes
        assertTrue(budget > 0)
        probe.clearCache()
        val renderer = InkPathRenderer(100, budget)
        renderer.draw(canvas, first)
        val original = canvas.paths.last()
        assertEquals(1, renderer.cachedShapeCount)
        val long = Stroke(first.brush, MutableStrokeInputBatch().apply {
            repeat(1024) { i -> add(InputToolType.MOUSE, 10f + i % 100, 10f + (i % 30), i * 10L) }
        }.toImmutable())
        renderer.draw(canvas, long)
        assertTrue(canvas.paths.last().isClosed, "oversized paths retire after their draw")
        assertFalse(original.isClosed, "a useful retained path survives an oversized transient draw")
        assertEquals(1, renderer.cachedShapeCount)
        assertTrue(renderer.cachedPathBytes <= budget)
        renderer.draw(canvas, first)
        assertSame(original, canvas.paths.last())
        renderer.draw(canvas, stroke())
        assertTrue(original.isClosed, "byte eviction closes even with spare entry capacity")
        assertEquals(1L, renderer.pathEvictionCount)
        renderer.clearCache()
        assertEquals(0L, renderer.cachedPathBytes)
        assertTrue(canvas.paths.all { it.isClosed })
    }

    @Test
    fun zeroByteBudgetDisablesRetentionAndLegacyConstructorsRemainCallable() = raster { canvas ->
        assertEquals(2048, InkPathRenderer::class.java.getConstructor().newInstance().cacheCapacity)
        assertEquals(3, InkPathRenderer::class.java.getConstructor(Int::class.javaPrimitiveType).newInstance(3).cacheCapacity)
        val renderer = InkPathRenderer(100, 0)
        renderer.draw(canvas, stroke())
        assertTrue(canvas.paths.last().isClosed)
        assertEquals(0, renderer.cachedShapeCount)
        assertEquals(0L, renderer.cachedPathBytes)
        assertFailsWith<IllegalArgumentException> { InkPathRenderer(100, -1) }
    }

    @Test
    fun finishedEvictionAndClearCloseSnapshotsWhileRecolourReusesThem() = raster { canvas ->
        val renderer = InkPathRenderer(1)
        val first = stroke()
        renderer.draw(canvas, first)
        val original = canvas.paths.last()
        assertFalse(original.isClosed)
        renderer.draw(canvas, first.copy(first.brush.copyWithColorIntArgb(0x80ff0000.toInt())))
        assertSame(original, canvas.paths.last())
        renderer.draw(canvas, stroke())
        assertTrue(original.isClosed)
        assertFalse(canvas.paths.last().isClosed)
        renderer.clearCache()
        assertTrue(canvas.paths.all { it.isClosed })
        renderer.clearCache()
        renderer.draw(canvas, first)
        assertFalse(canvas.paths.last().isClosed)
        renderer.clearCache()
    }

    @Test
    fun uncachedPathsStayOpenThroughDrawAndCloseOnReturn() = raster { canvas ->
        val renderer = InkPathRenderer(0)
        repeat(4) {
            renderer.draw(canvas, stroke())
            assertTrue(canvas.paths.last().isClosed)
        }
        assertEquals(0, renderer.cachedShapeCount)
        renderer.clearCache()
    }

    @Test
    fun aFailingCanvasRestoresItsStateAndReleasesUncachedPaths() = Surface.makeRasterN32Premul(128, 128).use { surface ->
        val canvas = RecordingCanvas(surface.canvas.asComposeCanvas(), fail = true)
        val saves = surface.canvas.saveCount
        assertFailsWith<IllegalStateException> { InkPathRenderer(0).draw(canvas, stroke()) }
        assertEquals(saves, surface.canvas.saveCount)
        assertTrue(canvas.paths.single().isClosed)
    }

    @Test
    fun liveReplacementClearAndRestartCloseOnlyObsoleteSnapshots() = raster { canvas ->
        val renderer = InkPathRenderer()
        val live = InProgressStroke()
        live.start(stroke().brush)
        live.enqueueInputs(inputs(), MutableStrokeInputBatch())
        live.updateShape(100L)
        renderer.draw(canvas, live)
        val first = canvas.paths.last()
        renderer.draw(canvas, live)
        assertSame(first, canvas.paths.last())
        live.enqueueInputs(MutableStrokeInputBatch().apply { add(InputToolType.MOUSE, 100f, 80f, 200L) }, MutableStrokeInputBatch())
        live.updateShape(200L)
        renderer.draw(canvas, live)
        assertTrue(first.isClosed)
        val second = canvas.paths.last()
        assertFalse(second.isClosed)
        live.clear()
        assertFalse(renderer.draw(canvas, live))
        assertTrue(second.isClosed)
        live.start(stroke().brush)
        live.enqueueInputs(inputs(), MutableStrokeInputBatch())
        live.updateShape(100L)
        renderer.draw(canvas, live)
        assertFalse(canvas.paths.last().isClosed)
        renderer.clearCache()
        assertTrue(canvas.paths.all { it.isClosed })
        live.clear()
    }

    @Test
    fun aCollectedLiveOwnerReleasesItsSnapshotOnTheNextDraw() = raster { canvas ->
        val renderer = InkPathRenderer()
        val owner = drawTemporaryLive(renderer, canvas)
        val snapshot = canvas.paths.last()
        for (attempt in 0 until 100) {
            if (owner.get() == null) break
            System.gc()
            Thread.sleep(10L)
        }
        assertTrue(owner.get() == null, "Renderer must not retain a live stroke owner")
        // ReferenceQueue delivery can follow weak-reference clearing; drain it on subsequent draws.
        for (attempt in 0 until 100) {
            renderer.draw(canvas, stroke())
            if (snapshot.isClosed) break
            System.gc()
            Thread.sleep(10L)
        }
        assertTrue(snapshot.isClosed)
        renderer.clearCache()
    }

    private fun drawTemporaryLive(renderer: InkPathRenderer, canvas: Canvas): WeakReference<InProgressStroke> {
        val live = InProgressStroke()
        live.start(stroke().brush)
        live.enqueueInputs(inputs(), MutableStrokeInputBatch())
        live.updateShape(100L)
        renderer.draw(canvas, live)
        return WeakReference(live)
    }

    private fun stroke(): Stroke = Stroke(ViveBrushes.highlighter(0x804020e0.toInt(), 8f), inputs())

    private fun inputs(): MutableStrokeInputBatch = MutableStrokeInputBatch().apply {
        add(InputToolType.MOUSE, 10f, 20f, 0L)
        add(InputToolType.MOUSE, 80f, 60f, 100L)
    }

    private fun raster(block: (RecordingCanvas) -> Unit) = Surface.makeRasterN32Premul(128, 128).use {
        block(RecordingCanvas(it.canvas.asComposeCanvas()))
    }

    private class RecordingCanvas(private val delegate: Canvas, val fail: Boolean = false) : Canvas by delegate {
        val paths = ArrayList<SkiaPath>()
        override fun drawPath(path: Path, paint: Paint) {
            val native = path.asSkiaPath()
            assertFalse(native.isClosed, "Snapshot must remain alive while being drawn")
            paths.add(native)
            if (fail) error("Canvas failure")
            delegate.drawPath(path, paint)
        }
    }
}
