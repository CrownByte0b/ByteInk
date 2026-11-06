@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.vivenotes.byteink.wasm

import kotlinx.coroutines.await
import com.vivenotes.byteink.kit.InkBounds
import com.vivenotes.byteink.kit.InkBrushCatalog
import com.vivenotes.byteink.kit.InkFamilyDefinition

/** One asynchronously loaded, isolated instance of the pinned Google Ink engine. */
public class InkWasm private constructor(private val module: NativeModule) {
    private val families: MutableMap<InkFamilyDefinition, ByteArray> = mutableMapOf()
    public val engineCommit: String get() = module.engineCommit()

    public companion object {
        /** Load byteink-engine.mjs; its .wasm file must be served beside it. Propagates load errors. */
        public suspend fun load(moduleUrl: String): InkWasm {
            require(moduleUrl.isNotBlank()) { "Engine module URL must not be blank" }
            val module = importEngine(moduleUrl).await()
            check(module.abiVersion() == 3) { "Incompatible ByteInk Wasm bridge ABI" }
            check(module.engineCommit() == ENGINE_COMMIT) { "Incompatible Ink engine pin" }
            return InkWasm(module)
        }
    }

    /** Exact ViveNotes catalog family, serialized by the real C++ engine. Returned bytes are owned. */
    public fun catalogFamily(familyId: String, stabilization: Int): ByteArray {
        val definition = InkBrushCatalog.describe(familyId, stabilization)
        return families.getOrPut(definition) {
            val bytes = dataOperation { module.catalogFamily(definition.kind, definition.pressureLevel,
                definition.windowMillis, definition.minSize, definition.maxSize) }
            ByteArray(bytes.length) { numberAt(bytes, it).toInt().toByte() }
        }.copyOf()
    }

    public fun catalogBrush(familyId: String, stabilization: Int, colorArgb: Int, size: Float,
                            epsilon: Float = InkBrushCatalog.EPSILON): InkBrush =
        InkBrush.fromFamilyProto(catalogFamily(familyId, stabilization), colorArgb, size, epsilon)

    public fun createStroke(brush: InkBrush, inputs: List<InkSample>): InkStroke {
        val family = brush.family
        val nativeInputs = inputs.nativeInputs()
        val color = brush.colorArgb.toUInt().toDouble()
        val native = dataOperation { if (family != null) module.createStroke(family.ordinal, color, brush.size, brush.epsilon, nativeInputs)
            else module.createStrokeWithFamily(brush.encodedFamily!!.nativeBytes(), color, brush.size, brush.epsilon, nativeInputs, brush.allowDevelopmentVersion) }
        return InkStroke(brush, native, this)
    }

    /** Rebuild from the raw upstream CodedStrokeInputBatch protobuf, without a gzip envelope. */
    public fun decodeStroke(brush: InkBrush, encodedInputs: ByteArray): InkStroke {
        val bytes = encodedInputs.nativeBytes()
        val family = brush.family
        val color = brush.colorArgb.toUInt().toDouble()
        val native = dataOperation { if (family != null) module.decodeStroke(family.ordinal, color, brush.size, brush.epsilon, bytes)
            else module.decodeStrokeWithFamily(brush.encodedFamily!!.nativeBytes(), color, brush.size, brush.epsilon, bytes, brush.allowDevelopmentVersion) }
        return InkStroke(brush, native, this)
    }

    /** Rebuild from ViveNotes' Android gzip input encoding, with bounded inflation and CRC checks. */
    public fun decodeAndroidStroke(brush: InkBrush, encodedInputs: ByteArray): InkStroke {
        val bytes = encodedInputs.nativeBytes()
        val family = brush.family
        val color = brush.colorArgb.toUInt().toDouble()
        val native = dataOperation { if (family != null) module.decodeAndroidStroke(family.ordinal, color, brush.size, brush.epsilon, bytes)
            else module.decodeAndroidStrokeWithFamily(brush.encodedFamily!!.nativeBytes(), color, brush.size, brush.epsilon, bytes, brush.allowDevelopmentVersion) }
        return InkStroke(brush, native, this)
    }

    public fun startStroke(brush: InkBrush): InkLiveStroke {
        val family = brush.family
        val color = brush.colorArgb.toUInt().toDouble()
        val native = dataOperation { if (family != null) module.startStroke(family.ordinal, color, brush.size, brush.epsilon)
            else module.startStrokeWithFamily(brush.encodedFamily!!.nativeBytes(), color, brush.size, brush.epsilon, brush.allowDevelopmentVersion) }
        return InkLiveStroke(brush, native, this)
    }
}

/** Owns a finished C++ stroke. Close when discarded; geometry/inputs returned earlier remain valid. */
public class InkStroke internal constructor(public val brush: InkBrush, private val native: NativeStroke, private val owner: InkWasm) : AutoCloseable {
    private var closed = false
    private fun checkOpen(): Unit = check(!closed) { "Stroke is closed" }
    public val isClosed: Boolean get() = closed
    /** Owned canonical vertices and all compatible paint preferences, with engine-applied colors. */
    public fun rendering(): InkRendering { checkOpen(); return renderingSnapshot(native.rendering()) }

    public fun geometry(): List<InkGeometryGroup> {
        checkOpen()
        return geometrySnapshot(native.geometry())
    }

    public fun inputs(): List<InkSample> {
        checkOpen()
        val values = native.inputs()
        return List(values.length / 8) { i ->
            fun field(offset: Int): Double = numberAt(values, i * 8 + offset)
            InkSample(field(0).toFloat(), field(1).toFloat(), field(2), InkToolType.entries[field(3).toInt()],
                field(4).takeIf { it >= 0 }?.toFloat(), field(5).takeIf { it >= 0 }?.toFloat(),
                field(6).takeIf { it >= 0 }?.toFloat(), field(7).takeIf { it > 0 }?.toFloat())
        }
    }

