package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.SpatialIndex
import java.util.Collections
import java.util.IdentityHashMap

/** One finished stroke, positioned and optionally recoloured within a scene. */
public data class InkSceneStroke(
    public val stroke: Stroke,
    public val strokeToScene: AffineTransform = AffineTransform.IDENTITY,
    public val colorArgb: Int? = null,
)

/**
 * An immutable page snapshot indexed by transformed stroke bounds. Construct it when the page's
 * strokes change and reuse it while panning or zooming. Only nearby strokes are sent to the path
 * renderer, in the original drawing order; their paths remain in stroke coordinates and reusable.
 * Mutable input transforms are copied so later mutations cannot invalidate the spatial index.
 */
public class InkScene(strokes: List<InkSceneStroke>) {
    public val strokes: List<InkSceneStroke> = Collections.unmodifiableList(strokes.map {
        requireFinite(it.strokeToScene)
        it.copy(strokeToScene = it.strokeToScene.toImmutable())
    })
    private val index = SpatialIndex.of(this.strokes) { item ->
        item.stroke.shape.computeBoundingBox()?.let { box ->
            val bounds = transformedBounds(Rect(box.xMin, box.yMin, box.xMax, box.yMax), item.strokeToScene)
            ImmutableBox.fromTwoPoints(ImmutableVec(bounds.left, bounds.top), ImmutableVec(bounds.right, bounds.bottom))
        }
    }

    /**
     * Conservative candidates for [viewport] in canvas coordinates, in drawing order. The viewport
     * includes the renderer's one-pixel antialiasing margin before its inverse transform; this also
     * handles rotations, shear, negative scales and nonuniform zoom. The renderer tests candidates
     * again against their canvas bounds. [sceneToCanvas] must be finite and invertible.
     */
    public fun visibleStrokes(
        viewport: Rect,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
    ): List<InkSceneStroke> {
        requireFinite(sceneToCanvas)
        require(viewport.left.isFinite() && viewport.top.isFinite() && viewport.right.isFinite() && viewport.bottom.isFinite()) {
            "viewport must be finite"
        }
        if (viewport.isEmpty) return emptyList()
        val inverse = sceneToCanvas.computeInverse()
        requireFinite(inverse)
        val bounds = transformedBounds(viewport.inflate(1f), inverse).inflate(0.01f)
        return index.query(bounds.left, bounds.top, bounds.right, bounds.bottom)
    }

    /** Draws visible strokes, returning how many the renderer actually drew. */
    public fun draw(
        canvas: Canvas,
        renderer: InkPathRenderer,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect,
    ): Int = draw(canvas, renderer, sceneToCanvas, viewport, emptySet())

    /** Draws a scene using the full mesh renderer or another InkRenderer implementation. */
    public fun draw(
        canvas: Canvas,
        renderer: InkRenderer,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect,
    ): Int = draw(canvas, renderer, sceneToCanvas, viewport, emptySet())

    /**
     * Draws visible strokes except [excludedStrokes], preserving their original drawing order.
     * Exclusions are checked only for viewport candidates, so changing them reuses this scene and
     * its spatial index. Use entries from [strokes] as keys: mutable transforms supplied to the
     * constructor were snapshotted there. Keys are matched by instance identity, allowing equal
     * stroke occurrences to be excluded independently. Use an identity-backed set when excluding
     * multiple equal occurrences together.
     */
    public fun draw(
        canvas: Canvas,
        renderer: InkPathRenderer,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect,
        excludedStrokes: Set<InkSceneStroke>,
    ): Int = draw(canvas, renderer as InkRenderer, sceneToCanvas, viewport, excludedStrokes)

    /** Draws visible, non-excluded occurrences with either renderer, in stroke order. */
    public fun draw(
        canvas: Canvas,
        renderer: InkRenderer,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect,
        excludedStrokes: Set<InkSceneStroke>,
    ): Int {
        val exclusions = if (excludedStrokes.isEmpty()) emptySet() else
            Collections.newSetFromMap(IdentityHashMap<InkSceneStroke, Boolean>(excludedStrokes.size)).apply {
                addAll(excludedStrokes)
            }
        val transform = MutableAffineTransform()
        var drawn = 0
        for (item in visibleStrokes(viewport, sceneToCanvas)) {
            if (item in exclusions) continue
            AffineTransform.multiply(sceneToCanvas, item.strokeToScene, transform)
            if (renderer.render(canvas, item.stroke, transform, viewport, item.colorArgb)) drawn++
        }
        return drawn
    }
}

/** Draws a finished page snapshot in this scope's pixel coordinates with spatial culling. */
public fun DrawScope.drawInkScene(
    scene: InkScene,
    renderer: InkPathRenderer,
    sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
): Int = drawInkScene(scene, renderer, sceneToCanvas, emptySet())

/** Draws visible scene strokes, matching [excludedStrokes] to [scene]'s strokes by instance identity. */
public fun DrawScope.drawInkScene(
    scene: InkScene,
    renderer: InkPathRenderer,
    sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
    excludedStrokes: Set<InkSceneStroke>,
): Int = scene.draw(drawContext.canvas, renderer, sceneToCanvas, Rect(0f, 0f, size.width, size.height), excludedStrokes)

/** Draws a scene with full mesh effects and viewport culling. */
public fun DrawScope.drawInkScene(
    scene: InkScene,
    renderer: InkRenderer,
    sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
    excludedStrokes: Set<InkSceneStroke> = emptySet(),
): Int = scene.draw(drawContext.canvas, renderer, sceneToCanvas, Rect(0f, 0f, size.width, size.height), excludedStrokes)

private fun requireFinite(transform: AffineTransform) {
    require(transform.m00.isFinite() && transform.m10.isFinite() && transform.m20.isFinite() &&
        transform.m01.isFinite() && transform.m11.isFinite() && transform.m21.isFinite()) {
        "scene transforms must be finite"
    }
}

private fun transformedBounds(rect: Rect, transform: AffineTransform): Rect {
    fun x(x: Float, y: Float): Float = transform.m00 * x + transform.m10 * y + transform.m20
    fun y(x: Float, y: Float): Float = transform.m01 * x + transform.m11 * y + transform.m21
    val xs = floatArrayOf(x(rect.left, rect.top), x(rect.right, rect.top), x(rect.left, rect.bottom), x(rect.right, rect.bottom))
    val ys = floatArrayOf(y(rect.left, rect.top), y(rect.right, rect.top), y(rect.left, rect.bottom), y(rect.right, rect.bottom))
    require(xs.all(Float::isFinite) && ys.all(Float::isFinite)) { "transformed bounds must be finite" }
    return Rect(xs.min(), ys.min(), xs.max(), ys.max())
}
