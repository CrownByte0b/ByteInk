package com.vivenotes.byteink.core

import androidx.ink.nativeloader.UsedByNative

/**
 * An owned rendering snapshot of one Ink mesh partition. Each vertex occupies [VERTEX_STRIDE]
 * floats: position XY, opacity shift, HSL shift, side derivative XY and label, forward derivative
 * XY and label, surface UV, and animation offset. Missing attributes are zero; [attributeMask]
 * records which attributes the source format contains, in that order (bits 0 through 8).
 * Indices are unsigned native indices widened to Int. Arrays never reference native memory.
 */
@UsedByNative
public class StrokeMesh @UsedByNative constructor(
    public val vertices: FloatArray,
    public val triangles: IntArray,
    public val attributeMask: Int,
) {
    public companion object {
        public const val VERTEX_STRIDE: Int = 15
    }

    public val vertexCount: Int get() = vertices.size / VERTEX_STRIDE
    public val triangleCount: Int get() = triangles.size / 3
    public val hasSurfaceUv: Boolean get() = attributeMask and (1 shl 7) != 0
    public val hasAnimationOffset: Boolean get() = attributeMask and (1 shl 8) != 0
}
