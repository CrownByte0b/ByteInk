package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.createClosedShape
import kotlin.math.floor
import kotlin.math.hypot

/** How close to the loop's edge ink may lie and still count as inside, in page units. */
public const val DEFAULT_LASSO_EDGE_TOLERANCE: Float = 4f

/**
 * The ink a lasso gesture selects, as the Android app's canvas selects it (`CanvasSelection.kt`):
 * nothing unless the gesture closes into a loop ([closesIntoALoop]), and then [selectWithLasso].
 */
public fun List<PageStroke>.selectInkWithLasso(
    path: List<InkPoint>,
    edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
): InkLassoSelection? {
    if (path.size < 3 || !path.closesIntoALoop(edgeTolerance)) return null
    return selectWithLasso(path, edgeTolerance)
}

/**
 * The projections whose ink lies inside the lasso [path], plus every projection of any group one of
 * them belongs to. A loop takes projections, not rows: a loop round one piece of a cut stroke takes
 * that piece. Only a group widens the catch, because a group is the user saying these rows are one
 * object.
 */
public fun List<PageStroke>.selectWithLasso(
    path: List<InkPoint>,
    edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
): InkLassoSelection? {
    if (path.size < 3) return null
    val lasso = LassoShape(path, edgeTolerance)
    return selectionOf(filter { stroke -> lasso.contains(stroke) }, path)
}

/**
 * The selection a lasso's [hits] on this page make: the hits, widened to every projection of any
 * group one of them belongs to.
 */
internal fun List<PageStroke>.selectionOf(hits: List<PageStroke>, path: List<InkPoint>): InkLassoSelection? {
    if (hits.isEmpty()) return null
    val hitGroups = hits.mapNotNull(PageStroke::groupId).toSet()
    val selected = if (hitGroups.isEmpty()) {
        hits
    } else {
        val hitKeys = hits.mapTo(HashSet(hits.size), PageStroke::projectionKey)
        filter { it.projectionKey in hitKeys || it.groupId != null && it.groupId in hitGroups }
    }
    val bounds = selected.mapNotNull(PageStroke::pageBounds).unionBounds() ?: return null
    return InkLassoSelection(
        path = path,
        targetIds = selected.map(PageStroke::id).toSet(),
        projections = selected.map(PageStroke::projectionKey).toSet(),
        bounds = bounds,
    )
}

/**
 * A lasso polygon, prepared once so testing a whole page against it does not walk every mesh.
 *
 * The exact test walks every outline vertex of a stroke. Two shortcuts reach the same conclusion
 * from the bounding box first: a box reaching past the loop's grown extent has a vertex outside, and
 * a convex loop containing all four corners of a box contains everything in it. A projection with no
 * outlines — a piece of erased ink — is asked through its mesh instead ([encloses]).
 */
