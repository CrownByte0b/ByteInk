package com.vivenotes.byteink.kit

/**
 * One row of ViveNotes' `ink_strokes` table, column for column as the Android app stores it.
 *
 * A stroke is its brush and its inputs; the mesh is rebuilt from them on load, so the row stores the
 * family id, version, size, colour, mesh tolerance and stabilization level that rebuild it, and the
 * inputs as [points] written by [enc]. byteink never rewrites a row: it reads rows and makes new ones.
 */
public data class StoredInkStroke(
    val id: String,
    val pageId: String,
    /** Draw order within the page, a logical clock; ties are broken by [id]. */
    val seq: Int,
    val brushFamily: String,
    /** The stock brush version the stroke was drawn with, pinned rather than "latest". */
    val brushVersion: Int,
    val sizeDp: Float,
    val colorArgb: Int,
    /** Whether [colorArgb] was the automatic colour; null when that was never recorded. */
    val colorFollowsTheme: Boolean?,
    val epsilon: Float,
    /** The stabilization level the stroke was drawn at, 0–5; a highlighter stores 0 for "none". */
    val stabilization: Int,
    /** The stroke's bounds on the page, as the drawing device measured them. */
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
    val points: ByteArray,
    /** Which encoder wrote [points]; see [ViveInkCodec.ENCODING]. */
    val enc: String,
    val createdAt: Long,
    /** An optional logical group of rows that select together. */
    val groupId: String? = null,
    /** Set once the stroke is deleted; ink is tombstoned rather than removed, so deletes replicate. */
    val deletedAt: Long? = null,
) {
    override fun equals(other: Any?): Boolean = this === other || other is StoredInkStroke &&
        id == other.id && pageId == other.pageId && seq == other.seq && brushFamily == other.brushFamily &&
        brushVersion == other.brushVersion && sizeDp == other.sizeDp && colorArgb == other.colorArgb &&
        colorFollowsTheme == other.colorFollowsTheme && epsilon == other.epsilon &&
        stabilization == other.stabilization && minX == other.minX && minY == other.minY &&
        maxX == other.maxX && maxY == other.maxY && points.contentEquals(other.points) && enc == other.enc &&
        createdAt == other.createdAt && groupId == other.groupId && deletedAt == other.deletedAt

    override fun hashCode(): Int = id.hashCode() * 31 + points.contentHashCode()
}

/** How an erase applies its mask. */
public enum class InkEraseMode {
    /** Cuts the mask's shape out of the strokes it targets. */
    Normal,

    /** Removes every disconnected piece of its targets that the mask touches. */
    Object,
    ;

    /** The value stored in `ink_erases.mode`. */
    public val stored: String get() = name

    public companion object {
        /** The mode a stored value names, or null for one this build does not know. */
        public fun of(stored: String): InkEraseMode? = entries.firstOrNull { it.stored == stored }
    }
}

/**
 * One row of `ink_erases` with its `ink_erase_targets`: an eraser gesture, stored as its mask's
 * inputs and diameter, and the strokes it touched when it was made. Replay applies it to those
 * strokes only, so ink drawn later through the same place is not erased.
 */
public data class StoredInkErase(
    val id: String,
    val pageId: String,
    /** [InkEraseMode.stored], kept as stored so a mode this build does not know survives untouched. */
    val mode: String,
    /** The eraser's diameter in page dp. */
    val sizeDp: Float,
    val points: ByteArray,
    val enc: String,
    val createdAt: Long,
    /** Set while the erase is undone. */
    val deletedAt: Long? = null,
    /** The ids of the strokes it applies to. */
    val targetIds: List<String>,
) {
    override fun equals(other: Any?): Boolean = this === other || other is StoredInkErase &&
        id == other.id && pageId == other.pageId && mode == other.mode && sizeDp == other.sizeDp &&
        points.contentEquals(other.points) && enc == other.enc && createdAt == other.createdAt &&
        deletedAt == other.deletedAt && targetIds == other.targetIds

    override fun hashCode(): Int = id.hashCode() * 31 + points.contentHashCode()
}

/**
 * One row of `ink_moves` with its `ink_move_targets`: a lasso move, and optionally a resize, stored
 * as the lasso's page-space polygon, the translation, and the scale about an anchor. Replay re-finds
 * its projections geometrically, inside that polygon among its targets.
 */
public data class StoredInkMove(
    val id: String,
    val pageId: String,
    val dxDp: Float,
    val dyDp: Float,
    /** A resize, composed after the translation around ([anchorX], [anchorY]); 1 for a plain move. */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val anchorX: Float = 0f,
    val anchorY: Float = 0f,
    /** The closed lasso's vertices, written by [enc]; see [ViveInkCodec.MOVE_ENCODING]. */
    val points: ByteArray,
    val enc: String,
    val createdAt: Long,
    /** Set while the move is undone. */
    val deletedAt: Long? = null,
    /** The ids of the strokes it applies to. */
    val targetIds: List<String>,
) {
    override fun equals(other: Any?): Boolean = this === other || other is StoredInkMove &&
        id == other.id && pageId == other.pageId && dxDp == other.dxDp && dyDp == other.dyDp &&
        scaleX == other.scaleX && scaleY == other.scaleY && anchorX == other.anchorX && anchorY == other.anchorY &&
        points.contentEquals(other.points) && enc == other.enc && createdAt == other.createdAt &&
        deletedAt == other.deletedAt && targetIds == other.targetIds

    override fun hashCode(): Int = id.hashCode() * 31 + points.contentHashCode()
}
