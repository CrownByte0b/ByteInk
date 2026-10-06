package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke

/** A reusable, single drawing-thread renderer for finished and live Ink. */
public interface InkRenderer {
    /** Changes when renderer settings or texture contents invalidate a previously rendered view. */
    public val renderVersion: Long get() = 0L
    public fun canDraw(stroke: Stroke): Boolean
    public fun canDraw(stroke: InProgressStroke): Boolean
    public fun render(
        canvas: Canvas,
        stroke: Stroke,
        strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect? = null,
        colorArgb: Int? = null,
    ): Boolean
    public fun render(
        canvas: Canvas,
        stroke: InProgressStroke,
        strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect? = null,
        colorArgb: Int? = null,
    ): Boolean
    public fun clearCache()
}

/** Draws Ink using either the path renderer or the full mesh renderer. */
public fun DrawScope.drawInk(
    renderer: InkRenderer,
    stroke: Stroke,
    strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
    colorArgb: Int? = null,
): Boolean = renderer.render(drawContext.canvas, stroke, strokeToCanvas, Rect(0f, 0f, size.width, size.height), colorArgb)

/** Draws the current live mesh, including predicted-input brush effects. */
public fun DrawScope.drawInk(
    renderer: InkRenderer,
    stroke: InProgressStroke,
    strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
    colorArgb: Int? = null,
): Boolean = renderer.render(drawContext.canvas, stroke, strokeToCanvas, Rect(0f, 0f, size.width, size.height), colorArgb)
