package com.vivenotes.byteink.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.skiaCanvas
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.ceil

/**
 * Caches finished scene views as transparent Skia rasters in physical viewport pixels.
 *
 * Reuse on one drawing thread. An unchanged page and view only require one image draw, avoiding
 * repeated geometry rasterization beneath a live stroke even when the path cache cannot hold the
 * entire page. Scene identity, transform values, viewport, physical scale and excluded occurrences
 * invalidate the raster.
 * Every new view is rendered at its requested resolution; revisiting a retained view reuses it.
 * The default retains one view. A larger [cacheCapacity] enables pan/zoom return reuse within
 * [pixelBudgetBytes]. Least recently used images close before a cacheable replacement is allocated.
 * A view exceeding the retention budget is drawn once and closed, without retaining its image.
 *
 * The temporary surface is closed after taking its immutable snapshot. Replacement, [clearCache]
 * and [close] close the retained image deterministically. Layering translucent paths into an
 * 8-bit premultiplied image can differ slightly from drawing them directly over the destination
 * because alpha compositing rounds at each step. The viewport is in the canvas's local pixels;
 * any existing canvas transform or clipping also applies to the resulting image. Pixel storage is
 * limited to the physical viewport, at most [Int.MAX_VALUE] bytes per raster.
 */
public class InkSceneRasterCache(
    public val cacheCapacity: Int,
    public val pixelBudgetBytes: Long,
) : AutoCloseable {
    public constructor() : this(1, DEFAULT_PIXEL_BUDGET_BYTES)

    init {
        require(cacheCapacity >= 0) { "cacheCapacity must be nonnegative" }
        require(pixelBudgetBytes >= 0L) { "pixelBudgetBytes must be nonnegative" }
    }

    public companion object {
        /** Retained N32 pixel budget; wrapper, scene and renderer storage are accounted separately. */
        public const val DEFAULT_PIXEL_BUDGET_BYTES: Long = 64L * 1024 * 1024
    }
    /** Total viewport raster builds, retained across cache clears. */
    public var rasterBuildCount: Long = 0L
        private set

    /** Pixel storage retained by all N32 rasters, bounded by [pixelBudgetBytes]. */
    public var retainedPixelBytes: Long = 0L
        private set

    /** Number of view images retained; bounded by [cacheCapacity]. */
    public val cachedViewCount: Int get() = views.size

    /** LRU image retirements caused by the capacity or pixel budget. */
    public var rasterEvictionCount: Long = 0L
        private set

    private data class View(val transform: ImmutableAffineTransform, val viewport: Rect, val scale: Float)
    private class Raster(val image: Image, val drawn: Int, val bytes: Long)
    private val views = LinkedHashMap<View, Raster>(4, 0.75f, true)
    private var cachedScene: InkScene? = null
    private var cachedRenderer: InkRenderer? = null
    private var cachedRenderVersion: Long = 0L
    private var cachedExcludedStrokes: Set<InkSceneStroke> = emptySet()
    private var closed: Boolean = false

    /**
     * Draws the current scene raster at the canvas's local origin, returning its visible stroke
     * count. Zero-sized viewports release the prior raster and draw nothing. Negative dimensions,
     * non-finite or non-invertible transforms are refused. Drawing after [close] is an error.
     */
    public fun draw(
        canvas: Canvas,
        scene: InkScene,
        renderer: InkPathRenderer,
        width: Int,
        height: Int,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
    ): Int = draw(canvas, scene, renderer as InkRenderer, width, height, sceneToCanvas)

    /** Draws or reuses a scene view rendered with full mesh effects. */
    public fun draw(
        canvas: Canvas,
        scene: InkScene,
        renderer: InkRenderer,
        width: Int,
        height: Int,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
    ): Int {
        check(!closed) { "InkSceneRasterCache is closed" }
        require(width >= 0 && height >= 0) { "Raster viewport dimensions must be nonnegative" }
        return draw(canvas, scene, renderer, Rect(0f, 0f, width.toFloat(), height.toFloat()), sceneToCanvas)
    }

    /**
     * Draws [viewport] in local canvas pixels, rasterized at [rasterScale] physical pixels per local
     * pixel. Include device density in [sceneToCanvas]; pass any ancestor zoom as [rasterScale]. For
     * sharp fractional scrolling, the viewport origin must map to the destination's physical pixel
     * origin. Ancestor zoom must be uniform and axis-aligned; the scene transform may be any affine
     * transform. Existing destination clipping is preserved and intersected with [viewport].
     *
     * The surface is ceil(viewport width * scale) by ceil(viewport height * scale); rounding padding
     * is clipped without stretching the raster. Neither a large document extent nor a nonzero
     * viewport origin increases storage. [excludedStrokes] holds occurrence keys from [InkScene.strokes];
     * a copied identity set prevents client set mutations from changing the retained cache key.
     * Empty viewports release the old raster. Invalid views fail before replacing a usable raster.
     */
    public fun draw(
        canvas: Canvas,
        scene: InkScene,
        renderer: InkPathRenderer,
        viewport: Rect,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
        rasterScale: Float = 1f,
        excludedStrokes: Set<InkSceneStroke> = emptySet(),
    ): Int = draw(canvas, scene, renderer as InkRenderer, viewport, sceneToCanvas, rasterScale, excludedStrokes)

    /** The physical viewport cache also tracks renderer identity, texture changes and animation. */
    public fun draw(
        canvas: Canvas,
        scene: InkScene,
        renderer: InkRenderer,
        viewport: Rect,
        sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
        rasterScale: Float = 1f,
        excludedStrokes: Set<InkSceneStroke> = emptySet(),
    ): Int {
        check(!closed) { "InkSceneRasterCache is closed" }
        require(viewport.left.isFinite() && viewport.top.isFinite() &&
            viewport.right.isFinite() && viewport.bottom.isFinite()) { "Raster viewport must be finite" }
        require(viewport.right >= viewport.left && viewport.bottom >= viewport.top) {
            "Raster viewport dimensions must be nonnegative"
        }
        require(rasterScale.isFinite() && rasterScale > 0f) { "Raster scale must be finite and positive" }
        if (viewport.isEmpty) {
            clearCache()
            return 0
        }
        val width = rasterDimension(viewport.right.toDouble() - viewport.left.toDouble(), rasterScale)
        val height = rasterDimension(viewport.bottom.toDouble() - viewport.top.toDouble(), rasterScale)
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= Int.MAX_VALUE / 4L) { "Raster pixel storage exceeds Int.MAX_VALUE bytes" }
        val transform = sceneToCanvas.toImmutable()
        requireFinite(transform)
        val canvasToScene = transform.computeInverse()
        requireFinite(canvasToScene)
        val sceneToRaster = ImmutableAffineTransform(
            transform.m00 * rasterScale, transform.m10 * rasterScale, (transform.m20 - viewport.left) * rasterScale,
            transform.m01 * rasterScale, transform.m11 * rasterScale, (transform.m21 - viewport.top) * rasterScale,
        )
        requireFinite(sceneToRaster)
        requireFinite(sceneToRaster.computeInverse())
        val inverseScale = 1f / rasterScale
        require(inverseScale.isFinite()) { "Inverse raster scale must be finite" }
        val drawingViewport = Rect(viewport.left, viewport.top,
            viewport.left + width * inverseScale, viewport.top + height * inverseScale)
            .inflate(maxOf(0f, inverseScale - 1f))
        requireFiniteViewportBounds(drawingViewport, canvasToScene)
        if (cachedScene !== scene || cachedRenderer !== renderer || cachedRenderVersion != renderer.renderVersion ||
            cachedExcludedStrokes.size != excludedStrokes.size ||
            excludedStrokes.any { it !in cachedExcludedStrokes }) {
            val exclusions = identitySnapshot(excludedStrokes)
            clearCache()
            cachedScene = scene
            cachedRenderer = renderer
            cachedRenderVersion = renderer.renderVersion
            cachedExcludedStrokes = exclusions
        }
        val view = View(transform, viewport, rasterScale)
        val bytes = pixelCount * 4L
        val cacheable = cacheCapacity > 0 && bytes <= pixelBudgetBytes
        val exclusions = cachedExcludedStrokes
        val raster = views[view] ?: run {
            if (cacheable) {
                while (views.size >= cacheCapacity || retainedPixelBytes > pixelBudgetBytes - bytes) evictOldest()
            } else {
                // Avoid retaining a full budget alongside an oversized transient viewport.
                clearCache()
            }
            var drawn = 0
            val snapshot = Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.clear(0)
                // Keep Skia's transform composition identical to direct ancestor-zoom drawing.
                surface.canvas.scale(rasterScale, rasterScale)
                surface.canvas.translate(-viewport.left, -viewport.top)
                // Clip before rasterizing too: ceil padding changes Skia's AA at crossed edges.
                surface.canvas.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom, false)
                drawn = scene.draw(surface.canvas.asComposeCanvas(), renderer, transform, drawingViewport, exclusions)
                surface.makeImageSnapshot()
            }
            rasterBuildCount++
            val created = Raster(snapshot, drawn, bytes)
            if (cacheable) {
                try {
                    views[view] = created
                    retainedPixelBytes += bytes
                } catch (failure: Throwable) {
                    snapshot.close()
                    throw failure
                }
            }
            created
        }
        val destination = canvas.skiaCanvas
        try {
            destination.save()
            try {
                destination.clipRect(viewport.left, viewport.top, viewport.right, viewport.bottom, false)
                destination.translate(viewport.left, viewport.top)
                destination.scale(inverseScale, inverseScale)
                destination.drawImage(raster.image, 0f, 0f)
            } finally {
                destination.restore()
            }
        } finally {
            if (!cacheable) raster.image.close()
        }
        return raster.drawn
    }

    /** Releases the cached raster and its page reference. The next draw builds a fresh snapshot. */
    public fun clearCache() {
        views.values.forEach { it.image.close() }
        views.clear()
        cachedScene = null
        cachedRenderer = null
        cachedExcludedStrokes = emptySet()
        retainedPixelBytes = 0L
    }

    /** Releases the raster and permanently prevents further drawing. Idempotent. */
    override public fun close() {
        clearCache()
        closed = true
    }

    private fun evictOldest() {
        val oldest = views.entries.iterator()
        val raster = oldest.next().value
        raster.image.close()
        oldest.remove()
        retainedPixelBytes -= raster.bytes
        rasterEvictionCount++
    }

    private fun requireFinite(transform: AffineTransform) {
        require(transform.m00.isFinite() && transform.m10.isFinite() && transform.m20.isFinite() &&
            transform.m01.isFinite() && transform.m11.isFinite() && transform.m21.isFinite()) {
            "Raster scene transform and its inverse must be finite"
        }
    }

    private fun rasterDimension(localSize: Double, rasterScale: Float): Int {
        val pixels = ceil(localSize * rasterScale.toDouble())
        require(pixels.isFinite() && pixels in 1.0..Int.MAX_VALUE.toDouble()) {
            "Physical raster dimensions must fit positive Int values"
        }
        return pixels.toInt()
    }

    private fun requireFiniteViewportBounds(viewport: Rect, canvasToScene: AffineTransform) {
        // Match InkScene's expanded inverse-mapped query before releasing a usable prior raster.
        val expanded = viewport.inflate(1f)
        var minX = Float.POSITIVE_INFINITY
        var minY = Float.POSITIVE_INFINITY
        var maxX = Float.NEGATIVE_INFINITY
        var maxY = Float.NEGATIVE_INFINITY
        repeat(4) { corner ->
            val x = if (corner and 1 == 0) expanded.left else expanded.right
            val y = if (corner and 2 == 0) expanded.top else expanded.bottom
            val sceneX = canvasToScene.m00 * x + canvasToScene.m10 * y + canvasToScene.m20
            val sceneY = canvasToScene.m01 * x + canvasToScene.m11 * y + canvasToScene.m21
            minX = minOf(minX, sceneX)
            minY = minOf(minY, sceneY)
            maxX = maxOf(maxX, sceneX)
            maxY = maxOf(maxY, sceneY)
        }
        val bounds = Rect(minX, minY, maxX, maxY).inflate(0.01f)
        require(bounds.left.isFinite() && bounds.top.isFinite() && bounds.right.isFinite() && bounds.bottom.isFinite()) {
            "Raster viewport inverse-mapped bounds must be finite"
        }
    }

    private fun identitySnapshot(strokes: Set<InkSceneStroke>): Set<InkSceneStroke> =
        Collections.unmodifiableSet(Collections.newSetFromMap(IdentityHashMap<InkSceneStroke, Boolean>()).apply {
            addAll(strokes)
        })
}

