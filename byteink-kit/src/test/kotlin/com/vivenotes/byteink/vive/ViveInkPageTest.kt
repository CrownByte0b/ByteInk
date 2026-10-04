package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The page fold: which rows and operations replay, and in what order. */
class ViveInkPageTest {

    @Test
    fun strokesDrawInSeqThenIdOrderWhateverOrderTheyArriveIn() {
        val rows = listOf(row("b", seq = 2), row("c", seq = 1), row("a", seq = 2), row("d", seq = 0))

        val page = ViveInkPage.load(rows.shuffled(java.util.Random(7)), emptyList(), emptyList())

        assertEquals(listOf("d", "c", "a", "b"), page.strokes.map { it.id })
    }

    /** SQLite's `ORDER BY id` compares UTF-8 bytes: a character past U+FFFF sorts after U+FFFD. */
    @Test
    fun idsCompareByCodePointAsSqliteOrdersThem() {
        val astral = "😀" // U+1F600
        val high = "�"
        assertTrue(astral < high, "Kotlin's UTF-16 order is the other way round")

        val page = ViveInkPage.load(listOf(row(astral, seq = 0), row(high, seq = 0)), emptyList(), emptyList())

        assertEquals(listOf(high, astral), page.strokes.map { it.id })
    }

    @Test
    fun tombstonedRowsAndOperationsAreLeftOut() {
        val rows = listOf(row("live", seq = 0), row("gone", seq = 1).copy(deletedAt = 5L))
        val undone = erase("undone", 1L, InkEraseMode.Object, listOf("live"), line(10f to 45f, 10f to 55f), 12f).copy(deletedAt = 9L)

        val page = ViveInkPage.load(rows, listOf(undone), emptyList())

        assertEquals(listOf("live"), page.strokes.map { it.id })
        assertEquals(emptyList(), page.erasedAway)
    }

    @Test
    fun rowsAndOperationsThisBuildCannotReadAreSkippedAndReported() {
        val rows = listOf(row("good", seq = 0), row("future", seq = 1).copy(enc = "ink/androidx9"), row("damaged", seq = 2).copy(points = byteArrayOf(1)))
        val unknownMode = erase("lasso", 1L, InkEraseMode.Object, listOf("good"), line(10f to 45f, 10f to 55f), 12f).copy(mode = "Lasso")
        val damagedMove = move("move", 2L, listOf("good"), dx = 30f).copy(points = byteArrayOf(0))

        val page = ViveInkPage.load(rows, listOf(unknownMode), listOf(damagedMove))

        assertEquals(listOf("good"), page.strokes.map { it.id })
        assertEquals(0f, page.strokes.single().offsetX)
        assertEquals(listOf("future", "damaged"), page.unreadable)
    }

    /** An erase made after a move cuts the ink where the move left it, and not the other way round. */
    @Test
    fun operationsReplayInTheOrderTheyWereMade() {
        val rows = listOf(row("stroke", seq = 0))
        // Moves the stroke (10..90, 50) down by 100.
        val moveFirst = move("m", createdAt = 1L, targets = listOf("stroke"), dy = 100f)
        // Cuts the middle of where the stroke is after the move.
        val cutAfter = erase("e", createdAt = 2L, InkEraseMode.Normal, listOf("stroke"), line(50f to 135f, 50f to 165f), 18f)

        val moved = ViveInkPage.load(rows, listOf(cutAfter), listOf(moveFirst)).strokes
        val notMoved = ViveInkPage.load(rows, listOf(cutAfter.copy(createdAt = 0L)), listOf(moveFirst)).strokes

        assertEquals(2, moved.size, "the erase made after the move cut the moved stroke in two")
        assertEquals(1, notMoved.size, "an erase replayed first found nothing where the stroke was")
    }

    @Test
    fun rowsTheEraserTookWhollyAreReported() {
        val rows = listOf(row("small", seq = 0, points = line(48f to 50f, 52f to 50f)), row("other", seq = 1, points = line(10f to 200f, 90f to 200f)))
        val everything = erase("e", 1L, InkEraseMode.Normal, listOf("small"), line(10f to 50f, 90f to 50f), 64f)

        val page = ViveInkPage.load(rows, listOf(everything), emptyList())

        assertEquals(listOf("other"), page.strokes.map { it.id })
        assertEquals(listOf("small"), page.erasedAway)
    }

