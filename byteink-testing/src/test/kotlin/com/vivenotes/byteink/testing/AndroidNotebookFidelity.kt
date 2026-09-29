package com.vivenotes.byteink.testing

import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import com.vivenotes.byteink.oracle.writeStrokeDiagnostics
import com.vivenotes.byteink.vive.StoredInkStroke
import com.vivenotes.byteink.vive.ViveInkCodec
import com.vivenotes.byteink.vive.ViveInkPage
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import java.util.zip.ZipFile
import java.sql.DriverManager
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max

/** Optional local reference preparation/comparison. Personal notebook data stays under build/. */
internal object AndroidNotebookFidelity {
    @JvmStatic
    fun main(args: Array<String>) {
        when (args.firstOrNull()) {
            "prepare" -> prepare(File(args[1]), File(args[2]))
            "compare" -> compare(File(args[1]))
            "diagnose" -> diagnose(File(args[1]))
            else -> error("Expected prepare <notebook-directory> <output-directory> or compare/diagnose <output-directory>")
        }
    }

    private fun diagnose(root: File) {
        File(root, "diagnostics.tsv").readLines().forEachIndexed { index, line ->
            val fields = line.split('\t')
            DriverManager.getConnection("jdbc:sqlite:${File(root, fields[0]).absolutePath}").use { database ->
                database.prepareStatement("SELECT * FROM ink_strokes WHERE id = ?").use { query ->
                    query.setString(1, fields[1])
                    query.executeQuery().use { row ->
                        check(row.next())
                        val stored = StoredInkStroke(
                            id = row.getString("id"), pageId = row.getString("pageId"), seq = row.getInt("seq"),
                            brushFamily = row.getString("brushFamily"), brushVersion = row.getInt("brushVersion"),
                            sizeDp = row.getFloat("sizeDp"), colorArgb = row.getInt("colorArgb"),
                            colorFollowsTheme = row.getObject("colorFollowsTheme")?.let { (it as Number).toInt() != 0 },
                            epsilon = row.getFloat("epsilon"), stabilization = row.getInt("stabilization"),
                            minX = row.getFloat("minX"), minY = row.getFloat("minY"), maxX = row.getFloat("maxX"), maxY = row.getFloat("maxY"),
                            points = row.getBytes("points"), enc = row.getString("enc"), createdAt = row.getLong("createdAt"),
                            groupId = row.getString("groupId"), deletedAt = null,
                        )
                        val stroke = requireNotNull(ViveInkCodec.decode(stored))
                        writeStrokeDiagnostics(stroke, File(root, "desktop/diagnostics/stroke-$index"))
                    }
                }
            }
        }
    }

