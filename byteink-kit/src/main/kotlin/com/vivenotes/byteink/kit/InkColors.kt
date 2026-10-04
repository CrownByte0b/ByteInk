package com.vivenotes.byteink.kit

/** What the automatic pen resolves to on a dark canvas. */
public const val AUTOMATIC_LIGHT: Int = 0xFFFFFFFF.toInt()

/** And on a light one. */
public const val AUTOMATIC_DARK: Int = 0xFF000000.toInt()

/** The colour the automatic pen shows on a dark or light canvas. */
public fun automaticInkFor(isDark: Boolean): Int = if (isDark) AUTOMATIC_LIGHT else AUTOMATIC_DARK

/**
 * The colour to draw a stored stroke with on a canvas whose automatic ink is [canvasInk], as the
 * Android app resolves it (`automaticColorOr`): the canvas ink if the stroke followed the theme, its
 * stored colour if it did not, and, for rows that never recorded which ([followsTheme] null), the
 * canvas ink only when the stored colour is one of the two automatic ones.
 */
public fun automaticColorOr(stored: Int, followsTheme: Boolean?, canvasInk: Int): Int = when {
    followsTheme == true -> canvasInk
    followsTheme == false -> stored
    stored == AUTOMATIC_LIGHT || stored == AUTOMATIC_DARK -> canvasInk
    else -> stored
}