    @Test
    fun streamedChunksAddUpToTheWholePageAndStopWhenAMoveIsStored() {
        val rows = (0 until 1100).map { index -> row("row%04d".format(index), seq = index, points = line(10f to index * 2f, 20f to index * 2f)) }
        val erases = listOf(erase("e", 1L, InkEraseMode.Object, listOf("row0001"), line(15f to 1f, 15f to 3f), 2f))
        val partials = mutableListOf<List<PageStroke>>()

        val streamed = ViveInkPage.load(rows, erases, emptyList(), onPartial = { partials += it })
        val whole = ViveInkPage.load(rows, erases, emptyList())

        assertEquals(3, partials.size, "1100 rows in chunks of 512")
        assertEquals(streamed.strokes.map { it.id to it.pageBounds }, partials.last().map { it.id to it.pageBounds })
        assertEquals(whole.strokes.map { it.id to it.pageBounds }, streamed.strokes.map { it.id to it.pageBounds })
        assertFalse("row0001" in streamed.strokes.map { it.id })

        val withMove = mutableListOf<List<PageStroke>>()
        ViveInkPage.load(rows, erases, listOf(move("m", 2L, listOf("row0005"), dx = 1f)), onPartial = { withMove += it })
        assertEquals(emptyList(), withMove, "a move's clamp depends on the whole page, so nothing is streamed")
    }

    @Test
    fun decodingOnAnExecutorGivesTheSamePage() {
        val rows = (0 until 700).map { index -> row("row%04d".format(index), seq = 700 - index, points = line(10f to index * 2f, 20f to index * 2f)) }
        val pool = Executors.newFixedThreadPool(4)
        try {
            val parallel = ViveInkPage.load(rows, emptyList(), emptyList(), executor = pool)
            val inline = ViveInkPage.load(rows, emptyList(), emptyList())

            assertEquals(inline.strokes.map { it.id to it.pageBounds }, parallel.strokes.map { it.id to it.pageBounds })
        } finally {
            pool.shutdownNow()
        }
    }

    /** A rebuilt page keeps the projection numbers a held selection names, unless the projection changed. */
    @Test
    fun aRebuiltPageKeepsItsProjectionNumbers() {
        val rows = listOf(row("a", seq = 0), row("b", seq = 1, points = line(10f to 200f, 90f to 200f)))
        val before = ViveInkPage.load(rows, emptyList(), emptyList()).strokes
        val cut = erase("e", 1L, InkEraseMode.Normal, listOf("b"), line(50f to 185f, 50f to 215f), 18f)

        val after = ViveInkPage.load(rows, listOf(cut), emptyList()).strokes.keepingProjectionsOf(before)

        assertEquals(before.first { it.id == "a" }.projection, after.single { it.id == "a" }.projection)
        val beforeB = before.first { it.id == "b" }.projection
        assertTrue(after.filter { it.id == "b" }.none { it.projection == beforeB }, "the cut pieces are new projections")
    }

    private fun row(id: String, seq: Int, points: androidx.ink.strokes.StrokeInputBatch = line(10f to 50f, 90f to 50f)): StoredInkStroke =
        ViveInkCodec.encodeStroke(
            stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 1, 0xFF000000.toInt(), 6f), points),
            id = id,
            pageId = "page",
            seq = seq,
            brushFamily = ViveBrushes.MARKER,
            stabilization = 1,
            colorFollowsTheme = null,
            createdAt = 0L,
        )

    private fun erase(
        id: String,
        createdAt: Long,
        mode: InkEraseMode,
        targets: List<String>,
        path: androidx.ink.strokes.StrokeInputBatch,
        size: Float,
    ): StoredInkErase = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(path, size), id, "page", mode, createdAt, targets)

    private fun move(id: String, createdAt: Long, targets: List<String>, dx: Float = 0f, dy: Float = 0f): StoredInkMove {
        val lasso = listOf(InkPoint(0f, 0f), InkPoint(1000f, 0f), InkPoint(1000f, 3000f), InkPoint(0f, 3000f))
        return ViveInkCodec.encodeMove(InkLassoMove(lasso, targets.toSet(), emptySet(), dx, dy), id, "page", createdAt)
    }

    private fun line(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }.toImmutable()
}