    private fun prepare(notebooks: File, root: File) {
        val notebooksToRead = requireNotNull(notebooks.listFiles()).filter { it.extension == "vive" }.sortedBy { it.name }
        require(notebooksToRead.isNotEmpty()) { "No .vive notebooks in $notebooks" }
        require(!File(root, "pages.tsv").exists()) { "Use a fresh output directory to avoid stale references" }
        root.mkdirs()
        val files = notebooksToRead + androidRenderingFixture(File(root, "synthetic"))
        val desktop = File(root, "desktop").apply { mkdirs() }
        File(desktop, "native.txt").writeText("sha256=${InkNativeLibrary.load().sha256}\n")
        File(root, "pages.tsv").bufferedWriter().use { pages ->
            File(desktop, "bounds.tsv").bufferedWriter().use { bounds ->
                File(desktop, "projections.tsv").bufferedWriter().use { projections ->
                    files.forEachIndexed { notebookIndex, file ->
                        ViveNotebook.open(file).use { notebook ->
                            val database = "notebook-$notebookIndex.sqlite"
                            // open() verified the archive; copy the same bytes Android will rebuild.
                            ZipFile(file).use { zip -> zip.getInputStream(zip.getEntry("notebook.sqlite")).use { input ->
                                File(root, database).outputStream().use { input.copyTo(it) }
                            } }
                            val renderer = InkPathRenderer()
                            notebook.pageIds.forEachIndexed { pageIndex, pageId ->
                                val case = "notebook-$notebookIndex/page-$pageIndex"
                                val page = notebook.page(pageId)
                                page.strokes.filter { it.deletedAt == null }.forEach { row ->
                                    val decoded = requireNotNull(ViveInkPage.decode(row)) { "Unreadable live row in $case" }
                                    val box = decoded.stroke.shape.computeBoundingBox()
                                    bounds.append(listOf(case, row.id, box?.xMin, box?.yMin, box?.xMax, box?.yMax).joinToString("\t")).append('\n')
                                }
                                val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                                check(loaded.unreadable.isEmpty())
                                val frame = NotebookInkImages.frame(loaded.strokes)
                                pages.append(listOf(case, database, pageId, frame.width, frame.height, frame.scale, frame.left, frame.top,
                                    if (notebookIndex == notebooksToRead.size) pageId else "real-notebook")
                                    .joinToString("\t")).append('\n')
                                loaded.strokes.forEachIndexed { i, stroke ->
                                    val box = stroke.pageBounds
                                    projections.append(listOf(case, i, stroke.id, stroke.offsetX, stroke.offsetY, stroke.scaleX, stroke.scaleY,
                                        box?.left, box?.top, box?.right, box?.bottom).joinToString("\t")).append('\n')
                                }
                                NotebookInkImages.writePng(loaded.strokes, File(desktop, "$case.png"), renderer = renderer)
                            }
                            println("Prepared notebook $notebookIndex: ${notebook.pageIds.size} pages")
                        }
                    }
                }
            }
        }
    }

