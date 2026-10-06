package com.vivenotes.byteink.testing

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.byteink.compose.InkAuthoringController
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import com.vivenotes.byteink.oracle.OracleProjection
import com.vivenotes.byteink.oracle.SyntheticFidelityMatrix
import com.vivenotes.byteink.oracle.geometryJson
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.InkPageIndex
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.StoredInkErase
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.byteink.kit.ViveInkTool
import com.vivenotes.byteink.kit.automaticColorOr
import com.vivenotes.byteink.kit.touches
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.sql.Connection
import java.sql.DriverManager
import java.util.Base64
import java.util.UUID
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/** Crosses the actual Android transfer boundary; all input data is public and synthetic. */
internal object AndroidNotebookRoundTrip {
    private val json = Json { prettyPrint = true }
    private const val themeArgb = 0xff006b61.toInt()
    private val inkTables = listOf("ink_strokes", "ink_erases", "ink_erase_targets", "ink_moves", "ink_move_targets")
    private val families by lazy {
        listOf(ViveBrushes.MARKER, ViveBrushes.DASHED_LINE, ViveBrushes.PRESSURE_PEN) +
            (0..ViveBrushes.MAX_CALLIGRAPHY_PRESSURE).map(ViveBrushes::calligraphy)
    }
    internal val unknownBrushId = uuid("unknown-brush")
    internal val unknownEncodingId = uuid("unknown-encoding")
    internal val tombstoneId = uuid("tombstone")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.isNotEmpty()) { "Expected prepare OUTPUT_DIRECTORY MATRIX_DIRECTORY | verify OUTPUT_DIRECTORY" }
        when (args[0]) {
            "prepare" -> { require(args.size == 3); prepare(File(args[1]), File(args[2])) }
            "verify" -> { require(args.size == 2); verify(File(args[1])) }
            else -> error("Unknown notebook round-trip command: ${args[0]}")
        }
    }

    internal fun prepare(root: File, matrix: File) {
        val base = File(matrix, "synthetic.vive")
        require(!root.canonicalFile.toPath().startsWith(matrix.canonicalFile.toPath())) {
            "Round-trip output must be outside immutable Android fixtures"
        }
        MatrixComparison.validateManifest(matrix)
        root.mkdirs()
        require(listOf("source.vive", "desktop.vive", "unknown-enc.vive", "expectations.json").none { File(root, it).exists() }) {
            "Round-trip directory already contains a prepared fixture: $root"
        }
        val source = File(root, "source.vive")
        val sourceOpaque = File(root, "source-unknown-enc.vive")
        val desktop = File(root, "desktop.vive")
        val opaque = File(root, "unknown-enc.vive")
        val authoredCases = families.flatMap { family -> (0..5).map { family to it } } + (ViveBrushes.HIGHLIGHTER to 0)
        val pages = SyntheticFidelityMatrix.cases.filter { it.scenario == "brush" }.take(authoredCases.size)
        val authored = authoredCases.mapIndexed { index, (family, level) ->
            val tool = ViveInkTool(family, level, if (family == ViveBrushes.HIGHLIGHTER) 0x80ffee00.toInt()
                else if (index % 3 == 1) 0x80522db5.toInt() else 0xff173b83.toInt(),
                if (family == ViveBrushes.HIGHLIGHTER) 20f else 8f,
                if (family == ViveBrushes.HIGHLIGHTER) false else when (index % 3) { 0 -> false; 1 -> null; else -> true })
            val stroke = author(tool.brush, index)
            tool.complete(stroke, uuid("stroke:$family:$level"), pages[index].pageId, 3, 4000L + index,
                if (index % 2 == 0) uuid("group:$index") else null)
        }
        val rows = authored.map { it.row }
        ViveNotebook.open(base).use { notebook ->
            val seed = notebook.page(pages.first().pageId).strokes.first()
            val sentinelRows = listOf(
                seed.copy(id = unknownBrushId, seq = 10, brushFamily = "future-brush-v17", colorFollowsTheme = null,
                    groupId = uuid("preservation-group"), createdAt = 3500L),
                seed.copy(id = tombstoneId, seq = 11, createdAt = 3501L, deletedAt = 3502L),
            )
            notebook.writeCopyWithStrokes(source, sentinelRows)
        }
        ViveNotebook.open(source).use { notebook ->
            val seed = notebook.page(pages.first().pageId).strokes.first()
            notebook.writeCopyWithStrokes(sourceOpaque, listOf(seed.copy(id = unknownEncodingId, seq = 12,
                brushFamily = "future-brush-v17", brushVersion = 17, enc = "ink/future-v17",
                points = byteArrayOf(0, 0xff.toByte(), 0x7f, 0x80.toByte(), 0, 0x12), colorFollowsTheme = null,
                createdAt = 3503L, groupId = uuid("opaque-group"))))
        }
        // The eraser goes through the same real authoring controller, then its persisted mask is
        // checked against the current projections before writing the operation's target links.
        val eraseCases = listOf(0 to InkEraseMode.Object, 18 to InkEraseMode.Object,
            1 to InkEraseMode.Normal, 19 to InkEraseMode.Normal, rows.lastIndex to InkEraseMode.Normal)
        val originalMasks = linkedMapOf<String, Stroke>()
        val erases = eraseCases.map { (index, mode) ->
            val target = rows[index]
            val decoded = requireNotNull(ViveInkCodec.decode(target))
            val bounds = requireNotNull(decoded.shape.computeBoundingBox())
            val samples = listOf(
                InkPointerSample((bounds.xMin + bounds.xMax) / 2f, bounds.yMin - 12f, 10000L),
                InkPointerSample((bounds.xMin + bounds.xMax) / 2f, bounds.yMax + 12f, 10100L),
            )
            val mask = authorSamples(ViveBrushes.eraseMask(decoded.inputs, 30f).brush, samples)
            val projected = listOf(requireNotNull(ViveInkPage.decode(target)))
            val preliminary = ViveInkCodec.encodeErase(mask, uuid("${mode.name.lowercase()}-erase:$index"), target.pageId,
                mode, 6000L + index, emptyList())
            val persistedMask = requireNotNull(ViveInkCodec.decodeErase(preliminary))
            val targets = InkPageIndex(projected).targetsFor(persistedMask)
            check(targets == listOf(target.id)) { "Authored eraser missed $index" }
            originalMasks[preliminary.id] = mask
            preliminary.copy(targetIds = targets)
        }
        ViveNotebook.open(source).use { it.writeCopyWithInk(desktop, rows, erases) }
        ViveNotebook.open(sourceOpaque).use { it.writeCopyWithInk(opaque, rows, erases) }
        assertOriginalRowsPreserved(source, desktop)
        assertOriginalRowsPreserved(sourceOpaque, opaque)
        val frames = (pages.map { it.pageId } + SyntheticFidelityMatrix.cases.filter { it.scenario == "operations" }.map { it.pageId }).distinct()
        val renderer = InkPathRenderer()
        ViveNotebook.open(desktop).use { notebook ->
            frames.forEach { id ->
                val page = notebook.page(id)
                val directory = File(root, "pages/$id").apply { mkdirs() }
                File(directory, "geometry.json").writeText(pageGeometry(page))
                render(page, renderer, File(directory, "desktop.png"))
            }
            check(notebook.pageIds.size == 280)
            check(notebook.pageIds.sumOf { notebook.page(it).strokes.size } == 897)
            erases.forEach { erase ->
                val page = notebook.page(erase.pageId)
                val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                if (erase.mode == InkEraseMode.Object.stored) {
                    check(erase.targetIds.all { it in loaded.erasedAway }) { "Object erase must remove its target" }
                } else {
                    val survivors = loaded.strokes.filter { it.id in erase.targetIds }
                    check(survivors.isNotEmpty()) { "Normal erase must leave ink on both sides" }
                    val mask = requireNotNull(ViveInkCodec.decodeErase(erase))
                    check(survivors.none { it.touches(mask) }) { "Normal erase must leave an empty gap" }
                    val bounds = requireNotNull(mask.shape.computeBoundingBox())
                    check(survivors.any { requireNotNull(it.pageBounds).left < bounds.xMin } &&
                        survivors.any { requireNotNull(it.pageBounds).right > bounds.xMax }) { "Normal erase lost an end" }
                }
            }
        }
        val notebookId = withDatabase(desktop) { db ->
            db.createStatement().use { statement -> statement.executeQuery("SELECT id FROM notebooks").use { it.next(); it.getString(1) } }
        }
        val expectations = obj(
            "schemaVersion" to JsonPrimitive(1), "notebookId" to JsonPrimitive(notebookId),
            "strokeCount" to JsonPrimitive(897),
            "authoredRows" to JsonArray(authored.map { strokeExpectation(it.row, it.stroke) }),
            "erases" to JsonArray(erases.map { eraseExpectation(it, originalMasks.getValue(it.id)) }),
            "encodingProbes" to encodingProbes(),
            "preservation" to obj("unknownBrushId" to JsonPrimitive(unknownBrushId),
                "tombstoneId" to JsonPrimitive(tombstoneId), "unknownEncodingId" to JsonPrimitive(unknownEncodingId)),
            "pages" to JsonArray(frames.map { id -> obj("id" to JsonPrimitive(id), "width" to JsonPrimitive(480),
                "height" to JsonPrimitive(960), "scale" to JsonPrimitive(1f), "left" to JsonPrimitive(0f),
                "top" to JsonPrimitive(0f), "themeArgb" to JsonPrimitive(themeArgb),
                "geometry" to JsonPrimitive("pages/$id/geometry.json")) }),
        )
        writeJson(File(root, "expectations.json"), expectations)
        val native = InkNativeLibrary.load()
        writeJson(File(root, "desktop-runtime.json"), obj("os" to JsonPrimitive(System.getProperty("os.name")),
            "architecture" to JsonPrimitive(System.getProperty("os.arch")), "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
            "nativeSha256" to JsonPrimitive(native.sha256), "baseSha256" to JsonPrimitive(MatrixComparison.sha256(base))))
        writeJson(File(root, "source-rows.json"), snapshot(source))
        writeJson(File(root, "desktop-rows.json"), snapshot(desktop))
        println("Prepared 55 controller-authored strokes, 2 Object and 3 Normal erases, 4 independent encoding probes, 95 replay pages and opaque preservation fixtures: $root")
    }

    internal fun verify(root: File) {
        val expected = Json.parseToJsonElement(File(root, "expectations.json").readText()).jsonObject
        val capture = validateCapturedEvidence(root, expected)
        val desktop = File(root, "desktop.vive")
        val android = File(root, "android/reexport.vive")
        assertSameRows(desktop, android)
        assertOriginalRowsPreserved(File(root, "source.vive"), android)
        check(archiveAttachments(desktop) == archiveAttachments(android)) { "Android re-export changed attachments" }
        val opaque = File(root, "android/unknown-android.vive")
        assertSameRows(File(root, "unknown-enc.vive"), opaque)
        val opaqueCopy = File(root, "unknown-desktop-copy.vive")
        if (opaqueCopy.exists()) opaqueCopy.delete()
        ViveNotebook.open(opaque).use { notebook ->
            val row = notebook.pageIds.asSequence().flatMap { notebook.page(it).strokes.asSequence() }.single { it.id == unknownEncodingId }
            check(row.enc == "ink/future-v17" && ViveInkCodec.decode(row) == null)
            notebook.writeCopyWithInk(opaqueCopy, emptyList(), emptyList())
        }
        assertSameRows(opaque, opaqueCopy)
        check(archiveAttachments(opaque) == archiveAttachments(opaqueCopy))
        val issues = mutableListOf<String>()
        val metrics = linkedMapOf<String, JsonElement>()
        var floatValues = 0
        var integerValues = 0
        var maximumGap = 0.0
        val encoding = mutableListOf<JsonElement>()
        ViveNotebook.open(desktop).use { notebook ->
            val renderer = InkPathRenderer()
            expected.getValue("pages").jsonArray.forEach { frame ->
                val id = frame.jsonObject.getValue("id").jsonPrimitive.content
                render(notebook.page(id), renderer, File(root, "pages/$id/desktop.png"))
            }
            val authoredIds = (expected.getValue("authoredRows").jsonArray + expected.getValue("erases").jsonArray)
                .map { it.jsonObject.getValue("id").jsonPrimitive.content }.toSet()
            notebook.pageIds.forEach { id ->
                val page = notebook.page(id)
                val blobs = page.strokes.map { it.id to it.points } + page.erases.map { it.id to it.points }
                blobs.filter { it.first in authoredIds }.forEach { (rowId, points) ->
                    val original = File(root, "android/encoding/$rowId.original.pb.gz").readBytes()
                    val identical = points.contentEquals(original)
                    encoding += obj("id" to JsonPrimitive(rowId), "gzipIdentical" to JsonPrimitive(identical),
                        "desktopSha256" to JsonPrimitive(points.inputStream().use { it.sha256() }),
                        "androidSha256" to JsonPrimitive(original.inputStream().use { it.sha256() }))
                    if (!identical) issues += "$rowId encoding differs from Android's canonical original-input encoder"
                }
            }
            check(encoding.size == authoredIds.size) { "Missing original Android encoding comparison" }
        }
        expected.getValue("encodingProbes").jsonArray.forEach { element ->
            val probe = element.jsonObject
            val id = probe.getValue("id").jsonPrimitive.content
            val points = Base64.getDecoder().decode(probe.getValue("pointsBase64").jsonPrimitive.content)
            val original = File(root, "android/encoding/$id.original.pb.gz").readBytes()
            val identical = points.contentEquals(original)
            encoding += obj("id" to JsonPrimitive(id), "inputCount" to JsonPrimitive(probe.getValue("originalInputs").jsonArray.size),
                "gzipIdentical" to JsonPrimitive(identical),
                "desktopSha256" to JsonPrimitive(points.inputStream().use { it.sha256() }),
                "androidSha256" to JsonPrimitive(original.inputStream().use { it.sha256() }))
            if (!identical) issues += "$id independent encoder probe differs from Android's actual encoder"
        }
        val expectedEncodingCount = expected.getValue("authoredRows").jsonArray.size +
            expected.getValue("erases").jsonArray.size + expected.getValue("encodingProbes").jsonArray.size
        check(encoding.size == expectedEncodingCount) { "Missing authored row, mask or independent encoding probe" }
        ViveNotebook.open(android).use { notebook ->
            expected.getValue("pages").jsonArray.forEach { frame ->
                val id = frame.jsonObject.getValue("id").jsonPrimitive.content
                val geometry = Json.parseToJsonElement(File(root, "pages/$id/geometry.json").readText())
                val observed = Json.parseToJsonElement(File(root, "android/pages/$id/geometry.json").readText())
                val reloaded = Json.parseToJsonElement(pageGeometry(notebook.page(id)))
                listOf("Android" to observed, "re-export" to reloaded).forEach { (name, candidate) ->
                    val comparison = MatrixComparison.geometry(geometry, candidate)
                    integerValues += comparison.integers; floatValues += comparison.floats
                    maximumGap = maxOf(maximumGap, comparison.maximumGap)
                    issues += comparison.issues.map { "$id $name $it" }
                }
                // Triangle fallback is the Stage 7 reference for Normal-erase pieces; standard
                // Android path rendering omits those pieces at the pinned alpha06.
                val pixels = MatrixComparison.pixels(ImageIO.read(File(root, "android/pages/$id/softwareTriangles.png")),
                    ImageIO.read(File(root, "pages/$id/desktop.png")), false)
                metrics[id] = pixels.json()
                if (pixels.maximum > 2) issues += "$id software triangle RGB delta ${pixels.maximum} > 2"
            }
        }
        val observed = Json.parseToJsonElement(File(root, "android/observed.json").readText()).jsonObject
        val eraseCounts = expected.getValue("erases").jsonArray.groupingBy {
            it.jsonObject.getValue("mode").jsonPrimitive.content
        }.eachCount()
        check(listOf("unknownEncodingRejectedEmpty", "unknownEncodingRejectedOccupied").all {
            observed.getValue(it).jsonPrimitive.content == "true"
        }) {
            "Android must record atomic unknown-encoding rejection"
        }
        val report = obj("schemaVersion" to JsonPrimitive(1), "passed" to JsonPrimitive(issues.isEmpty()),
            "preservedStrokeRows" to JsonPrimitive(897), "authoredStrokeRows" to JsonPrimitive(55),
            "newObjectErases" to JsonPrimitive(eraseCounts["Object"] ?: 0),
            "newNormalErases" to JsonPrimitive(eraseCounts["Normal"] ?: 0), "renderedPages" to JsonPrimitive(metrics.size),
            "integerValues" to JsonPrimitive(integerValues), "floatValues" to JsonPrimitive(floatValues),
            "maximumGeometryGap" to JsonPrimitive(maximumGap), "issues" to JsonArray(issues.take(40).map(::JsonPrimitive)),
            "encoding" to JsonArray(encoding), "pixels" to JsonObject(metrics),
            "desktopRuntime" to Json.parseToJsonElement(File(root, "desktop-runtime.json").readText()),
            "verificationRuntime" to obj("os" to JsonPrimitive(System.getProperty("os.name")),
                "architecture" to JsonPrimitive(System.getProperty("os.arch")), "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
                "nativeSha256" to JsonPrimitive(InkNativeLibrary.load().sha256)),
            "androidObserved" to observed, "androidCapture" to capture)
        writeJson(File(root, "roundtrip-report.json"), report)
        check(issues.isEmpty()) { "Notebook round-trip differs: ${issues.take(8)}; see $root/roundtrip-report.json" }
        println("Round trip passed: 897 stroke rows preserved exactly; 55 authored rows; $eraseCounts erases; ${metrics.size} pages; geometry gap $maximumGap")
    }

    /** Binds a successful pinned Android run to the exact prepared inputs and captured inventory. */
    internal fun validateCapturedEvidence(root: File, expected: JsonObject): JsonObject {
        require(expected.getValue("schemaVersion").jsonPrimitive.content == "1") { "Unsupported round-trip expectations" }
        val inputs = linkedSetOf("expectations.json", "desktop.vive", "unknown-enc.vive")
        val artifacts = linkedSetOf("capture-environment.json", "observed.json", "reexport.vive",
            "reimport-reexport.vive", "unknown-android.vive")
        val pageIds = HashSet<String>()
        expected.getValue("pages").jsonArray.forEach { element ->
            val page = element.jsonObject
            val id = page.getValue("id").jsonPrimitive.content
            require(id.matches(Regex("[A-Za-z0-9_-]+")) && pageIds.add(id)) { "Invalid or duplicate captured page id: $id" }
            val geometry = page.getValue("geometry").jsonPrimitive.content
            require(geometry == "pages/$id/geometry.json") { "Unexpected round-trip geometry path: $geometry" }
            inputs += geometry
            artifacts += geometry
            listOf("hardware", "software", "softwareTriangles").forEach { mode -> artifacts += "pages/$id/$mode.png" }
        }
        val encodingIds = HashSet<String>()
        listOf("authoredRows", "erases", "encodingProbes").forEach { key ->
            expected.getValue(key).jsonArray.forEach { element ->
                val id = element.jsonObject.getValue("id").jsonPrimitive.content
                require(id.matches(Regex("[A-Za-z0-9_-]+")) && encodingIds.add(id)) { "Invalid or duplicate captured encoding id: $id" }
                listOf("desktop", "original", "reencoded").forEach { kind ->
                    artifacts += "encoding/$id.$kind.pb"
                    artifacts += "encoding/$id.$kind.pb.gz"
                }
            }
        }
        val android = File(root, "android")
        val capture = Json.parseToJsonElement(evidenceFile(android, "capture-environment.json").readText()).jsonObject
        require(capture.getValue("instrumentation_passed").jsonPrimitive.booleanOrNull == true) {
            "Android instrumentation did not pass; captured evidence cannot certify this round trip"
        }
        require(capture.getValue("android_app_commit").jsonPrimitive.content == "af05908e57f69593e32107b97bcdf19250637fa3" &&
            capture.getValue("androidx_ink").jsonPrimitive.content == "1.1.0-alpha06") { "Android capture versions differ from the pinned app and Ink" }
        val environment = capture.getValue("environment").jsonObject
        require(environment.getValue("ro.build.version.sdk").jsonPrimitive.content == "36" &&
            environment.getValue("ro.product.cpu.abi").jsonPrimitive.content == "x86_64" &&
            environment.getValue("graphics").jsonPrimitive.content.contains("SwiftShader", ignoreCase = true)) {
            "Android capture must use the pinned API 36 x86_64 SwiftShader reference"
        }
        val hashes = capture.getValue("roundtrip_inputs_sha256").jsonObject
        require(hashes.keys == inputs) { "Captured round-trip input inventory differs from the current expectations" }
        hashes.forEach { (path, value) ->
            val hash = value.jsonPrimitive.content
            require(hash.matches(Regex("[0-9a-f]{64}")) && MatrixComparison.sha256(evidenceFile(root, path)) == hash) {
                "Captured round-trip input checksum differs: $path"
            }
        }
        val manifest = evidenceFile(android, "manifest.sha256")
        val entries = manifest.readLines().filter(String::isNotBlank).map { line ->
            require(line.matches(Regex("[0-9a-f]{64}  .+"))) { "Malformed captured artifact checksum line" }
            line.substring(66) to line.substring(0, 64)
        }
        require(entries.map { it.first }.toSet().size == entries.size) { "Duplicate captured artifact manifest paths" }
        require(entries.map { it.first }.toSet() == artifacts) { "Captured artifact manifest inventory differs from the expected round trip" }
        val directory = android.toPath()
        val actual = Files.walk(directory).use { paths ->
            paths.filter { it != directory && !Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
                .map { directory.relativize(it).toString().replace(File.separatorChar, '/') }.toList().toSet()
        }
        require(actual == artifacts + "manifest.sha256") { "Captured directory contains missing or unlisted artifacts" }
        entries.forEach { (path, hash) ->
            require(MatrixComparison.sha256(evidenceFile(android, path)) == hash) { "Captured artifact checksum differs: $path" }
        }
        return capture
    }

    private fun evidenceFile(root: File, path: String): File {
        require(!path.startsWith('/') && '\\' !in path && ':' !in path && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) {
            "Unsafe round-trip evidence path: $path"
        }
        val file = File(root, path)
        require(file.canonicalFile.toPath().startsWith(root.canonicalFile.toPath()) &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Missing or unsafe round-trip evidence file: $path" }
        return file
    }

    /** Compare raw SQLite storage classes and values, including exact floating point bits/BLOBs. */
    internal fun snapshot(archive: File): JsonObject = withDatabase(archive) { db ->
        JsonObject(inkTables.associateWith { table ->
            val columns = db.createStatement().use { sql -> sql.executeQuery("PRAGMA table_info($table)").use { rows ->
                buildList { while (rows.next()) add(rows.getString("name")) }
            } }
            val records = db.createStatement().use { sql ->
                sql.executeQuery("SELECT " + columns.joinToString { "typeof(`$it`), `$it`" } + " FROM `$table`").use { rows ->
                    buildList {
                        while (rows.next()) add(JsonArray(columns.indices.map { column ->
                            val type = rows.getString(column * 2 + 1)
                            val index = column * 2 + 2
                            val value = when (type) {
                                "null" -> JsonNull
                                "integer" -> JsonPrimitive(rows.getLong(index).toString())
                                "real" -> JsonPrimitive(java.lang.Double.toHexString(rows.getDouble(index)))
                                "blob" -> JsonPrimitive(Base64.getEncoder().encodeToString(rows.getBytes(index)))
                                "text" -> JsonPrimitive(rows.getString(index))
                                else -> error("Unknown SQLite storage class $type")
                            }
                            obj("type" to JsonPrimitive(type), "value" to value)
                        }))
                    }
                }
            }.sortedBy { it.toString() }
            obj("columns" to JsonArray(columns.map(::JsonPrimitive)), "rows" to JsonArray(records))
        })
    }

    internal fun assertSameRows(before: File, after: File) {
        val first = snapshot(before)
        val second = snapshot(after)
        inkTables.forEach { table -> check(first[table] == second[table]) { "Stored SQLite rows differ: $table ($before -> $after)" } }
    }

    internal fun assertOriginalRowsPreserved(before: File, after: File) {
        val first = snapshot(before)
        val second = snapshot(after)
        inkTables.forEach { table ->
            val a = first.getValue(table).jsonObject
            val b = second.getValue(table).jsonObject
            check(a["columns"] == b["columns"]) { "SQLite columns changed: $table" }
            check(b.getValue("rows").jsonArray.containsAll(a.getValue("rows").jsonArray)) { "Original SQLite rows changed: $table" }
        }
    }

    private fun archiveAttachments(file: File): Map<String, String> = verifiedArchive(file) { zip, _ ->
        zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("attachments/") }.associate {
            it.name to zip.getInputStream(it).use { bytes -> bytes.sha256() }
        }
    }

    private fun <T> withDatabase(archive: File, read: (Connection) -> T): T {
        val database = Files.createTempFile("byteink-roundtrip-", ".sqlite").toFile()
        try {
            verifiedArchive(archive) { zip, _ -> zip.getInputStream(zip.getEntry("notebook.sqlite")).use { input ->
                database.outputStream().use(input::copyTo)
            } }
            return DriverManager.getConnection("jdbc:sqlite:${database.absolutePath}").use(read)
        } finally { database.delete() }
    }

    private fun strokeExpectation(row: StoredInkStroke, original: Stroke): JsonObject {
        val stroke = requireNotNull(ViveInkCodec.decode(row))
        val family = ByteArrayOutputStream().also { stroke.brush.family.encode(it) }.toByteArray()
        val protobuf = GZIPInputStream(family.inputStream()).use { it.readBytes() }
        return obj("id" to JsonPrimitive(row.id), "pageId" to JsonPrimitive(row.pageId), "seq" to JsonPrimitive(row.seq),
            "brushFamily" to JsonPrimitive(row.brushFamily), "brushVersion" to JsonPrimitive(row.brushVersion),
            "sizeDp" to JsonPrimitive(row.sizeDp), "colorArgb" to JsonPrimitive(row.colorArgb),
            "colorFollowsTheme" to (row.colorFollowsTheme?.let(::JsonPrimitive) ?: JsonNull),
            "epsilon" to JsonPrimitive(row.epsilon), "stabilization" to JsonPrimitive(row.stabilization),
            "bounds" to JsonArray(listOf(row.minX, row.minY, row.maxX, row.maxY).map(::JsonPrimitive)),
            "pointsSha256" to JsonPrimitive(row.points.inputStream().use { it.sha256() }),
            "familyPbBase64" to JsonPrimitive(Base64.getEncoder().encodeToString(protobuf)), "inputs" to inputs(stroke),
            "originalInputs" to inputs(original), "noiseSeed" to JsonPrimitive(original.inputs.getNoiseSeed()))
    }

    private fun eraseExpectation(row: StoredInkErase, original: Stroke): JsonObject = obj("id" to JsonPrimitive(row.id),
        "pageId" to JsonPrimitive(row.pageId), "mode" to JsonPrimitive(row.mode), "sizeDp" to JsonPrimitive(row.sizeDp),
        "enc" to JsonPrimitive(row.enc), "createdAt" to JsonPrimitive(row.createdAt), "deletedAt" to JsonNull,
        "targetIds" to JsonArray(row.targetIds.map(::JsonPrimitive)),
        "pointsSha256" to JsonPrimitive(row.points.inputStream().use { it.sha256() }),
        "inputs" to inputs(requireNotNull(ViveInkCodec.decodeErase(row))),
        "originalInputs" to inputs(original), "noiseSeed" to JsonPrimitive(original.inputs.getNoiseSeed()))

    private fun inputs(stroke: Stroke): JsonArray = inputs(stroke.inputs)

    private fun inputs(batch: StrokeInputBatch): JsonArray = JsonArray((0 until batch.size).map { index ->
        val input = batch[index]
        obj("tool" to JsonPrimitive(input.toolType.toString()), "elapsedTimeMillis" to JsonPrimitive(input.elapsedTimeMillis),
            "x" to JsonPrimitive(input.x), "y" to JsonPrimitive(input.y), "strokeUnitLengthCm" to JsonPrimitive(input.strokeUnitLengthCm),
            "pressure" to JsonPrimitive(input.pressure), "tiltRadians" to JsonPrimitive(input.tiltRadians),
            "orientationRadians" to JsonPrimitive(input.orientationRadians))
    })

    /** Encoder-only probes exercise empty/single batches and long, irregular native delta streams. */
    internal fun encodingProbes(): JsonArray {
        val marker = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff173b83.toInt(), 8f)
        // The public serialization constructor keeps native inputs and a separately supplied mesh.
        // These probes never render or persist rows, so modelling 40,000 points adds no coverage.
        val emptyShape = Stroke(marker, MutableStrokeInputBatch()).shape
        fun encode(id: String, batch: MutableStrokeInputBatch, eventTimes: List<Long>? = null): JsonObject {
            val native = batch.toImmutable()
            val row = ViveInkCodec.encodeStroke(Stroke(marker, native, emptyShape), id, "encoding-probe", 0,
                ViveBrushes.MARKER, 0, false, 0L)
            // Native inputs store float seconds. At long durations, the millisecond getter may
            // round by one millisecond; retain the original clock passed across JNI for encoding.
            val original = inputs(native).mapIndexed { index, element ->
                if (eventTimes == null) element else JsonObject(element.jsonObject +
                    ("elapsedTimeMillis" to JsonPrimitive(eventTimes[index])))
            }
            return obj("id" to JsonPrimitive(id), "noiseSeed" to JsonPrimitive(native.getNoiseSeed()),
                "originalInputs" to JsonArray(original),
                "pointsBase64" to JsonPrimitive(Base64.getEncoder().encodeToString(row.points)))
        }
        fun large(size: Int, stylus: Boolean): Pair<MutableStrokeInputBatch, List<Long>> {
            val eventTimes = ArrayList<Long>(size)
            val batch = MutableStrokeInputBatch().apply {
                var random = 0x45d9f3b
                fun next(): Int { random = random * 1664525 + 1013904223; return random ushr 1 }
                var elapsed = 0L
                var x = 0f
                var y = 0f
                repeat(size) { index ->
                    eventTimes += elapsed
                    val a = next()
                    val b = next()
                    // Alternating blocks combine variable deltas with repeatable paths.
                    val repeated = !stylus && (index / 2048) % 3 == 1
                    if (repeated) {
                        x = (index % 256 - 128) / 8f
                        y = (index % 37 - 18) / 16f
                    } else {
                        x += ((a and 511) - 255) / 32f
                        y += ((b and 511) - 255) / 64f
                    }
                    if (stylus) add(InputToolType.STYLUS, x, y, elapsed, strokeUnitLengthCm = 0.03125f,
                        pressure = ((a ushr 12) and 1023) / 1024f,
                        tiltRadians = ((b ushr 12) and 511) / 512f,
                        orientationRadians = ((a ushr 21) and 511) / 128f)
                    else add(InputToolType.MOUSE, x, y, elapsed, strokeUnitLengthCm = 0.015625f)
                    elapsed += (1 + (a ushr 9) % 7) * 125L
                }
                setNoiseSeed(if (stylus) 0x12345678 else 0x76543210)
            }
            return batch to eventTimes
        }
        val stylus = large(8192, true)
        val mouse = large(40000, false)
        return JsonArray(listOf(
            encode("encoding-empty", MutableStrokeInputBatch()),
            encode("encoding-single-noise", MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, -1024.125f, 4096.0625f, 0L, strokeUnitLengthCm = 0.03125f,
                    pressure = 0.375f, tiltRadians = 0.625f, orientationRadians = 2.875f)
                setNoiseSeed(0x31415926)
            }),
            encode("encoding-stylus-8192", stylus.first, stylus.second),
            encode("encoding-mouse-40000", mouse.first, mouse.second),
        ))
    }

    private fun author(brush: Brush, index: Int): Stroke {
        val tool = if (index % 2 == 0) InputToolType.STYLUS else InputToolType.MOUSE
        val samples = (0..24).map { step -> InkPointerSample(28f + step * 7f,
            400f + when (step % 8) { 0 -> 0f; 1 -> -8f; 2 -> -12f; 3 -> -8f; 4 -> 0f; 5 -> 8f; 6 -> 12f; else -> 8f },
            10000L + step * 16L, tool, if (tool == InputToolType.STYLUS) (step % 7 + 2) / 10f else null) }
        return authorSamples(brush, samples)
    }

    private fun authorSamples(brush: Brush, samples: List<InkPointerSample>): Stroke = InkAuthoringController().use { controller ->
        controller.begin(brush, samples.first())
        samples.drop(1).dropLast(1).forEach { check(controller.append(it)); controller.advance(it.uptimeMillis) }
        requireNotNull(controller.finish(samples.last()))
    }

    private fun raw(page: NotebookPage): List<PageStroke> = page.strokes.filter { it.deletedAt == null }
        .sortedWith(compareBy(StoredInkStroke::seq, StoredInkStroke::id)).mapNotNull(ViveInkPage::decode)

    private fun pageGeometry(page: NotebookPage): String = geometryJson(raw(page).map { it.oracle() },
        ViveInkPage.load(page.strokes, page.erases, page.moves).strokes.map { it.oracle() })

    private fun render(page: NotebookPage, renderer: InkPathRenderer, file: File) {
        val projections = ViveInkPage.load(page.strokes, page.erases, page.moves).strokes
        Surface.makeRasterN32Premul(480, 960).use { surface ->
            surface.canvas.clear(0xffffffff.toInt())
            val canvas = surface.canvas.asComposeCanvas()
            projections.forEach { stroke ->
                renderer.draw(canvas, stroke.stroke, ImmutableAffineTransform(stroke.scaleX, 0f, 16f + stroke.offsetX,
                    0f, stroke.scaleY, 16f + stroke.offsetY), colorArgb = automaticColorOr(
                        stroke.stroke.brush.colorIntArgb, stroke.colorFollowsTheme, themeArgb))
            }
            surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { file.writeBytes(it.bytes) } }
        }
    }

    private fun PageStroke.oracle(): OracleProjection = OracleProjection(id, stroke, offsetX, offsetY, scaleX, scaleY, colorFollowsTheme)
    private fun uuid(key: String): String = UUID.nameUUIDFromBytes("byteink-roundtrip-v1:$key".toByteArray(Charsets.UTF_8)).toString()
    private fun obj(vararg fields: Pair<String, JsonElement>): JsonObject = JsonObject(linkedMapOf(*fields))
    private fun writeJson(file: File, value: JsonElement) { file.writeText(json.encodeToString(JsonElement.serializer(), value) + "\n") }
}
