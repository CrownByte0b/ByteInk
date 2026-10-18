package com.vivenotes.byteink.kit

/** Highlighter settings with the Android app's field names, units and defaults. */
public data class HighlighterSettings(
    /** ARGB including alpha; translucency is part of the stored color. */
    val colorArgb: Int = DEFAULT_COLOR,
    /** Band width in page dp. */
    val thickness: Int = DEFAULT_THICKNESS,
) {
    public companion object {
        public const val MIN_THICKNESS: Int = 6
        public const val MAX_THICKNESS: Int = 40
        public const val DEFAULT_THICKNESS: Int = 18
        public const val DEFAULT_COLOR: Int = 0x66FFEB3B
    }
}
