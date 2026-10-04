/*
 * Portions derived from AndroidX CanvasPathRenderer and AndroidGraphicsConversionExtensions.
 * Copyright (C) 2024 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at https://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.SelfOverlap
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.Box
import androidx.ink.geometry.BoxAccumulator
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkMeshes
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/**
 * Draws the real Ink geometry as antialiased filled paths on Compose Desktop/Skia.
 *
 * Each coat is filled once with its paint's colour functions, as Android's CanvasPathRenderer does.
 * ANY and DISCARD paints without textures are supported. Vertex colour/opacity behaviours and
 * prediction fading are not represented by a uniform path fill; ACCUMULATE and textures are refused.
 * A split stroke has no outlines at the pinned Ink release, so its triangles form one winding path.
 *
 * Reuse an instance on one drawing thread. Finished shapes have a bounded cache in stroke coordinates,
 * shared by projections and recolours; panning and zooming do not rebuild paths. Live paths are held
 * weakly and updated when InProgressStroke's version changes. Call [clearCache] when changing pages
 * to release cached geometry promptly.
 */
public class InkPathRenderer(public val cacheCapacity: Int, public val cacheByteBudget: Long) {
    public constructor() : this(2048, DEFAULT_CACHE_BYTE_BUDGET)
    public constructor(cacheCapacity: Int = 2048) : this(cacheCapacity, DEFAULT_CACHE_BYTE_BUDGET)

    public companion object {
        /** Budget for estimated finished path storage, separate from live paths and Ink meshes. */
        public const val DEFAULT_CACHE_BYTE_BUDGET: Long = 64L * 1024 * 1024
    }

    init {
        require(cacheCapacity >= 0) { "cacheCapacity must be nonnegative" }
        require(cacheByteBudget >= 0L) { "cacheByteBudget must be nonnegative" }
    }

    private class CachedShape(val paths: List<InkRenderPath>, val bytes: Long)
    private val shapes = LinkedHashMap<PartitionedMesh, CachedShape>(16, 0.75f, true)
    private val collectedStrokes = ReferenceQueue<InProgressStroke>()
    private val live = HashMap<StrokeReference, LivePaths>()
    private val paint = Paint().apply { isAntiAlias = true; style = PaintingStyle.Fill }
    private val bounds = BoxAccumulator()
    private val coatBounds = BoxAccumulator()
    /** Total path builds since construction; pan and zoom should reuse cached paths. */
    public var pathBuildCount: Long = 0
        private set
    /** Number of finished meshes retained, bounded by [cacheCapacity]. */
    public val cachedShapeCount: Int get() = shapes.size

    /** Twice Skia's approximate path size, accounting conservatively for Compose's copied builder.
     * Excludes wrapper metadata, Ink meshes, and the current live geometry. Zero after [clearCache]. */
    public var cachedPathBytes: Long = 0L
        private set

    /** Finished shape retirements caused by either cache limit. */
    public var pathEvictionCount: Long = 0L
        private set

    /** Whether every coat has a texture-free ANY or DISCARD paint this renderer supports. */
    public fun canDraw(stroke: Stroke): Boolean = supported(stroke.brush)

    /** An unstarted stroke has nothing to draw; otherwise checks all its coats' paints. */
    public fun canDraw(stroke: InProgressStroke): Boolean = stroke.brush?.let(::supported) ?: true

