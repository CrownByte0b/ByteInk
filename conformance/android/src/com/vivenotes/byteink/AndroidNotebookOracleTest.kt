package com.vivenotes.byteink

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.HardwareRenderer
import android.graphics.Matrix
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Build
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vivenotes.data.EraserMode
import com.vivenotes.data.db.InkStrokeEntity
import com.vivenotes.data.db.InkEraseEntity
import com.vivenotes.data.db.InkMoveEntity
import com.vivenotes.ink.CanvasInkPainter
import com.vivenotes.ink.InkCodec
import com.vivenotes.ink.InkPoint
import com.vivenotes.ink.PageStroke
import com.vivenotes.ink.eraseObjects
import com.vivenotes.ink.replayMove
import com.vivenotes.ink.replayResize
import com.vivenotes.ink.subtract
import com.vivenotes.byteink.oracle.writeStrokeDiagnostics
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Reference images and geometry from the Android app's actual codec, brushes and replay functions. */
@RunWith(AndroidJUnit4::class)
class AndroidNotebookOracleTest {
    @Test
    fun diagnoseStrokes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "byteink-oracle")
        File(root, "diagnostics.tsv").readLines().forEachIndexed { i, line ->
            val fields = line.split('\t')
            SQLiteDatabase.openDatabase(File(root, fields[0]).path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                val row = database.rows("SELECT * FROM ink_strokes WHERE id = ?", fields[1], ::strokeRow).single()
                val stroke = requireNotNull(InkCodec.decode(row))
                writeStrokeDiagnostics(stroke, File(root, "android/diagnostics/stroke-$i"))
            }
        }
    }

    @Test
    fun rebuildAndRender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.filesDir, "byteink-oracle")
        val output = File(root, "android").apply { mkdirs() }
        File(output, "device.txt").writeText("api=${Build.VERSION.SDK_INT}\nabis=${Build.SUPPORTED_ABIS.joinToString()}\nrenderer=HardwareRenderer + CanvasStrokeRenderer\n")
        File(output, "bounds.tsv").bufferedWriter().use { bounds ->
            File(output, "projections.tsv").bufferedWriter().use { projections ->
                File(root, "pages.tsv").readLines().forEach { line ->
                    val fields = line.split('\t')
                    val case = fields[0]
                    val pageId = fields[2]
                    val width = fields[3].toInt()
                    val height = fields[4].toInt()
                    val scale = fields[5].toFloat()
                    val left = fields[6].toFloat()
                    val top = fields[7].toFloat()
                    SQLiteDatabase.openDatabase(File(root, fields[1]).path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                        val strokes = database.rows("SELECT * FROM ink_strokes WHERE pageId = ? AND deletedAt IS NULL ORDER BY seq, id", pageId) { row ->
                            strokeRow(row)
                        }.mapNotNull { row ->
                            InkCodec.decode(row)?.let { stroke ->
                                val box = stroke.shape.computeBoundingBox()
                                bounds.append(listOf(case, row.id, box?.xMin, box?.yMin, box?.xMax, box?.yMax).joinToString("\t")).append('\n')
                                PageStroke(row.id, stroke, brushFamily = row.brushFamily, brushVersion = row.brushVersion,
                                    stabilization = row.stabilization, colorFollowsTheme = row.colorFollowsTheme, groupId = row.groupId)
                            }
                        }
                        val replayed = replay(database, pageId, strokes)
                        replayed.forEachIndexed { i, stroke ->
                            val box = stroke.pageBounds
                            projections.append(listOf(case, i, stroke.id, stroke.offsetX, stroke.offsetY, stroke.scaleX, stroke.scaleY,
                                box?.left, box?.top, box?.right, box?.bottom).joinToString("\t")).append('\n')
                        }
                        val painter = CanvasInkPainter(Color.BLACK)
                        val renderers = listOf(
                            "" to CanvasStrokeRenderer.create(),
                            "path/" to CanvasStrokeRenderer.create(forcePathRendering = true),
                            "software/" to CanvasStrokeRenderer.create(forcePathRendering = true),
                        )
                        renderers.forEach { (prefix, renderer) ->
                            val draw: (Canvas) -> Unit = { canvas ->
                                drawPage(canvas, renderer, painter, replayed, scale, left, top)
                            }
                            val bitmap = if (prefix == "software/") {
                                Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { draw(Canvas(it)) }
                            } else hardwareBitmap(width, height) { canvas ->
                                assertTrue("hardware reference must use a hardware canvas", canvas.isHardwareAccelerated)
                                draw(canvas)
                            }
                            val png = File(output, "$prefix$case.png").apply { parentFile?.mkdirs() }
                            try { png.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                            finally { bitmap.recycle() }
                        }
                    }
                }
            }
        }
    }

    private fun drawPage(canvas: Canvas, renderer: CanvasStrokeRenderer, painter: CanvasInkPainter,
        strokes: List<PageStroke>, scale: Float, left: Float, top: Float) {
        canvas.drawColor(Color.WHITE)
        strokes.forEach { projection ->
            val transform = Matrix().apply { setValues(floatArrayOf(
                scale * projection.scaleX, 0f, 16f + scale * (projection.offsetX - left),
                0f, scale * projection.scaleY, 16f + scale * (projection.offsetY - top),
                0f, 0f, 1f,
            )) }
            canvas.save()
            try {
                canvas.concat(transform)
                renderer.draw(canvas, painter.paint(projection), transform)
            } finally { canvas.restore() }
        }
    }

    /** Mirrors InkPageLoader's ordered fold, calling its original operations rather than reimplementing geometry. */
    private fun replay(database: SQLiteDatabase, pageId: String, strokes: List<PageStroke>): List<PageStroke> {
        val erases = database.rows("SELECT * FROM ink_erases WHERE pageId = ? AND deletedAt IS NULL", pageId) { row ->
            InkEraseEntity(row.string("id"), row.string("pageId"), EraserMode.valueOf(row.string("mode")),
                row.float("sizeDp"), row.blob("points"), row.string("enc"), row.long("createdAt"))
        }
        val moves = database.rows("SELECT * FROM ink_moves WHERE pageId = ? AND deletedAt IS NULL", pageId) { row ->
            InkMoveEntity(row.string("id"), row.string("pageId"), row.float("dxDp"), row.float("dyDp"),
                row.float("scaleX"), row.float("scaleY"), row.float("anchorX"), row.float("anchorY"),
                row.blob("points"), row.string("enc"), row.long("createdAt"))
        }
        val operations = erases.map { Operation(it.id, it.createdAt, erase = it) } +
            moves.map { Operation(it.id, it.createdAt, move = it) }
        return operations.sortedWith(compareBy(Operation::createdAt, Operation::id)).fold(strokes) { current, operation ->
            operation.erase?.let { erase ->
                val mask = InkCodec.decodeErase(erase) ?: return@fold current
                val targets = database.rows("SELECT strokeId FROM ink_erase_targets WHERE eraseId = ?", erase.id) { it.getString(0) }
                return@fold when (erase.mode) {
                    EraserMode.Normal -> current.subtract(mask, targets)
                    EraserMode.Object -> current.eraseObjects(mask, targets)
                }
            }
            val move = requireNotNull(operation.move)
            val path = InkCodec.decodeMove(move) ?: return@fold current
            val targets = database.rows("SELECT strokeId FROM ink_move_targets WHERE moveId = ?", move.id) { it.getString(0) }
            current.replayMove(path, targets, move.dxDp, move.dyDp)
                .replayResize(path, targets, InkPoint(move.anchorX, move.anchorY), move.scaleX, move.scaleY)
        }
    }

    private data class Operation(val id: String, val createdAt: Long, val erase: InkEraseEntity? = null, val move: InkMoveEntity? = null)

    private fun strokeRow(row: Cursor): InkStrokeEntity = InkStrokeEntity(
        id = row.string("id"), pageId = row.string("pageId"), seq = row.int("seq"),
        brushFamily = row.string("brushFamily"), brushVersion = row.int("brushVersion"), sizeDp = row.float("sizeDp"),
        colorArgb = row.int("colorArgb"), colorFollowsTheme = row.nullableInt("colorFollowsTheme")?.let { it != 0 },
        epsilon = row.float("epsilon"), stabilization = row.int("stabilization"),
        minX = row.float("minX"), minY = row.float("minY"), maxX = row.float("maxX"), maxY = row.float("maxY"),
        points = row.blob("points"), enc = row.string("enc"), createdAt = row.long("createdAt"),
        groupId = row.stringOrNull("groupId"),
    )

    private fun <T> SQLiteDatabase.rows(sql: String, argument: String, read: (Cursor) -> T): List<T> =
        rawQuery(sql, arrayOf(argument)).use { rows -> buildList { while (rows.moveToNext()) add(read(rows)) } }
    private fun Cursor.string(name: String): String = getString(getColumnIndexOrThrow(name))
    private fun Cursor.stringOrNull(name: String): String? = getString(getColumnIndexOrThrow(name))
    private fun Cursor.int(name: String): Int = getInt(getColumnIndexOrThrow(name))
    private fun Cursor.nullableInt(name: String): Int? = getColumnIndexOrThrow(name).let { if (isNull(it)) null else getInt(it) }
    private fun Cursor.float(name: String): Float = getFloat(getColumnIndexOrThrow(name))
    private fun Cursor.long(name: String): Long = getLong(getColumnIndexOrThrow(name))
    private fun Cursor.blob(name: String): ByteArray = getBlob(getColumnIndexOrThrow(name))

    /** Android's documented HardwareRenderer/ImageReader capture, including hardware Canvas.drawMesh. */
    private fun hardwareBitmap(width: Int, height: Int, draw: (Canvas) -> Unit): Bitmap {
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        val node = RenderNode("ByteInk reference").apply { setPosition(0, 0, width, height) }
        val renderer = HardwareRenderer()
        try {
            renderer.setSurface(reader.surface)
            renderer.setContentRoot(node)
            val canvas = node.beginRecording(width, height)
            try { draw(canvas) } finally { node.endRecording() }
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
            reader.acquireNextImage().use { image ->
                requireNotNull(image) { "HardwareRenderer produced no image" }
                requireNotNull(image.hardwareBuffer).use { buffer ->
                    val hardware = requireNotNull(Bitmap.wrapHardwareBuffer(buffer, null))
                    try { return requireNotNull(hardware.copy(Bitmap.Config.ARGB_8888, false)) }
                    finally { hardware.recycle() }
                }
            }
        } finally {
            node.discardDisplayList()
            renderer.destroy()
            reader.close()
        }
    }
}
