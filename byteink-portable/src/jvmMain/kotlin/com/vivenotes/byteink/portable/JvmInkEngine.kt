@file:OptIn(androidx.ink.strokes.ExperimentalInkEraserApi::class)

package com.vivenotes.byteink.portable

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableAffineTransform
import androidx.ink.geometry.Intersection.intersects
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke as NativeStroke
import androidx.ink.storage.encode
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.kit.*

/** JVM adapter over the existing AndroidX types. Legacy kit entry points remain available. */
public class JvmInkEngine : InkEngine {
    override public fun familyProto(style: InkStyle): ByteArray = java.util.zip.GZIPInputStream(
        ViveBrushes.family(style.familyId, style.stabilization).encode().inputStream()).use { it.readBytes() }
    override public fun createStroke(style: InkStyle, inputs: List<InkSample>): InkStroke {
        val brush = Brush.createWithColorIntArgb(ViveBrushes.family(style.familyId, style.stabilization),
            style.colorArgb, style.size, style.epsilon)
        val batch = MutableStrokeInputBatch()
        inputs.forEach { sample -> batch.add(InputToolType.entriesCompat(sample.tool), sample.x, sample.y, sample.elapsedTimeMillis,
            sample.strokeUnitLengthCm ?: 0f, sample.pressure ?: -1f, sample.tiltRadians ?: -1f, sample.orientationRadians ?: -1f) }
        return JvmStroke(style, NativeStroke(brush, batch.toImmutable()), this)
    }

    override public fun decodeStroke(style: InkStyle, androidInputs: ByteArray): InkStroke {
        val row = StoredInkStroke("", "", 0, style.familyId, 1, style.size, style.colorArgb, null, style.epsilon,
            style.stabilization, 0f, 0f, 0f, 0f, androidInputs, ViveInkCodec.ENCODING, 0)
        return JvmStroke(style, requireNotNull(ViveInkCodec.decode(row)) { "Unreadable Android stroke inputs" }, this)
    }

    /** Borrow the original AndroidX stroke for Compose/Skia integration; retain its owner while reading. */
    public fun nativeStroke(stroke: InkStroke): NativeStroke = checked(stroke).value

    private fun checked(stroke: InkStroke): JvmStroke {
        require(stroke is JvmStroke && stroke.owner === this) { "Stroke belongs to another engine" }
        return stroke
    }

    private class JvmStroke(style: InkStyle, native: NativeStroke, val owner: JvmInkEngine) : InkStroke(style) {
        private var peer: NativeStroke? = native
        val value: NativeStroke get() = checkNotNull(peer) { "Stroke is closed" }
        override val bounds: InkBounds? get() = value.shape.computeBoundingBox()?.let { InkBounds(it.xMin, it.yMin, it.xMax, it.yMax) }
        override fun geometry(): List<InkGeometry> {
            val shape = value.shape
            return List(shape.getRenderGroupCount()) { group -> InkGeometry(
                InkMeshes.triangles(shape, group).map { InkMesh(it.positions, it.triangles) }, InkMeshes.outlines(shape, group)) }
        }
        override fun inputs(): List<InkSample> = List(value.inputs.size) { i ->
            val sample = value.inputs[i]
            InkSample(sample.x, sample.y, sample.elapsedTimeMillis,
                when (sample.toolType) { InputToolType.MOUSE -> InkTool.MOUSE; InputToolType.TOUCH -> InkTool.TOUCH
                    InputToolType.STYLUS -> InkTool.STYLUS; else -> InkTool.UNKNOWN },
                sample.pressure.takeIf { it >= 0 }, sample.tiltRadians.takeIf { it >= 0 },
                sample.orientationRadians.takeIf { it >= 0 }, sample.strokeUnitLengthCm.takeIf { it > 0 })
        }
        override fun encodeInputs(): ByteArray = ViveInkCodec.encodeStroke(value, "", "", 0, style.familyId,
            style.stabilization, false, 0).points
        override fun contains(x: Float, y: Float): Boolean {
            require(x.isFinite() && y.isFinite())
            return bounds != null && value.shape.intersects(ImmutableVec(x, y), AffineTransform.IDENTITY)
        }
        override fun coverage(box: InkBounds): Float {
            require(box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                box.left <= box.right && box.top <= box.bottom) { "Invalid coverage box" }
            return if (bounds == null) 0f else value.shape.computeCoverage(
                ImmutableBox.fromTwoPoints(ImmutableVec(box.left,box.top),ImmutableVec(box.right,box.bottom)))
        }
        override fun copy(): InkStroke = JvmStroke(style, value.copy(value.brush), owner)
        override fun intersects(other: InkStroke, thisToPage: InkTransform, otherToPage: InkTransform): Boolean {
            val mask = owner.checked(other).value
            val toThis = MutableAffineTransform().also { AffineTransform.multiply(thisToPage.native().computeInverse(), otherToPage.native(), it) }
            return bounds != null && other.bounds != null && value.shape.computeCoverageIsGreaterThan(mask.shape, 0f,
                toThis)
        }
        override fun subtract(mask: InkStroke, thisToPage: InkTransform): InkStroke =
            JvmStroke(style, value.subtract(owner.checked(mask).value.shape, AffineTransform.IDENTITY, thisToPage.native()), owner)
        override fun split(thisToPage: InkTransform): List<InkStroke> = value.split(thisToPage.native(), 0f).map { JvmStroke(style, it, owner) }
        override fun enclosedBy(path: List<InkPoint>, thisToPage: InkTransform, edgeTolerance: Float): Boolean =
            LassoShape(path, edgeTolerance).contains(PageStroke("", value, thisToPage.offsetX, thisToPage.offsetY,
                thisToPage.scaleX, thisToPage.scaleY))
        override fun close() { peer = null }
    }
}

private fun InkTransform.native(): AffineTransform = ImmutableAffineTransform(scaleX, 0f, offsetX, 0f, scaleY, offsetY)
private fun InputToolType.Companion.entriesCompat(tool: InkTool): InputToolType = when (tool) {
    InkTool.MOUSE -> InputToolType.MOUSE; InkTool.TOUCH -> InputToolType.TOUCH
    InkTool.STYLUS -> InputToolType.STYLUS; InkTool.UNKNOWN -> InputToolType.UNKNOWN
}
