package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import java.util.concurrent.atomic.AtomicInteger

/**
 * One projection of a stored stroke on its page: the row's id, the stroke as replay has left it, and
 * the page transform moves and resizes have given it. A port of the Android app's `ink/PageStroke.kt`.
 *
 * A [Stroke] has no identity of its own, so the row id travels beside it. An erase that cuts a stroke
 * leaves one row and several projections, each told apart by [projection].
 */
public data class PageStroke(
    val id: String,
    val stroke: Stroke,
    /** Translation from this projection's stroke coordinates into page coordinates. */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Axis-aligned scale into page coordinates; resizes compose into it. */
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** The row's codec metadata, kept so a duplicate is stored like its source. */
    val brushFamily: String = ViveBrushes.PRESSURE_PEN,
    val brushVersion: Int = ViveBrushes.BRUSH_VERSION,
    val stabilization: Int = 0,
    /** Whether the stroke's colour is the automatic one; see [automaticColorOr]. */
    val colorFollowsTheme: Boolean? = null,
    val groupId: String? = null,
    /**
     * What makes this projection this projection for as long as it is on the page. Carried rather
     * than derived from the allocation, so a projection keeps its identity through anything `copy`
     * does to it; only a genuinely new projection mints a number — the default here, and the pieces
     * an erase leaves.
     */
    val projection: Int = newProjection(),
) {
    /**
     * This projection's page-space rectangle, or null once a cut has left it no geometry. Computed
     * once per instance, since the draw pass and lasso replay ask every stroke for it.
     */
    val pageBounds: InkBounds? by lazy {
        stroke.shape.computeBoundingBox()?.let {
            InkBounds(
                left = it.xMin * scaleX + offsetX,
                top = it.yMin * scaleY + offsetY,
                right = it.xMax * scaleX + offsetX,
                bottom = it.yMax * scaleY + offsetY,
            )
        }
    }

    /** The key selections hold this projection by. */
    val projectionKey: InkProjectionKey get() = InkProjectionKey(id, projection)

    /** The transform from this projection's stroke coordinates to page coordinates. */
    public fun strokeToPageTransform(): AffineTransform =
        ImmutableAffineTransform(scaleX, 0f, offsetX, 0f, scaleY, offsetY)

    internal fun pageToStrokeTransform(): AffineTransform = strokeToPageTransform().computeInverse()
}

/** Mints a projection number. Process-local and never stored: a projection lives on a page, not in a file. */
internal fun newProjection(): Int = projectionCounter.incrementAndGet()

private val projectionCounter = AtomicInteger()

/** What makes two projections, decoded on either side of a rebuild, the same projection. */
private data class ProjectionIdentity(val strokeId: String, val ordinal: Int, val bounds: InkBounds?)

/**
 * This page, wearing the projection numbers [previous] gave the projections it still holds: the same
 * row, the same one of that row's projections in draw order, the same page rectangle. A rebuild mints
 * new numbers, which would silently drop a selection the user is holding; anything an operation
 * changed changes its bounds, and so still gets a new number.
 */
public fun List<PageStroke>.keepingProjectionsOf(previous: List<PageStroke>): List<PageStroke> {
    if (isEmpty() || previous.isEmpty()) return this
    val ordinals = HashMap<String, Int>()
    val held = HashMap<ProjectionIdentity, Int>(previous.size)
    previous.forEach { stroke ->
        val ordinal = ordinals.getOrDefault(stroke.id, 0)
        ordinals[stroke.id] = ordinal + 1
        held[ProjectionIdentity(stroke.id, ordinal, stroke.pageBounds)] = stroke.projection
    }
    ordinals.clear()
    return map { stroke ->
        val ordinal = ordinals.getOrDefault(stroke.id, 0)
        ordinals[stroke.id] = ordinal + 1
        val kept = held[ProjectionIdentity(stroke.id, ordinal, stroke.pageBounds)]
        if (kept == null || kept == stroke.projection) stroke else stroke.copy(projection = kept)
    }
}

/**
 * Whether this stroke still has any geometry. `subtract` can cut a mesh away entirely, and Ink's
 * shape comparisons abort the process on an empty mesh (`CHECK failed: !meshes_.empty()`) rather
 * than answer, so every comparison is guarded by this. `computeBoundingBox` is the safe test.
 */
public val Stroke.hasGeometry: Boolean get() = shape.computeBoundingBox() != null

/**
 * Whether this projection's page-space geometry is touched at all by [mask]. Guarded on both sides,
 * since it runs over every stroke on a page and one empty mesh would abort the process.
 */
public fun PageStroke.touches(mask: Stroke): Boolean {
    if (!stroke.hasGeometry || !mask.hasGeometry) return false
    return stroke.shape.computeCoverageIsGreaterThan(
        other = mask.shape,
        coverageThreshold = 0f,
        otherShapeToThis = pageToStrokeTransform(),
    )
}

