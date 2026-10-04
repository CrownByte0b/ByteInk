package com.vivenotes.byteink.kit

import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPInputStream

/**
 * ViveNotes' stored ink rows, read and written as the Android app does (its `ink/InkCodec.kt`).
 *
 * Reading never throws and never guesses: a row this build cannot read yields null, which costs
 * that one stroke rather than its page. Writing makes new rows only. The caller supplies ids, draw
 * order and clocks, which belong to the app's database.
 */
public object ViveInkCodec {

    /** AndroidX Ink's gzip-compressed protobuf of a stroke's inputs, as `StrokeInputBatch.encode` writes it. */
    public const val ENCODING: String = "ink/androidx1"

    /** A little-endian point count, then that many x/y float pairs, all in page dp. */
    public const val MOVE_ENCODING: String = "ink/lasso-f32le1"

    /**
     * How far a point blob may decompress before it is refused. AndroidX decompresses without a
     * limit, so a crafted blob would exhaust the heap; this is the desktop app's own reader's limit.
     */
    public const val MAX_DECOMPRESSED_BYTES: Int = 64 * 1024 * 1024

    /**
     * Rebuilds a stored stroke, or null if it cannot be read: an encoder this build does not know,
     * damaged inputs, or a brush the engine refuses. A family id it does not know draws as the
     * pressure pen, as on Android.
     *
     * The row's own stabilization level is used, not a pen's current one: the mesh is derived from
     * the inputs through that model, so another level would reshape ink already on the page.
     */
    public fun decode(row: StoredInkStroke): Stroke? {
        if (row.enc != ENCODING) return null
        return readOrNull {
            val brush = Brush.createWithColorIntArgb(
                family = ViveBrushes.family(row.brushFamily, row.stabilization),
                colorIntArgb = row.colorArgb,
                size = row.sizeDp,
                epsilon = row.epsilon,
            )
            Stroke(brush = brush, inputs = decodeInputs(row.points))
        }
    }

    /** Whether a point blob is one AndroidX Ink reads, without building a mesh from it. */
    public fun hasValidInputData(points: ByteArray): Boolean = readOrNull { decodeInputs(points) } != null

    /**
     * A new row for [stroke], drawn with the family [brushFamily] at [stabilization]. Its bounds are
     * the mesh's, and zeros for a stroke with no geometry, which has nothing to draw.
     */
    public fun encodeStroke(
        stroke: Stroke,
        id: String,
        pageId: String,
        seq: Int,
        brushFamily: String,
        stabilization: Int,
        colorFollowsTheme: Boolean?,
        createdAt: Long,
        groupId: String? = null,
    ): StoredInkStroke {
        val box = stroke.shape.computeBoundingBox()
        return StoredInkStroke(
            id = id,
            pageId = pageId,
            seq = seq,
            brushFamily = brushFamily,
            brushVersion = ViveBrushes.BRUSH_VERSION,
            sizeDp = stroke.brush.size,
            colorArgb = stroke.brush.colorIntArgb,
            colorFollowsTheme = colorFollowsTheme,
            epsilon = stroke.brush.epsilon,
            stabilization = stabilization,
            minX = box?.xMin ?: 0f,
            minY = box?.yMin ?: 0f,
            maxX = box?.xMax ?: 0f,
            maxY = box?.yMax ?: 0f,
            points = encodeInputs(stroke.inputs),
            enc = ENCODING,
            createdAt = createdAt,
            groupId = groupId,
        )
    }

    /**
     * A new highlighter row. Its stabilization is 0, meaning "no such setting", and its colour never
     * follows the theme: a highlight is translucent by definition, and the theme's ink would turn it
     * into an opaque band over the writing it marks.
     */
    public fun encodeHighlighter(
        stroke: Stroke,
        id: String,
        pageId: String,
        seq: Int,
        createdAt: Long,
        groupId: String? = null,
    ): StoredInkStroke = encodeStroke(
        stroke = stroke,
        id = id,
        pageId = pageId,
        seq = seq,
        brushFamily = ViveBrushes.HIGHLIGHTER,
        stabilization = 0,
        colorFollowsTheme = false,
        createdAt = createdAt,
        groupId = groupId,
    )

