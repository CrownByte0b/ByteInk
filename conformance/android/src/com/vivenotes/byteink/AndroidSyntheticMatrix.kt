package com.vivenotes.byteink

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.EGL14
import android.opengl.GLES20
import android.os.Build
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.byteink.oracle.MatrixCase
import com.vivenotes.byteink.oracle.OracleProjection
import com.vivenotes.byteink.oracle.SyntheticFidelityMatrix
import com.vivenotes.byteink.oracle.geometryJson
import com.vivenotes.data.AttachmentStore
import com.vivenotes.data.EraserMode
import com.vivenotes.data.NotebookTransferManager
import com.vivenotes.data.db.InkEraseTargetEntity
import com.vivenotes.data.db.InkMoveTargetEntity
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.NotebookEntity
import com.vivenotes.data.db.NotesDatabase
import com.vivenotes.data.db.PageContentEntity
import com.vivenotes.data.db.PageEntity
import com.vivenotes.data.db.SectionEntity
import com.vivenotes.ink.CanvasInkPainter
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkLassoResize
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageStroke
import com.vivenotes.model.JsonDocumentCodec
import com.vivenotes.model.PageDoc
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/** Produces public, non-sensitive fixtures through the pinned Android application's code. */
internal fun generateAndroidSyntheticMatrix(
    hardwareBitmap: (Int, Int, (Canvas) -> Unit) -> Bitmap,
    drawPage: (Canvas, CanvasStrokeRenderer, CanvasInkPainter, List<PageStroke>, Float, Float, Float) -> Unit,
    replay: (SQLiteDatabase, String, List<PageStroke>) -> List<PageStroke>,
) = runBlocking {
    require(Build.VERSION.SDK_INT == 36 && "x86_64" in Build.SUPPORTED_ABIS) {
        "Certified synthetic references require API 36 x86_64"
    }
    val graphics = graphicsIdentity()
    require(graphics.values.any { it.contains("swiftshader", ignoreCase = true) }) {
        "Certified synthetic references require SwiftShader: $graphics"
    }
    val context = InstrumentationRegistry.getInstrumentation().targetContext
    val root = File(context.filesDir, "byteink-oracle/android-matrix")
    root.deleteRecursively()
    root.mkdirs()
    val scratch = File(context.cacheDir, "byteink-matrix-source").apply { deleteRecursively(); mkdirs() }
    val db = Room.databaseBuilder(context, NotesDatabase::class.java, File(scratch, "source.db").absolutePath)
        .addCallback(NotesDatabase.SYNC_TRIGGER_CALLBACK).build()
    try {
        val notebookId = SyntheticFidelityMatrix.uuid("notebook")
        val sectionId = SyntheticFidelityMatrix.uuid("section")
        db.notebookDao().insert(NotebookEntity(notebookId, "ByteInk synthetic fidelity matrix", 0xff173b83.toInt(),
            0, createdAt = 1000L, updatedAt = 1000L))
        db.sectionDao().insert(SectionEntity(sectionId, notebookId, "Generated brush and operation cases",
            0xff173b83.toInt(), 0, 1000L, 1000L))
        val rows = linkedMapOf<String, List<InkStrokeEntity>>()
        SyntheticFidelityMatrix.cases.forEachIndexed { pageIndex, case ->
            db.pageDao().insert(PageEntity(case.pageId, sectionId, case.id, pageIndex,
                createdAt = 1000L, updatedAt = 1000L))
            db.pageContentDao().upsert(PageContentEntity(case.pageId,
                JsonDocumentCodec.encodeToString(PageDoc()), 1000L, JsonDocumentCodec.id))
            val pageRows = (0..2).map { variant -> syntheticRow(case, variant) }
            rows[case.id] = pageRows
            db.inkStrokeDao().insert(pageRows)
            if (case.scenario == "operations") addOperations(db, case, pageRows)
        }
        val transfers = NotebookTransferManager(context, db, AttachmentStore(context, db),
            transferRoot = File(scratch, "transfers"), clock = { 1000L })
        File(root, "synthetic.vive").outputStream().use { transfers.exportNotebook(notebookId, it) }
        // Render the persisted export, so the operation reference crosses the same boundary as desktop.
        val exportedDatabase = File(scratch, "exported.sqlite")
        ZipFile(File(root, "synthetic.vive")).use { zip ->
            zip.getInputStream(zip.getEntry("notebook.sqlite")).use { input ->
                exportedDatabase.outputStream().use(input::copyTo)
            }
        }
        File(root, "cases.tsv").writeText(SyntheticFidelityMatrix.header + "\n" +
            SyntheticFidelityMatrix.cases.joinToString("\n", postfix = "\n", transform = MatrixCase::toTsv))
        SQLiteDatabase.openDatabase(exportedDatabase.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
            SyntheticFidelityMatrix.cases.forEachIndexed { index, case ->
                val pageRows = rows.getValue(case.id)
                val raw = pageRows.map { row ->
                    PageStroke(row.id, requireNotNull(InkCodec.decode(row)), brushFamily = row.brushFamily,
                        stabilization = row.stabilization, colorFollowsTheme = row.colorFollowsTheme)
                }
                val replayed = replay(database, case.pageId, raw)
                require(replayed.isNotEmpty()) { "An operation case must retain visible ink: ${case.id}" }
                if (case.scenario == "operations") {
                    require(replayed.none { it.id == pageRows[0].id }) { "Object erasure did not take its target" }
                    val cutProbe = ImmutableBox.fromTwoPoints(ImmutableVec(102f, 204f), ImmutableVec(106f, 284f))
                    val cutPieces = replayed.filter { it.id == pageRows[2].id }
                    require(raw[2].stroke.shape.computeCoverage(cutProbe) > 0f && cutPieces.isNotEmpty() &&
                        cutPieces.all { it.stroke.shape.computeCoverage(cutProbe) == 0f }) {
                        "Normal erasure did not cut its target while preserving its remaining ink"
                    }
                    require(replayed.any { it.id == pageRows[1].id && it.offsetX != 0f && it.scaleX != 1f }) {
                        "Move and resize did not apply to their target"
                    }
                }
                val directory = File(root, "cases/${case.id}").apply { mkdirs() }
                val family = encoded { raw.first().stroke.brush.family.encode(it) }
                File(directory, "family.pb.gz").writeBytes(family)
                File(directory, "family.pb").writeBytes(GZIPInputStream(family.inputStream()).use { it.readBytes() })
                pageRows.zip(raw).forEach { (row, projection) ->
                    File(directory, "${row.id}.inputs.pb.gz").writeBytes(row.points)
                    File(directory, "${row.id}.roundtrip.pb.gz").writeBytes(encoded { projection.stroke.inputs.encode(it) })
                }
                File(directory, "geometry.json").writeText(geometryJson(raw.map(PageStroke::oracle), replayed.map(PageStroke::oracle)))
                listOf("hardware", "path", "software", "softwareTriangles").forEach { mode ->
                    val renderer = CanvasStrokeRenderer.create(forcePathRendering = mode != "hardware")
                    val painter = CanvasInkPainter(case.themeArgb)
                    val draw: (Canvas) -> Unit = { canvas ->
                        if (mode == "softwareTriangles") {
                            drawAndroidTriangleReference(canvas, painter, replayed, case.scale, case.left, case.top)
                        } else drawPage(canvas, renderer, painter, replayed, case.scale, case.left, case.top)
                    }
                    val bitmap = if (mode == "software" || mode == "softwareTriangles") {
                        Bitmap.createBitmap(case.width, case.height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
                    } else hardwareBitmap(case.width, case.height) { canvas ->
                        require(canvas.isHardwareAccelerated)
                        draw(canvas)
                    }
                    try {
                        File(root, "images/$mode/${case.id}.png").apply { parentFile?.mkdirs() }.outputStream().use {
                            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                    } finally { bitmap.recycle() }
                }
                if (index % 20 == 0) println("Android matrix ${index + 1}/${SyntheticFidelityMatrix.cases.size}: ${case.id}")
            }
        }
        val provenance = JSONObject().apply {
            put("schemaVersion", 1)
            put("generator", "Android")
            put("androidxInk", "1.1.0-alpha06")
            put("appCommit", "af05908e57f69593e32107b97bcdf19250637fa3")
            put("api", Build.VERSION.SDK_INT)
            put("abi", "x86_64")
            put("buildFingerprint", Build.FINGERPRINT)
            put("glesVendor", graphics.getValue("vendor"))
            put("glesRenderer", graphics.getValue("renderer"))
            put("glesVersion", graphics.getValue("version"))
            put("caseCount", SyntheticFidelityMatrix.cases.size)
            put("rawStrokeCount", rows.values.sumOf { it.size })
            put("sizesDp", org.json.JSONArray(SyntheticFidelityMatrix.sizes))
            put("colors", "opaque,translucent,theme")
            put("operationCases", SyntheticFidelityMatrix.cases.count { it.scenario == "operations" })
            put("capturedAtEpochMillis", System.currentTimeMillis())
            put("coordinatePolicy", "shared deterministic rational samples; original app brush, codec and replay")
        }
        File(root, "provenance.json").writeText(provenance.toString(2) + "\n")
        val inventory = root.walkTopDown().filter(File::isFile).sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        File(root, "manifest.sha256").writeText(inventory.joinToString("\n", postfix = "\n") {
            "${it.sha256()}  ${it.relativeTo(root).invariantSeparatorsPath}"
        })
    } finally {
        db.close()
        scratch.deleteRecursively()
    }
}

private fun syntheticRow(case: MatrixCase, variant: Int): InkStrokeEntity {
    val inputs = SyntheticFidelityMatrix.inputs(SyntheticFidelityMatrix.tool(case.tool), variant)
    val id = SyntheticFidelityMatrix.strokeId(case, variant)
    // The public decoder is the app's authoritative family-id/stabilization mapping.
    val template = InkStrokeEntity(id = id, pageId = case.pageId, seq = variant, brushFamily = case.family,
        brushVersion = 1, sizeDp = SyntheticFidelityMatrix.sizes[variant], colorArgb = SyntheticFidelityMatrix.colors[variant],
        colorFollowsTheme = variant == 2, epsilon = 0.25f, stabilization = case.stabilization,
        minX = 0f, minY = 0f, maxX = 0f, maxY = 0f,
        points = encoded { inputs.encode(it) }, enc = InkCodec.ENCODING, createdAt = 1000L)
    val brush = requireNotNull(InkCodec.decode(template)).brush
    val stroke = Stroke(brush, inputs)
    val source = PageStroke(id, stroke, brushFamily = case.family, stabilization = case.stabilization,
        colorFollowsTheme = variant == 2)
    return InkCodec.encodeCopy(source, stroke, case.pageId, now = 1000L).copy(id = id, seq = variant)
}

private suspend fun addOperations(db: NotesDatabase, case: MatrixCase, rows: List<InkStrokeEntity>) {
    suspend fun erase(key: String, mode: EraserMode, y1: Float, y2: Float, size: Float, target: String, now: Long) {
        val mask = InkCodec.eraseMask(MutableStrokeInputBatch().apply {
            add(InputToolType.STYLUS, 104f, y1, 0L)
            add(InputToolType.STYLUS, 104f, y2, 80L)
        }.toImmutable(), size)
        val operation = InkCodec.encodeErase(mask, case.pageId, mode, now)
            .copy(id = SyntheticFidelityMatrix.uuid("${case.id}:$key"))
        db.inkEraseDao().insert(operation)
        db.inkEraseDao().insertTargets(listOf(InkEraseTargetEntity(operation.id, target)))
    }
    erase("normal", EraserMode.Normal, 200f, 288f, 12f, rows[2].id, 2000L)
    erase("object", EraserMode.Object, 4f, 84f, 220f, rows[0].id, 2100L)
    fun rectangle(y1: Float, y2: Float) = listOf(InkPoint(0f, y1), InkPoint(236f, y1), InkPoint(236f, y2), InkPoint(0f, y2))
    val move = InkCodec.encodeMove(rectangle(95f, 195f), case.pageId, 8f, 16f, 2200L)
        .copy(id = SyntheticFidelityMatrix.uuid("${case.id}:move"))
    db.inkMoveDao().insert(move)
    db.inkMoveDao().insertTargets(listOf(InkMoveTargetEntity(move.id, rows[1].id)))
    val resize = InkCodec.encodeResize(InkLassoResize(rectangle(105f, 215f), setOf(rows[1].id), emptySet(),
        InkPoint(20f, 116f), 0.8f, 1.1f), case.pageId, 2300L)
        .copy(id = SyntheticFidelityMatrix.uuid("${case.id}:resize"))
    db.inkMoveDao().insert(resize)
    db.inkMoveDao().insertTargets(listOf(InkMoveTargetEntity(resize.id, rows[1].id)))
}

private fun PageStroke.oracle() = OracleProjection(id, stroke, offsetX, offsetY, scaleX, scaleY, colorFollowsTheme)
private fun encoded(write: (ByteArrayOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().also(write).toByteArray()
private fun File.sha256(): String = inputStream().use { input ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(65536)
    while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
    digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
}

/** Queries the actual EGL backend, rather than accepting an emulator command-line label. */
private fun graphicsIdentity(): Map<String, String> {
    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    require(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0))
    var surface = EGL14.EGL_NO_SURFACE
    var context = EGL14.EGL_NO_CONTEXT
    try {
        val configurations = arrayOfNulls<android.opengl.EGLConfig>(1)
        val attributes = intArrayOf(EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
        require(EGL14.eglChooseConfig(display, attributes, 0, configurations, 0, 1, IntArray(1), 0))
        val configuration = requireNotNull(configurations[0])
        context = EGL14.eglCreateContext(display, configuration, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        surface = EGL14.eglCreatePbufferSurface(display, configuration,
            intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        require(EGL14.eglMakeCurrent(display, surface, surface, context))
        return mapOf("vendor" to (GLES20.glGetString(GLES20.GL_VENDOR) ?: "unknown"),
            "renderer" to (GLES20.glGetString(GLES20.GL_RENDERER) ?: "unknown"),
            "version" to (GLES20.glGetString(GLES20.GL_VERSION) ?: "unknown"))
    } finally {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
    }
}
