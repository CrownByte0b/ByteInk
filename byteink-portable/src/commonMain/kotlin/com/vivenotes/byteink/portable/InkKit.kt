package com.vivenotes.byteink.portable

import com.vivenotes.byteink.kit.*
import kotlin.time.Clock
import kotlin.uuid.Uuid

/** Shared row writers and replay, independent of the renderer and platform input adapter. */
public class InkKit(public val engine: InkEngine) {
    public companion object {
        public const val ENCODING: String = "ink/androidx1"
        public const val MOVE_ENCODING: String = InkMoveCodec.ENCODING
        public const val BRUSH_VERSION: Int = InkBrushCatalog.BRUSH_VERSION
    }

    public fun decode(row: StoredInkStroke): InkStroke? {
        if (row.enc != ENCODING) return null
        return readOrNull { engine.decodeStroke(InkStyle(row.brushFamily, row.stabilization, row.colorArgb, row.sizeDp, row.epsilon), row.points) }
    }

    public fun encodeStroke(
        stroke: InkStroke, id: String, pageId: String, seq: Int, brushFamily: String,
        stabilization: Int, colorFollowsTheme: Boolean?, createdAt: Long, groupId: String? = null,
    ): StoredInkStroke {
        val bounds = stroke.bounds
        return StoredInkStroke(id, pageId, seq, brushFamily, BRUSH_VERSION, stroke.style.size, stroke.style.colorArgb,
            colorFollowsTheme, stroke.style.epsilon, stabilization, bounds?.left ?: 0f, bounds?.top ?: 0f,
            bounds?.right ?: 0f, bounds?.bottom ?: 0f, stroke.encodeInputs(), ENCODING, createdAt, groupId)
    }

    public fun encode(stroke: InkStroke, pageId: String, seq: Int, pen: PenPreset,
                      now: Long = Clock.System.now().toEpochMilliseconds()): StoredInkStroke = encodeStroke(
        stroke, newId(), pageId, seq, InkStyle.forPen(pen).familyId, pen.stabilization, pen.colorFollowsTheme, now)

    public fun encode(stroke: InkStroke, pageId: String, seq: Int, highlighter: HighlighterSettings,
                      now: Long = Clock.System.now().toEpochMilliseconds()): StoredInkStroke =
        encodeStroke(stroke, newId(), pageId, seq, InkBrushCatalog.HIGHLIGHTER, 0, false, now)

    public fun eraseMask(inputs: List<InkSample>, sizeDp: Float): InkStroke =
        engine.createStroke(InkStyle(InkBrushCatalog.MARKER, 1, 0xff000000.toInt(), sizeDp), inputs)

    public fun encodeCopy(source: InkProjection, stroke: InkStroke, id: String, pageId: String, seq: Int,
                          createdAt: Long, groupId: String? = null): StoredInkStroke =
        encodeStroke(stroke, id, pageId, seq, source.stroke.style.familyId, source.stroke.style.stabilization,
            source.colorFollowsTheme, createdAt, groupId).copy(brushVersion = source.brushVersion)

    public fun reloadedEraseMask(inputs: List<InkSample>, sizeDp: Float): InkStroke? = readOrNull {
        eraseMask(inputs, sizeDp).use { mask -> engine.decodeStroke(mask.style, mask.encodeInputs()) }
    }

    public fun encodeErase(mask: InkStroke, id: String, pageId: String, mode: InkEraseMode,
                           createdAt: Long, targetIds: List<String>): StoredInkErase =
        StoredInkErase(id, pageId, mode.stored, mask.style.size, mask.encodeInputs(), ENCODING, createdAt, targetIds = targetIds.toList())

    public fun decodeErase(row: StoredInkErase): InkStroke? {
        if (row.enc != ENCODING) return null
        return readOrNull { engine.decodeStroke(InkStyle(InkBrushCatalog.MARKER, 1, 0xff000000.toInt(), row.sizeDp), row.points) }
    }

    public fun encodeMove(move: InkLassoMove, id: String, pageId: String, createdAt: Long): StoredInkMove =
        StoredInkMove(id, pageId, move.dx, move.dy, points = InkMoveCodec.encodePath(move.path), enc = MOVE_ENCODING,
            createdAt = createdAt, targetIds = move.targetIds.toList())

    public fun encodeResize(resize: InkLassoResize, id: String, pageId: String, createdAt: Long): StoredInkMove =
        StoredInkMove(id, pageId, 0f, 0f, resize.scaleX, resize.scaleY, resize.anchor.x, resize.anchor.y,
            InkMoveCodec.encodePath(resize.path), MOVE_ENCODING, createdAt, targetIds = resize.targetIds.toList())

    public fun decodeMove(row: StoredInkMove): List<InkPoint>? =
        if (row.enc == MOVE_ENCODING) InkMoveCodec.decodePath(row.points) else null

    /** Load/replay one page. Original records, including tombstones and unreadable data, are retained. */
    public fun load(pageId: String, strokes: List<StoredInkStroke>, erases: List<StoredInkErase> = emptyList(),
                    moves: List<StoredInkMove> = emptyList()): InkPage = loadInkPage(this, pageId, strokes, erases, moves)

    @OptIn(kotlin.uuid.ExperimentalUuidApi::class)
    private fun newId(): String {
        val millis = Clock.System.now().toEpochMilliseconds()
        val bytes = Uuid.random().toByteArray()
        for (i in 0..5) bytes[i] = (millis ushr (8 * (5 - i))).toByte()
        bytes[6] = ((bytes[6].toInt() and 15) or 0x70).toByte()
        return Uuid.fromByteArray(bytes).toString()
    }
}

/** Backends translate malformed-data failures to IllegalArgumentException; engine/runtime errors propagate. */
internal inline fun <T> readOrNull(block: () -> T): T? = try { block() } catch (_: IllegalArgumentException) { null }
