package com.vivenotes.byteink.wasm

/** Owned canonical mesh: x/y, opacity, HSL, side derivative/label, forward derivative/label,
 * surface UV and animation offset. Absent attributes are zero; mask bit order follows that list.
 * Arrays survive engine updates/close and must be treated as immutable while a renderer uses them.
 */
public class InkRenderingMesh internal constructor(
    public val vertices: FloatArray,
    public val triangles: IntArray,
    public val attributeMask: Int,
) {
    public val vertexCount: Int get() = vertices.size / VERTEX_STRIDE
    public val triangleCount: Int get() = triangles.size / 3
    public companion object { public const val VERTEX_STRIDE: Int = 15 }
}

public enum class InkSelfOverlap { ANY, ACCUMULATE, DISCARD }

/** Blend indices are the pinned Ink order: modulate, dstIn, dstOut, srcAtop, srcIn, srcOver,
 * dstOver, src, dst, srcOut, dstAtop, xor. Texture images are supplied separately by client ID.
 */
public sealed interface InkRenderTexture {
    public val id: String
    public val blend: Int
}

/** Origin: stroke/first/last input (0/1/2); units: brush size/stroke (0/1); wrap: repeat/mirror/clamp (0/1/2). */
@ConsistentCopyVisibility
public data class InkTilingTexture internal constructor(
    override val id: String, override val blend: Int,
    public val origin: Int, public val sizeUnit: Int, public val wrapX: Int, public val wrapY: Int,
    public val sizeX: Float, public val sizeY: Float, public val offsetX: Float, public val offsetY: Float,
    public val rotationRadians: Float,
) : InkRenderTexture

@ConsistentCopyVisibility
public data class InkStampingTexture internal constructor(
    override val id: String, override val blend: Int,
    public val frames: Int, public val rows: Int, public val columns: Int,
    public val durationMillis: Long, public val reverse: Boolean,
) : InkRenderTexture

public class InkRenderingPaint internal constructor(
    public val compatible: Boolean,
    public val selfOverlap: InkSelfOverlap,
    /** Unpremultiplied linear sRGB, after the engine's coat color functions. */
    public val linearColor: FloatArray,
    /** Unpremultiplied gamma-encoded sRGB for uniform path fills. */
    public val srgbColor: FloatArray,
    public val textures: List<InkRenderTexture>,
)

public class InkRenderingGroup internal constructor(
    public val meshes: List<InkRenderingMesh>, public val outlines: List<FloatArray>,
    public val paints: List<InkRenderingPaint>,
)

public class InkRendering internal constructor(
    public val groups: List<InkRenderingGroup>, public val brushSize: Float,
    /** First x/y, last x/y (including prediction for a live stroke). */
    public val inputPositions: FloatArray,
)
