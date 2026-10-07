@file:OptIn(androidx.ink.brush.ExperimentalInkAnimationApi::class)

package com.vivenotes.byteink.compose

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.SelfOverlap
import androidx.ink.geometry.Box
import androidx.ink.geometry.BoxAccumulator
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.InProgressStroke
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Surface
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Owned by one authoring panel on its drawing thread. The destination must be the physical,
 * untransformed sRGB N32 premultiplied canvas used by Skiko's software Swing painter. The panel
 * owns its engines' updated-region accumulator; another consumer must not reset that accumulator.
 * Finished pixels include the clear color so translucent drawing has the same rounding as a full
 * redraw. Only complete successful paints commit damage consumption and presented-stroke state.
 */
internal class InkRetainedAuthoringRaster(pixelBudgetBytes: Long = DEFAULT_PIXEL_BUDGET_BYTES) : AutoCloseable {
    companion object {
        const val DEFAULT_PIXEL_BUDGET_BYTES: Long = 64L * 1024 * 1024
    }

    var pixelBudgetBytes: Long = pixelBudgetBytes
        set(value) {
            require(value >= 0L) { "Retained pixel budget must be nonnegative" }
            if (field != value) { field = value; clear() }
        }
    init { require(pixelBudgetBytes >= 0L) { "Retained pixel budget must be nonnegative" } }

    var retainedPixelBytes: Long = 0L
        private set
    var backgroundBuildCount: Long = 0L
        private set
    var fullRedrawCount: Long = 0L
        private set
    var dirtyRedrawCount: Long = 0L
        private set
    var lastRedrawnPixelCount: Long = 0L
        private set

    private data class Damage(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val pixels: Long get() = (right - left).toLong() * (bottom - top)
        fun union(other: Damage): Damage = Damage(minOf(left, other.left), minOf(top, other.top),
            maxOf(right, other.right), maxOf(bottom, other.bottom))
        fun intersects(other: Damage): Boolean = left < other.right && right > other.left &&
            top < other.bottom && bottom > other.top
    }
    private data class Presented(
        val version: Long,
        val transform: ImmutableAffineTransform,
        val bounds: Damage?,
        val outlined: Boolean,
    )
    private val presented = IdentityHashMap<InProgressStroke, Presented>()
    private val scratch = BoxAccumulator()
    private val totalBounds = BoxAccumulator()
    private val copyPaint = Paint().apply { blendMode = BlendMode.SRC; isAntiAlias = false }
    private var background: Surface? = null
    private var frame: Surface? = null
    private var width = 0
    private var height = 0
    private var scale = 0f
    private var color = 0
    private var renderer: InkRenderer? = null
    private var rendererVersion = 0L
    private var contentInvalid = true
    private var contentRevision = 0L
    private var pendingDamage: Damage? = null
    private var closed = false

    fun invalidateContent() { contentInvalid = true; contentRevision++ }

    /** Capture visible old pixels before finish/cancel clears or reuses the native engine. */
    fun retireLiveStroke(stroke: InProgressStroke) {
        presented.remove(stroke)?.bounds?.let { addPending(it) }
    }

