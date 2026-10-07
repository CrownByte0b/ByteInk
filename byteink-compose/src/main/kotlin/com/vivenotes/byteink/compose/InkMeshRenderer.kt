@file:OptIn(androidx.ink.nativeloader.InkInternalOnlyApi::class, androidx.ink.brush.ExperimentalInkAnimationApi::class)

package com.vivenotes.byteink.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeShader
import androidx.compose.ui.graphics.skiaCanvas
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.color.Color as InkColor
import androidx.ink.brush.color.colorspace.ColorSpaces
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.BoxAccumulator
import androidx.ink.geometry.MeshFormat
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.core.StrokeMesh
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Matrix33
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.Shader
import org.jetbrains.skia.BlendMode as SkiaBlendMode
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full Ink rendering on Compose Desktop, using the real mesh and Ink's vertex/fragment shader
 * math: per-vertex opacity and HSL shifts, predicted-input fade, derivative-based antialiasing,
 * tiling and particle stamping textures, texture blends, and atlas animations.
 * ANY and ACCUMULATE use mesh overlap. DISCARD uses a single path fill with tiling textures,
 * following the pinned Ink contract (which ignores vertex color effects on DISCARD coats).
 *
 * Reuse on one drawing thread. Finished geometry and prepared vertices are bounded by both cache
 * limits; live geometry is weakly owned and replaced on every shape-version change. Texture shaders
 * retain their images independently of the provider, with at most [textureCacheCapacity] entries.
 * [cachedGeometryBytes] excludes JVM object headers, driver uploads and provider-owned images.
 * Set [animationTimeMillis] to advance texture animations; shape animations still need updateShape.
 * [clearCache] also invalidates textures; [close] releases shaders and compiled runtime effects.
 */
