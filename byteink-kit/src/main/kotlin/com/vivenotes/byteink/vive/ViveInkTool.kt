package com.vivenotes.byteink.vive

import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke

/** A completed stroke and its new Android-compatible stored row. */
public data class AuthoredViveStroke(public val stroke: Stroke, public val row: StoredInkStroke) {
    /** Storage-roundtripped geometry, decoded once and independent of the authoring engine. */
    public val canonicalStroke: Stroke by lazy {
        requireNotNull(ViveInkCodec.decode(row)) { "The authored row could not be decoded" }
    }
}

/**
 * A drawing tool whose brush and stored metadata stay together. Capture this value when a gesture
 * starts, draw with [brush], then call [complete] when input finishes. Database ids, sequence and
 * clocks remain the caller's responsibility. Existing rows are never modified.
 */
public class ViveInkTool(
    public val familyId: String = ViveBrushes.MARKER,
    public val stabilization: Int = 0,
    public val colorArgb: Int = 0xff202020.toInt(),
    public val sizeDp: Float = 3f,
    public val colorFollowsTheme: Boolean? = false,
) {
    init {
        require(familyId in supportedFamilies) { "Unknown authoring brush family: $familyId" }
        require(stabilization in 0..ViveBrushes.MAX_STABILIZATION) { "Stabilization must be between 0 and 5" }
        if (familyId == ViveBrushes.HIGHLIGHTER) {
            require(stabilization == 0) { "Highlighters do not have stabilization" }
            require(colorFollowsTheme == false) { "Highlighter colour must not follow the theme" }
        }
    }

    /** The real catalog brush, including this tool's stabilization input model. */
    public val brush: Brush = ViveBrushes.brush(familyId, stabilization, colorArgb, sizeDp)

    /** Encodes a finished stroke drawn with this tool, returning both the stroke and the new row. */
    public fun complete(
        stroke: Stroke,
        id: String,
        pageId: String,
        seq: Int,
        createdAt: Long,
        groupId: String? = null,
    ): AuthoredViveStroke {
        require(stroke.brush == brush) { "The finished stroke must use the tool captured at pointer down" }
        return AuthoredViveStroke(stroke, ViveInkCodec.encodeStroke(
            stroke, id, pageId, seq, familyId, stabilization, colorFollowsTheme, createdAt, groupId,
        ))
    }

    private companion object {
        val supportedFamilies: Set<String> = setOf(
            ViveBrushes.MARKER, ViveBrushes.DASHED_LINE, ViveBrushes.HIGHLIGHTER, ViveBrushes.PRESSURE_PEN,
        ) + (0..ViveBrushes.MAX_CALLIGRAPHY_PRESSURE).map(ViveBrushes::calligraphy)
    }
}
