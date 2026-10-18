package com.vivenotes.byteink.kit

/** The pen nib names used by the Android app. */
public enum class PenKind {
    Fountain,
    Calligraphy,
}

/** Android's pen line types; both non-solid values select the dashed Ink family. */
public enum class LineType {
    Solid,
    Dashed,
    Dotted,
}

/**
 * Pen settings accepted by [ViveInkCodec.brushFor] and [ViveInkCodec.encode], with the Android
 * app's field names and defaults. Applications own preference persistence and theme resolution.
 * Capture the preset at gesture start so the brush and encoded metadata describe the same pen.
 */
public data class PenPreset(
    val kind: PenKind = PenKind.Fountain,
    val lineType: LineType = LineType.Solid,
    val colorArgb: Int = 0xFF000000.toInt(),
    val colorFollowsTheme: Boolean = false,
    val thickness: Float = 1.5f,
    val pressure: Int = 3,
    val stabilization: Int = 1,
    /** Application gesture preference; it does not change the Ink brush or stored row. */
    val holdForStraightLine: Boolean = true,
) {
    public companion object {
        public const val MIN_THICKNESS: Float = 1f
        public const val MAX_THICKNESS: Float = 8f
        public const val THICKNESS_STEP: Float = 0.5f
        public const val MAX_PRESSURE: Int = ViveBrushes.MAX_CALLIGRAPHY_PRESSURE
        public const val MAX_STABILIZATION: Int = ViveBrushes.MAX_STABILIZATION
    }
}