public class InkMeshRenderer(
    public val textureStore: InkTextureStore? = null,
    public val cacheCapacity: Int = 2048,
    public val cacheByteBudget: Long = 64L * 1024 * 1024,
    public val textureCacheCapacity: Int = 64,
) : InkRenderer, AutoCloseable {
    init {
        require(cacheCapacity >= 0 && cacheByteBudget >= 0L) { "Geometry cache limits must be nonnegative" }
        require(textureCacheCapacity > 0) { "textureCacheCapacity must be positive" }
    }

    private var animationClock: Long by mutableLongStateOf(0L)
    public var animationTimeMillis: Long
        get() = animationClock
        set(value) {
            require(value >= 0L) { "animationTimeMillis must be nonnegative" }
            if (animationClock != value) { animationClock = value; renderVersion++ }
        }
    override var renderVersion: Long by mutableLongStateOf(0L)
        private set
    public var meshBuildCount: Long = 0L
        private set
    internal var meshSnapshotCount: Long = 0L
        private set
    public val cachedShapeCount: Int get() = shapes.size
    public val cachedTextureCount: Int get() = textures.size
    /** Active wet caches, separate from the finished geometry budget. */
    public val cachedLiveShapeCount: Int get() = live.size
    public val cachedLiveGeometryBytes: Long get() = live.values.sumOf { it.geometry.bytes }
    public var cachedGeometryBytes: Long = 0L
        private set

    private data class PreparedKey(val transform: MeshLinearTransform, val color: MeshColor, val stamp: StampAnimation?)
    private class CoatGeometry {
        var meshes: List<StrokeMesh>? = null
        var path: InkRenderPath? = null
        var key: PreparedKey? = null
        var chunks: List<MeshChunk> = emptyList()
        val bytes: Long get() = (meshes?.sumOf { it.vertices.size * 4L + it.triangles.size * 4L } ?: 0L) +
            chunks.sumOf { it.varyings.size * 4L + it.vertices.positions.size * 4L +
                it.vertices.textureCoordinates.size * 4L + it.vertices.colors.size * 4L + it.vertices.indices.size * 2L } +
            (path?.approximateBytesUsed ?: 0L)
        fun close() { path?.close(); path = null; meshes = null; chunks = emptyList() }
    }
    private class Geometry(val coats: List<CoatGeometry>) {
        val bytes: Long get() = coats.sumOf { it.bytes }
        var retainedBytes: Long = 0L
        fun close() = coats.forEach { it.close() }
    }
    private class LiveGeometry(val version: Long, val geometry: Geometry)
    private val shapes = LinkedHashMap<PartitionedMesh, Geometry>(16, .75f, true)
    private val textures = LinkedHashMap<BrushPaint.TextureLayer, Shader>(16, .75f, true)
    private val queue = ReferenceQueue<InProgressStroke>()
    private val live = HashMap<StrokeReference, LiveGeometry>()
    private val paint = Paint().apply { isAntiAlias = true }
    private val bounds = BoxAccumulator()
    private val coatBounds = BoxAccumulator()
    private val first = StrokeInput()
    private val last = StrokeInput()
    private var meshEffect: RuntimeEffect? = null
    private var pathEffect: RuntimeEffect? = null
    private var white: Shader? = null
    private var closed = false

    override fun canDraw(stroke: Stroke): Boolean {
        checkOpen()
        return stroke.brush.family.coats.indices.all { coat ->
            choosePaint(stroke.brush, coat, stroke.shape.renderGroupFormat(coat)) != null
        }
    }

    override fun canDraw(stroke: InProgressStroke): Boolean {
        checkOpen()
        val brush = stroke.brush ?: return true
        return brush.family.coats.indices.all { choosePaint(brush, it, stroke.getMeshFormat(it)) != null }
    }

    public fun draw(
        canvas: Canvas,
        stroke: Stroke,
        strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect? = null,
        colorArgb: Int? = null,
    ): Boolean {
        checkOpen(); releaseCollected()
        val matrix = strokeToCanvas.composeMatrix()
        val box = stroke.shape.computeBoundingBox() ?: return false
        if (stroke.inputs.size == 0 || (viewport != null &&
                !matrix.map(Rect(box.xMin, box.yMin, box.xMax, box.yMax)).inflate(1f).overlaps(viewport))) return false
        val paints = stroke.brush.family.coats.indices.map { coat ->
            requireNotNull(choosePaint(stroke.brush, coat, stroke.shape.renderGroupFormat(coat))) { "No drawable paint for Ink coat $coat (check textures and mesh format)" }
        }
        val cached = shapes[stroke.shape]
        val geometry = cached ?: Geometry(paints.indices.map { CoatGeometry() })
        val snapshots = { coat: Int -> renderingMeshes(geometry.coats[coat]) { InkMeshes.rendering(stroke.shape, coat) } }
        stroke.inputs.populate(0, first); stroke.inputs.populate(stroke.inputs.size - 1, last)
        var retained = cached != null
        try {
            canvas.save()
            try {
                canvas.concat(matrix)
                drawCoats(canvas, geometry, stroke.brush, paints, colorArgb, snapshots) { coat ->
                    finishedPath(stroke.shape, coat) { snapshots(coat) }
                }
            } finally { canvas.restore() }
            if (!retained && cacheCapacity > 0 && geometry.bytes <= cacheByteBudget) {
                shapes[stroke.shape] = geometry
                retained = true
            }
            if (retained) {
                cachedGeometryBytes += geometry.bytes - geometry.retainedBytes
                geometry.retainedBytes = geometry.bytes
            }
            trimCache()
        } finally {
            if (!retained) geometry.close()
            else if (shapes[stroke.shape] === geometry && geometry.bytes != geometry.retainedBytes) {
                cachedGeometryBytes += geometry.bytes - geometry.retainedBytes
                geometry.retainedBytes = geometry.bytes
                trimCache()
            }
        }
        return true
    }

    public fun draw(
        canvas: Canvas,
        stroke: InProgressStroke,
        strokeToCanvas: AffineTransform = AffineTransform.IDENTITY,
        viewport: Rect? = null,
        colorArgb: Int? = null,
    ): Boolean {
        checkOpen(); releaseCollected()
        val key = StrokeReference(stroke)
        val brush = stroke.brush
        if (brush == null || stroke.getInputCount() == 0) {
            live.remove(key)?.geometry?.close()
            return false
        }
        val matrix = strokeToCanvas.composeMatrix()
        bounds.reset()
        repeat(brush.family.coats.size) { bounds.add(stroke.populateMeshBounds(it, coatBounds).box) }
        val box = bounds.box ?: run { live.remove(key)?.geometry?.close(); return false }
        if (viewport != null && !matrix.map(Rect(box.xMin, box.yMin, box.xMax, box.yMax)).inflate(1f).overlaps(viewport)) return false
        val paints = brush.family.coats.indices.map { coat ->
            requireNotNull(choosePaint(brush, coat, stroke.getMeshFormat(coat))) { "No drawable paint for live Ink coat $coat (check textures and mesh format)" }
        }
        val cached = live[key]
        val geometry = if (cached?.version == stroke.shapeVersion()) cached.geometry else {
            Geometry(paints.indices.map { CoatGeometry() }).also {
                live[if (cached == null) StrokeReference(stroke, queue) else key] = LiveGeometry(stroke.shapeVersion(), it)
                cached?.geometry?.close()
            }
        }
        stroke.populateInput(first, 0); stroke.populateInput(last, stroke.getInputCount() - 1)
        canvas.save()
        try {
            canvas.concat(matrix)
            drawCoats(canvas, geometry, brush, paints, colorArgb,
                { coat -> renderingMeshes(geometry.coats[coat]) { InkMeshes.rendering(stroke, coat) } }) { coat ->
                outlineInkPath(InkMeshes.outlines(stroke, coat))
            }
        } finally { canvas.restore() }
        return true
    }

    override fun render(canvas: Canvas, stroke: Stroke, strokeToCanvas: AffineTransform, viewport: Rect?, colorArgb: Int?): Boolean =
        draw(canvas, stroke, strokeToCanvas, viewport, colorArgb)
    override fun render(canvas: Canvas, stroke: InProgressStroke, strokeToCanvas: AffineTransform, viewport: Rect?, colorArgb: Int?): Boolean =
        draw(canvas, stroke, strokeToCanvas, viewport, colorArgb)

    override fun clearCache() {
        shapes.values.forEach { it.close() }; shapes.clear()
        cachedGeometryBytes = 0L
        live.values.forEach { it.geometry.close() }; live.clear()
        textures.values.forEach { it.close() }; textures.clear()
        releaseCollected()
        renderVersion++
    }

    override fun releaseLiveStroke(stroke: InProgressStroke) {
        live.remove(StrokeReference(stroke))?.geometry?.close()
    }

    private fun renderingMeshes(coat: CoatGeometry, load: () -> List<StrokeMesh>): List<StrokeMesh> =
        coat.meshes ?: load().also { coat.meshes = it; meshSnapshotCount++ }

    override fun close() {
        if (closed) return
        clearCache()
        paint.shader = null
        meshEffect?.close(); pathEffect?.close(); white?.close()
        meshEffect = null; pathEffect = null; white = null
        closed = true
    }

    private fun checkOpen() { check(!closed) { "InkMeshRenderer is closed" } }
    private fun trimCache() {
        while (shapes.isNotEmpty() && (shapes.size > cacheCapacity || cachedGeometryBytes > cacheByteBudget)) {
            val oldest = shapes.entries.iterator()
            val geometry = oldest.next().value
            cachedGeometryBytes -= geometry.retainedBytes
            geometry.close(); oldest.remove()
        }
    }

    private fun choosePaint(brush: Brush, coat: Int, format: MeshFormat): BrushPaint? =
        brush.family.coats[coat].paintPreferences.firstOrNull { value ->
            value.isCompatibleWithMeshFormat(format) &&
                (value.selfOverlap != SelfOverlap.DISCARD || value.textureLayers.all { it is BrushPaint.TilingTexture }) &&
                value.textureLayers.all { layer -> texture(layer) != null }
        }

    private fun texture(layer: BrushPaint.TextureLayer): Shader? {
        textures[layer]?.let { return it }
        val id = when (layer) {
            is BrushPaint.TilingTexture -> layer.clientTextureId
            is BrushPaint.StampingTexture -> layer.clientTextureId
            else -> return null
        }
        val image = textureStore?.get(id) ?: return null
        require(!image.isClosed) { "Texture '$id' is closed" }
        val matrix: Matrix33
        val wrapX: FilterTileMode
        val wrapY: FilterTileMode
        when (layer) {
            is BrushPaint.TilingTexture -> {
                val radians = Math.toRadians(layer.rotationDegrees.toDouble())
                val c = cos(radians).toFloat(); val s = sin(radians).toFloat()
                matrix = Matrix33(layer.sizeX * c / image.width, -layer.sizeX * s / image.height,
                    layer.sizeX * (.5f + c * (layer.offsetX - .5f) - s * (layer.offsetY - .5f)),
                    layer.sizeY * s / image.width, layer.sizeY * c / image.height,
                    layer.sizeY * (.5f + s * (layer.offsetX - .5f) + c * (layer.offsetY - .5f)), 0f, 0f, 1f)
                wrapX = wrap(layer.wrapX); wrapY = wrap(layer.wrapY)
            }
            else -> { matrix = Matrix33.makeScale(1f / image.width, 1f / image.height); wrapX = FilterTileMode.REPEAT; wrapY = FilterTileMode.REPEAT }
        }
        val shader = image.makeShader(wrapX, wrapY, localMatrix = matrix)
        while (textures.size >= textureCacheCapacity) {
            val oldest = textures.entries.iterator(); oldest.next().value.close(); oldest.remove()
        }
        textures[layer] = shader
        return shader
    }

    private fun paintTexture(brush: Brush, value: BrushPaint): Shader? {
        var result: Shader? = null
        try {
            value.textureLayers.forEachIndexed { index, layer ->
                val base = requireNotNull(texture(layer)) { "Texture disappeared while drawing" }
                val matrix = if (layer is BrushPaint.TilingTexture) {
                    val unit = if (layer.sizeUnit == BrushPaint.TextureLayer.SizeUnit.BRUSH_SIZE) brush.size else 1f
                    val origin = when (layer.origin) {
                        BrushPaint.TilingTexture.Origin.FIRST_STROKE_INPUT -> first
                        BrushPaint.TilingTexture.Origin.LAST_STROKE_INPUT -> last
                        else -> null
                    }
                    Matrix33(unit, 0f, origin?.x ?: 0f, 0f, unit, origin?.y ?: 0f, 0f, 0f, 1f)
                } else Matrix33.IDENTITY
                val next = base.makeWithLocalMatrix(matrix)
                if (result == null) result = next else {
                    val previous = result
                    try { result = Shader.makeBlend(blend(value.textureLayers[index - 1].blendMode), next, previous) }
                    finally { next.close(); previous.close() }
                }
            }
            return result
        } catch (failure: Throwable) { result?.let { if (!it.isClosed) it.close() }; throw failure }
    }

    private fun drawCoats(canvas: Canvas, geometry: Geometry, brush: Brush, paints: List<BrushPaint>, colorArgb: Int?,
        meshes: (Int) -> List<StrokeMesh>, buildPath: (Int) -> InkRenderPath) {
        val m = canvas.skiaCanvas.localToDeviceAsMatrix33.mat
        require(m.all(Float::isFinite) && m[6] == 0f && m[7] == 0f && m[8] == 1f) { "Ink mesh rendering requires a finite affine canvas transform" }
        val linear = MeshLinearTransform(m[0], m[1], m[3], m[4])
        if (linear.a * linear.e - linear.b * linear.d == 0f) return
        paints.forEachIndexed { index, value ->
            val coat = geometry.coats[index]
            val texture = paintTexture(brush, value)
            try {
                if (value.selfOverlap == SelfOverlap.DISCARD) {
                    val path = coat.path ?: buildPath(index).also { coat.path = it }
                    if (texture == null) {
                        paint.color = value.composeColor(brush, colorArgb)
                        canvas.drawPath(path.path, paint)
                    } else {
                        val color = value.composeColor(brush, colorArgb)
                        val effect = pathEffect ?: RuntimeEffect.makeForShader(INK_PATH_TEXTURE_SKSL).also { pathEffect = it }
                        RuntimeShaderBuilder(effect).use { builder ->
                            builder.child("coatTexture", texture)
                            builder.uniform("brushColor", color.red, color.green, color.blue, color.alpha)
                            builder.uniform("textureBlend", blendIndex(value.textureLayers.last().blendMode))
                            builder.makeShader().use { shader ->
                                paint.color = Color.White; paint.shader = shader.asComposeShader()
                                try { canvas.drawPath(path.path, paint) } finally { paint.shader = null }
                            }
                        }
                    }
                } else {
                    val base = if (colorArgb == null) InkColor(brush.colorLong.toULong()) else InkColor(colorArgb)
                    val color = value.applyColorFunctions(base).convert(ColorSpaces.LinearExtendedSrgb)
                    val stamp = (value.textureLayers.firstOrNull() as? BrushPaint.StampingTexture)?.let(::stampAnimation)
                    val key = PreparedKey(linear, MeshColor(color.red, color.green, color.blue, color.alpha), stamp)
                    if (key != coat.key) {
                        coat.chunks = meshes(index).flatMap { prepareMesh(it, linear, key.color, stamp) }
                        coat.key = key
                        meshBuildCount++
                    }
                    val effect = meshEffect ?: RuntimeEffect.makeForShader(INK_MESH_SKSL).also { meshEffect = it }
                    val textureChild = texture ?: white ?: Shader.makeColor(0xffffffff.toInt()).also { white = it }
                    RuntimeShaderBuilder(effect).use { builder ->
                        builder.child("coatTexture", textureChild)
                        builder.uniform("hasTexture", if (texture == null) 0 else 1)
                        builder.uniform("textureBlend", if (texture == null) 0 else blendIndex(value.textureLayers.last().blendMode))
                        builder.uniform("colorSpaceProbe", .5f, .5f, .5f, 1f)
                        coat.chunks.forEach { chunk ->
                            builder.uniform("vertexData", chunk.varyings)
                            builder.makeShader().use { shader ->
                                paint.color = Color.White; paint.shader = shader.asComposeShader()
                                try { canvas.drawVertices(chunk.vertices, BlendMode.Modulate, paint) } finally { paint.shader = null }
                            }
                        }
                    }
                }
            } finally { texture?.close() }
        }
    }

    private fun stampAnimation(layer: BrushPaint.StampingTexture): StampAnimation {
        val duration = layer.animationDurationMillis
        val progress = if (duration == 0L) 0f else {
            val fraction = (animationTimeMillis % duration).toDouble() / duration
            if (layer.animationRepeatMode == BrushPaint.TextureLayer.AnimationRepeatMode.REVERSE && (animationTimeMillis / duration) % 2L != 0L)
                (1.0 - fraction).toFloat() else fraction.toFloat()
        }
        return StampAnimation(progress, layer.animationFrames, layer.animationRows, layer.animationColumns)
    }

    private fun finishedPath(shape: PartitionedMesh, coat: Int, meshes: () -> List<StrokeMesh>): InkRenderPath {
        val outlines = InkMeshes.outlines(shape, coat)
        if (outlines.any { it.isNotEmpty() }) return outlineInkPath(outlines)
        return buildInkPath {
            meshes().forEach { mesh ->
                val p = mesh.vertices
                for (t in mesh.triangles.indices step 3) {
                    val a = mesh.triangles[t] * StrokeMesh.VERTEX_STRIDE
                    var b = mesh.triangles[t + 1] * StrokeMesh.VERTEX_STRIDE
                    var c = mesh.triangles[t + 2] * StrokeMesh.VERTEX_STRIDE
                    val area = (p[b] - p[a]) * (p[c + 1] - p[a + 1]) - (p[b + 1] - p[a + 1]) * (p[c] - p[a])
                    if (area == 0f) continue
                    if (area < 0f) { val swap = b; b = c; c = swap }
                    moveTo(p[a], p[a + 1]); lineTo(p[b], p[b + 1]); lineTo(p[c], p[c + 1]); closePath()
                }
            }
        }
    }

    private fun releaseCollected() {
        while (true) { val key = queue.poll() ?: return; live.remove(key)?.geometry?.close() }
    }

    private class StrokeReference(stroke: InProgressStroke, queue: ReferenceQueue<InProgressStroke>? = null) : WeakReference<InProgressStroke>(stroke, queue) {
        private val hash = System.identityHashCode(stroke)
        override fun hashCode(): Int = hash
        override fun equals(other: Any?): Boolean = this === other || (other is StrokeReference && get()?.let { it === other.get() } == true)
    }
}

