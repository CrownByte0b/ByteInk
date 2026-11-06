@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.vivenotes.byteink.wasm

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.Promise

internal external interface NativeNumbers : JsAny {
    val length: Int
}

internal external interface NativeMesh : JsAny {
    val positions: NativeNumbers
    val triangles: NativeNumbers
}

internal external interface NativeGroup : JsAny {
    val meshes: JsArray<NativeMesh>
    val outlines: JsArray<NativeNumbers>
}

internal external interface NativeStroke : JsAny {
    fun geometry(): JsArray<NativeGroup>
    fun inputs(): NativeNumbers
    fun encodeInputs(): NativeNumbers
    fun encodeAndroidInputs(): NativeNumbers
    fun contains(x: Float, y: Float): Boolean
    fun delete()
}

internal external interface NativeLive : JsAny {
    fun enqueue(real: NativeNumbers, predicted: NativeNumbers)
    fun update(elapsedMillis: Float)
    fun geometry(): JsArray<NativeGroup>
    fun finish(elapsedMillis: Float): NativeStroke
    fun realInputCount(): Int
    fun predictedInputCount(): Int
    fun delete()
}

internal external interface NativeModule : JsAny {
    fun abiVersion(): Int
    fun engineCommit(): String
    fun createStroke(family: Int, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
    fun decodeStroke(family: Int, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
    fun createStrokeWithFamily(family: NativeNumbers, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
    fun decodeStrokeWithFamily(family: NativeNumbers, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
    fun startStroke(family: Int, color: Double, size: Float, epsilon: Float): NativeLive
    fun startStrokeWithFamily(family: NativeNumbers, color: Double, size: Float, epsilon: Float): NativeLive
    fun decodeAndroidStroke(family: Int, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
    fun decodeAndroidStrokeWithFamily(family: NativeNumbers, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
}

// An application supplies its own static URL; no npm path or global Module is needed.
internal fun importEngine(url: String): Promise<NativeModule> =
    js("import(/* webpackIgnore: true */ url).then(module => module.default())")

internal fun numberArray(length: Int): NativeNumbers = js("new Float64Array(length)")
internal fun byteArray(length: Int): NativeNumbers = js("new Uint8Array(length)")
internal fun numberAt(array: NativeNumbers, index: Int): Double = js("array[index]")
internal fun setNumber(array: NativeNumbers, index: Int, value: Double): Unit = js("{ array[index] = value; }")

internal fun ByteArray.nativeBytes(): NativeNumbers {
    require(size <= 64 * 1024 * 1024) { "Protobuf exceeds 64 MiB" }
    val bytes = byteArray(size)
    forEachIndexed { i, byte -> setNumber(bytes, i, (byte.toInt() and 255).toDouble()) }
    return bytes
}

internal fun List<InkSample>.nativeInputs(): NativeNumbers {
    require(size <= 1_000_000) { "Too many stroke inputs" }
    val result = numberArray(size * 8)
    forEachIndexed { index, sample ->
        val fields = doubleArrayOf(sample.x.toDouble(), sample.y.toDouble(), sample.elapsedTimeMillis,
            sample.toolType.ordinal.toDouble(), sample.pressure?.toDouble() ?: -1.0,
            sample.tiltRadians?.toDouble() ?: -1.0, sample.orientationRadians?.toDouble() ?: -1.0,
            sample.strokeUnitLengthCm?.toDouble() ?: 0.0)
        fields.forEachIndexed { field, value -> setNumber(result, index * 8 + field, value) }
    }
    return result
}

internal fun geometrySnapshot(groups: JsArray<NativeGroup>): List<InkGeometryGroup> =
    List(groups.length) { g ->
        val group = groups[g]!!
        InkGeometryGroup(
            List(group.meshes.length) { m ->
                val mesh = group.meshes[m]!!
                InkMesh(FloatArray(mesh.positions.length) { numberAt(mesh.positions, it).toFloat() },
                    IntArray(mesh.triangles.length) { numberAt(mesh.triangles, it).toInt() })
            },
            List(group.outlines.length) { o ->
                val outline = group.outlines[o]!!
                FloatArray(outline.length) { numberAt(outline, it).toFloat() }
            },
        )
    }