/** Remembers one viewport cache and closes its native raster when it leaves composition. */
@Composable
public fun rememberInkSceneRasterCache(): InkSceneRasterCache {
    val cache = remember { InkSceneRasterCache() }
    DisposableEffect(cache) { onDispose { cache.close() } }
    return cache
}

/** Remembers a byte-budgeted LRU of exact viewport views, closing every image on disposal. */
@Composable
public fun rememberInkSceneRasterCache(cacheCapacity: Int, pixelBudgetBytes: Long): InkSceneRasterCache {
    val cache = remember(cacheCapacity, pixelBudgetBytes) { InkSceneRasterCache(cacheCapacity, pixelBudgetBytes) }
    DisposableEffect(cache) { onDispose { cache.close() } }
    return cache
}

/** Draws a finished scene through a native-resolution viewport raster, reused until the view changes. */
public fun DrawScope.drawCachedInkScene(
    cache: InkSceneRasterCache,
    scene: InkScene,
    renderer: InkPathRenderer,
    sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
): Int = cache.draw(drawContext.canvas, scene, renderer,
    ceil(size.width.toDouble()).toInt(), ceil(size.height.toDouble()).toInt(), sceneToCanvas)

/** Draws a scrolled viewport at physical ancestor-zoom resolution, reusing its finished raster. */
public fun DrawScope.drawCachedInkScene(
    cache: InkSceneRasterCache,
    scene: InkScene,
    renderer: InkPathRenderer,
    viewport: Rect,
    sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
    rasterScale: Float = 1f,
    excludedStrokes: Set<InkSceneStroke> = emptySet(),
): Int = cache.draw(drawContext.canvas, scene, renderer, viewport, sceneToCanvas, rasterScale, excludedStrokes)

/** Draws a finished scene with full mesh effects through a physical viewport raster. */
public fun DrawScope.drawCachedInkScene(
    cache: InkSceneRasterCache,
    scene: InkScene,
    renderer: InkRenderer,
    sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
): Int = cache.draw(drawContext.canvas, scene, renderer,
    ceil(size.width.toDouble()).toInt(), ceil(size.height.toDouble()).toInt(), sceneToCanvas)

/** Draws a scrolled scene raster, invalidating it when texture animation advances. */
public fun DrawScope.drawCachedInkScene(
    cache: InkSceneRasterCache,
    scene: InkScene,
    renderer: InkRenderer,
    viewport: Rect,
    sceneToCanvas: AffineTransform = AffineTransform.IDENTITY,
    rasterScale: Float = 1f,
    excludedStrokes: Set<InkSceneStroke> = emptySet(),
): Int = cache.draw(drawContext.canvas, scene, renderer, viewport, sceneToCanvas, rasterScale, excludedStrokes)