    /** Raw upstream input protobuf. Store brush configuration alongside it to rebuild the stroke. */
    public fun encodeInputs(): ByteArray {
        checkOpen()
        val bytes = native.encodeInputs()
        return ByteArray(bytes.length) { numberAt(bytes, it).toInt().toByte() }
    }

    /** Android-compatible gzip input blob, including the private zero animation phase field.
     * Encoding quantizes inputs; retain the original stored bytes when reading existing rows.
     */
    public fun encodeAndroidInputs(): ByteArray {
        checkOpen()
        val bytes = native.encodeAndroidInputs()
        return ByteArray(bytes.length) { numberAt(bytes, it).toInt().toByte() }
    }

    public fun contains(x: Float, y: Float): Boolean {
        checkOpen()
        require(x.isFinite() && y.isFinite()) { "Hit point must be finite" }
        return native.contains(x, y)
    }

    public fun coverage(left: Float, top: Float, right: Float, bottom: Float): Float {
        checkOpen()
        return dataOperation { native.coverage(left, top, right, bottom) }
    }

    public val bounds: InkBounds? get() {
        checkOpen()
        val value = native.bounds() ?: return null
        return InkBounds(numberAt(value, 0).toFloat(), numberAt(value, 1).toFloat(), numberAt(value, 2).toFloat(), numberAt(value, 3).toFloat())
    }

    public fun copy(): InkStroke { checkOpen(); return InkStroke(brush, native.copy(), owner) }

    /** Transform arrays are scaleX, scaleY, offsetX, offsetY in page units. */
    public fun intersects(other: InkStroke, thisToPage: FloatArray, otherToPage: FloatArray = floatArrayOf(1f, 1f, 0f, 0f)): Boolean {
        checkOpen(); other.checkOpen(); require(owner === other.owner) { "Strokes belong to different engine instances" }
        return dataOperation { native.intersects(other.native, thisToPage.nativeNumbers(), otherToPage.nativeNumbers()) }
    }

    public fun subtract(mask: InkStroke, thisToPage: FloatArray): InkStroke {
        checkOpen(); mask.checkOpen(); require(owner === mask.owner) { "Strokes belong to different engine instances" }
        return InkStroke(brush, dataOperation { native.subtract(mask.native, thisToPage.nativeNumbers()) }, owner)
    }

    public fun split(thisToPage: FloatArray): List<InkStroke> {
        checkOpen()
        val pieces = dataOperation { native.split(thisToPage.nativeNumbers()) }
        return List(pieces.length) { InkStroke(brush, pieces[it]!!, owner) }
    }

    public fun touchesTriangle(points: FloatArray, thisToPage: FloatArray): Boolean {
        checkOpen(); return dataOperation { native.touchesTriangle(points.nativeNumbers(), thisToPage.nativeNumbers()) }
    }

    public fun overlapsPolygon(points: FloatArray, thisToPage: FloatArray): Boolean {
        checkOpen(); return dataOperation { native.overlapsPolygon(points.nativeNumbers(), thisToPage.nativeNumbers()) }
    }

    override public fun close() {
        if (!closed) { closed = true; native.delete() }
    }
}

/** Incremental engine authoring. Prediction is replaced per enqueue and removed before finish. */
public class InkLiveStroke internal constructor(public val brush: InkBrush, private val native: NativeLive, private val owner: InkWasm) : AutoCloseable {
    private var closed = false
    private var finished = false
    private fun checkOpen(): Unit = check(!closed) { "Live stroke is closed" }
    public val isClosed: Boolean get() = closed
    /** Changes after every successful shape update, including timed effects and prediction removal. */
    public var version: Long = 0L
        private set
    public val needsUpdate: Boolean get() { checkOpen(); return native.needsUpdate() }
    public fun rendering(): InkRendering { checkOpen(); return renderingSnapshot(native.rendering()) }

    public val realInputCount: Int get() { checkOpen(); return native.realInputCount() }
    public val predictedInputCount: Int get() { checkOpen(); return native.predictedInputCount() }

    public fun enqueue(inputs: List<InkSample>, predictedInputs: List<InkSample> = emptyList()) {
        checkOpen()
        check(!finished) { "Inputs are finished" }
        dataOperation { native.enqueue(inputs.nativeInputs(), predictedInputs.nativeInputs()) }
    }

    public fun update(elapsedTimeMillis: Double) {
        checkOpen()
        check(!finished) { "Inputs are finished" }
        dataOperation { native.update(checkedTime(elapsedTimeMillis)) }
        version++
    }

    public fun geometry(): List<InkGeometryGroup> {
        checkOpen()
        return geometrySnapshot(native.geometry())
    }

    /** Removes prediction and rebuilds finished geometry from real inputs to flush modelling.
     * Returns a separate owned stroke. Close the live object separately.
     */
    public fun finish(elapsedTimeMillis: Double): InkStroke {
        checkOpen()
        check(!finished) { "Inputs are finished" }
        val result = dataOperation { native.finish(checkedTime(elapsedTimeMillis)) }
        finished = true
        version++
        return InkStroke(brush, result, owner)
    }

    override public fun close() {
        if (!closed) { closed = true; native.delete() }
    }

    private fun checkedTime(time: Double): Float {
        require(time.isFinite() && time >= 0 && time <= Float.MAX_VALUE.toDouble()) { "Elapsed time must be finite and nonnegative" }
        return time.toFloat()
    }
}