/** The rows on this page that [mask] touches: what an erase made now is stored as applying to. */
public fun List<PageStroke>.targetsFor(mask: Stroke): List<String> =
    filter { it.touches(mask) }.map(PageStroke::id).distinct()

/**
 * Whether this stroke can only be drawn from its mesh's outlines. `SelfOverlap.DISCARD` forces the
 * path renderer, which draws nothing for a mesh without outlines, and `Stroke.split` returns pieces
 * without them: splitting such a stroke would blank it.
 */
private val Stroke.isDrawnFromOutlines: Boolean
    get() = brush.family.coats.any { coat -> coat.paintPreferences.all { it.selfOverlap == SelfOverlap.DISCARD } }

/**
 * Replays a Normal erase: [mask] is cut out of the projections of [targetIds]. Each disconnected
 * piece of a cut stroke becomes its own projection, so a later Object erase or lasso can take one of
 * them — except a stroke drawn from its outlines, which stays one projection however it is cut.
 */
@OptIn(ExperimentalInkEraserApi::class)
public fun List<PageStroke>.subtract(mask: Stroke, targetIds: Collection<String>): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty() || !mask.hasGeometry) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else {
            val cut = pageStroke.stroke.subtract(
                maskShape = mask.shape,
                maskToWorldTransform = AffineTransform.IDENTITY,
                strokeToWorldTransform = pageStroke.strokeToPageTransform(),
            )
            if (cut.isDrawnFromOutlines) {
                // Erased down to nothing is erased, not an invisible stroke that aborts the next
                // comparison it meets. What is left is new geometry, so a new projection.
                if (cut.hasGeometry) listOf(pageStroke.copy(stroke = cut, projection = newProjection())) else emptyList()
            } else {
                cut.split(strokeToWorldTransform = pageStroke.strokeToPageTransform(), tolerance = 0f)
                    .map { component -> pageStroke.copy(stroke = component, projection = newProjection()) }
            }
        }
    }
}

/**
 * Replays an Object erase: every disconnected piece of [targetIds]'s projections that [mask] touches
 * is removed, and each surviving piece becomes its own projection. A stroke drawn from its outlines
 * is never split, so touching it removes all of it.
 */
@OptIn(ExperimentalInkEraserApi::class)
public fun List<PageStroke>.eraseObjects(mask: Stroke, targetIds: Collection<String>): List<PageStroke> {
    val targets = targetIds.toSet()
    if (targets.isEmpty() || !mask.hasGeometry) return this
    return flatMap { pageStroke ->
        if (pageStroke.id !in targets) {
            listOf(pageStroke)
        } else if (pageStroke.stroke.isDrawnFromOutlines) {
            if (pageStroke.touches(mask)) emptyList() else listOf(pageStroke)
        } else {
            pageStroke.stroke
                .split(strokeToWorldTransform = pageStroke.strokeToPageTransform(), tolerance = 0f)
                .filter { it.hasGeometry }
                .filterNot { component ->
                    component.shape.computeCoverageIsGreaterThan(
                        other = mask.shape,
                        coverageThreshold = 0f,
                        otherShapeToThis = pageStroke.pageToStrokeTransform(),
                    )
                }
                .map { component -> pageStroke.copy(stroke = component, projection = newProjection()) }
        }
    }
}

/** One stored Object erase standing for "remove this piece", and the page it leaves. */
public data class InkPieceErase(val mask: Stroke, val rowId: String, val after: List<PageStroke>)

/** What deleting a set of projections costs: some erases, some whole rows, and the page they leave. */
public data class InkProjectionDelete(
    val erases: List<InkPieceErase>,
    /** Rows nothing of which survives: the caller tombstones these rather than erasing them. */
    val wholeRows: List<String>,
    val after: List<PageStroke>,
)

/**
 * How to delete exactly [held]. A piece of ink has no stored form of its own, so removing one piece
 * of a row is another erase: an Object erase with a dot mask on that piece, proved to take it and
 * none of the row's other pieces (see [pointOnInk]). A row every piece of which is held is
 * tombstoned instead. One erase per piece, since a mask is one continuous path and a path visiting
 * two pieces would run through the gap between them.
 */