    /**
     * Draws [stroke], applying [strokeToCanvas] on top of the canvas's existing transform.
     * [viewport], when supplied, is in the destination coordinates of [strokeToCanvas], before any
     * existing canvas transform. It culls bounds before constructing paths; canvas clipping is left
     * to the caller. [colorArgb] overrides the base brush colour before paint colour functions.
     * Returns false for empty or culled geometry. Unsupported paints throw before drawing any coat.
     */
    public fun draw(
        canvas: Canvas,
        stroke: Stroke,
        strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect? = null,
        colorArgb: Int? = null,
    ): Boolean {
        releaseCollectedPaths()
        val box = stroke.shape.computeBoundingBox() ?: return false
        if (stroke.inputs.size == 0) return false
        val matrix = strokeToCanvas.composeMatrix()
        if (!visible(box, matrix, viewport)) return false
        val paints = paints(stroke.brush)
        val cached = shapes[stroke.shape]
        val paths = cached?.paths ?: buildPaths(stroke.shape.getRenderGroupCount()) { group ->
            finishedPath(stroke.shape, group)
        }
        var retained = cached != null
        try {
            if (!retained && cacheCapacity > 0 && cacheByteBudget > 0L) {
                val bytes = paths.sumOf { it.approximateBytesUsed }
                if (bytes <= cacheByteBudget) {
                    while (shapes.size >= cacheCapacity || cachedPathBytes > cacheByteBudget - bytes) {
                        val oldest = shapes.entries.iterator()
                        val retired = oldest.next().value
                        retired.paths.forEach(InkRenderPath::close)
                        oldest.remove()
                        cachedPathBytes -= retired.bytes
                        pathEvictionCount++
                    }
                    shapes[stroke.shape] = CachedShape(paths, bytes)
                    cachedPathBytes += bytes
                    retained = true
                }
            }
            drawPaths(canvas, paths, paints, stroke.brush, matrix, colorArgb)
        } finally {
            if (!retained) paths.forEach(InkRenderPath::close)
        }
        return true
    }

    /** Draws the latest shape of [stroke]; call updateShape before drawing a new input batch. */
    public fun draw(
        canvas: Canvas,
        stroke: InProgressStroke,
        strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect? = null,
        colorArgb: Int? = null,
    ): Boolean {
        releaseCollectedPaths()
        val key = StrokeReference(stroke)
        val brush = stroke.brush
        if (brush == null || stroke.getInputCount() == 0) {
            live.remove(key)?.paths?.forEach(InkRenderPath::close)
            return false
        }
        bounds.reset()
        repeat(brush.family.coats.size) { bounds.add(stroke.populateMeshBounds(it, coatBounds).box) }
        val box = bounds.box ?: run {
            live.remove(key)?.paths?.forEach(InkRenderPath::close)
            return false
        }
        val matrix = strokeToCanvas.composeMatrix()
        if (!visible(box, matrix, viewport)) return false
        val paints = paints(brush)
        val cached = live[key]
        val paths = if (cached != null && cached.version == stroke.shapeVersion()) cached.paths else {
            buildPaths(brush.family.coats.size) { coat -> outlinePath(InkMeshes.outlines(stroke, coat)) }
                .also {
                    live[if (cached == null) StrokeReference(stroke, collectedStrokes) else key] =
                        LivePaths(stroke.shapeVersion(), it)
                    cached?.paths?.forEach(InkRenderPath::close)
                }
        }
        drawPaths(canvas, paths, paints, brush, matrix, colorArgb)
        return true
    }

    /** Drops both finished and live path caches. */
    public fun clearCache() {
        shapes.values.forEach { it.paths.forEach(InkRenderPath::close) }
        shapes.clear()
        cachedPathBytes = 0L
        live.values.forEach { it.paths.forEach(InkRenderPath::close) }
        live.clear()
        releaseCollectedPaths()
    }

    private fun supported(brush: Brush): Boolean = brush.family.coats.all { coat ->
        coat.paintPreferences.any(::supportedPaint)
    }

    private fun supportedPaint(paint: BrushPaint): Boolean = paint.textureLayers.isEmpty() &&
        (paint.selfOverlap == SelfOverlap.ANY || paint.selfOverlap == SelfOverlap.DISCARD)

    private fun paints(brush: Brush): List<BrushPaint> = brush.family.coats.mapIndexed { coat, value ->
        requireNotNull(value.paintPreferences.firstOrNull(::supportedPaint)) {
            "InkPathRenderer cannot draw coat $coat: requires a texture-free ANY or DISCARD paint"
        }
    }

