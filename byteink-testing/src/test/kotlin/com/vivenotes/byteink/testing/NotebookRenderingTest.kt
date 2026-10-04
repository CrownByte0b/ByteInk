package com.vivenotes.byteink.testing

import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkPage
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class NotebookRenderingTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun everyBrushCanBeWrittenIntoANotebookAndRenderedAfterReopening() {
        val source = syntheticNotebook(temporary.root)
        val ids = listOf(ViveBrushes.MARKER, ViveBrushes.PRESSURE_PEN, ViveBrushes.HIGHLIGHTER, ViveBrushes.DASHED_LINE) + (0..5).map(ViveBrushes::calligraphy)
        val rows = ids.mapIndexed { i, id -> newStroke("new-$i", "page", 3 + i, id) }
        val copy = File(temporary.root, "brushes.vive")
        ViveNotebook.open(source).use { it.writeCopyWithStrokes(copy, rows) }
        ViveNotebook.open(copy).use { notebook ->
            val page = notebook.page("page")
            assertEquals(rows, page.strokes.filter { it.id.startsWith("new-") })
            val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
            assertEquals(listOf("opaque"), loaded.unreadable)
            val image = File(temporary.root, "after.png")
            val raster = NotebookInkImages.writePng(loaded.strokes, image)
            assertEquals(10, raster.drawn)
            assertTrue(image.length() > 100)
            val expected = rows.map { PageStroke(it.id, assertNotNull(ViveInkCodec.decode(it))) }
            val before = File(temporary.root, "before.png")
            NotebookInkImages.writePng(expected, before)
            assertTrue(before.readBytes().contentEquals(image.readBytes()), "written strokes render identically after rereading")
            val empty = NotebookInkImages.writePng(emptyList(), File(temporary.root, "empty.png"))
            assertEquals(32, empty.width)
            assertEquals(32, empty.height)
        }
    }

    @Test
    fun realNotebooksVerifyRenderEveryPageAndRoundTripNewStrokes() {
        val directory = System.getProperty("byteink.test.notebooks")
        assumeTrue("no notebooks named (-PbyteinkNotebooks or BYTEINK_NOTEBOOKS)", directory != null)
        val files = File(directory!!).listFiles { file -> file.extension == "vive" }.orEmpty().sortedBy { it.name }
        assertTrue(files.isNotEmpty(), "no notebooks in $directory")
        val output = File(System.getProperty("byteink.test.notebookReports", temporary.root.path), "rendering").apply { mkdirs() }
        val report = StringBuilder("| Notebook | Pages | Projections rendered | Outline-free groups | Max stored/rebuilt bound deviation (dp) | Cold render+PNG (ms) | Warm render+PNG (ms) | New rows reread |\n|---|---:|---:|---:|---:|---:|---:|---:|\n")
        files.forEachIndexed { notebookIndex, file ->
            val originalHash = file.inputStream().use { it.sha256() }
            var drawn = 0
            var outlineFree = 0
            var maxBoundsGap = 0f
            var cold = 0.0
            var warm = 0.0
            ViveNotebook.open(file).use { notebook ->
                val appended = notebook.pageIds.mapIndexed { i, pageId ->
                    val page = notebook.page(pageId)
                    val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                    assertTrue(loaded.unreadable.all { id -> page.strokes.single { it.id == id }.enc != ViveInkCodec.ENCODING }, "unexplained unreadable row")
                    loaded.strokes.forEach { projection ->
                        repeat(projection.stroke.shape.getRenderGroupCount()) { group ->
                            if (projection.stroke.shape.getOutlineCount(group) == 0) outlineFree++
                        }
                    }
                    page.strokes.filter { it.deletedAt == null }.forEach { row ->
                        ViveInkCodec.decode(row)?.shape?.computeBoundingBox()?.let { box ->
                            maxBoundsGap = maxOf(maxBoundsGap, abs(row.minX - box.xMin), abs(row.minY - box.yMin), abs(row.maxX - box.xMax), abs(row.maxY - box.yMax))
                        }
                    }
                    val renderer = InkPathRenderer(cacheCapacity = loaded.strokes.size)
                    val png = File(output, "notebook-$notebookIndex/page-$i.png")
                    val result = NotebookInkImages.writePng(loaded.strokes, png, renderer = renderer)
                    assertEquals(loaded.strokes.size, result.drawn)
                    drawn += result.drawn
                    cold += result.elapsedMillis
                    val warmPng = File(temporary.root, "warm.png")
                    warm += NotebookInkImages.writePng(loaded.strokes, warmPng, renderer = renderer).elapsedMillis
                    assertTrue(png.readBytes().contentEquals(warmPng.readBytes()), "cache changes the image")
                    renderer.clearCache()
                    newStroke("byteink-proof-$i", pageId, (page.strokes.maxOfOrNull { it.seq } ?: 0) + 1)
                }
                val copy = File(temporary.root, "notebook-$notebookIndex.vive")
                notebook.writeCopyWithStrokes(copy, appended)
                ViveNotebook.open(copy).use { reread ->
                    appended.forEach { row ->
                        val page = reread.page(row.pageId)
                        assertEquals(row, page.strokes.single { it.id == row.id })
                        val original = notebook.page(row.pageId)
                        assertEquals(original.strokes, page.strokes.filterNot { it.id == row.id })
                        assertEquals(original.erases, page.erases)
                        assertEquals(original.moves, page.moves)
                        val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                        assertTrue(loaded.strokes.any { it.id == row.id })
                    }
                }
                report.append("| ${file.nameWithoutExtension} | ${notebook.pageIds.size} | $drawn | $outlineFree | $maxBoundsGap | ${"%.1f".format(cold)} | ${"%.1f".format(warm)} | ${appended.size} |\n")
            }
            assertEquals(originalHash, file.inputStream().use { it.sha256() }, "source notebook changed")
        }
        report.append("\nImages contain ink only, fitted to its bounds on white paper. Stored bounds are diagnostic; Android screenshots and Android rebuilt-geometry goldens are still required for fidelity certification.\n")
        File(output, "report.md").writeText(report.toString())
        println(report)
    }
}