public class LassoShape(
    public val path: List<InkPoint>,
    private val edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE,
) {
    public val usable: Boolean = path.size >= 3

    private val minX = if (usable) path.minOf { it.x } - edgeTolerance else 0f
    private val minY = if (usable) path.minOf { it.y } - edgeTolerance else 0f
    private val maxX = if (usable) path.maxOf { it.x } + edgeTolerance else 0f
    private val maxY = if (usable) path.maxOf { it.y } + edgeTolerance else 0f

    /** Whether every turn goes the same way, so four corners inside mean the whole box is inside. */
    public val acceptsWholeBox: Boolean = isConvex(path)

    /** Whether any stroke with these bounds could be inside at all; false is exact, not a guess. */
    public fun couldContain(bounds: InkBounds): Boolean =
        bounds.left >= minX && bounds.top >= minY && bounds.right <= maxX && bounds.bottom <= maxY

    public fun contains(stroke: PageStroke): Boolean {
        if (!usable) return false
        val bounds = stroke.pageBounds ?: return false
        if (!couldContain(bounds)) return false
        // Without the tolerance, so this only concludes what the tolerant walk would also conclude.
        if (acceptsWholeBox && corners(bounds).all { pointInPolygon(it, path) }) return true
        return stroke.containsExactly(path, edgeTolerance) ?: encloses(stroke)
    }

    /**
     * Containment for a projection with no outlines to walk: it overlaps the filled loop ([region])
     * and nothing of it reaches over the loop's edge ([crosses]). A piece is one connected region, so
     * one that overlaps the loop without crossing its edge lies wholly inside. The edge tolerance is
     * deliberately not applied here.
     */
    private fun encloses(stroke: PageStroke): Boolean {
        val region = region ?: return false
        val bounds = stroke.pageBounds ?: return false
        val shape = stroke.stroke.shape
        val toStroke = stroke.pageToStrokeTransform()
        if (!shape.computeCoverageIsGreaterThan(region, 0f, toStroke)) return false
        return !crosses(shape, bounds, toStroke)
    }

    /** Whether any ink of [shape] lies on the loop's edge, tested as a thin quad per segment near [bounds]. */
    private fun crosses(shape: PartitionedMesh, bounds: InkBounds, toStroke: AffineTransform): Boolean {
        val reach = EDGE_WIDTH / 2f
        closedPath.zipWithNext().forEach { (a, b) ->
            val nearBounds = minOf(a.x, b.x) - reach <= bounds.right &&
                maxOf(a.x, b.x) + reach >= bounds.left &&
                minOf(a.y, b.y) - reach <= bounds.bottom &&
                maxOf(a.y, b.y) + reach >= bounds.top
            if (!nearBounds) return@forEach
            val length = hypot(b.x - a.x, b.y - a.y)
            if (length == 0f) return@forEach
            val nx = -(b.y - a.y) / length * reach
            val ny = (b.x - a.x) / length * reach
            val corner1 = ImmutableVec(a.x + nx, a.y + ny)
            val corner2 = ImmutableVec(b.x + nx, b.y + ny)
            val corner3 = ImmutableVec(b.x - nx, b.y - ny)
            val corner4 = ImmutableVec(a.x - nx, a.y - ny)
            val touched = shape.computeCoverageIsGreaterThan(ImmutableTriangle(corner1, corner2, corner3), 0f, toStroke) ||
                shape.computeCoverageIsGreaterThan(ImmutableTriangle(corner1, corner3, corner4), 0f, toStroke)
            if (touched) return true
        }
        return false
    }

    /** The path closed back to its first point: a gesture is a run of samples, not a loop. */
    private val closedPath: List<InkPoint> = if (usable) path + path.first() else path

    /**
     * The loop as a filled shape, built only if a projection without outlines asks. Null when the
     * path cannot be made into stroke inputs, which leaves [encloses] answering no.
     */
    private val region: PartitionedMesh? by lazy {
        runCatching {
            MutableStrokeInputBatch().apply {
                var previous: InkPoint? = null
                closedPath.forEachIndexed { index, point ->
                    if (point != previous) {
                        add(InputToolType.UNKNOWN, point.x, point.y, index.toLong())
                        previous = point
                    }
                }
            }.toImmutable().createClosedShape()
        }.getOrNull()
    }

    private fun corners(bounds: InkBounds): List<InkPoint> = listOf(
        InkPoint(bounds.left, bounds.top),
        InkPoint(bounds.right, bounds.top),
        InkPoint(bounds.right, bounds.bottom),
        InkPoint(bounds.left, bounds.bottom),
    )

    private companion object {
        /** How wide the loop's own edge is taken to be, in page units. */
        const val EDGE_WIDTH = 1f
    }
}

private fun pointInPolygon(point: InkPoint, polygon: List<InkPoint>): Boolean {
    var inside = false
    var previous = polygon.last()
    polygon.forEach { current ->
        val crosses = (current.y > point.y) != (previous.y > point.y)
        if (crosses) {
            val crossingX = (previous.x - current.x) * (point.y - current.y) / (previous.y - current.y) + current.x
            if (point.x < crossingX) inside = !inside
        }
        previous = current
    }
    return inside
}

private fun isConvex(polygon: List<InkPoint>): Boolean {
    if (polygon.size < 3) return false
    var sign = 0
    polygon.indices.forEach { index ->
        val a = polygon[index]
        val b = polygon[(index + 1) % polygon.size]
        val c = polygon[(index + 2) % polygon.size]
        val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
        // Collinear vertices turn neither way and constrain nothing.
        if (cross != 0f) {
            val turn = if (cross > 0f) 1 else -1
            if (sign == 0) sign = turn else if (sign != turn) return false
        }
    }
    return sign != 0
}

/**
 * Whether every outline vertex of this projection is in or near [polygon]; null when it has no
 * outline to walk — a piece of erased ink — which is not the same as outside.
 */
private fun PageStroke.containsExactly(polygon: List<InkPoint>, edgeTolerance: Float): Boolean? {
    val position = MutableVec()
    var outlineVertexCount = 0
    val shape = stroke.shape
    repeat(shape.getRenderGroupCount()) { groupIndex ->
        repeat(shape.getOutlineCount(groupIndex)) { outlineIndex ->
            repeat(shape.getOutlineVertexCount(groupIndex, outlineIndex)) { vertexIndex ->
                shape.populateOutlinePosition(groupIndex, outlineIndex, vertexIndex, position)
                outlineVertexCount++
                val pagePoint = InkPoint(position.x * scaleX + offsetX, position.y * scaleY + offsetY)
                if (!pointInOrNearPolygon(pagePoint, polygon, edgeTolerance)) return false
            }
        }
    }
    return if (outlineVertexCount > 0) true else null
}

