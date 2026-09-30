package com.vivenotes.byteink

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.util.Base64
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.byteink.oracle.OracleProjection
import com.vivenotes.byteink.oracle.geometryJson
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.EraserMode
import com.vivenotes.data.NotebookTransferException
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.ink.CanvasInkPainter
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.PageStroke
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Exercises the application's real transfer boundary, including validation and Room installation. */
internal fun verifyAndroidNotebookRoundTrip(
    hardwareBitmap: (Int, Int, (Canvas) -> Unit) -> Bitmap,
    drawPage: (Canvas, CanvasStrokeRenderer, CanvasInkPainter, List<PageStroke>, Float, Float, Float) -> Unit,
    replay: (SQLiteDatabase, String, List<PageStroke>) -> List<PageStroke>,
) = runBlocking {
    require(Build.VERSION.SDK_INT == 36 && "x86_64" in Build.SUPPORTED_ABIS) {
        "Notebook acceptance requires the pinned API 36 x86_64 reference"
    }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.filesDir, "byteink-oracle")
    val expected = JSONObject(File(root, "expectations.json").readText())
    require(expected.getInt("schemaVersion") == 1)
    val input = File(root, "desktop.vive")
    val unknownInput = File(root, "unknown-enc.vive")
    require(input.isFile && unknownInput.isFile)
    val output = File(root, "android").apply { deleteRecursively(); mkdirs() }
    val scratch = File(context.cacheDir, "byteink-roundtrip").apply { deleteRecursively(); mkdirs() }
    val database = Room.databaseBuilder(context, NotesDatabase::class.java, File(scratch, "notes.db").path)
        .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK).build()
    val transfers = NotebookTransferManager(context, database, AttachmentStore(context, database),
        transferRoot = File(scratch, "transfers"), clock = { 1000L })
    try {
        // Initializing Room is outside the atomicity assertion: a failed import must write no rows.
        database.openHelper.writableDatabase
        val emptyState = roundTripSnapshot { database.openHelper.readableDatabase.query(it) }
        val emptyRejection = runCatching { unknownInput.inputStream().use { transfers.importNotebook(it) } }
        require(emptyRejection.exceptionOrNull() is NotebookTransferException) {
            "The application's unsupported-encoding validation unexpectedly accepted the bundle"
        }
        require(emptyState == roundTripSnapshot { database.openHelper.readableDatabase.query(it) }) {
            "Rejected import changed an empty application's database"
        }

        val imported = input.inputStream().use { transfers.importNotebook(it) }
        require(imported.created && imported.notebookId == expected.getString("notebookId"))
        val occupiedState = roundTripSnapshot { database.openHelper.readableDatabase.query(it) }
        val occupiedRejection = runCatching { unknownInput.inputStream().use { transfers.importNotebook(it) } }
        require(occupiedRejection.exceptionOrNull() is NotebookTransferException)
        require(occupiedState == roundTripSnapshot { database.openHelper.readableDatabase.query(it) }) {
            "Rejected import changed an existing application's database"
        }

        val exported = File(output, "reexport.vive")
        exported.outputStream().use { transfers.exportNotebook(imported.notebookId, it) }
        val sourceSqlite = roundTripExtract(input, File(scratch, "source.sqlite"))
        val exportedSqlite = roundTripExtract(exported, File(scratch, "exported.sqlite"))
        var decodedCount = 0
        var tombstoneCount = 0
        val pageResults = JSONArray()
        val encodingResults = JSONArray()
        SQLiteDatabase.openDatabase(sourceSqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { source ->
            SQLiteDatabase.openDatabase(exportedSqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { persisted ->
                roundTripAssertInkPreserved(source, persisted)
                val rows = persisted.roundTripRows("SELECT * FROM ink_strokes ORDER BY seq, id", ::roundTripStroke)
                require(rows.size == expected.getInt("strokeCount"))
                val byId = rows.associateBy(InkStrokeEntity::id)
                val decoded = rows.associate { row ->
                    // Include tombstones: their payload must still decode after Room installation.
                    val stroke = requireNotNull(InkCodec.decode(row)) { "App codec failed for ${row.id}" }
                    decodedCount++
                    if (row.deletedAt != null) tombstoneCount++
                    row.id to stroke
                }
                expected.getJSONArray("authoredRows").roundTripObjects().forEach { wanted ->
                    val row = byId.getValue(wanted.getString("id"))
                    roundTripAssertAuthored(wanted, row, decoded.getValue(row.id))
                    encodingResults.put(roundTripCaptureEncoding(output, wanted, row.points, decoded.getValue(row.id).inputs))
                }
                expected.getJSONArray("erases").roundTripObjects().forEach { wanted ->
                    val row = persisted.roundTripRows("SELECT * FROM ink_erases WHERE id = ?",
                        arrayOf(wanted.getString("id")), ::roundTripErase).single()
                    val mask = requireNotNull(InkCodec.decodeErase(row))
                    require(row.pageId == wanted.getString("pageId") && row.mode.name == wanted.getString("mode"))
                    require(row.enc == wanted.getString("enc") && row.createdAt == wanted.getLong("createdAt"))
                    roundTripAssertJson(wanted.opt("deletedAt"), row.deletedAt, "erase.${row.id}.deletedAt")
                    roundTripAssertNumber(wanted.getDouble("sizeDp"), row.sizeDp.toDouble(), "erase.${row.id}.sizeDp")
                    require(roundTripSha256(row.points) == wanted.getString("pointsSha256"))
                    roundTripAssertInputs(wanted.getJSONArray("inputs"), mask, "erase.${row.id}")
                    encodingResults.put(roundTripCaptureEncoding(output, wanted, row.points, mask.inputs))
                    val targets = persisted.roundTripRows("SELECT strokeId FROM ink_erase_targets WHERE eraseId = ? ORDER BY strokeId",
                        arrayOf(row.id)) { it.getString(0) }
                    require(targets == wanted.getJSONArray("targetIds").roundTripStrings().sorted())
                }
                val preservation = expected.getJSONObject("preservation")
                val unknownBrush = byId.getValue(preservation.getString("unknownBrushId"))
                require(unknownBrush.brushFamily !in listOf("marker", "pressure-pen", "highlighter", "dashed-line") &&
                    !unknownBrush.brushFamily.startsWith("calligraphy-v1-p"))
                require(byId.getValue(preservation.getString("tombstoneId")).deletedAt != null)

                expected.getJSONArray("pages").roundTripObjects().forEach { page ->
                    val pageId = page.getString("id")
                    val raw = rows.filter { it.pageId == pageId && it.deletedAt == null }.map { row ->
                        PageStroke(row.id, decoded.getValue(row.id), brushFamily = row.brushFamily,
                            brushVersion = row.brushVersion, stabilization = row.stabilization,
                            colorFollowsTheme = row.colorFollowsTheme, groupId = row.groupId)
                    }
                    val replayed = replay(persisted, pageId, raw)
                    val actualGeometry = geometryJson(raw.map(::roundTripProjection), replayed.map(::roundTripProjection))
                    roundTripAssertJson(JSONObject(File(root, page.getString("geometry")).readText()),
                        JSONObject(actualGeometry), "page.$pageId")
                    val directory = File(output, "pages/$pageId").apply { mkdirs() }
                    File(directory, "geometry.json").writeText(actualGeometry)
                    val width = page.getInt("width")
                    val height = page.getInt("height")
                    val scale = page.getDouble("scale").toFloat()
                    val left = page.getDouble("left").toFloat()
                    val top = page.getDouble("top").toFloat()
                    val painter = CanvasInkPainter(page.getInt("themeArgb"))
                    listOf("hardware", "software", "softwareTriangles").forEach { mode ->
                        val renderer = CanvasStrokeRenderer.create(forcePathRendering = mode != "hardware")
                        val draw: (Canvas) -> Unit = { canvas ->
                            if (mode == "softwareTriangles") {
                                drawAndroidTriangleReference(canvas, painter, replayed, scale, left, top)
                            } else drawPage(canvas, renderer, painter, replayed, scale, left, top)
                        }
                        val bitmap = if (mode == "hardware") hardwareBitmap(width, height) { canvas ->
                            require(canvas.isHardwareAccelerated)
                            draw(canvas)
                        } else Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
                        try {
                            File(directory, "$mode.png").outputStream().use {
                                require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                            }
                        } finally { bitmap.recycle() }
                    }
                    pageResults.put(JSONObject().put("id", pageId).put("rawCount", raw.size)
                        .put("projectionCount", replayed.size))
                }
            }
        }

        // Reimport an already installed archive and prove all immutable ink survives the reset/upsert.
        val repeated = input.inputStream().use { transfers.importNotebook(it) }
        require(!repeated.created && !repeated.restored && repeated.notebookId == imported.notebookId)
        val repeatedExport = File(output, "reimport-reexport.vive")
        repeatedExport.outputStream().use { transfers.exportNotebook(imported.notebookId, it) }
        val repeatedSqlite = roundTripExtract(repeatedExport, File(scratch, "repeated.sqlite"))
        SQLiteDatabase.openDatabase(exportedSqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { first ->
            SQLiteDatabase.openDatabase(repeatedSqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { second ->
                roundTripAssertInkPreserved(first, second)
            }
        }

        // Android exports opaque rows without decoding them. Exercise this direction through the
        // real app DAO/exporter while preserving its strict inbound validation policy.
        val unknownSqlite = roundTripExtract(unknownInput, File(scratch, "unknown.sqlite"))
        val opaque = SQLiteDatabase.openDatabase(unknownSqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            db.roundTripRows("SELECT * FROM ink_strokes WHERE id = ?",
                arrayOf(expected.getJSONObject("preservation").getString("unknownEncodingId")), ::roundTripStroke).single()
        }
        require(opaque.enc != InkCodec.ENCODING && InkCodec.decode(opaque) == null)
        database.inkStrokeDao().insert(listOf(opaque))
        val unknownExport = File(output, "unknown-android.vive")
        unknownExport.outputStream().use { transfers.exportNotebook(imported.notebookId, it) }
        val unknownExportSqlite = roundTripExtract(unknownExport, File(scratch, "unknown-export.sqlite"))
        SQLiteDatabase.openDatabase(unknownSqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { before ->
            SQLiteDatabase.openDatabase(unknownExportSqlite.path, null, SQLiteDatabase.OPEN_READONLY).use { after ->
                roundTripAssertInkPreserved(before, after)
            }
        }
        expected.getJSONArray("encodingProbes").roundTripObjects().forEach { probe ->
            val stored = Base64.decode(probe.getString("pointsBase64"), Base64.DEFAULT)
            val decoded = stored.inputStream().use { StrokeInputBatch.decode(it) }
            require(decoded.size == probe.getJSONArray("originalInputs").length()) {
                "${probe.getString("id")} stress-probe input count changed"
            }
            require(decoded.getNoiseSeed() == probe.getInt("noiseSeed")) {
                "${probe.getString("id")} stress-probe noise seed changed"
            }
            encodingResults.put(roundTripCaptureEncoding(output, probe, stored, decoded))
        }
        File(output, "observed.json").writeText(JSONObject().put("schemaVersion", 1)
            .put("notebookId", imported.notebookId).put("decodedRows", decodedCount)
            .put("tombstoneRows", tombstoneCount).put("authoredRows", expected.getJSONArray("authoredRows").length())
            .put("authoredErases", expected.getJSONArray("erases").length()).put("pages", pageResults)
            .put("unknownEncodingRejectedEmpty", true).put("unknownEncodingRejectedOccupied", true)
            .put("reimportInkPreserved", true).put("unknownEncodingExportId", opaque.id)
            .put("unknownEncoding", opaque.enc).put("unknownEncodingPointsSha256", roundTripSha256(opaque.points))
            .put("encoding", encodingResults)
            .toString(2) + "\n")
        require(encodingResults.roundTripObjects().all { it.getBoolean("originalGzipEqual") && it.getBoolean("originalProtoEqual") }) {
            "Fresh desktop encodings differ from Android's actual encoder; see observed.json and encoding artifacts"
        }
    } finally {
        database.close()
        scratch.deleteRecursively()
    }
}

/** Capture original inputs separately: decode/encode can legitimately select a different grid. */
private fun roundTripCaptureEncoding(output: File, expected: JSONObject, stored: ByteArray, decoded: StrokeInputBatch): JSONObject {
    val id = expected.getString("id")
    require(id.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid encoding artifact id: $id" }
    val original = MutableStrokeInputBatch().apply {
        expected.getJSONArray("originalInputs").roundTripObjects().forEach { sample ->
            val tool = listOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH, InputToolType.STYLUS)
                .single { it.toString() == sample.getString("tool") }
            add(tool, sample.getDouble("x").toFloat(), sample.getDouble("y").toFloat(),
                sample.getLong("elapsedTimeMillis"), strokeUnitLengthCm = sample.getDouble("strokeUnitLengthCm").toFloat(),
                pressure = sample.getDouble("pressure").toFloat(), tiltRadians = sample.getDouble("tiltRadians").toFloat(),
                orientationRadians = sample.getDouble("orientationRadians").toFloat())
        }
        setNoiseSeed(expected.getInt("noiseSeed"))
    }
    val originalBytes = ByteArrayOutputStream().also { original.encode(it) }.toByteArray()
    val decodedBytes = ByteArrayOutputStream().also { decoded.encode(it) }.toByteArray()
    fun inflate(bytes: ByteArray) = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }
    val originalProto = inflate(originalBytes)
    val storedProto = inflate(stored)
    val decodedProto = inflate(decodedBytes)
    val directory = File(output, "encoding").apply { mkdirs() }
    listOf("desktop" to stored, "original" to originalBytes, "reencoded" to decodedBytes).forEach { (name, bytes) ->
        File(directory, "$id.$name.pb.gz").writeBytes(bytes)
        File(directory, "$id.$name.pb").writeBytes(inflate(bytes))
    }
    return JSONObject().put("id", id).put("originalGzipEqual", stored.contentEquals(originalBytes))
        .put("originalProtoEqual", storedProto.contentEquals(originalProto))
        .put("decodedGzipEqual", stored.contentEquals(decodedBytes))
        .put("decodedProtoEqual", storedProto.contentEquals(decodedProto))
        .put("desktopGzipSha256", roundTripSha256(stored)).put("androidOriginalGzipSha256", roundTripSha256(originalBytes))
        .put("desktopProtoSha256", roundTripSha256(storedProto)).put("androidOriginalProtoSha256", roundTripSha256(originalProto))
}

private fun roundTripAssertAuthored(expected: JSONObject, row: InkStrokeEntity, stroke: Stroke) {
    val path = "stroke.${row.id}"
    require(row.pageId == expected.getString("pageId") && row.seq == expected.getInt("seq"))
    require(row.brushFamily == expected.getString("brushFamily") && row.brushVersion == expected.getInt("brushVersion"))
    require(row.colorArgb == expected.getInt("colorArgb") && stroke.brush.colorIntArgb == row.colorArgb)
    require(row.stabilization == expected.getInt("stabilization"))
    roundTripAssertJson(expected.opt("colorFollowsTheme"), row.colorFollowsTheme, "$path.colorFollowsTheme")
    roundTripAssertNumber(expected.getDouble("sizeDp"), row.sizeDp.toDouble(), "$path.sizeDp")
    roundTripAssertNumber(row.sizeDp.toDouble(), stroke.brush.size.toDouble(), "$path.brushSize")
    roundTripAssertNumber(expected.getDouble("epsilon"), row.epsilon.toDouble(), "$path.epsilon")
    roundTripAssertNumber(row.epsilon.toDouble(), stroke.brush.epsilon.toDouble(), "$path.brushEpsilon")
    roundTripAssertJson(expected.getJSONArray("bounds"), JSONArray(listOf(row.minX, row.minY, row.maxX, row.maxY)), "$path.storedBounds")
    // Stored bounds predate input quantization; rebuilt bounds use the independently emitted
    // desktop page geometry below rather than treating stored bounds as an exact engine oracle.
    requireNotNull(stroke.shape.computeBoundingBox())
    require(roundTripSha256(row.points) == expected.getString("pointsSha256"))
    val compressed = ByteArrayOutputStream().also { stroke.brush.family.encode(it) }.toByteArray()
    val actualFamily = GZIPInputStream(compressed.inputStream()).use { it.readBytes() }
    require(actualFamily.contentEquals(Base64.decode(expected.getString("familyPbBase64"), Base64.DEFAULT))) {
        "$path brush-family protobuf differs from desktop"
    }
    roundTripAssertInputs(expected.getJSONArray("inputs"), stroke, path)
}

private fun roundTripAssertInputs(expected: JSONArray, stroke: Stroke, path: String) {
    require(expected.length() == stroke.inputs.size) { "$path input count differs" }
    expected.roundTripObjects().forEachIndexed { index, wanted ->
        val input = stroke.inputs[index]
        require(wanted.getString("tool") == input.toolType.toString())
        require(wanted.getLong("elapsedTimeMillis") == input.elapsedTimeMillis)
        listOf("x" to input.x, "y" to input.y, "strokeUnitLengthCm" to input.strokeUnitLengthCm,
            "pressure" to input.pressure, "tiltRadians" to input.tiltRadians,
            "orientationRadians" to input.orientationRadians).forEach { (name, actual) ->
            roundTripAssertNumber(wanted.getDouble(name), actual.toDouble(), "$path.input[$index].$name")
        }
    }
}

private fun roundTripProjection(stroke: PageStroke) = OracleProjection(stroke.id, stroke.stroke,
    stroke.offsetX, stroke.offsetY, stroke.scaleX, stroke.scaleY, stroke.colorFollowsTheme)

/** Recursive comparison also checks every outline, triangle, replay transform and coverage probe. */
private fun roundTripAssertJson(expected: Any?, actual: Any?, path: String) {
    val wanted = expected.takeUnless { it == JSONObject.NULL }
    val observed = actual.takeUnless { it == JSONObject.NULL }
    when {
        wanted is JSONObject && observed is JSONObject -> {
            val names = wanted.keys().asSequence().toSet()
            require(names == observed.keys().asSequence().toSet()) { "$path JSON keys differ" }
            names.forEach { roundTripAssertJson(wanted.get(it), observed.get(it), "$path.$it") }
        }
        wanted is JSONArray && observed is JSONArray -> {
            require(wanted.length() == observed.length()) { "$path array length differs" }
            repeat(wanted.length()) { roundTripAssertJson(wanted.get(it), observed.get(it), "$path[$it]") }
        }
        wanted is Number && observed is Number -> {
            if (wanted is Float || wanted is Double || observed is Float || observed is Double) {
                roundTripAssertNumber(wanted.toDouble(), observed.toDouble(), path)
            } else require(wanted.toLong() == observed.toLong()) { "$path integer differs: $wanted != $observed" }
        }
        else -> require(wanted == observed) { "$path differs: $wanted != $observed" }
    }
}

private fun roundTripAssertNumber(expected: Double, actual: Double, path: String) {
    require(expected.isFinite() && actual.isFinite() && abs(expected - actual) <= 1e-4 + 1e-5 * max(abs(expected), abs(actual))) {
        "$path differs: $expected != $actual"
    }
}

private val roundTripInkTables = listOf("ink_strokes", "ink_erases", "ink_erase_targets", "ink_moves", "ink_move_targets")

private fun roundTripAssertInkPreserved(before: SQLiteDatabase, after: SQLiteDatabase) {
    val first = roundTripSnapshot(roundTripInkTables) { before.rawQuery(it, null) }
    val second = roundTripSnapshot(roundTripInkTables) { after.rawQuery(it, null) }
    roundTripInkTables.forEach { table ->
        require(first.getValue(table) == second.getValue(table)) { "$table stored column values or blobs changed in Android transfer" }
    }
}

private data class RoundTripTable(val columns: List<String>, val rows: List<List<String>>)

/** Uses SQLite value types and exact double bits; BLOBs are compared byte-for-byte, never decoded. */
private fun roundTripSnapshot(tables: List<String>? = null, query: (String) -> Cursor): Map<String, RoundTripTable> {
    val names = tables ?: query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
        .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    return names.associateWith { table ->
        require(table.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        query("SELECT * FROM \"$table\"").use { cursor ->
            val columns = cursor.columnNames.toList()
            val rows = buildList {
                while (cursor.moveToNext()) add(columns.indices.map { index ->
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> "null"
                        Cursor.FIELD_TYPE_INTEGER -> "integer:${cursor.getLong(index)}"
                        Cursor.FIELD_TYPE_FLOAT -> "real:${cursor.getDouble(index).toRawBits()}"
                        Cursor.FIELD_TYPE_STRING -> "text:${cursor.getString(index)}"
                        Cursor.FIELD_TYPE_BLOB -> "blob:${Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP)}"
                        else -> error("Unknown SQLite value type")
                    }
                })
            }.sortedWith { a, b ->
                var result = 0
                for (index in a.indices) {
                    result = a[index].compareTo(b[index])
                    if (result != 0) break
                }
                result
            }
            RoundTripTable(columns, rows)
        }
    }
}

private fun roundTripExtract(bundle: File, destination: File): File {
    ZipFile(bundle).use { zip ->
        zip.getInputStream(requireNotNull(zip.getEntry("notebook.sqlite"))).use { input ->
            destination.outputStream().use(input::copyTo)
        }
    }
    return destination
}

private fun roundTripSha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
    .joinToString("") { "%02x".format(it) }

private fun JSONArray.roundTripObjects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
private fun JSONArray.roundTripStrings(): List<String> = (0 until length()).map(::getString)
private fun <T> SQLiteDatabase.roundTripRows(sql: String, read: (Cursor) -> T): List<T> = roundTripRows(sql, null, read)
private fun <T> SQLiteDatabase.roundTripRows(sql: String, args: Array<String>?, read: (Cursor) -> T): List<T> =
    rawQuery(sql, args).use { cursor -> buildList { while (cursor.moveToNext()) add(read(cursor)) } }
private fun Cursor.roundTripString(name: String): String = getString(getColumnIndexOrThrow(name))
private fun Cursor.roundTripInt(name: String): Int = getInt(getColumnIndexOrThrow(name))
private fun Cursor.roundTripFloat(name: String): Float = getFloat(getColumnIndexOrThrow(name))
private fun Cursor.roundTripLong(name: String): Long = getLong(getColumnIndexOrThrow(name))
private fun Cursor.roundTripNullableLong(name: String): Long? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getLong(it) }
private fun Cursor.roundTripNullableString(name: String): String? = getString(getColumnIndexOrThrow(name))
private fun Cursor.roundTripBlob(name: String): ByteArray = getBlob(getColumnIndexOrThrow(name))
private fun roundTripStroke(row: Cursor) = InkStrokeEntity(
    id = row.roundTripString("id"), pageId = row.roundTripString("pageId"), seq = row.roundTripInt("seq"),
    brushFamily = row.roundTripString("brushFamily"), brushVersion = row.roundTripInt("brushVersion"),
    sizeDp = row.roundTripFloat("sizeDp"), colorArgb = row.roundTripInt("colorArgb"),
    colorFollowsTheme = row.getColumnIndexOrThrow("colorFollowsTheme").let { if (row.isNull(it)) null else row.getInt(it) != 0 },
    epsilon = row.roundTripFloat("epsilon"), stabilization = row.roundTripInt("stabilization"),
    minX = row.roundTripFloat("minX"), minY = row.roundTripFloat("minY"), maxX = row.roundTripFloat("maxX"), maxY = row.roundTripFloat("maxY"),
    points = row.roundTripBlob("points"), enc = row.roundTripString("enc"), createdAt = row.roundTripLong("createdAt"),
    groupId = row.roundTripNullableString("groupId"), deletedAt = row.roundTripNullableLong("deletedAt"),
)
private fun roundTripErase(row: Cursor) = InkEraseEntity(row.roundTripString("id"), row.roundTripString("pageId"),
    EraserMode.valueOf(row.roundTripString("mode")), row.roundTripFloat("sizeDp"), row.roundTripBlob("points"),
    row.roundTripString("enc"), row.roundTripLong("createdAt"), row.roundTripNullableLong("deletedAt"))
