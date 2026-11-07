package com.vivenotes.byteink.portable

import com.vivenotes.byteink.kit.*

/** One captured ViveNotes style. Unknown family IDs retain Android's pressure-pen fallback. */
public data class InkStyle(
    public val familyId: String = InkBrushCatalog.MARKER,
    public val stabilization: Int = 0,
    public val colorArgb: Int = 0xff000000.toInt(),
    public val size: Float = 4f,
    public val epsilon: Float = InkBrushCatalog.EPSILON,
) {
    init { require(size.isFinite() && epsilon.isFinite() && epsilon > 0f && size >= epsilon) { "Invalid brush size/epsilon" } }

    public companion object {
        public fun forPen(pen: PenPreset): InkStyle = InkStyle(
            InkBrushCatalog.penFamilyId(pen.lineType == LineType.Solid, pen.kind == PenKind.Fountain, pen.pressure),
            pen.stabilization, pen.colorArgb, pen.thickness)
        public fun forHighlighter(highlighter: HighlighterSettings): InkStyle =
            InkStyle(InkBrushCatalog.HIGHLIGHTER, 0, highlighter.colorArgb, highlighter.thickness.toFloat())
    }
}

public enum class InkTool { UNKNOWN, MOUSE, TOUCH, STYLUS }

/** Real observations. Optional channels must have consistent presence within a stroke. */
public data class InkSample(
    public val x: Float,
    public val y: Float,
    public val elapsedTimeMillis: Long,
    public val tool: InkTool = InkTool.UNKNOWN,
    public val pressure: Float? = null,
    public val tiltRadians: Float? = null,
    public val orientationRadians: Float? = null,
    public val strokeUnitLengthCm: Float? = null,
) {
    init {
        require(x.isFinite() && y.isFinite() && elapsedTimeMillis >= 0) { "Input coordinates/time must be valid" }
        require(pressure == null || pressure.isFinite() && pressure in 0f..1f) { "Invalid pressure" }
        require(tiltRadians == null || tiltRadians.isFinite() && tiltRadians in 0f..(kotlin.math.PI / 2).toFloat()) { "Invalid tilt" }
        require(orientationRadians == null || orientationRadians.isFinite() && orientationRadians >= 0f &&
            orientationRadians < (2 * kotlin.math.PI).toFloat()) { "Invalid orientation" }
        require(strokeUnitLengthCm == null || strokeUnitLengthCm.isFinite() && strokeUnitLengthCm > 0f) { "Invalid unit length" }
    }
}

/** Axis-aligned stroke-to-page mapping. The stored resize format uses positive scales. */
public data class InkTransform(
    public val scaleX: Float = 1f,
    public val scaleY: Float = 1f,
    public val offsetX: Float = 0f,
    public val offsetY: Float = 0f,
) {
    init {
        require(scaleX.isFinite() && scaleY.isFinite() && scaleX > 0 && scaleY > 0 && offsetX.isFinite() && offsetY.isFinite()) {
            "A page transform must have finite positive scale and finite translation"
        }
    }
    public fun apply(point: InkPoint): InkPoint = InkPoint(point.x * scaleX + offsetX, point.y * scaleY + offsetY)
    public fun apply(bounds: InkBounds): InkBounds = InkBounds(bounds.left * scaleX + offsetX, bounds.top * scaleY + offsetY,
        bounds.right * scaleX + offsetX, bounds.bottom * scaleY + offsetY)
}

public class InkMesh(public val positions: FloatArray, public val triangles: IntArray)
public class InkGeometry(public val meshes: List<InkMesh>, public val outlines: List<FloatArray>)

/** An owned engine stroke. Geometry snapshots survive close; close releases this handle only. */
public abstract class InkStroke protected constructor(public val style: InkStyle) : AutoCloseable {
    public abstract val bounds: InkBounds?
    public abstract fun geometry(): List<InkGeometry>
    public abstract fun inputs(): List<InkSample>
    public abstract fun encodeInputs(): ByteArray
    public abstract fun contains(x: Float, y: Float): Boolean
    /** Native coverage of this stroke by a box in stroke coordinates. */
    public abstract fun coverage(box: InkBounds): Float
    public abstract fun copy(): InkStroke
    public abstract fun intersects(other: InkStroke, thisToPage: InkTransform, otherToPage: InkTransform = InkTransform()): Boolean
    public abstract fun subtract(mask: InkStroke, thisToPage: InkTransform): InkStroke
    public abstract fun split(thisToPage: InkTransform): List<InkStroke>
    public abstract fun enclosedBy(path: List<InkPoint>, thisToPage: InkTransform, edgeTolerance: Float = 4f): Boolean
}

/** Platform engine entry point. Geometry and codecs always use the pinned real Ink engine. */
public interface InkEngine {
    /** Uncompressed upstream BrushFamily protobuf for the captured ViveNotes style. */
    public fun familyProto(style: InkStyle): ByteArray
    public fun createStroke(style: InkStyle, inputs: List<InkSample>): InkStroke
    public fun decodeStroke(style: InkStyle, androidInputs: ByteArray): InkStroke
}