    private fun compare(root: File) {
        val desktop = File(root, "desktop")
        val android = File(root, "android")
        val bounds = geometry(File(desktop, "bounds.tsv"), File(android, "bounds.tsv"), keyColumns = 2)
        val projections = geometry(File(desktop, "projections.tsv"), File(android, "projections.tsv"), keyColumns = 3)
        val cases = File(root, "pages.tsv").readLines().map { it.substringBefore('\t') }
        val pathMetrics = mutableMapOf<String, Pixels>()
        val softwareMetrics = mutableMapOf<String, Pixels>()
        val metrics = cases.map { case ->
            require(case.matches(Regex("notebook-\\d+/page-\\d+")))
            val a = requireNotNull(ImageIO.read(File(desktop, "$case.png")))
            val b = requireNotNull(ImageIO.read(File(android, "$case.png")))
            val result = pixels(a, b)
            File(android, "path/$case.png").takeIf { it.isFile }?.let { reference ->
                pathMetrics[case] = pixels(a, requireNotNull(ImageIO.read(reference)))
            }
            val software = requireNotNull(ImageIO.read(File(android, "software/$case.png"))) {
                "Missing Android software path reference for $case; repeat the Android capture"
            }
            softwareMetrics[case] = pixels(a, software)
            val output = File(root, "comparison/$case")
            output.parentFile.mkdirs()
            ImageIO.write(result.difference, "PNG", File("${output.path}-difference.png"))
            val pair = BufferedImage(a.width * 2, a.height, BufferedImage.TYPE_INT_RGB)
            pair.createGraphics().let { graphics ->
                try { graphics.drawImage(a, 0, 0, null); graphics.drawImage(b, a.width, 0, null) }
                finally { graphics.dispose() }
            }
            ImageIO.write(pair, "PNG", File("${output.path}-pair.png"))
            case to result
        }
        val report = buildString {
            append("# Android / desktop notebook comparison\n\n")
            append(File(android, "device.txt").readText().trim().replace("\n", "  \n")).append("\n\n")
            File(desktop, "native.txt").takeIf { it.isFile }?.let { append("Desktop native: `${it.readText().trim()}`\n\n") }
            append("Same stored bytes, draw order, white background, viewport and automatic ink colour. Android uses the pinned app's codec, replay operations and hardware CanvasStrokeRenderer; desktop uses byteink's path renderer. Pair PNGs have desktop on the left. Difference PNGs amplify channel deltas by four.\n\n")
            append("## Rebuilt geometry\n\n")
            append("Floats pass when |a − b| ≤ 1e-4 + 1e-5 · max(|a|, |b|), the native oracle's tolerance. Counts and identities must match.\n\n")
            append("| Data | Rows | Numeric values | Largest gap (dp) | Mismatches |\n|---|---:|---:|---:|---:|\n")
            listOf("Decoded bounds" to bounds, "Replayed projections" to projections).forEach { (name, result) ->
                append("| $name | ${result.rows} | ${result.values} | ${number(result.maxGap)} | ${result.mismatches} |\n")
            }
            if (bounds.issues.isNotEmpty() || projections.issues.isNotEmpty()) {
                append("\nFirst geometry mismatches (desktop → Android):\n\n")
                (bounds.issues + projections.issues).take(10).forEach { append("- $it\n") }
            }
            append("\n## Raster differences\n\n")
            append("The path port passes when every pixel of the Android software path reference differs by at most two 8-bit RGB levels. This admits rasterizer rounding while rejecting missing ink, colour changes and changed overlap coverage. Hardware mesh/path antialiasing is measured separately; these modes need not rasterize identical pixels.\n\n")
            append("MAE is mean absolute RGB channel error over the whole image (0–255). Ink pixels are the union where either image has an RGB channel below 250. >16 counts ink pixels with a maximum channel delta above 16. SSIM uses 8×8 luminance windows, constants C1=(0.01·255)², C2=(0.03·255)² and population variance. These are measurements, not a substitute for accepted brush-specific fidelity thresholds.\n\n")
            append("| Case | MAE | Max delta | Ink pixels | Ink >16 (%) | SSIM |\n|---|---:|---:|---:|---:|---:|\n")
            metrics.forEach { (case, result) ->
                append("| $case | ${number(result.mae)} | ${result.maximum} | ${result.ink} | ${number(result.differingInk * 100.0 / max(1, result.ink))} | ${number(result.ssim)} |\n")
            }
            if (pathMetrics.isNotEmpty()) {
                append("\n## Android path renderer reference\n\n")
                append("This compares the port against Android's forcePathRendering=true, separating path conversion from the mesh renderer's antialiasing.\n\n")
                append("| Case | MAE | Max delta | Ink >16 (%) | SSIM |\n|---|---:|---:|---:|---:|\n")
                pathMetrics.forEach { (case, result) ->
                    append("| $case | ${number(result.mae)} | ${result.maximum} | ${number(result.differingInk * 100.0 / max(1, result.ink))} | ${number(result.ssim)} |\n")
                }
            }
            append("\n## Software path acceptance\n\n")
            append("| Case / brush | MAE | Max delta | SSIM | Pass (delta ≤2) |\n|---|---:|---:|---:|---|\n")
            val names = File(root, "pages.tsv").readLines().associate {
                val fields = it.split('\t'); fields[0] to fields.getOrElse(8) { "real-notebook" }
            }
            softwareMetrics.forEach { (case, result) ->
                append("| $case / ${names.getValue(case)} | ${number(result.mae)} | ${result.maximum} | ${number(result.ssim)} | ${result.maximum <= 2} |\n")
            }
        }
        File(root, "comparison.md").writeText(report)
        println("${cases.size} pages; bounds: ${bounds.rows} rows, max gap ${bounds.maxGap}, ${bounds.mismatches} mismatches; projections: ${projections.rows} rows, max gap ${projections.maxGap}, ${projections.mismatches} mismatches")
        println("Report: ${File(root, "comparison.md").absolutePath}")
        check(bounds.mismatches == 0 && projections.mismatches == 0) { "Android rebuilt geometry differs; see local report" }
        check(softwareMetrics.values.all { it.maximum <= 2 }) { "Android software path rendering differs; see local report" }
    }

    internal data class Geometry(val rows: Int, val values: Int, val maxGap: Double, val mismatches: Int, val issues: List<String>)

