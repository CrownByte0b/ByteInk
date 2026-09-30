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
import kotlin.math.ceil

/**
 * Caches one finished scene as a transparent Skia raster in local viewport pixels.
 *
 * Reuse on one drawing thread. An unchanged page and view only require one image draw, avoiding
 * repeated geometry rasterization beneath a live stroke even when the path cache cannot hold the
 * entire page. Scene identity, transform values and viewport dimensions invalidate the raster.
 * Every changed view is rendered at its new resolution; a stale raster is never scaled for zoom.
 *
 * The temporary surface is closed after taking its immutable snapshot. Replacement, [clearCache]
 * and [close] close the retained image deterministically. Layering translucent paths into an
 * 8-bit premultiplied image can differ slightly from drawing them directly over the destination
 * because alpha compositing rounds at each step. The viewport is in the canvas's local pixels;
 * any existing canvas transform or clipping also applies to the resulting image.
 */
public class InkSceneRasterCache : AutoCloseable {
    /** Total viewport raster builds, retained across cache clears. */
    public var rasterBuildCount: Long = 0L
        private set

    /** Pixel storage retained by the current N32 raster; zero after clear or close. */
    public var retainedPixelBytes: Long = 0L
        private set

    private var image: Image? = null
    private var cachedScene: InkScene? = null
    private var cachedTransform: ImmutableAffineTransform? = null
    private var cachedWidth: Int = 0
    private var cachedHeight: Int = 0
    private var cachedDrawnCount: Int = 0
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
    ): Int {
        check(!closed) { "InkSceneRasterCache is closed" }
        require(width >= 0 && height >= 0) { "Raster viewport dimensions must be nonnegative" }
        if (width == 0 || height == 0) {
            clearCache()
            return 0
        }
        val pixelCount = width.toLong() * height.toLong()
        require(pixelCount <= Long.MAX_VALUE / 4L) { "Raster pixel storage size is too large" }
        val transform = sceneToCanvas.toImmutable()
        requireFinite(transform)
        requireFinite(transform.computeInverse())
        if (image == null || cachedScene !== scene || cachedTransform != transform ||
            cachedWidth != width || cachedHeight != height) {
            // The old view cannot be reused. Release it before allocating the next viewport.
            clearCache()
            var drawn = 0
            val snapshot = Surface.makeRasterN32Premul(width, height).use { surface ->
                surface.canvas.clear(0)
                drawn = scene.draw(surface.canvas.asComposeCanvas(), renderer, transform,
                    Rect(0f, 0f, width.toFloat(), height.toFloat()))
                surface.makeImageSnapshot()
            }
            image = snapshot
            cachedScene = scene
            cachedTransform = transform
            cachedWidth = width
            cachedHeight = height
            cachedDrawnCount = drawn
            retainedPixelBytes = pixelCount * 4L
            rasterBuildCount++
        }
        canvas.skiaCanvas.drawImage(requireNotNull(image), 0f, 0f)
        return cachedDrawnCount
    }

    /** Releases the cached raster and its page reference. The next draw builds a fresh snapshot. */
    public fun clearCache() {
        image?.close()
        image = null
        cachedScene = null
        cachedTransform = null
        cachedWidth = 0
        cachedHeight = 0
        cachedDrawnCount = 0
        retainedPixelBytes = 0L
    }

    /** Releases the raster and permanently prevents further drawing. Idempotent. */
    override public fun close() {
        clearCache()
        closed = true
    }

    private fun requireFinite(transform: AffineTransform) {
        require(transform.m00.isFinite() && transform.m10.isFinite() && transform.m20.isFinite() &&
            transform.m01.isFinite() && transform.m11.isFinite() && transform.m21.isFinite()) {
            "Raster scene transform and its inverse must be finite"
        }
    }
}

/** Remembers one viewport cache and closes its native raster when it leaves composition. */
@Composable
public fun rememberInkSceneRasterCache(): InkSceneRasterCache {
    val cache = remember { InkSceneRasterCache() }
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