    /**
     * A new row for a pasted copy of [source]: its family, version, stabilization and theme flag
     * carry over, since a duplicate of automatic ink is still automatic. Android writes seq 0 here
     * and lets its repository allocate the real one.
     */
    public fun encodeCopy(
        source: PageStroke,
        stroke: Stroke,
        id: String,
        pageId: String,
        seq: Int,
        createdAt: Long,
        groupId: String? = null,
    ): StoredInkStroke {
        val box = stroke.shape.computeBoundingBox()
        return StoredInkStroke(
            id = id,
            pageId = pageId,
            seq = seq,
            brushFamily = source.brushFamily,
            brushVersion = source.brushVersion,
            sizeDp = stroke.brush.size,
            colorArgb = stroke.brush.colorIntArgb,
            colorFollowsTheme = source.colorFollowsTheme,
            epsilon = stroke.brush.epsilon,
            stabilization = source.stabilization,
            minX = box?.xMin ?: 0f,
            minY = box?.yMin ?: 0f,
            maxX = box?.xMax ?: 0f,
            maxY = box?.yMax ?: 0f,
            points = encodeInputs(stroke.inputs),
            enc = ENCODING,
            createdAt = createdAt,
            groupId = groupId,
        )
    }

    /** A new erase row for [mask], applied in [mode] to [targetIds]. */
    public fun encodeErase(
        mask: Stroke,
        id: String,
        pageId: String,
        mode: InkEraseMode,
        createdAt: Long,
        targetIds: List<String>,
    ): StoredInkErase = StoredInkErase(
        id = id,
        pageId = pageId,
        mode = mode.stored,
        sizeDp = mask.brush.size,
        points = encodeInputs(mask.inputs),
        enc = ENCODING,
        createdAt = createdAt,
        targetIds = targetIds,
    )

    /** An erase row's mask, rebuilt, or null if it cannot be read. */
    public fun decodeErase(row: StoredInkErase): Stroke? {
        if (row.enc != ENCODING) return null
        return readOrNull { ViveBrushes.eraseMask(decodeInputs(row.points), row.sizeDp) }
    }

    /**
     * A mask as replay will rebuild it: through the point codec and back. Storage quantizes inputs,
     * so a mask that is to be proved against the page (see [planProjectionDelete]) is proved in the
     * form that will actually be applied on the next load.
     */
    public fun reloadedEraseMask(inputs: StrokeInputBatch, sizeDp: Float): Stroke? =
        readOrNull { ViveBrushes.eraseMask(decodeInputs(encodeInputs(inputs)), sizeDp) }

    /** A new move row for a lasso drag. */
    public fun encodeMove(move: InkLassoMove, id: String, pageId: String, createdAt: Long): StoredInkMove =
        StoredInkMove(
            id = id,
            pageId = pageId,
            dxDp = move.dx,
            dyDp = move.dy,
            points = encodePath(move.path),
            enc = MOVE_ENCODING,
            createdAt = createdAt,
            targetIds = move.targetIds.toList(),
        )

    /** A new move row for a corner resize: no translation, the scale about the resize's anchor. */
    public fun encodeResize(resize: InkLassoResize, id: String, pageId: String, createdAt: Long): StoredInkMove =
        StoredInkMove(
            id = id,
            pageId = pageId,
            dxDp = 0f,
            dyDp = 0f,
            scaleX = resize.scaleX,
            scaleY = resize.scaleY,
            anchorX = resize.anchor.x,
            anchorY = resize.anchor.y,
            points = encodePath(resize.path),
            enc = MOVE_ENCODING,
            createdAt = createdAt,
            targetIds = resize.targetIds.toList(),
        )

    /** A move row's lasso path, or null if it is not one: another encoding, fewer than three points, or damaged. */
    public fun decodeMove(row: StoredInkMove): List<InkPoint>? {
        if (row.enc != MOVE_ENCODING) return null
        return readOrNull {
            val buffer = ByteBuffer.wrap(row.points).order(ByteOrder.LITTLE_ENDIAN)
            val count = buffer.int
            require(count >= 3 && buffer.remaining() == count * 2 * Float.SIZE_BYTES)
            List(count) {
                InkPoint(buffer.float, buffer.float).also { point -> require(point.x.isFinite() && point.y.isFinite()) }
            }
        }
    }

