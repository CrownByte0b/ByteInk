package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkMeshes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class PreparedInkReplayTest {
    @Test
    fun streamingDecodesEachMaskOnceAndVisitsOnlyItsTargets() {
        val template = row("template", 0)
        val rows = List(4096) { template.copy(id = "row-$it", seq = it) }
        val erases = List(64) { erase("erase-$it", it.toLong(), InkEraseMode.Object,
            listOf("row-$it", "row-$it", "absent"), 10000f, 10000f) }
        val work = InkReplayWork()
        val partials = mutableListOf<List<PageStroke>>()
        val page = ViveInkPage.load(rows, erases, emptyList(), null, { partials += it }, work)
        assertEquals(64, work.maskDecodes)
        assertEquals(64L, work.targetBuckets)
        assertEquals(64L, work.targetProjections)
        assertEquals((1..8).map { it * 512 }, partials.map { it.size })
        assertEquals(rows.map { it.id }, page.strokes.map { it.id })
        assertEquals(512, partials.first().size, "publication snapshots must remain independent")
        assertEquals(4096, page.sourceStrokes.size)
        assertEquals(64, page.operations.size)
        assertFailsWith<UnsupportedOperationException> {
            (page.operations.first().targetIds as MutableSet).add("later")
        }
    }

    @Test
    fun bucketReplayMatchesWholePageFoldForSplitsGroupsMovesAndResizes() {
        val rows = listOf(row("a", 2), row("b", 1, y = 100f),
            row("😀", 0, y = 150f), row("�", 0, y = 200f),
            row("dead", 3).copy(deletedAt = 3), row("bad", 4).copy(points = byteArrayOf(1)))
        val erases = listOf(
            erase("cut", 1, InkEraseMode.Normal, listOf("a"), 50f, 50f, 18f),
            erase("object", 3, InkEraseMode.Object, listOf("a", "absent"), 80f, 60f, 12f),
            erase("unknown", 4, InkEraseMode.Object, listOf("b"), 50f, 100f).copy(mode = "Future"),
            erase("broken", 5, InkEraseMode.Object, listOf("b"), 50f, 100f).copy(points = byteArrayOf(0)),
        )
        val moves = listOf(move("move", 2, listOf("b", "a", "b"), -70f, 10f).copy(
            scaleX = 1.2f, scaleY = 1.1f, anchorX = 20f, anchorY = 20f))
        val page = ViveInkPage.load(rows, erases.reversed(), moves)
        val sorted = rows.filter { it.deletedAt == null }.sortedWith(compareBy<StoredInkStroke> { it.seq }
            .thenComparator { a, b -> a.id.codePoints().toArray().let { x ->
                val y = b.id.codePoints().toArray(); x.zip(y).firstOrNull { it.first != it.second }
                    ?.let { it.first.compareTo(it.second) } ?: x.size.compareTo(y.size)
            } })
        val decoded = sorted.mapNotNull(ViveInkPage::decode)
        val expected = reference(decoded, erases, moves)
        assertProjections(expected, page.strokes)
        assertEquals(listOf("bad"), page.unreadable)
        assertEquals(decoded.map { it.id }.distinct().filterNot { id -> expected.any { it.id == id } }, page.erasedAway)
        val untouched = page.sourceStrokes.single { it.id == "😀" }
        assertSame(untouched, page.strokes.single { it.id == "😀" })
    }

    @Test
    fun globalMoveClampIncludesTargetsAcrossDecodeChunksEvenForZeroTranslation() {
        val template = row("t", 0, x = 100f)
        val rows = List(1100) { template.copy(id = "row-$it", seq = it) }.toMutableList()
        rows[0] = row("row-0", 0, x = -50f)
        val move = move("m", 1, listOf("row-1099", "row-0"), 0f, 0f)
        val partials = mutableListOf<List<PageStroke>>()
        val page = ViveInkPage.load(rows, emptyList(), listOf(move), onPartial = { partials += it })
        val expected = reference(rows.mapNotNull(ViveInkPage::decode), emptyList(), listOf(move))
        assertProjections(expected, page.strokes)
        assertEquals(emptyList(), partials)
        val left = page.strokes.first()
        val right = page.strokes.last()
        assertTrue(left.offsetX > 0f)
        assertEquals(left.offsetX, right.offsetX, "all selected rows receive one global clamped delta")
        assertEquals(0f, left.pageBounds!!.left)
    }

    @Test
    fun resizeReselectsInTheOriginalLassoAfterTranslationAndParsesPathOnce() {
        val source = row("a", 0)
        val move = ViveInkCodec.encodeMove(InkLassoMove(listOf(InkPoint(0f, 0f), InkPoint(100f, 0f),
            InkPoint(100f, 100f), InkPoint(0f, 100f)), setOf("a"), emptySet(), 200f, 0f), "m", "page", 1)
            .copy(scaleX = 2f, scaleY = 2f)
        val work = InkReplayWork()
        val page = ViveInkPage.load(listOf(source), emptyList(), listOf(move), null, null, work)
        assertEquals(1, work.pathDecodes)
        assertEquals(1L, work.targetProjections)
        assertEquals(200f, page.strokes.single().offsetX)
        assertEquals(1f, page.strokes.single().scaleX, "the translated stroke is outside the original resize lasso")
        assertProjections(reference(listOf(assertNotNull(ViveInkPage.decode(source))), emptyList(), listOf(move)), page.strokes)
    }

    @Test
    fun executorHasAtMostFourUnconsumedJobsAndPublishesInOrder() {
        val template = row("t", 0)
        val rows = List(4096) { template.copy(id = "r-$it", seq = it) }
        val queued = ConcurrentLinkedQueue<Runnable>()
        val fourQueued = CountDownLatch(4)
        val executor = Executor { queued.add(it); fourQueued.countDown() }
        val caller = Executors.newSingleThreadExecutor()
        val work = InkReplayWork()
        val partials = mutableListOf<List<PageStroke>>()
        try {
            val future = caller.submit<LoadedInkPage> {
                ViveInkPage.load(rows, emptyList(), emptyList(), executor, { partials += it }, work)
            }
            assertTrue(fourQueued.await(10, TimeUnit.SECONDS))
            assertEquals(4, queued.size, "submitting all eight chunks would exceed the bounded window")
            // Completing later chunks first must not publish them ahead of the first.
            val first = queued.remove()
            repeat(3) { queued.remove().run() }
            assertEquals(0, partials.size)
            first.run()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (!future.isDone && System.nanoTime() < deadline) {
                queued.poll()?.run() ?: Thread.yield()
            }
            val page = future.get(5, TimeUnit.SECONDS)
            assertEquals(4, work.peakDecodeJobs)
            assertEquals(rows.map { it.id }, page.strokes.map { it.id })
            assertEquals((1..8).map { it * 512 }, partials.map { it.size })
        } finally { caller.shutdownNow() }
    }

    @Test
    fun failedPublicationCancelsQueuedDecodeSuppliers() {
        val template = row("t", 0)
        val rows = List(4096) { template.copy(id = "r-$it", seq = it) }
        val queued = ConcurrentLinkedQueue<Runnable>()
        var submitted = 0
        val executor = Executor { task -> if (submitted++ == 0) task.run() else queued.add(task) }
        assertFailsWith<IllegalStateException> {
            ViveInkPage.load(rows, emptyList(), emptyList(), executor) { error("stop publication") }
        }
        assertEquals(4, submitted)
        assertEquals(3, queued.size)
        queued.forEach(Runnable::run)
    }

    private fun reference(source: List<PageStroke>, erases: List<StoredInkErase>, moves: List<StoredInkMove>): List<PageStroke> {
        val operations = (erases.filter { it.deletedAt == null }.map { Triple(it.createdAt, it.id, it as Any) } +
            moves.filter { it.deletedAt == null }.map { Triple(it.createdAt, it.id, it as Any) }).sortedWith(compareBy({ it.first }, { it.second }))
        return operations.fold(source) { current, (_, _, operation) -> when (operation) {
            is StoredInkErase -> {
                val mode = InkEraseMode.of(operation.mode)
                val mask = ViveInkCodec.decodeErase(operation)
                if (mode == null || mask == null) current else when (mode) {
                    InkEraseMode.Normal -> current.subtract(mask, operation.targetIds)
                    InkEraseMode.Object -> current.eraseObjects(mask, operation.targetIds)
                }
            }
            is StoredInkMove -> ViveInkCodec.decodeMove(operation)?.let { path ->
                current.replayMove(path, operation.targetIds, operation.dxDp, operation.dyDp)
                    .replayResize(path, operation.targetIds, InkPoint(operation.anchorX, operation.anchorY), operation.scaleX, operation.scaleY)
            } ?: current
            else -> error("Unknown operation")
        } }
    }

    private fun assertProjections(expected: List<PageStroke>, actual: List<PageStroke>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertEquals(a.copy(stroke = b.stroke, projection = b.projection), b)
            assertEquals(a.pageBounds, b.pageBounds)
            assertEquals(a.stroke.shape.getRenderGroupCount(), b.stroke.shape.getRenderGroupCount())
            repeat(a.stroke.shape.getRenderGroupCount()) { group ->
                val am = InkMeshes.triangles(a.stroke.shape, group)
                val bm = InkMeshes.triangles(b.stroke.shape, group)
                assertEquals(am.size, bm.size)
                am.zip(bm).forEach { (x, y) ->
                    assertContentEquals(x.positions, y.positions)
                    assertContentEquals(x.triangles, y.triangles)
                }
            }
        }
    }

    private fun row(id: String, seq: Int, x: Float = 10f, y: Float = 50f): StoredInkStroke = ViveInkCodec.encodeStroke(
        Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff112233.toInt(), 6f), line(x, y, x + 80f, y)),
        id, "page", seq, ViveBrushes.MARKER, 0, true, 0, "group")
    private fun erase(id: String, time: Long, mode: InkEraseMode, targets: List<String>, x: Float, y: Float, size: Float = 12f) =
        ViveInkCodec.encodeErase(ViveBrushes.eraseMask(line(x, y - 15f, x, y + 15f), size), id, "page", mode, time, targets)
    private fun move(id: String, time: Long, targets: List<String>, dx: Float, dy: Float) = ViveInkCodec.encodeMove(
        InkLassoMove(listOf(InkPoint(-1000f, -1000f), InkPoint(2000f, -1000f), InkPoint(2000f, 2000f), InkPoint(-1000f, 2000f)), targets.toSet(), emptySet(), dx, dy), id, "page", time)
    private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = MutableStrokeInputBatch().apply {
        add(InputToolType.MOUSE, x1, y1, 0)
        add(InputToolType.MOUSE, x2, y2, 100)
    }
}
