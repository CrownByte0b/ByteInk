package com.vivenotes.byteink.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.vivenotes.byteink.portable.InkTransform
import com.vivenotes.byteink.wasm.*
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import org.jetbrains.skia.impl.use
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import kotlin.math.cos
import kotlin.math.sin

/** Images are borrowed. Clear the renderer cache after replacing a client texture ID. */
public fun interface InkTextureStore { public operator fun get(clientTextureId: String): Image? }

/** Atlas metadata that the pinned experimental brush protobuf does not serialize. Keep it with
 * the application's texture definition. When absent, use the engine snapshot's texture metadata.
 */
public data class InkTextureAnimation(
    public val frames: Int, public val rows: Int, public val columns: Int,
    public val durationMillis: Long = 1000L, public val reverse: Boolean = false,
) {
    init { require(frames in 1..16777216 && rows in 1..4096 && columns in 1..4096 &&
        frames <= rows.toLong() * columns && durationMillis in 0L..16777216L) { "Invalid Ink texture atlas" } }
}
public fun interface InkTextureAnimations { public operator fun get(clientTextureId: String): InkTextureAnimation? }

/** Draw on one UI thread. Transforms compose with the canvas transform; clipping is caller-owned. */
public interface InkRenderer : AutoCloseable {
    public fun draw(canvas: Canvas, stroke: InkStroke, transform: InkTransform = InkTransform(), viewport: Rect? = null): Boolean
    public fun draw(canvas: Canvas, stroke: InkLiveStroke, transform: InkTransform = InkTransform(), viewport: Rect? = null): Boolean
    public fun release(stroke: InkStroke)
    public fun release(stroke: InkLiveStroke)
    public fun clearCache()
}

/** Compose/Wasm renderer using the desktop vertex and fragment math. Full mesh effects for ANY/
 * ACCUMULATE, compound path union for DISCARD, tiling/stamping textures and atlas animation.
 * Both finished and live caches are explicitly bounded. Release live entries on finish/cancel;
 * close clears owned shaders, paths and effects, and never closes strokes or provider images.
 */