/** Remembers a full renderer and releases all owned paths, shaders and effects on disposal. */
@Composable
public fun rememberInkMeshRenderer(textureStore: InkTextureStore? = null): InkMeshRenderer {
    val renderer = remember(textureStore) { InkMeshRenderer(textureStore) }
    DisposableEffect(renderer) { onDispose { renderer.close() } }
    return renderer
}

private fun wrap(value: BrushPaint.TextureLayer.Wrap): FilterTileMode = when (value) {
    BrushPaint.TextureLayer.Wrap.REPEAT -> FilterTileMode.REPEAT
    BrushPaint.TextureLayer.Wrap.MIRROR -> FilterTileMode.MIRROR
    BrushPaint.TextureLayer.Wrap.CLAMP -> FilterTileMode.CLAMP
    else -> error("Unknown Ink texture wrap: $value")
}

private val inkBlends = listOf(BrushPaint.TextureLayer.BlendMode.MODULATE, BrushPaint.TextureLayer.BlendMode.DST_IN,
    BrushPaint.TextureLayer.BlendMode.DST_OUT, BrushPaint.TextureLayer.BlendMode.SRC_ATOP, BrushPaint.TextureLayer.BlendMode.SRC_IN,
    BrushPaint.TextureLayer.BlendMode.SRC_OVER, BrushPaint.TextureLayer.BlendMode.DST_OVER, BrushPaint.TextureLayer.BlendMode.SRC,
    BrushPaint.TextureLayer.BlendMode.DST, BrushPaint.TextureLayer.BlendMode.SRC_OUT, BrushPaint.TextureLayer.BlendMode.DST_ATOP,
    BrushPaint.TextureLayer.BlendMode.XOR)
private val skiaBlends = listOf(SkiaBlendMode.MODULATE, SkiaBlendMode.DST_IN, SkiaBlendMode.DST_OUT, SkiaBlendMode.SRC_ATOP,
    SkiaBlendMode.SRC_IN, SkiaBlendMode.SRC_OVER, SkiaBlendMode.DST_OVER, SkiaBlendMode.SRC, SkiaBlendMode.DST,
    SkiaBlendMode.SRC_OUT, SkiaBlendMode.DST_ATOP, SkiaBlendMode.XOR)
private fun blendIndex(value: BrushPaint.TextureLayer.BlendMode): Int = inkBlends.indexOf(value).also { require(it >= 0) { "Unknown Ink texture blend: $value" } }
private fun blend(value: BrushPaint.TextureLayer.BlendMode): SkiaBlendMode = skiaBlends[blendIndex(value)]