public fun List<PageStroke>.planProjectionDelete(held: Set<InkProjectionKey>): InkProjectionDelete {
    val byRow = groupBy(PageStroke::id)
    val wholeRows = byRow.filterValues { pieces -> pieces.all { it.projectionKey in held } }.keys.toList()
    var current = this
    val erases = mutableListOf<InkPieceErase>()
    byRow.forEach { (rowId, pieces) ->
        val doomed = pieces.filter { it.projectionKey in held }
        if (doomed.isEmpty() || doomed.size == pieces.size) return@forEach
        val survivors = pieces.filterNot { it.projectionKey in held }
        doomed.forEach { piece ->
            // Built against the original pieces and applied to the running page: a mask is geometry,
            // and does not care that the first cut renumbered the projections.
            val mask = piece.eraserDotAvoiding(survivors) ?: return@forEach
            current = current.eraseObjects(mask, listOf(rowId))
            erases += InkPieceErase(mask, rowId, current)
        }
    }
    val after = if (wholeRows.isEmpty()) {
        current
    } else {
        val wholeRowIds = wholeRows.toSet()
        current.filterNot { it.id in wholeRowIds }
    }
    return InkProjectionDelete(erases, wholeRows, after)
}

/**
 * A round mask that takes this projection and none of [others], or null if none was proved. The
 * smallest dot is tried first and doubled until it touches this piece; if that size also reaches
 * another, no larger one would do better, and the piece is left rather than risk erasing ink nobody
 * chose. Proved on the mask as it comes back through the codec, which is what replay applies.
 */
private fun PageStroke.eraserDotAvoiding(others: List<PageStroke>): Stroke? {
    val point = pointOnInk() ?: return null
    val inputs = MutableStrokeInputBatch().apply { add(InputToolType.UNKNOWN, point.x, point.y, 0L) }.toImmutable()
    var size = DOT_MASK_MIN_DP
    while (size <= DOT_MASK_MAX_DP) {
        val mask = ViveInkCodec.reloadedEraseMask(inputs, size)
        if (mask != null && mask.hasGeometry && touches(mask)) {
            return if (others.none { it.touches(mask) }) mask else null
        }
        size *= 2f
    }
    return null
}

/**
 * A page-space point certainly on this projection's ink, or null once it has no mesh. Found by
 * asking the mesh through public API — a probe triangle's coverage — first at each of the stroke's
 * own input samples, which run down the middle of its ink, then over its rectangle for a sliver too
 * short to hold a sample.
 */
public fun PageStroke.pointOnInk(): InkPoint? {
    val bounds = pageBounds ?: return null
    val inputs = stroke.inputs
    val sample = StrokeInput()
    repeat(inputs.size) { index ->
        inputs.populate(index, sample)
        val point = InkPoint(sample.x * scaleX + offsetX, sample.y * scaleY + offsetY)
        if (covers(point)) return point
    }
    val step = maxOf(PROBE_REACH, minOf(bounds.right - bounds.left, bounds.bottom - bounds.top) / 2f)
    var y = bounds.top
    while (y <= bounds.bottom) {
        var x = bounds.left
        while (x <= bounds.right) {
            val point = InkPoint(x, y)
            if (covers(point)) return point
            x += step
        }
        y += step
    }
    return null
}

/** Whether this projection has ink at a page-space point, asked with a probe triangle around it. */
private fun PageStroke.covers(point: InkPoint): Boolean = stroke.shape.computeCoverageIsGreaterThan(
    triangle = ImmutableTriangle(
        ImmutableVec(point.x - PROBE_REACH, point.y - PROBE_REACH),
        ImmutableVec(point.x + PROBE_REACH, point.y - PROBE_REACH),
        ImmutableVec(point.x, point.y + PROBE_REACH),
    ),
    coverageThreshold = 0f,
    triangleToThis = pageToStrokeTransform(),
)

/** How far a probe reaches around its point: the mesh's own tolerance, [ViveBrushes.EPSILON]. */
private const val PROBE_REACH = 0.25f

/** The smallest dot a piece is deleted with, in page units. */
private const val DOT_MASK_MIN_DP = 1f

/** Where growing stops: past this a dot is no smaller than the cut that made the pieces. */
private const val DOT_MASK_MAX_DP = 4f

/**
 * Rebinds [ids]'s strokes to a brush of [colorArgb]. Picking a colour makes it deliberate, so the
 * automatic flag becomes false.
 */
public fun List<PageStroke>.recolor(ids: Collection<String>, colorArgb: Int): List<PageStroke> {
    val targets = ids.toSet()
    return map { pageStroke ->
        if (pageStroke.id in targets) {
            pageStroke.copy(
                stroke = pageStroke.stroke.copy(pageStroke.stroke.brush.copyWithColorIntArgb(colorArgb)),
                colorFollowsTheme = false,
            )
        } else {
            pageStroke
        }
    }
}

/** Moves rows into groups (or out of them, for null); the geometry is untouched. */
public fun List<PageStroke>.regroup(groups: Map<String, String?>): List<PageStroke> = map { stroke ->
    if (stroke.id in groups) stroke.copy(groupId = groups[stroke.id]) else stroke
}

