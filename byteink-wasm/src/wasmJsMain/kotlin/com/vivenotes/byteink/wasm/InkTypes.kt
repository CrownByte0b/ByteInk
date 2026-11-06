package com.vivenotes.byteink.wasm

/** Pinned version 1 stock families. The highlighter uses DISCARD self overlap. */
public enum class InkStockBrush { MARKER, PRESSURE_PEN, HIGHLIGHTER, DASHED_LINE }

public enum class InkToolType { UNKNOWN, MOUSE, TOUCH, STYLUS }

/** Width and epsilon are finite positive values in the same units as input positions. */
public class InkBrush private constructor(
    /** Null when constructed from a serialized family. */
    public val family: InkStockBrush?,
    public val colorArgb: Int,
    public val size: Float,
    public val epsilon: Float,
    internal val encodedFamily: ByteArray?,
) {
    public constructor(
        family: InkStockBrush = InkStockBrush.MARKER,
        colorArgb: Int = 0xff000000.toInt(),
        size: Float = 4f,
        epsilon: Float = 0.25f,
    ) : this(family, colorArgb, size, epsilon, null)

    public companion object {
        /** Use an upstream BrushFamily protobuf, including custom tips, behaviors and input models.
         * Textured families need a renderer with texture support; geometry alone cannot paint them.
         */
        public fun fromFamilyProto(
            encodedFamily: ByteArray,
            colorArgb: Int = 0xff000000.toInt(),
            size: Float = 4f,
            epsilon: Float = 0.25f,
        ): InkBrush {
            require(encodedFamily.isNotEmpty() && encodedFamily.size <= 64 * 1024 * 1024) { "Invalid brush family protobuf size" }
            return InkBrush(null, colorArgb, size, epsilon, encodedFamily.copyOf())
        }
    }

    init {
        require(size.isFinite() && epsilon.isFinite() && epsilon > 0f && size >= epsilon) {
            "Brush size must be finite and at least its positive epsilon"
        }
    }
}

/** Optional pen attributes must be present consistently throughout one stroke. Time starts at 0. */
public data class InkSample(
    public val x: Float,
    public val y: Float,
    public val elapsedTimeMillis: Double,
    public val toolType: InkToolType = InkToolType.UNKNOWN,
    public val pressure: Float? = null,
    public val tiltRadians: Float? = null,
    public val orientationRadians: Float? = null,
    public val strokeUnitLengthCm: Float? = null,
) {
    init {
        require(x.isFinite() && y.isFinite()) { "Input positions must be finite" }
        require(elapsedTimeMillis.isFinite() && elapsedTimeMillis >= 0 && elapsedTimeMillis <= Float.MAX_VALUE.toDouble()) {
            "Elapsed time must be finite and nonnegative"
        }
        require(pressure == null || pressure.isFinite() && pressure in 0f..1f) { "Pressure must be in [0, 1]" }
        require(tiltRadians == null || tiltRadians.isFinite() && tiltRadians in 0f..(kotlin.math.PI / 2).toFloat()) {
            "Tilt must be in [0, pi/2]"
        }
        require(orientationRadians == null || orientationRadians.isFinite() && orientationRadians >= 0f && orientationRadians < (2 * kotlin.math.PI).toFloat()) {
            "Orientation must be in [0, 2*pi)"
        }
        require(strokeUnitLengthCm == null || strokeUnitLengthCm.isFinite() && strokeUnitLengthCm > 0f) {
            "Stroke unit length must be finite and positive"
        }
    }
}

/** Owned position pairs and triangle indices, copied out of the engine. No Wasm memory views. */
public class InkMesh internal constructor(public val positions: FloatArray, public val triangles: IntArray) {
    public val vertexCount: Int get() = positions.size / 2
    public val triangleCount: Int get() = triangles.size / 3
}

/** Owned snapshots of one coat. Outlines are closed contours of x/y pairs. */
public class InkGeometryGroup internal constructor(public val meshes: List<InkMesh>, public val outlines: List<FloatArray>)
