@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.vivenotes.byteink.wasm

import kotlinx.coroutines.await

/** One asynchronously loaded, isolated instance of the pinned Google Ink engine. */
public class InkWasm private constructor(private val module: NativeModule) {
    public val engineCommit: String get() = module.engineCommit()

    public companion object {
        /** Load byteink-engine.mjs; its .wasm file must be served beside it. Propagates load errors. */
        public suspend fun load(moduleUrl: String): InkWasm {
            require(moduleUrl.isNotBlank()) { "Engine module URL must not be blank" }
            val module = importEngine(moduleUrl).await()
            check(module.abiVersion() == 1) { "Incompatible ByteInk Wasm bridge ABI" }
            check(module.engineCommit() == ENGINE_COMMIT) { "Incompatible Ink engine pin" }
            return InkWasm(module)
        }
    }

    public fun createStroke(brush: InkBrush, inputs: List<InkSample>): InkStroke {
        val family = brush.family
        val nativeInputs = inputs.nativeInputs()
        val color = brush.colorArgb.toUInt().toDouble()
        val native = if (family != null) module.createStroke(family.ordinal, color, brush.size, brush.epsilon, nativeInputs)
            else module.createStrokeWithFamily(brush.encodedFamily!!.nativeBytes(), color, brush.size, brush.epsilon, nativeInputs)
        return InkStroke(brush, native)
    }

    /** Rebuild from the raw upstream CodedStrokeInputBatch protobuf, without a gzip envelope. */
    public fun decodeStroke(brush: InkBrush, encodedInputs: ByteArray): InkStroke {
        val bytes = encodedInputs.nativeBytes()
        val family = brush.family
        val color = brush.colorArgb.toUInt().toDouble()
        val native = if (family != null) module.decodeStroke(family.ordinal, color, brush.size, brush.epsilon, bytes)
            else module.decodeStrokeWithFamily(brush.encodedFamily!!.nativeBytes(), color, brush.size, brush.epsilon, bytes)
        return InkStroke(brush, native)
    }

    /** Rebuild from ViveNotes' Android gzip input encoding, with bounded inflation and CRC checks. */
    public fun decodeAndroidStroke(brush: InkBrush, encodedInputs: ByteArray): InkStroke {
        val bytes = encodedInputs.nativeBytes()
        val family = brush.family
        val color = brush.colorArgb.toUInt().toDouble()
        val native = if (family != null) module.decodeAndroidStroke(family.ordinal, color, brush.size, brush.epsilon, bytes)
            else module.decodeAndroidStrokeWithFamily(brush.encodedFamily!!.nativeBytes(), color, brush.size, brush.epsilon, bytes)
        return InkStroke(brush, native)
    }

    public fun startStroke(brush: InkBrush): InkLiveStroke {
        val family = brush.family
        val color = brush.colorArgb.toUInt().toDouble()
        val native = if (family != null) module.startStroke(family.ordinal, color, brush.size, brush.epsilon)
            else module.startStrokeWithFamily(brush.encodedFamily!!.nativeBytes(), color, brush.size, brush.epsilon)
        return InkLiveStroke(brush, native)
    }
}

/** Owns a finished C++ stroke. Close when discarded; geometry/inputs returned earlier remain valid. */
public class InkStroke internal constructor(public val brush: InkBrush, private val native: NativeStroke) : AutoCloseable {
    private var closed = false
    private fun checkOpen(): Unit = check(!closed) { "Stroke is closed" }

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

    override public fun close() {
        if (!closed) { closed = true; native.delete() }
    }
}

/** Incremental engine authoring. Prediction is replaced per enqueue and removed before finish. */
public class InkLiveStroke internal constructor(public val brush: InkBrush, private val native: NativeLive) : AutoCloseable {
    private var closed = false
    private var finished = false
    private fun checkOpen(): Unit = check(!closed) { "Live stroke is closed" }

    public val realInputCount: Int get() { checkOpen(); return native.realInputCount() }
    public val predictedInputCount: Int get() { checkOpen(); return native.predictedInputCount() }

    public fun enqueue(inputs: List<InkSample>, predictedInputs: List<InkSample> = emptyList()) {
        checkOpen()
        check(!finished) { "Inputs are finished" }
        native.enqueue(inputs.nativeInputs(), predictedInputs.nativeInputs())
    }

    public fun update(elapsedTimeMillis: Double) {
        checkOpen()
        check(!finished) { "Inputs are finished" }
        native.update(checkedTime(elapsedTimeMillis))
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
        val result = native.finish(checkedTime(elapsedTimeMillis))
        finished = true
        return InkStroke(brush, result)
    }

    override public fun close() {
        if (!closed) { closed = true; native.delete() }
    }

    private fun checkedTime(time: Double): Float {
        require(time.isFinite() && time >= 0 && time <= Float.MAX_VALUE.toDouble()) { "Elapsed time must be finite and nonnegative" }
        return time.toFloat()
    }
}