public class InkMeshRenderer private constructor(
    public val textureStore: InkTextureStore?, public val cacheCapacity: Int,
    public val cacheByteBudget: Long, public val textureCacheCapacity: Int,
    private val pathsOnly: Boolean, public val textureAnimations: InkTextureAnimations?,
) : InkRenderer {
    public constructor(textureStore: InkTextureStore? = null, cacheCapacity: Int = 2048,
        cacheByteBudget: Long = 64L * 1024 * 1024, textureCacheCapacity: Int = 64,
        textureAnimations: InkTextureAnimations? = null) :
        this(textureStore, cacheCapacity, cacheByteBudget, textureCacheCapacity, false, textureAnimations)

    internal companion object {
        fun paths(capacity: Int, budget: Long): InkMeshRenderer = InkMeshRenderer(null, capacity, budget, 1, true, null)
    }

    init { require(cacheCapacity >= 0 && cacheByteBudget >= 0 && textureCacheCapacity > 0) { "Invalid cache limits" } }
    public var animationTimeMillis: Long = 0L
        set(value) { require(value >= 0); field = value }
    public val cachedShapeCount: Int get() = entries.size
    public val cachedTextureCount: Int get() = textures.size
    /** Estimated snapshot, preparation, path and retained uniform bytes, excluding wrapper/driver storage. */
    public val cachedGeometryBytes: Long get() = entries.values.sumOf { it.bytes }
    public var snapshotCount: Long = 0L
        private set

    private class Coat {
        var path: Path? = null
        var meshes: List<PreparedMesh> = emptyList()
        fun close() { path?.reset(); path = null; meshes.forEach { it.close() }; meshes = emptyList() }
    }
    private class Entry(var version: Long, var snapshot: InkRendering) {
        val coats = snapshot.groups.map { Coat() }
        val bytes: Long get() = snapshot.groups.sumOf { group ->
            group.meshes.sumOf { it.vertices.size * 4L + it.triangles.size * 4L } + group.outlines.sumOf { it.size * 4L }
        } + coats.sumOf { coat -> coat.meshes.sumOf { it.scratchBytes + it.chunks.sumOf { chunk -> chunk.bytes } } +
            (coat.path?.let { snapshot.groups.sumOf { g -> g.outlines.sumOf { it.size * 16L } + g.meshes.sumOf { it.triangles.size * 48L } } } ?: 0L) }
        fun close() = coats.forEach { it.close() }
    }
    private val entries = linkedMapOf<Any, Entry>()
    private val textures = linkedMapOf<InkRenderTexture, Shader>()
    private val paint = Paint().apply { isAntiAlias = true }
    private var effect: RuntimeEffect? = null
    private var pathEffect: RuntimeEffect? = null
    private var white: Shader? = null
    private var closed = false

    override fun draw(canvas: Canvas, stroke: InkStroke, transform: InkTransform, viewport: Rect?): Boolean {
        check(!stroke.isClosed) { "Stroke is closed" }
        return draw(canvas, stroke, 0L, transform, viewport, stroke::rendering)
    }
    override fun draw(canvas: Canvas, stroke: InkLiveStroke, transform: InkTransform, viewport: Rect?): Boolean {
        check(!stroke.isClosed) { "Live stroke is closed" }
        return draw(canvas, stroke, stroke.version, transform, viewport, stroke::rendering)
    }
    override fun release(stroke: InkStroke) { entries.remove(stroke)?.close() }
    override fun release(stroke: InkLiveStroke) { entries.remove(stroke)?.close() }

    /** Draw an owned snapshot, including after its engine stroke has closed. Treat its arrays as
     * immutable while cached. clearCache releases snapshot entries along with stroke entries.
     */
    public fun draw(canvas: Canvas, snapshot: InkRendering, transform: InkTransform = InkTransform(), viewport: Rect? = null): Boolean =
        draw(canvas, snapshot, 0L, transform, viewport) { snapshot }
    public fun release(snapshot: InkRendering) { entries.remove(snapshot)?.close() }

    private fun draw(canvas: Canvas, key: Any, version: Long, transform: InkTransform, viewport: Rect?, load: () -> InkRendering): Boolean {
        check(!closed) { "Renderer is closed" }
        var entry = entries.remove(key)
        if (entry == null) { entry = Entry(version, load()); snapshotCount++ }
        else if (entry.version != version) {
            val next = try { load() } catch (error: Throwable) { entry.close(); throw error }
            entry.coats.forEach { it.path?.reset(); it.path = null }
            entry.snapshot = next; entry.version = version; snapshotCount++
        }
        val current = entry
        var retained = false
        try {
            val snapshot = current.snapshot
            val selected = snapshot.groups.mapIndexed { index, group ->
                requireNotNull(group.paints.firstOrNull { candidate ->
                    candidate.compatible && (!pathsOnly || candidate.textures.isEmpty() && candidate.selfOverlap != InkSelfOverlap.ACCUMULATE) &&
                        (candidate.selfOverlap != InkSelfOverlap.DISCARD || candidate.textures.all { it is InkTilingTexture }) &&
                        candidate.textures.all { texture(it) != null }
                }) { "No drawable paint for Ink coat $index (check textures, overlap and mesh attributes)" }
            }
            canvas.save()
            try {
                canvas.concat(Matrix().apply {
                    this[0, 0] = transform.scaleX; this[1, 1] = transform.scaleY
                    this[3, 0] = transform.offsetX; this[3, 1] = transform.offsetY
                })
                val m = canvas.skiaCanvas.localToDeviceAsMatrix33.mat
                require(m.all { it.isFinite() } && m[6] == 0f && m[7] == 0f && m[8] == 1f) { "Ink requires a finite affine canvas transform" }
                val linear = MeshLinearTransform(m[0], m[1], m[3], m[4])
                if (linear.a * linear.e - linear.b * linear.d == 0f) return false
                // Culling uses unoutset geometry with a conservative device-space AA margin.
                val det = linear.a * linear.e - linear.b * linear.d
                val margin = 2f * kotlin.math.sqrt(linear.a * linear.a + linear.b * linear.b + linear.d * linear.d + linear.e * linear.e) /
                    kotlin.math.abs(det) * maxOf(transform.scaleX, transform.scaleY)
                if (viewport != null && !visible(snapshot, transform, viewport, margin)) return false
                snapshot.groups.forEachIndexed { index, group -> drawCoat(canvas, snapshot, group, current.coats[index], selected[index], linear) }
            } finally { canvas.restore() }
            if (cacheCapacity > 0 && current.bytes <= cacheByteBudget) {
                entries[key] = current; retained = true
                while (entries.size > cacheCapacity || cachedGeometryBytes > cacheByteBudget) {
                    val oldest = entries.keys.first(); entries.remove(oldest)?.close()
                }
            }
            return snapshot.groups.any { it.meshes.any { mesh -> mesh.triangleCount > 0 } }
        } finally { if (!retained) current.close() }
    }

    private fun visible(snapshot: InkRendering, transform: InkTransform, viewport: Rect, margin: Float): Boolean {
        var left = Float.POSITIVE_INFINITY; var right = Float.NEGATIVE_INFINITY
        var top = Float.POSITIVE_INFINITY; var bottom = Float.NEGATIVE_INFINITY
        snapshot.groups.forEach { g -> g.meshes.forEach { mesh ->
            for (i in mesh.vertices.indices step 15) {
                val x = mesh.vertices[i] * transform.scaleX + transform.offsetX
                val y = mesh.vertices[i + 1] * transform.scaleY + transform.offsetY
                left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
            }
        } }
        return left <= right && Rect(left, top, right, bottom).inflate(margin).overlaps(viewport)
    }

    private fun drawCoat(canvas: Canvas, snapshot: InkRendering, group: InkRenderingGroup, coat: Coat,
        value: InkRenderingPaint, linear: MeshLinearTransform) {
        val texture = paintTexture(snapshot, value)
        try {
            if (pathsOnly || value.selfOverlap == InkSelfOverlap.DISCARD) {
                val path = coat.path ?: outlinePath(group).also { coat.path = it }
                val c = value.srgbColor
                paint.color = Color(c[0].coerceIn(0f, 1f), c[1].coerceIn(0f, 1f), c[2].coerceIn(0f, 1f), c[3])
                if (texture == null) canvas.drawPath(path, paint)
                else {
                    val e = pathEffect ?: RuntimeEffect.makeForShader(INK_PATH_TEXTURE_SKSL).also { pathEffect = it }
                    RuntimeShaderBuilder(e).use { builder ->
                        builder.child("coatTexture", texture); builder.uniform("brushColor", c)
                        builder.uniform("textureBlend", value.textures.last().blend)
                        builder.makeShader().use { shader ->
                            paint.color = Color.White; paint.shader = shader.asComposeShader()
                            try { canvas.drawPath(path, paint) } finally { paint.shader = null }
                        }
                    }
                }
            } else {
                val c = value.linearColor
                val color = MeshColor(c[0], c[1], c[2], c[3])
                val stamp = (value.textures.firstOrNull() as? InkStampingTexture)?.let { layer ->
                    val animation = textureAnimations?.get(layer.id) ?: InkTextureAnimation(layer.frames, layer.rows, layer.columns, layer.durationMillis, layer.reverse)
                    val duration = animation.durationMillis
                    val fraction = if (duration == 0L) 0.0 else (animationTimeMillis % duration).toDouble() / duration
                    StampAnimation((if (animation.reverse && duration > 0 && animationTimeMillis / duration % 2L == 1L) 1.0 - fraction else fraction).toFloat(),
                        animation.frames, animation.rows, animation.columns)
                }
                val previous = coat.meshes
                coat.meshes = group.meshes.mapIndexed { i, mesh ->
                    val old = previous.getOrNull(i)
                    if (old != null && old.mesh === mesh && old.transform == linear && old.color == color && old.stamp == stamp) old
                    else prepareMeshIncrementally(mesh, linear, color, stamp, old)
                }
                previous.drop(coat.meshes.size).forEach { it.close() }
                val e = effect ?: RuntimeEffect.makeForShader(INK_MESH_SKSL).also { effect = it }
                val textureChild = texture ?: white ?: Shader.makeColor(0xffffffff.toInt()).also { white = it }
                RuntimeShaderBuilder(e).use { builder ->
                    builder.child("coatTexture", textureChild); builder.uniform("hasTexture", if (texture == null) 0 else 1)
                    builder.uniform("textureBlend", value.textures.lastOrNull()?.blend ?: 0)
                    builder.uniform("colorSpaceProbe", .5f, .5f, .5f, 1f)
                    coat.meshes.flatMap { it.chunks }.forEach { chunk ->
                        val shader = if (texture == null && chunk.untexturedShader != null) chunk.untexturedShader!! else {
                            builder.uniform("vertexData", chunk.varyings)
                            builder.makeShader().also { if (texture == null) chunk.untexturedShader = it }
                        }
                        try {
                            paint.color = Color.White; paint.shader = shader.asComposeShader()
                            try { canvas.drawVertices(chunk.vertices, BlendMode.Modulate, paint) } finally { paint.shader = null }
                        } finally { if (texture != null) shader.close() }
                    }
                }
            }
        } finally { texture?.close() }
    }

    private fun texture(layer: InkRenderTexture): Shader? {
        textures.remove(layer)?.let { textures[layer] = it; return it }
        val image = textureStore?.get(layer.id) ?: return null
        require(!image.isClosed) { "Texture '${layer.id}' is closed" }
        val matrix = if (layer is InkTilingTexture) {
            val c = cos(layer.rotationRadians); val s = sin(layer.rotationRadians)
            Matrix33(layer.sizeX * c / image.width, -layer.sizeX * s / image.height,
                layer.sizeX * (.5f + c * (layer.offsetX - .5f) - s * (layer.offsetY - .5f)),
                layer.sizeY * s / image.width, layer.sizeY * c / image.height,
                layer.sizeY * (.5f + s * (layer.offsetX - .5f) + c * (layer.offsetY - .5f)), 0f, 0f, 1f)
        } else Matrix33.makeScale(1f / image.width, 1f / image.height)
        val shader = image.makeShader(if (layer is InkTilingTexture) wrap(layer.wrapX) else FilterTileMode.REPEAT,
            if (layer is InkTilingTexture) wrap(layer.wrapY) else FilterTileMode.REPEAT, localMatrix = matrix)
        while (textures.size >= textureCacheCapacity) textures.remove(textures.keys.first())?.close()
        textures[layer] = shader
        return shader
    }

    private fun paintTexture(snapshot: InkRendering, paint: InkRenderingPaint): Shader? {
        var result: Shader? = null
        try {
            paint.textures.forEachIndexed { index, layer ->
                val base = requireNotNull(texture(layer)) { "Texture disappeared while drawing" }
                val matrix = if (layer is InkTilingTexture) {
                    val unit = if (layer.sizeUnit == 0) snapshot.brushSize else 1f
                    val offset = when (layer.origin) { 1 -> 0; 2 -> 2; else -> -1 }
                    Matrix33(unit, 0f, if (offset >= 0) snapshot.inputPositions[offset] else 0f,
                        0f, unit, if (offset >= 0) snapshot.inputPositions[offset + 1] else 0f, 0f, 0f, 1f)
                } else Matrix33.IDENTITY
                val next = base.makeWithLocalMatrix(matrix)
                if (result == null) result = next else {
                    val previous = result
                    try { result = Shader.makeBlend(blends[paint.textures[index - 1].blend], next, previous) }
                    finally { next.close(); previous.close() }
                }
            }
            return result
        } catch (error: Throwable) { result?.let { if (!it.isClosed) it.close() }; throw error }
    }

    override fun clearCache() {
        entries.values.forEach { it.close() }; entries.clear()
        textures.values.forEach { it.close() }; textures.clear()
    }
    override fun close() {
        if (closed) return
        clearCache(); paint.shader = null
        effect?.close(); pathEffect?.close(); white?.close()
        effect = null; pathEffect = null; white = null; closed = true
    }
}