    fun draw(
        canvas: org.jetbrains.skia.Canvas,
        width: Int,
        height: Int,
        scale: Float,
        clearColorArgb: Int,
        renderer: InkRenderer,
        liveStrokes: List<InkLiveStroke>,
        drawContent: (Canvas, Int, Int) -> Unit,
    ) {
        check(!closed) { "Retained authoring raster is closed" }
        require(width >= 0 && height >= 0) { "Physical dimensions must be nonnegative" }
        require(scale.isFinite() && scale > 0f) { "Physical scale must be finite and positive" }
        lastRedrawnPixelCount = 0L
        if (width == 0 || height == 0) { clear(); return }
        val pixelCount = width.toLong() * height
        // Each persistent N32 raster must fit Skia's Int row/storage limits. Divide rather than
        // multiply before comparing, so untrusted large dimensions cannot overflow a Long.
        if ((renderer !is InkMeshRenderer && renderer !is InkPathRenderer) ||
            pixelCount > minOf(Int.MAX_VALUE / 4L, pixelBudgetBytes / 8L)) {
            clear()
            drawFull(canvas, width, height, scale, clearColorArgb, renderer, liveStrokes, drawContent)
            fullRedrawCount++
            backgroundBuildCount++
            lastRedrawnPixelCount = pixelCount
            return
        }
        if (this.width != width || this.height != height || this.scale != scale) {
            clear()
            val info = ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL)
            val base = Surface.makeRaster(info)
            try { frame = Surface.makeRaster(info); background = base }
            catch (failure: Throwable) { base.close(); throw failure }
            this.width = width; this.height = height; this.scale = scale
            retainedPixelBytes = pixelCount * 8L
        }
        if (this.color != clearColorArgb || this.renderer !== renderer || rendererVersion != renderer.renderVersion) {
            contentInvalid = true
        }
        val whole = Damage(0, 0, width, height)
        val next = IdentityHashMap<InProgressStroke, Presented>()
        var damage = pendingDamage
        fun add(value: Damage?) { if (value != null) damage = damage?.union(value) ?: value }
        liveStrokes.forEach { live ->
            val transform = live.strokeToView.toImmutable()
            val bounds = meshBounds(live, renderer, width, height, scale)
            val version = live.stroke.shapeVersion()
            val prior = presented[live.stroke]
            val outlined = renderer is InkPathRenderer || hasDiscardPaint(live.stroke)
            next[live.stroke] = Presented(version, transform, bounds, outlined)
            when {
                prior == null || prior.transform != transform -> { add(prior?.bounds); add(bounds) }
                prior.version != version -> {
                    val updated = screenBounds(live.stroke.populateUpdatedRegion(scratch).box,
                        transform, renderer, width, height, scale)
                    // Skia's filled-outline tessellation/AA can change prefix coverage even when
                    // the native mesh damage only touches the tail. Repaint the whole path extent.
                    if (outlined || updated == null || hasGlobalTextureChange(live.stroke)) {
                        add(prior.bounds); add(bounds)
                    } else add(updated)
                }
            }
        }
        presented.forEach { (stroke, prior) -> if (!next.containsKey(stroke)) add(prior.bounds) }
        val full = contentInvalid
        val contentAtStart = contentRevision
        val rendererAtStart = renderer.renderVersion
        if (full) damage = whole
        else if (damage != null) {
            // Clipping a filled outline can alter its AA coverage even without a version change.
            // Cover every intersecting outline in full, including transitively overlapping ones.
            var expanded: Boolean
            do {
                expanded = false
                next.values.forEach { shown ->
                    val bounds = shown.bounds
                    val dirty = requireNotNull(damage)
                    if (shown.outlined && bounds != null && bounds.intersects(dirty)) {
                        val union = dirty.union(bounds)
                        if (union != dirty) { damage = union; expanded = true }
                    }
                }
            } while (expanded)
        }
        try {
            if (full) {
                val base = requireNotNull(background).canvas
                base.clear(clearColorArgb)
                drawLogical(base, scale) { logical -> drawContent(logical, (width / scale).toInt(), (height / scale).toInt()) }
            }
            damage?.let { dirty ->
                val target = requireNotNull(frame).canvas
                target.save()
                try {
                    target.clipRect(dirty.left.toFloat(), dirty.top.toFloat(), dirty.right.toFloat(), dirty.bottom.toFloat(), false)
                    requireNotNull(background).makeImageSnapshot().use { target.drawImage(it, 0f, 0f, copyPaint) }
                    drawLogical(target, scale) { logical ->
                        val viewport = Rect(0f, 0f, (width / scale).toInt().toFloat(), (height / scale).toInt().toFloat())
                        liveStrokes.forEach { live ->
                            val bounds = next[live.stroke]?.bounds
                            if (bounds != null && bounds.intersects(dirty)) renderer.render(logical, live.stroke, live.strokeToView, viewport)
                        }
                    }
                } finally { target.restore() }
            }
            // The painter cleared its backing surface. Blit even when no engine pixels changed.
            // Scoped full snapshots share owned raster pixels. Releasing them before the next
            // write avoids copy-on-write; Surface.draw's mutable bitmap path can copy instead.
            requireNotNull(frame).makeImageSnapshot().use { canvas.drawImage(it, 0f, 0f, copyPaint) }
            liveStrokes.forEach { it.stroke.resetUpdatedRegion() }
            presented.clear(); presented.putAll(next)
            pendingDamage = null
            contentInvalid = contentRevision != contentAtStart
            this.color = clearColorArgb; this.renderer = renderer; rendererVersion = rendererAtStart
            if (full) { backgroundBuildCount++; fullRedrawCount++ }
            else if (damage != null) dirtyRedrawCount++
            lastRedrawnPixelCount = damage?.pixels ?: 0L
        } catch (failure: Throwable) {
            // A partial callback/renderer draw is never a reusable frame. Native damage has not
            // been consumed, and the next successful attempt rebuilds the complete background.
            clear()
            throw failure
        }
    }

    /** Discard physical pixels on detach/budget/view changes without closing the helper. */
    fun clear() {
        background?.close(); background = null
        frame?.close(); frame = null
        retainedPixelBytes = 0L
        width = 0; height = 0; scale = 0f
        renderer = null
        presented.clear(); pendingDamage = null; contentInvalid = true
    }

    override fun close() {
        if (closed) return
        clear(); copyPaint.close(); closed = true
    }

    private fun addPending(damage: Damage) { pendingDamage = pendingDamage?.union(damage) ?: damage }

    private fun meshBounds(live: InkLiveStroke, renderer: InkRenderer, width: Int, height: Int, scale: Float): Damage? {
        totalBounds.reset()
        repeat(live.stroke.getBrushCoatCount()) { totalBounds.add(live.stroke.populateMeshBounds(it, scratch)) }
        return screenBounds(totalBounds.box, live.strokeToView.toImmutable(), renderer, width, height, scale)
    }

    private fun screenBounds(box: Box?, t: ImmutableAffineTransform, renderer: InkRenderer,
        width: Int, height: Int, scale: Float): Damage? {
        if (box == null) return null
        var left = Double.POSITIVE_INFINITY; var top = Double.POSITIVE_INFINITY
        var right = Double.NEGATIVE_INFINITY; var bottom = Double.NEGATIVE_INFINITY
        var mappingMagnitude = 0.0
        repeat(4) { corner ->
            val x = if (corner and 1 == 0) box.xMin.toDouble() else box.xMax.toDouble()
            val y = if (corner and 2 == 0) box.yMin.toDouble() else box.yMax.toDouble()
            val px = (t.m00 * x + t.m10 * y + t.m20) * scale
            val py = (t.m01 * x + t.m11 * y + t.m21) * scale
            mappingMagnitude = maxOf(mappingMagnitude,
                (abs(t.m00 * x) + abs(t.m10 * y) + abs(t.m20.toDouble())) * scale,
                (abs(t.m01 * x) + abs(t.m11 * y) + abs(t.m21.toDouble())) * scale)
            left = minOf(left, px); top = minOf(top, py); right = maxOf(right, px); bottom = maxOf(bottom, py)
        }
        // Derivative AA displaces vertex positions. Under shear/anisotropy the two outsets can
        // grow with the affine condition number; a fixed one-pixel expansion is insufficient.
        val a = t.m00.toDouble(); val b = t.m10.toDouble(); val d = t.m01.toDouble(); val e = t.m11.toDouble()
        val det = abs(a * e - b * d)
        val norm = a * a + b * b + d * d + e * e
        val discriminant = maxOf(0.0, norm * norm - 4.0 * det * det)
        val condition = if (det == 0.0) Double.POSITIVE_INFINITY else (norm + sqrt(discriminant)) / (2.0 * det)
        // Include float matrix/vertex rounding, especially when huge coordinates cancel after
        // translation. Double AABB mapping alone can otherwise understate the actual Skia pixels.
        val outset = (if (renderer is InkMeshRenderer) 1.414214 * condition + 1.0 else 1.0) + mappingMagnitude * 0.000001
        if (!left.isFinite() || !top.isFinite() || !right.isFinite() || !bottom.isFinite() || !outset.isFinite()) {
            return Damage(0, 0, width, height)
        }
        val l = floor(left - outset).coerceIn(0.0, width.toDouble()).toInt()
        val r = ceil(right + outset).coerceIn(0.0, width.toDouble()).toInt()
        val u = floor(top - outset).coerceIn(0.0, height.toDouble()).toInt()
        val v = ceil(bottom + outset).coerceIn(0.0, height.toDouble()).toInt()
        return if (l < r && u < v) Damage(l, u, r, v) else null
    }

    private fun hasGlobalTextureChange(stroke: InProgressStroke): Boolean = stroke.brush?.family?.coats?.any { coat ->
        coat.paintPreferences.any { paint -> paint.textureLayers.any { layer ->
            layer is BrushPaint.TilingTexture && layer.origin == BrushPaint.TilingTexture.Origin.LAST_STROKE_INPUT ||
                layer is BrushPaint.StampingTexture && layer.animationDurationMillis > 0L
        } }
    } == true

    // Paint preference/texture availability can select a different coat path at runtime. Any
    // DISCARD preference is conservatively eligible for the renderer's filled-outline branch.
    private fun hasDiscardPaint(stroke: InProgressStroke): Boolean = stroke.brush?.family?.coats?.any { coat ->
        coat.paintPreferences.any { it.selfOverlap == SelfOverlap.DISCARD }
    } == true

    private fun drawFull(canvas: org.jetbrains.skia.Canvas, width: Int, height: Int, scale: Float,
        color: Int, renderer: InkRenderer, live: List<InkLiveStroke>, drawContent: (Canvas, Int, Int) -> Unit) {
        canvas.clear(color)
        drawLogical(canvas, scale) { logical ->
            drawContent(logical, (width / scale).toInt(), (height / scale).toInt())
            val viewport = Rect(0f, 0f, (width / scale).toInt().toFloat(), (height / scale).toInt().toFloat())
            live.forEach { renderer.render(logical, it.stroke, it.strokeToView, viewport) }
        }
    }

    private inline fun drawLogical(canvas: org.jetbrains.skia.Canvas, scale: Float, draw: (Canvas) -> Unit) {
        canvas.save()
        try { canvas.scale(scale, scale); draw(canvas.asComposeCanvas()) } finally { canvas.restore() }
    }
}