/** Whether [point] is inside [polygon] or within [edgeTolerance] of its edge. */
public fun pointInOrNearPolygon(point: InkPoint, polygon: List<InkPoint>, edgeTolerance: Float): Boolean {
    if (pointInPolygon(point, polygon)) return true
    val toleranceSquared = edgeTolerance.coerceAtLeast(0f).let { it * it }
    if (toleranceSquared == 0f) return false
    var previous = polygon.last()
    polygon.forEach { current ->
        if (point.distanceSquaredToSegment(previous, current) <= toleranceSquared) return true
        previous = current
    }
    return false
}

/** The squared distance from this point to the segment [start]–[end]. */
public fun InkPoint.distanceSquaredToSegment(start: InkPoint, end: InkPoint): Float {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val lengthSquared = dx * dx + dy * dy
    if (lengthSquared == 0f) {
        val pointDx = x - start.x
        val pointDy = y - start.y
        return pointDx * pointDx + pointDy * pointDy
    }
    val fraction = (((x - start.x) * dx + (y - start.y) * dy) / lengthSquared).coerceIn(0f, 1f)
    val nearestX = start.x + fraction * dx
    val nearestY = start.y + fraction * dy
    val pointDx = x - nearestX
    val pointDy = y - nearestY
    return pointDx * pointDx + pointDy * pointDy
}

/**
 * Whether a lasso gesture is a closed path: somewhere the stroke meets the stroke, by crossing or by
 * stopping within [touch] of it. A C left a hair open encloses nothing. Two segments only count if
 * the pen travelled far enough between them ([CLOSING_TRAVEL] times the reach), since a gesture
 * sampled every half page unit is always within reach of its own next segments.
 *
 * Not asked by replay: a move stored before this rule existed may name a path that fails it.
 */
public fun List<InkPoint>.closesIntoALoop(touch: Float): Boolean {
    if (size < 4) return false
    val reach = maxOf(touch, CLOSING_TOUCH_FLOOR)
    val travelled = travelledTo()
    val apart = reach * CLOSING_TRAVEL
    val buckets = HashMap<Long, MutableList<Int>>()
    for (index in 0 until size - 1) {
        val from = this[index]
        val to = this[index + 1]
        val minColumn = cellOf(minOf(from.x, to.x) - reach, reach)
        val maxColumn = cellOf(maxOf(from.x, to.x) + reach, reach)
        val minRow = cellOf(minOf(from.y, to.y) - reach, reach)
        val maxRow = cellOf(maxOf(from.y, to.y) + reach, reach)
        for (column in minColumn..maxColumn) {
            for (row in minRow..maxRow) {
                val cell = (column.toLong() shl 32) or (row.toLong() and 0xFFFF_FFFFL)
                val sharing = buckets.getOrPut(cell) { mutableListOf() }
                val met = sharing.any { earlier ->
                    travelled[index] - travelled[earlier + 1] > apart && meets(earlier, index, reach)
                }
                if (met) return true
                sharing += index
            }
        }
    }
    return false
}

/** How far the pen had travelled by each sample. */
private fun List<InkPoint>.travelledTo(): FloatArray {
    val distances = FloatArray(size)
    for (index in 1 until size) {
        val from = this[index - 1]
        val to = this[index]
        distances[index] = distances[index - 1] + hypot(to.x - from.x, to.y - from.y)
    }
    return distances
}

/** Whether the segments starting at [first] and [second] cross, or run into one another. */
private fun List<InkPoint>.meets(first: Int, second: Int, reach: Float): Boolean {
    val a = this[first]
    val b = this[first + 1]
    val c = this[second]
    val d = this[second + 1]
    if (crosses(a, b, c, d)) return true
    val touching = reach * reach
    return a.distanceSquaredToSegment(c, d) <= touching ||
        b.distanceSquaredToSegment(c, d) <= touching ||
        c.distanceSquaredToSegment(a, b) <= touching ||
        d.distanceSquaredToSegment(a, b) <= touching
}

/** Proper crossing: each segment has one end on either side of the other's line. */
private fun crosses(a: InkPoint, b: InkPoint, c: InkPoint, d: InkPoint): Boolean {
    val first = side(a, b, c)
    val second = side(a, b, d)
    val third = side(c, d, a)
    val fourth = side(c, d, b)
    return first * second < 0f && third * fourth < 0f
}

private fun side(from: InkPoint, to: InkPoint, point: InkPoint): Float =
    (to.x - from.x) * (point.y - from.y) - (to.y - from.y) * (point.x - from.x)

private fun cellOf(coordinate: Float, reach: Float): Int = floor(coordinate / (reach * 4f)).toInt()

/** The least a gesture's closing allowance may be, in page units: samples are half a unit apart. */
private const val CLOSING_TOUCH_FLOOR = 2f

/** How far the pen must travel between two segments, in multiples of the reach, for them to close a loop. */
private const val CLOSING_TRAVEL = 8f
