package com.vivenotes.byteink.vive

/** One point in page coordinates (dp), where stored ink and lasso paths live. */
public data class InkPoint(val x: Float, val y: Float)

/** A page-space rectangle, used by selections and their move and resize handles. */
public data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {

    public val center: InkPoint get() = InkPoint((left + right) / 2f, (top + bottom) / 2f)

    public fun contains(point: InkPoint): Boolean = point.x in left..right && point.y in top..bottom

    public fun translated(dx: Float, dy: Float): InkBounds = InkBounds(left + dx, top + dy, right + dx, bottom + dy)

    public fun scaled(anchor: InkPoint, scaleX: Float, scaleY: Float): InkBounds {
        val x1 = anchor.x + (left - anchor.x) * scaleX
        val x2 = anchor.x + (right - anchor.x) * scaleX
        val y1 = anchor.y + (top - anchor.y) * scaleY
        val y2 = anchor.y + (bottom - anchor.y) * scaleY
        return InkBounds(minOf(x1, x2), minOf(y1, y2), maxOf(x1, x2), maxOf(y1, y2))
    }
}

/** The smallest rectangle around all of these, or null for none. */
public fun List<InkBounds>.unionBounds(): InkBounds? {
    val first = firstOrNull() ?: return null
    return drop(1).fold(first) { result, next ->
        InkBounds(
            left = minOf(result.left, next.left),
            top = minOf(result.top, next.top),
            right = maxOf(result.right, next.right),
            bottom = maxOf(result.bottom, next.bottom),
        )
    }
}

/**
 * Identifies one live projection of a stored stroke, including a disconnected piece that shares its
 * row id with others: an erase that cuts a stroke in two leaves one row and two projections.
 */
public data class InkProjectionKey(val strokeId: String, val projection: Int)

/** A completed lasso drag, ready to apply immediately and to store for replay. */
public data class InkLassoMove(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val dx: Float,
    val dy: Float,
)

/** A completed corner-handle drag, scaling the selected projections around [anchor]. */
public data class InkLassoResize(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val anchor: InkPoint,
    val scaleX: Float,
    val scaleY: Float,
)

/** What a lasso took: the rows it names, the projections it holds, and their union rectangle. */
public data class InkLassoSelection(
    val path: List<InkPoint>,
    val targetIds: Set<String>,
    val projections: Set<InkProjectionKey>,
    val bounds: InkBounds,
)