    private fun encodePath(path: List<InkPoint>): ByteArray {
        require(path.size >= 3) { "A lasso needs at least three points" }
        require(path.all { it.x.isFinite() && it.y.isFinite() }) { "Lasso points must be finite" }
        return ByteBuffer.allocate(Int.SIZE_BYTES + path.size * 2 * Float.SIZE_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(path.size)
            .apply { path.forEach { point -> putFloat(point.x).putFloat(point.y) } }
            .array()
    }

    internal fun encodeInputs(inputs: StrokeInputBatch): ByteArray {
        // Keep the real engine's delta-coded protobuf. The pinned Android binary additionally
        // writes its private fixed32 field 10, the default animation phase, even when it is zero;
        // public google/ink reserves that field. Compression uses pinned classic deflate rather
        // than the host JVM's zlib, whose implementation changes output bytes across machines.
        val proto = InkInputCodec.encode(inputs)
        return AndroidInkCompression.gzip(withAndroidAnimationPhase(proto))
    }

    /** Supplies alpha06's default private phase without rewriting any engine-produced field. */
    internal fun withAndroidAnimationPhase(proto: ByteArray): ByteArray {
        var at = 0
        var insertion = proto.size
        var hasPhase = false
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                require(at < proto.size && shift < 64) { "Malformed input protobuf varint" }
                val byte = proto[at++].toInt() and 0xff
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) return value
                shift += 7
            }
        }
        while (at < proto.size) {
            val start = at
            val key = varint()
            val field = key ushr 3
            require(field > 0) { "Invalid input protobuf field" }
            if (field == 10L) hasPhase = true
            if (field > 10L && insertion == proto.size) insertion = start
            val size = when ((key and 7).toInt()) {
                0 -> { varint(); 0L }
                1 -> 8L
                2 -> varint()
                5 -> 4L
                else -> throw IllegalArgumentException("Unsupported input protobuf wire type")
            }
            require(size >= 0L && size <= proto.size.toLong() - at) { "Truncated input protobuf" }
            at += size.toInt()
        }
        // Preserve an existing native phase verbatim, including a non-default value.
        if (hasPhase) return proto
        return ByteArrayOutputStream(proto.size + 5).use { out ->
            out.write(proto, 0, insertion)
            out.write(byteArrayOf(0x55, 0, 0, 0, 0))
            out.write(proto, insertion, proto.size - insertion)
            out.toByteArray()
        }
    }

    internal fun decodeInputs(points: ByteArray): StrokeInputBatch {
        val initial = decodeScratch.get() ?: ByteArray(INITIAL_DECODE_CAPACITY)
        decodeScratch.set(null)
        var buffer = initial
        try {
            GZIPInputStream(ByteArrayInputStream(points)).use { gzip ->
                var size = 0
                while (true) {
                    if (size == buffer.size) {
                        if (size == MAX_DECOMPRESSED_BYTES) {
                            // Read through the trailer (and any following member) even at the cap.
                            if (gzip.read() >= 0) throw expansionLimitFailure()
                            break
                        }
                        buffer = buffer.copyOf(minOf(buffer.size * 2, MAX_DECOMPRESSED_BYTES))
                    }
                    val read = gzip.read(buffer, size, buffer.size - size)
                    if (read < 0) break
                    size += read
                }
                return InkInputCodec.decode(buffer, size)
            }
        } finally {
            // Keep bounded thread-confined storage only; a large row cannot pin its expanded blob.
            decodeScratch.set(if (buffer.size <= MAX_RETAINED_DECODE_CAPACITY) buffer else initial)
        }
    }

    private const val INITIAL_DECODE_CAPACITY = 1024
    private const val MAX_RETAINED_DECODE_CAPACITY = 64 * 1024
    // Empty while leased, including native parsing; concurrent and reentrant reads are independent.
    private val decodeScratch = ThreadLocal<ByteArray?>()

    private fun expansionLimitFailure(): IOException =
        IOException("The ink blob expands past $MAX_DECOMPRESSED_BYTES bytes")

    /**
     * [read]'s result, or null if it throws an [Exception]: damaged data, which costs one row. Errors
     * are not caught, so a missing native library is reported rather than shown as blank pages.
     */
    private inline fun <T> readOrNull(read: () -> T): T? = try {
        read()
    } catch (unreadable: Exception) {
        null
    }
}