    private fun drawPaths(canvas: Canvas, paths: List<InkRenderPath>, paints: List<BrushPaint>, brush: Brush, matrix: Matrix, colorArgb: Int?) {
        canvas.save()
        try {
            canvas.concat(matrix)
            paths.forEachIndexed { coat, path ->
                paint.color = paints[coat].composeColor(brush, colorArgb)
                canvas.drawPath(path.path, paint)
            }
        } finally {
            canvas.restore()
        }
    }

    private fun finishedPath(shape: PartitionedMesh, group: Int): InkRenderPath {
        val outlines = InkMeshes.outlines(shape, group)
        if (outlines.any { it.isNotEmpty() }) return outlinePath(outlines)
        // split() drops outlines. Fill all triangles together, with matching winding: this draws
        // their union without dark seams at shared edges or repeated alpha at self intersections.
        pathBuildCount++
        return buildInkPath {
            InkMeshes.triangles(shape, group).forEach { mesh ->
                val p = mesh.positions
                val indices = mesh.triangles
                for (t in indices.indices step 3) {
                    val a = indices[t] * 2
                    var b = indices[t + 1] * 2
                    var c = indices[t + 2] * 2
                    val area = (p[b] - p[a]) * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[a])
                    if (area == 0f) continue
                    if (area < 0f) { val swap = b; b = c; c = swap }
                    moveTo(p[a], p[a + 1])
                    lineTo(p[b], p[b + 1])
                    lineTo(p[c], p[c + 1])
                    closePath()
                }
            }
        }
    }

    private fun outlinePath(outlines: List<FloatArray>): InkRenderPath {
        pathBuildCount++
        return outlineInkPath(outlines)
    }

    private fun buildPaths(count: Int, build: (Int) -> InkRenderPath): List<InkRenderPath> {
        val result = ArrayList<InkRenderPath>(count)
        try {
            repeat(count) { result.add(build(it)) }
            return result
        } catch (failure: Throwable) {
            result.forEach(InkRenderPath::close)
            throw failure
        }
    }

    private fun releaseCollectedPaths() {
        while (true) {
            val key = collectedStrokes.poll() ?: return
            live.remove(key)?.paths?.forEach(InkRenderPath::close)
        }
    }

    private fun visible(box: Box, matrix: Matrix, viewport: Rect?): Boolean = viewport == null ||
        matrix.map(Rect(box.xMin, box.yMin, box.xMax, box.yMax)).inflate(1f).overlaps(viewport)

    private class LivePaths(val version: Long, val paths: List<InkRenderPath>)

    private class StrokeReference(stroke: InProgressStroke, queue: ReferenceQueue<InProgressStroke>? = null) :
        WeakReference<InProgressStroke>(stroke, queue) {
        private val hash = System.identityHashCode(stroke)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other ||
            (other is StrokeReference && get()?.let { it === other.get() } == true)
    }
}

/** Draws Ink in this scope's pixel coordinates, with viewport culling. Reuse [renderer] across frames. */
public fun DrawScope.drawInk(
    renderer: InkPathRenderer,
    stroke: Stroke,
    strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
    colorArgb: Int? = null,
): Boolean = renderer.draw(drawContext.canvas, stroke, strokeToCanvas, Rect(0f, 0f, size.width, size.height), colorArgb)

/** Draws live Ink in this scope's pixel coordinates, with viewport culling. */
public fun DrawScope.drawInk(
    renderer: InkPathRenderer,
    stroke: InProgressStroke,
    strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
    colorArgb: Int? = null,
): Boolean = renderer.draw(drawContext.canvas, stroke, strokeToCanvas, Rect(0f, 0f, size.width, size.height), colorArgb)

private fun AffineTransform.composeMatrix(): Matrix {
    require(m00.isFinite() && m10.isFinite() && m20.isFinite() && m01.isFinite() && m11.isFinite() && m21.isFinite()) {
        "strokeToCanvas must be finite"
    }
    return Matrix().also {
        it[0, 0] = m00; it[1, 0] = m10; it[3, 0] = m20
        it[0, 1] = m01; it[1, 1] = m11; it[3, 1] = m21
    }
}
