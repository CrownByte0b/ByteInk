package com.vivenotes.byteink.testing

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import com.vivenotes.byteink.vive.InkEraseMode
import com.vivenotes.byteink.vive.InkLassoResize
import com.vivenotes.byteink.vive.InkPoint
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkCodec
import com.vivenotes.byteink.vive.ViveInkPage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/** Cross-OS replay and software raster proof from the same archive bytes, with optional notebooks. */
internal object DesktopParity {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "dump" -> dump(File(args[1]), args.getOrNull(2)?.let(::File))
            "compare" -> compare(File(args[1]), File(args[2]))
            else -> error("Expected dump OUTPUT [REFERENCE_FIXTURES] or compare REFERENCE CANDIDATE")
        }
    }

    internal fun dump(output: File, fixtures: File?) {
        output.mkdirs()
        val input = File(output, "fixtures").apply { mkdirs() }
        if (fixtures == null) {
            val generated = Files.createTempDirectory(output.toPath(), "generated-").toFile()
            try {
                androidRenderingFixture(generated).copyTo(File(input, "brushes.vive"), overwrite = true)
            } finally {
                generated.deleteRecursively()
            }
        } else {
            val archives = fixtures.listFiles { file -> file.extension == "vive" }.orEmpty().sortedBy { it.name }
            require(archives.isNotEmpty()) { "No .vive fixtures in $fixtures" }
            archives.forEach { it.copyTo(File(input, it.name), overwrite = true) }
        }
        val library = InkNativeLibrary.load()
        File(output, "runtime.txt").writeText(
            "${System.getProperty("os.name")} / ${System.getProperty("java.vendor")} ${System.getProperty("java.version")}\n" +
                "Ink SHA-256: ${library.sha256}\n",
        )
        val cases = mutableListOf<String>()
        File(output, "bounds.tsv").bufferedWriter().use { bounds ->
            File(output, "projections.tsv").bufferedWriter().use { projections ->
                input.listFiles { file -> file.extension == "vive" }!!.sortedBy { it.name }.forEachIndexed { n, file ->
                    ViveNotebook.open(file).use { notebook ->
                        val renderer = InkPathRenderer()
                        notebook.pageIds.forEachIndexed { p, pageId ->
                            val page = notebook.page(pageId)
                            val prefix = "notebook-$n/page-$p"
                            page.strokes.filter { it.deletedAt == null }.forEach { row ->
                                val decoded = ViveInkPage.decode(row) ?: return@forEach
                                val box = decoded.stroke.shape.computeBoundingBox()
                                bounds.append(listOf(prefix, row.id, box?.xMin, box?.yMin, box?.xMax, box?.yMax).joinToString("\t")).append('\n')
                            }
                            val targets = page.strokes.filter { it.deletedAt == null }.map { it.id }
                            val mask = MutableStrokeInputBatch().apply {
                                add(InputToolType.MOUSE, 100f, 10f, 0L)
                                add(InputToolType.MOUSE, 100f, 300f, 100L)
                            }
                            val erase = ViveInkCodec.encodeErase(ViveBrushes.eraseMask(mask, 12f),
                                "parity-erase", pageId, InkEraseMode.Normal, Long.MAX_VALUE - 2, targets)
                            val resize = InkLassoResize(
                                listOf(InkPoint(-500f, -500f), InkPoint(500f, -500f), InkPoint(500f, 500f), InkPoint(-500f, 500f)),
                                targets.toSet(), emptySet(), InkPoint(100f, 120f), 0.8f, 1.2f,
                            )
                            val move = ViveInkCodec.encodeResize(resize, "parity-move", pageId, Long.MAX_VALUE - 1)
                                .copy(dxDp = 8f, dyDp = -6f)
                            val variants = listOf(
                                "stored" to (page.erases to page.moves),
                                "partial" to (page.erases + erase to page.moves),
                                "moved" to (page.erases + erase to page.moves + move),
                                "object" to (page.erases + erase.copy(mode = InkEraseMode.Object.stored) to page.moves),
                            )
                            variants.forEach { (variant, operations) ->
                                val case = "$prefix/$variant"
                                cases += case
                                val loaded = ViveInkPage.load(page.strokes, operations.first, operations.second)
                                // Include count even for a fully erased page; an empty projection TSV alone
                                // cannot distinguish a missing page from correct object erasure.
                                projections.append(listOf(case, "count", "projections", loaded.strokes.size).joinToString("\t")).append('\n')
                                loaded.strokes.forEachIndexed { i, stroke ->
                                    val box = stroke.pageBounds
                                    projections.append(listOf(case, i, stroke.id, stroke.offsetX, stroke.offsetY, stroke.scaleX, stroke.scaleY,
                                        box?.left, box?.top, box?.right, box?.bottom).joinToString("\t")).append('\n')
                                }
                                NotebookInkImages.writePng(loaded.strokes, File(output, "$case.png"), renderer = renderer)
                            }
                        }
                    }
                }
            }
        }
        require(cases.isNotEmpty())
        File(output, "cases.txt").writeText(cases.joinToString("\n", postfix = "\n"))
        println("Dumped ${cases.size} replay/raster cases using $library into $output")
    }

    internal fun compare(reference: File, candidate: File) {
        val cases = File(reference, "cases.txt").readLines()
        require(cases.isNotEmpty() && cases == File(candidate, "cases.txt").readLines()) { "Replay cases differ" }
        val bounds = AndroidNotebookFidelity.geometry(File(reference, "bounds.tsv"), File(candidate, "bounds.tsv"), 2)
        val projections = AndroidNotebookFidelity.geometry(File(reference, "projections.tsv"), File(candidate, "projections.tsv"), 3)
        // Counts must match exactly, independently of the floating-point tolerance.
        fun counts(root: File) = File(root, "projections.tsv").readLines().filter { it.split('\t')[1] == "count" }
        val equalCounts = counts(reference) == counts(candidate)
        val pixels = cases.map { case ->
            require(case.matches(Regex("notebook-\\d+/page-\\d+/[a-z]+")))
            case to AndroidNotebookFidelity.pixels(
                requireNotNull(ImageIO.read(File(reference, "$case.png"))),
                requireNotNull(ImageIO.read(File(candidate, "$case.png"))),
            )
        }
        File(candidate, "comparison.md").writeText(buildString {
            append("# Desktop platform parity\n\nReference: ${File(reference, "runtime.txt").readText().trim()}\n\n")
            append("Candidate: ${File(candidate, "runtime.txt").readText().trim()}\n\n")
            append("Same fixture archive bytes, stored replay, partial/object erases and move/resize. Geometry uses 1e-4 + 1e-5·max tolerance; identities and projection counts are exact. Software raster acceptance: maximum RGB delta ≤2.\n\n")
            append("Bounds: ${bounds.rows} rows / ${bounds.mismatches} mismatches / max gap ${bounds.maxGap}.\n\n")
            append("Projections: ${projections.rows} rows / ${projections.mismatches} mismatches / max gap ${projections.maxGap}; exact counts: $equalCounts.\n\n")
            append("| Case | Max RGB delta | MAE | SSIM |\n|---|---:|---:|---:|\n")
            pixels.forEach { (case, result) -> append("| $case | ${result.maximum} | ${result.mae} | ${result.ssim} |\n") }
            (bounds.issues + projections.issues).take(10).forEach { append("\n- $it") }
        })
        check(equalCounts && bounds.mismatches == 0 && projections.mismatches == 0) { "Desktop replay geometry differs" }
        check(pixels.all { it.second.maximum <= 2 }) { "Desktop software raster differs; see ${File(candidate, "comparison.md")}" }
        println("${cases.size} cases passed geometry, projection-count and raster parity")
    }
}