    internal fun geometry(a: File, b: File, keyColumns: Int): Geometry {
        fun read(file: File) = file.readLines().map { it.split('\t') }.let { rows ->
            rows.associateBy { it.take(keyColumns) }.also { check(it.size == rows.size) { "Duplicate reference keys" } }
        }
        val left = read(a)
        val right = read(b)
        var mismatches = (left.keys - right.keys).size + (right.keys - left.keys).size
        val issues = mutableListOf<String>()
        (left.keys - right.keys).take(10).forEach { issues += "Missing Android row: ${it.joinToString(" / ")}" }
        (right.keys - left.keys).take(10).forEach { issues += "Extra Android row: ${it.joinToString(" / ")}" }
        var values = 0
        var maxGap = 0.0
        (left.keys intersect right.keys).forEach { key ->
            val x = left.getValue(key).drop(keyColumns)
            val y = right.getValue(key).drop(keyColumns)
            if (x.size != y.size) { mismatches++; issues += "Column count differs: ${key.joinToString(" / ")}"; return@forEach }
            x.zip(y).forEachIndexed { column, (p, q) ->
                if (p == "null" || q == "null") {
                    if (p != q) { mismatches++; if (issues.size < 10) issues += "${key.joinToString(" / ")}, coordinate $column: $p → $q" }
                    return@forEachIndexed
                }
                val first = p.toDouble()
                val second = q.toDouble()
                val gap = abs(first - second)
                values++
                maxGap = max(maxGap, gap)
                if (!gap.isFinite() || gap > 1e-4 + 1e-5 * max(abs(first), abs(second))) {
                    mismatches++
                    if (issues.size < 10) issues += "${key.joinToString(" / ")}, coordinate $column: $p → $q (gap ${number(gap)})"
                }
            }
        }
        return Geometry(left.size, values, maxGap, mismatches, issues)
    }

    internal data class Pixels(val mae: Double, val maximum: Int, val ink: Int, val differingInk: Int,
        val ssim: Double, val difference: BufferedImage)

    internal fun pixels(a: BufferedImage, b: BufferedImage): Pixels {
        require(a.width == b.width && a.height == b.height) { "Image dimensions differ" }
        val difference = BufferedImage(a.width, a.height, BufferedImage.TYPE_INT_RGB)
        var sum = 0L
        var maximum = 0
        var ink = 0
        var differingInk = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            val p = a.getRGB(x, y)
            val q = b.getRGB(x, y)
            var pixelMax = 0
            var isInk = false
            var amplified = 0
            for (shift in listOf(16, 8, 0)) {
                val first = (p ushr shift) and 255
                val second = (q ushr shift) and 255
                val delta = abs(first - second)
                sum += delta
                pixelMax = max(pixelMax, delta)
                isInk = isInk || first < 250 || second < 250
                amplified = amplified or (minOf(255, delta * 4) shl shift)
            }
            maximum = max(maximum, pixelMax)
            if (isInk) { ink++; if (pixelMax > 16) differingInk++ }
            difference.setRGB(x, y, amplified)
        }
        return Pixels(sum.toDouble() / (a.width.toLong() * a.height * 3), maximum, ink, differingInk,
            ssim(a, b), difference)
    }

    private fun ssim(a: BufferedImage, b: BufferedImage): Double {
        fun luminance(rgb: Int) = 0.2126 * ((rgb ushr 16) and 255) + 0.7152 * ((rgb ushr 8) and 255) + 0.0722 * (rgb and 255)
        var sum = 0.0
        var windows = 0
        for (top in 0 until a.height step 8) for (left in 0 until a.width step 8) {
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var syy = 0.0; var sxy = 0.0; var count = 0
            for (y in top until minOf(top + 8, a.height)) for (x in left until minOf(left + 8, a.width)) {
                val p = luminance(a.getRGB(x, y)); val q = luminance(b.getRGB(x, y))
                sx += p; sy += q; sxx += p * p; syy += q * q; sxy += p * q; count++
            }
            val mx = sx / count; val my = sy / count
            val vx = max(0.0, sxx / count - mx * mx); val vy = max(0.0, syy / count - my * my)
            val covariance = sxy / count - mx * my
            val c1 = 6.5025; val c2 = 58.5225
            sum += ((2 * mx * my + c1) * (2 * covariance + c2)) / ((mx * mx + my * my + c1) * (vx + vy + c2))
            windows++
        }
        return sum / windows
    }

    private fun number(value: Double) = String.format(Locale.ROOT, "%.6f", value)
}