/** Uniform path fills for texture-free ANY/DISCARD coats; vertex effects and prediction fade need mesh rendering. */
public class InkPathRenderer(cacheCapacity: Int = 2048, cacheByteBudget: Long = 64L * 1024 * 1024) :
    InkRenderer by InkMeshRenderer.paths(cacheCapacity, cacheByteBudget)

private fun outlinePath(group: InkRenderingGroup): Path = Path().apply {
    fillType = PathFillType.NonZero
    if (group.outlines.any { it.size >= 6 }) {
        for (points in group.outlines) if (points.size >= 6) {
            moveTo(points[0], points[1]); for (i in 2 until points.size step 2) lineTo(points[i], points[i + 1]); close()
        }
    } else for (mesh in group.meshes) for (i in mesh.triangles.indices step 3) {
        val a = mesh.triangles[i] * 15; var b = mesh.triangles[i + 1] * 15; var c = mesh.triangles[i + 2] * 15
        val p = mesh.vertices
        val area = (p[b] - p[a]) * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[a])
        if (area == 0f) continue
        if (area < 0f) { val swap = b; b = c; c = swap }
        moveTo(p[a], p[a + 1]); lineTo(p[b], p[b + 1]); lineTo(p[c], p[c + 1]); close()
    }
}
private fun wrap(value: Int): FilterTileMode = when (value) { 0 -> FilterTileMode.REPEAT; 1 -> FilterTileMode.MIRROR; 2 -> FilterTileMode.CLAMP; else -> error("Unknown Ink wrap") }
private val blends = listOf(SkiaBlendMode.MODULATE, SkiaBlendMode.DST_IN, SkiaBlendMode.DST_OUT, SkiaBlendMode.SRC_ATOP,
    SkiaBlendMode.SRC_IN, SkiaBlendMode.SRC_OVER, SkiaBlendMode.DST_OVER, SkiaBlendMode.SRC, SkiaBlendMode.DST,
    SkiaBlendMode.SRC_OUT, SkiaBlendMode.DST_ATOP, SkiaBlendMode.XOR)

public fun DrawScope.drawInk(renderer: InkRenderer, stroke: InkStroke, transform: InkTransform = InkTransform()): Boolean =
    renderer.draw(drawContext.canvas, stroke, transform, Rect(0f, 0f, size.width, size.height))
public fun DrawScope.drawInk(renderer: InkRenderer, stroke: InkLiveStroke, transform: InkTransform = InkTransform()): Boolean =
    renderer.draw(drawContext.canvas, stroke, transform, Rect(0f, 0f, size.width, size.height))

/** Releases the renderer when this composition leaves the UI. Supplied texture images remain borrowed. */
@Composable
public fun rememberInkMeshRenderer(textureStore: InkTextureStore? = null, textureAnimations: InkTextureAnimations? = null): InkMeshRenderer {
    val renderer = remember(textureStore, textureAnimations) { InkMeshRenderer(textureStore, textureAnimations = textureAnimations) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }
    return renderer
}