/**
 * A self-contained copy moved by ([dx], [dy]), with the page transform baked into its inputs: what a
 * paste stores. Storage quantizes inputs, and a decoded batch can hold samples at the same position
 * and time that a new batch refuses, as can a transform small enough to collapse positions; those
 * carry no geometry and are dropped.
 */
public fun PageStroke.translatedCopy(dx: Float, dy: Float): Stroke {
    val moved = MutableStrokeInputBatch()
    val source = stroke.inputs
    val seen = HashSet<InputTripletKey>(source.size)
    repeat(source.size) { index ->
        val input = source[index]
        val x = input.x * scaleX + offsetX + dx
        val y = input.y * scaleY + offsetY + dy
        if (!seen.add(InputTripletKey(x.inputKeyBits(), y.inputKeyBits(), input.elapsedTimeMillis))) return@repeat
        moved.add(
            type = input.toolType,
            x = x,
            y = y,
            elapsedTimeMillis = input.elapsedTimeMillis,
            strokeUnitLengthCm = input.strokeUnitLengthCm,
            pressure = input.pressure,
            tiltRadians = input.tiltRadians,
            orientationRadians = input.orientationRadians,
        )
    }
    moved.setNoiseSeed(source.getNoiseSeed())
    return Stroke(stroke.brush, moved.toImmutable())
}

private data class InputTripletKey(val xBits: Int, val yBits: Int, val elapsedTimeMillis: Long)

/** Ink compares positions numerically, so the two bit patterns of zero are one key. */
private fun Float.inputKeyBits(): Int = if (this == 0f) 0 else toRawBits()

/** Applies a live move to exactly the projections the gesture held. */
public fun List<PageStroke>.moveSelected(move: InkLassoMove): List<PageStroke> = map { stroke ->
    if (stroke.projectionKey in move.projections) {
        stroke.copy(offsetX = stroke.offsetX + move.dx, offsetY = stroke.offsetY + move.dy)
    } else {
        stroke
    }
}

/** Applies a live corner resize after each held projection's existing page transform. */
public fun List<PageStroke>.resizeSelected(resize: InkLassoResize): List<PageStroke> = map { stroke ->
    if (stroke.projectionKey in resize.projections) stroke.scaledAround(resize.anchor, resize.scaleX, resize.scaleY) else stroke
}

private fun PageStroke.scaledAround(anchor: InkPoint, x: Float, y: Float): PageStroke = copy(
    scaleX = scaleX * x,
    scaleY = scaleY * y,
    offsetX = anchor.x + (offsetX - anchor.x) * x,
    offsetY = anchor.y + (offsetY - anchor.y) * y,
)

/**
 * Replays a stored move: the projections of [targetIds] inside [path] move by ([dx], [dy]), held off
 * the page's origin corner by [PageBounds.clampTranslation]. Ink's final position is only known at
 * the end of replay, so this is where that rule is enforced for it; it also brings back ink that
 * builds without the rule dragged off the corner.
 */
public fun List<PageStroke>.replayMove(
    path: List<InkPoint>,
    targetIds: Collection<String>,
    dx: Float,
    dy: Float,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (path.size < 3 || targets.isEmpty()) return this
    val lasso = LassoShape(path)
    val selected = map { it.id in targets && lasso.contains(it) }
    val delta = movingBounds(selected)?.let { PageBounds.clampTranslation(it, dx, dy) } ?: InkPoint(dx, dy)
    return mapIndexed { index, stroke ->
        if (selected[index]) stroke.copy(offsetX = stroke.offsetX + delta.x, offsetY = stroke.offsetY + delta.y) else stroke
    }
}

/** Replays a stored resize against the projections of [targetIds] inside [path]. */
public fun List<PageStroke>.replayResize(
    path: List<InkPoint>,
    targetIds: Collection<String>,
    anchor: InkPoint,
    scaleX: Float,
    scaleY: Float,
): List<PageStroke> {
    val targets = targetIds.toSet()
    if (path.size < 3 || targets.isEmpty()) return this
    // Every stored move replays through here, and a plain move's scale of 1 changes nothing.
    if (scaleX == 1f && scaleY == 1f) return this
    val lasso = LassoShape(path)
    val selected = map { it.id in targets && lasso.contains(it) }
    val scale = movingBounds(selected)?.let { PageBounds.clampScale(it, anchor, scaleX, scaleY) } ?: InkPoint(scaleX, scaleY)
    return mapIndexed { index, stroke -> if (selected[index]) stroke.scaledAround(anchor, scale.x, scale.y) else stroke }
}

/** The rectangle around everything a replayed operation is about to move. */
private fun List<PageStroke>.movingBounds(selected: List<Boolean>): InkBounds? =
    filterIndexed { index, _ -> selected[index] }.mapNotNull(PageStroke::pageBounds).unionBounds()
