package com.vivenotes.byteink.kit

import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.SpatialIndex
import kotlin.math.hypot

/**
 * A page's projections with a spatial index over them, for the questions the eraser, the lasso and
 * the pointer ask many times a second. Each answer is the one the unindexed scan gives
 * ([targetsFor], [selectWithLasso]) — the index only skips projections whose bounds cannot meet
 * the question, and the same exact test is put to the rest — so what the desktop stores, Android
 * replays identically.
 *
 * Built for one state of the page; build another after the page changes.
 */
public class InkPageIndex(public val strokes: List<PageStroke>) {

    private val index = SpatialIndex.of(strokes) { stroke ->
        stroke.pageBounds?.let { ImmutableBox.fromTwoPoints(ImmutableVec(it.left, it.top), ImmutableVec(it.right, it.bottom)) }
    }

    /** The projections [mask] touches, in page order. */
    public fun touching(mask: Stroke): List<PageStroke> {
        val box = mask.shape.computeBoundingBox() ?: return emptyList()
        return near(box.xMin, box.yMin, box.xMax, box.yMax).filter { it.touches(mask) }
    }

    /** The rows [mask] touches, as [targetsFor] finds them: what an erase made now applies to. */
    public fun targetsFor(mask: Stroke): List<String> = touching(mask).map(PageStroke::id).distinct()

    /** What [selectWithLasso] selects on this page. */
    public fun selectWithLasso(path: List<InkPoint>, edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE): InkLassoSelection? {
        if (path.size < 3) return null
        val lasso = LassoShape(path, edgeTolerance)
        val hits = near(
            path.minOf { it.x } - edgeTolerance,
            path.minOf { it.y } - edgeTolerance,
            path.maxOf { it.x } + edgeTolerance,
            path.maxOf { it.y } + edgeTolerance,
        ).filter { lasso.contains(it) }
        return strokes.selectionOf(hits, path)
    }

    /** What [selectInkWithLasso] selects on this page: nothing unless the gesture closes into a loop. */
    public fun selectInkWithLasso(path: List<InkPoint>, edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE): InkLassoSelection? {
        if (path.size < 3 || !path.closesIntoALoop(edgeTolerance)) return null
        return selectWithLasso(path, edgeTolerance)
    }

    /** The projections with ink within the square of half-side [reach] around [point], in page order. */
    public fun at(point: InkPoint, reach: Float): List<PageStroke> {
        val box = ImmutableBox.fromTwoPoints(
            ImmutableVec(point.x - reach, point.y - reach),
            ImmutableVec(point.x + reach, point.y + reach),
        )
        return near(box.xMin, box.yMin, box.xMax, box.yMax).filter { stroke ->
            stroke.stroke.hasGeometry && stroke.stroke.shape.computeCoverageIsGreaterThan(box, 0f, stroke.pageToStrokeTransform())
        }
    }

    /**
     * The projections with ink on the segment from [from] to [to], taken as a band [width] wide: what
     * an eraser crosses between two samples of a drag.
     */
    public fun crossing(from: InkPoint, to: InkPoint, width: Float): List<PageStroke> {
        val reach = width / 2f
        val length = hypot(to.x - from.x, to.y - from.y)
        if (length == 0f) return at(from, reach)
        val nx = -(to.y - from.y) / length * reach
        val ny = (to.x - from.x) / length * reach
        val corner1 = ImmutableVec(from.x + nx, from.y + ny)
        val corner2 = ImmutableVec(to.x + nx, to.y + ny)
        val corner3 = ImmutableVec(to.x - nx, to.y - ny)
        val corner4 = ImmutableVec(from.x - nx, from.y - ny)
        val first = ImmutableTriangle(corner1, corner2, corner3)
        val second = ImmutableTriangle(corner1, corner3, corner4)
        return near(
            minOf(from.x, to.x) - reach,
            minOf(from.y, to.y) - reach,
            maxOf(from.x, to.x) + reach,
            maxOf(from.y, to.y) + reach,
        ).filter { stroke ->
            if (!stroke.stroke.hasGeometry) return@filter false
            val toStroke = stroke.pageToStrokeTransform()
            val shape = stroke.stroke.shape
            shape.computeCoverageIsGreaterThan(first, 0f, toStroke) || shape.computeCoverageIsGreaterThan(second, 0f, toStroke)
        }
    }

    /** The projections whose bounds come within [SLACK] of the rectangle: a superset of any exact answer. */
    private fun near(xMin: Float, yMin: Float, xMax: Float, yMax: Float): List<PageStroke> =
        index.query(xMin - SLACK, yMin - SLACK, xMax + SLACK, yMax + SLACK)

    private companion object {
        /**
         * How far past a question's box a projection's bounds may lie and still be tested, in page
         * units: room for the rounding in page transforms, so the index never skips a projection the
         * exact test would have found.
         */
        const val SLACK = 0.01f
    }
}
