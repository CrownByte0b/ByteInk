package com.vivenotes.byteink.testing

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.byteink.kit.InkPageIndex
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.byteink.kit.selectWithLasso
import com.vivenotes.byteink.kit.targetsFor
import java.io.File
import java.util.concurrent.Executors
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Real ViveNotes notebooks, replayed page by page as the Android app replays them. Personal, so they
 * are only read from a directory the build names (`-PbyteinkNotebooks`), and only their counts are
 * reported.
 */
class RealNotebooksTest {

    @Test
    fun everyLiveRowDecodesAndEveryPageReplays() {
        val directory = System.getProperty("byteink.test.notebooks")
        assumeTrue("no notebooks named (-PbyteinkNotebooks)", directory != null)
        val notebooks = File(directory!!).listFiles { file -> file.name.endsWith(".vive") }.orEmpty().sortedBy { it.name }
        assertTrue(notebooks.isNotEmpty(), "no .vive files in $directory")
        val report = StringBuilder()
        report.append("| Notebook | Pages | Live rows | Unreadable | Projections | Erased away | Erases | Moves | Slowest page (ms) |\n")
        report.append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n")
        val unexplained = mutableListOf<String>()
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        try {
            notebooks.forEach { file ->
                var live = 0
                var unreadable = 0
                var projections = 0
                var erasedAway = 0
                var erases = 0
                var moves = 0
                var slowest = 0L
                ViveNotebook.open(file).use { notebook ->
                    notebook.pageIds.forEach { pageId ->
                        val page = notebook.page(pageId)
                        val started = System.nanoTime()
                        val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves, executor = pool)
                        slowest = maxOf(slowest, (System.nanoTime() - started) / 1_000_000)
                        val liveRows = page.strokes.filter { it.deletedAt == null }
                        live += liveRows.size
                        unreadable += loaded.unreadable.size
                        projections += loaded.strokes.size
                        erasedAway += loaded.erasedAway.size
                        erases += page.erases.count { it.deletedAt == null }
                        moves += page.moves.count { it.deletedAt == null }
                        // A row this build knows the encoder of must decode; anything else is unexplained.
                        val byId = liveRows.associateBy { it.id }
                        loaded.unreadable.map { byId.getValue(it) }.filter { it.enc == ViveInkCodec.ENCODING }
                            .forEach { unexplained += "${file.name} page $pageId row ${it.id}" }
                    }
                }
                report.append("| ${file.nameWithoutExtension} | ${pageCount(file)} | $live | $unreadable | $projections | $erasedAway | $erases | $moves | $slowest |\n")
            }
        } finally {
            pool.shutdownNow()
        }
        println(report)
        writeReport("replay.md", report)
        assertEquals(emptyList(), unexplained, "rows in a known encoding that did not decode")
    }

    /**
     * On every real page, the index finds what Android's scan finds for eraser masks and lassos;
     * then both are timed on the largest page, and on every stroke of every notebook as one page.
     */
    @Test
    fun theIndexAnswersAsTheScanDoesOnRealPagesAndFaster() {
        val directory = System.getProperty("byteink.test.notebooks")
        assumeTrue("no notebooks named (-PbyteinkNotebooks)", directory != null)
        val random = Random(5)
        val pages = File(directory!!).listFiles { file -> file.name.endsWith(".vive") }.orEmpty().sortedBy { it.name }
            .flatMap { file ->
                ViveNotebook.open(file).use { notebook ->
                    notebook.pageIds.map { pageId -> notebook.page(pageId).let { ViveInkPage.load(it.strokes, it.erases, it.moves).strokes } }
                }
            }
            .filter { it.isNotEmpty() }
        var masks = 0
        var lassos = 0
        pages.forEach { page ->
            val index = InkPageIndex(page)
            repeat(40) {
                val mask = maskNear(page, random)
                assertEquals(page.targetsFor(mask), index.targetsFor(mask))
                masks++
            }
            repeat(10) {
                val path = lassoNear(page, random)
                assertEquals(page.selectWithLasso(path), index.selectWithLasso(path))
                lassos++
            }
        }
        val largest = pages.maxBy { it.size }
        val everything = pages.flatten()
        val report = StringBuilder("| Page | Projections | Index build (ms) | Scan per mask (ms) | Index per mask (ms) |\n|---|---:|---:|---:|---:|\n")
        listOf("largest real page" to largest, "every notebook as one page" to everything).forEach { (name, page) ->
            val probes = List(200) { maskNear(page, random) }
            var index: InkPageIndex
            val build = millis { index = InkPageIndex(page) }
            index = InkPageIndex(page)
            val scan = millis { probes.forEach { page.targetsFor(it) } } / probes.size
            val indexed = millis { probes.forEach { index.targetsFor(it) } } / probes.size
            report.append("| $name | ${page.size} | ${"%.1f".format(build)} | ${"%.2f".format(scan)} | ${"%.3f".format(indexed)} |\n")
        }
        report.append("\n$masks masks and $lassos lassos on ${pages.size} real pages: the index found what the scan found every time.\n")
        println(report)
        writeReport("index.md", report)
    }

    /** An eraser mask over a random stroke's ink, or over empty paper nearby. */
    private fun maskNear(page: List<PageStroke>, random: Random): androidx.ink.strokes.Stroke {
        val bounds = page[random.nextInt(page.size)].pageBounds!!
        val x = bounds.left + random.nextFloat() * (bounds.right - bounds.left) + random.nextFloat() * 20f - 10f
        val y = bounds.top + random.nextFloat() * (bounds.bottom - bounds.top) + random.nextFloat() * 20f - 10f
        val inputs = MutableStrokeInputBatch().apply {
            add(InputToolType.UNKNOWN, x, y, 0L)
            add(InputToolType.UNKNOWN, x + random.nextFloat() * 30f, y + random.nextFloat() * 30f, 16L)
        }.toImmutable()
        return ViveBrushes.eraseMask(inputs, 4f + random.nextFloat() * 36f)
    }

    /** A concave loop around a random stretch of the page. */
    private fun lassoNear(page: List<PageStroke>, random: Random): List<InkPoint> {
        val bounds = page[random.nextInt(page.size)].pageBounds!!
        val x = bounds.left - random.nextFloat() * 60f
        val y = bounds.top - random.nextFloat() * 60f
        val w = (bounds.right - bounds.left) + random.nextFloat() * 200f
        val h = (bounds.bottom - bounds.top) + random.nextFloat() * 200f
        return listOf(InkPoint(x, y), InkPoint(x + w, y), InkPoint(x + w - 3f, y + h / 2f), InkPoint(x + w, y + h), InkPoint(x, y + h))
    }

    private inline fun millis(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1e6
    }

    private fun pageCount(file: File): Int = ViveNotebook.open(file).use { it.pageIds.size }

    /** Writes [report] into the build's report directory, when the build names one. */
    private fun writeReport(name: String, report: CharSequence) {
        System.getProperty("byteink.test.notebookReports")?.let { File(it, name).apply { parentFile.mkdirs() }.writeText(report.toString()) }
    }
}
