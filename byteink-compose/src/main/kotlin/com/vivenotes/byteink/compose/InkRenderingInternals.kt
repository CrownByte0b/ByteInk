@file:OptIn(androidx.ink.nativeloader.InkInternalOnlyApi::class)

package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.Color
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.color.Color as InkColor
import androidx.ink.brush.color.colorspace.ColorSpaces
import androidx.ink.strokes.InProgressStroke

/** The same colour functions as Android's CanvasPathRenderer; this is the renderer's internal API seam. */
internal fun BrushPaint.composeColor(brush: Brush, colorArgb: Int?): Color {
    val base = if (colorArgb == null) InkColor(brush.colorLong.toULong()) else InkColor(colorArgb)
    val color = applyColorFunctions(base).convert(ColorSpaces.Srgb)
    return Color(color.red, color.green, color.blue, color.alpha)
}

/** Ink's mutation counter, needed to invalidate cached live paths even when input count is unchanged. */
internal fun InProgressStroke.shapeVersion(): Long = getVersion()
