package com.vivenotes.byteink.kit

/**
 * The one edge an infinite page still has: its origin corner. The page grows right and down without
 * limit, but anything at a negative coordinate can never be scrolled to, so ink is held off it where
 * its final position is decided — on replay (see [replayMove]). A port of the Android app's
 * `ink/PageBounds.kt`; everything is in page units.
 */
public object PageBounds {

    public const val MIN_X: Float = 0f
    public const val MIN_Y: Float = 0f

    /**
     * As much of ([dx], [dy]) as keeps [bounds] on the page, each axis capped on its own, so a drag
     * into the corner slides along whichever edge it meets first. A rectangle already off the page
     * raises the floor above zero, so the same call pulls old content back inside.
     */
    public fun clampTranslation(bounds: InkBounds, dx: Float, dy: Float): InkPoint =
        InkPoint(maxOf(dx, MIN_X - bounds.left), maxOf(dy, MIN_Y - bounds.top))

    /**
     * As much of ([scaleX], [scaleY]) as keeps [bounds] on the page when scaled about [anchor]. Only
     * ever limits growth toward the origin; an edge already off the page is left to [clampTranslation],
     * since no positive scale about a near-side anchor brings it back.
     */
    public fun clampScale(bounds: InkBounds, anchor: InkPoint, scaleX: Float, scaleY: Float): InkPoint = InkPoint(
        scaleX.coerceAtMost(limitFor(anchor.x, bounds.left, MIN_X)),
        scaleY.coerceAtMost(limitFor(anchor.y, bounds.top, MIN_Y)),
    )

    private fun limitFor(anchor: Float, edge: Float, wall: Float): Float {
        if (edge < wall) return Float.MAX_VALUE
        val reach = anchor - edge
        if (reach <= 0f) return Float.MAX_VALUE
        return (anchor - wall) / reach
    }
}
