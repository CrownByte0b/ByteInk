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
    fun rendering(): NativeRendering
    fun inputs(): NativeNumbers
    fun encodeInputs(): NativeNumbers
    fun encodeAndroidInputs(): NativeNumbers
    fun bounds(): NativeNumbers?
    fun copy(): NativeStroke
    fun intersects(other: NativeStroke, thisToPage: NativeNumbers, otherToPage: NativeNumbers): Boolean
    fun subtract(mask: NativeStroke, thisToPage: NativeNumbers): NativeStroke
    fun split(thisToPage: NativeNumbers): JsArray<NativeStroke>
    fun touchesTriangle(points: NativeNumbers, thisToPage: NativeNumbers): Boolean
    fun overlapsPolygon(points: NativeNumbers, thisToPage: NativeNumbers): Boolean
    fun contains(x: Float, y: Float): Boolean
    fun coverage(left: Float, top: Float, right: Float, bottom: Float): Float
    fun delete()
}

internal external interface NativeLive : JsAny {
    fun enqueue(real: NativeNumbers, predicted: NativeNumbers)
    fun update(elapsedMillis: Float)
    fun geometry(): JsArray<NativeGroup>
    fun rendering(): NativeRendering
    fun needsUpdate(): Boolean
    fun finish(elapsedMillis: Float): NativeStroke
    fun realInputCount(): Int
    fun predictedInputCount(): Int
    fun delete()
}

internal external interface NativeRendering : JsAny {
    val groups: JsArray<NativeRenderingGroup>
    val brushSize: Float
    val inputPositions: NativeNumbers
}
internal external interface NativeRenderingGroup : JsAny {
    val meshes: JsArray<NativeRenderingMesh>
    val outlines: JsArray<NativeNumbers>
    val paints: JsArray<NativePaint>
}
internal external interface NativeRenderingMesh : JsAny {
    val vertices: NativeNumbers
    val triangles: NativeNumbers
    val attributeMask: Int
}
internal external interface NativePaint : JsAny {
    val compatible: Boolean
    val overlap: Int
    val linearColor: NativeNumbers
    val srgbColor: NativeNumbers
    val textures: JsArray<NativeTexture>
}
internal external interface NativeTexture : JsAny {
    val id: String
    val blend: Int
    val mapping: Int
    val parameters: NativeNumbers
}

private fun NativeNumbers.floats(): FloatArray = FloatArray(length) { numberAt(this, it).toFloat() }
internal fun renderingSnapshot(value: NativeRendering): InkRendering = InkRendering(
    List(value.groups.length) { index ->
        val group = value.groups[index]!!
        InkRenderingGroup(List(group.meshes.length) { meshIndex ->
            val mesh = group.meshes[meshIndex]!!
            InkRenderingMesh(mesh.vertices.floats(), IntArray(mesh.triangles.length) { numberAt(mesh.triangles, it).toInt() }, mesh.attributeMask)
        }, List(group.outlines.length) { group.outlines[it]!!.floats() }, List(group.paints.length) { paintIndex ->
            val paint = group.paints[paintIndex]!!
            InkRenderingPaint(paint.compatible, InkSelfOverlap.entries[paint.overlap], paint.linearColor.floats(),
                paint.srgbColor.floats(), List(paint.textures.length) { textureIndex ->
                    val texture = paint.textures[textureIndex]!!
                    val p = texture.parameters.floats()
                    if (texture.mapping == 0) InkTilingTexture(texture.id, texture.blend, p[0].toInt(), p[1].toInt(),
                        p[2].toInt(), p[3].toInt(), p[4], p[5], p[6], p[7], p[8])
                    else InkStampingTexture(texture.id, texture.blend, p[0].toInt(), p[1].toInt(), p[2].toInt(), p[3].toLong(), p[4] == 1f)
                })
        })
    }, value.brushSize, value.inputPositions.floats())

internal external interface NativeModule : JsAny {
    fun abiVersion(): Int
    fun mathProfile(): String
    fun engineCommit(): String
    fun catalogFamily(kind: Int, pressure: Int, windowMillis: Int, minSize: Float, maxSize: Float): NativeNumbers
    fun createStroke(family: Int, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
    fun decodeStroke(family: Int, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
    fun createStrokeWithFamily(family: NativeNumbers, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers, allowDevelopment: Boolean): NativeStroke
    fun decodeStrokeWithFamily(family: NativeNumbers, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers, allowDevelopment: Boolean): NativeStroke
    fun startStroke(family: Int, color: Double, size: Float, epsilon: Float): NativeLive
    fun startStrokeWithFamily(family: NativeNumbers, color: Double, size: Float, epsilon: Float, allowDevelopment: Boolean): NativeLive
    fun decodeAndroidStroke(family: Int, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers): NativeStroke
    fun decodeAndroidStrokeWithFamily(family: NativeNumbers, color: Double, size: Float, epsilon: Float, inputs: NativeNumbers, allowDevelopment: Boolean): NativeStroke
}

// An application supplies its own static URL; no npm path or global Module is needed.
internal fun importEngine(url: String): Promise<NativeModule> =
    js("import(/* webpackIgnore: true */ url).then(module => module.default())")

internal fun numberArray(length: Int): NativeNumbers = js("new Float64Array(length)")
internal fun byteArray(length: Int): NativeNumbers = js("new Uint8Array(length)")
internal fun numberAt(array: NativeNumbers, index: Int): Double = js("array[index]")
internal fun setNumber(array: NativeNumbers, index: Int, value: Double): Unit = js("{ array[index] = value; }")
internal fun isDataError(value: kotlin.js.JsAny?): Boolean = js("value != null && value.name === 'ByteInkDataError'")
internal inline fun <T> dataOperation(block: () -> T): T = try { block() } catch (error: kotlin.js.JsException) {
    if (isDataError(error.thrownValue)) throw IllegalArgumentException(error.message, error)
    throw error
}

internal fun FloatArray.nativeNumbers(): NativeNumbers {
    val values = numberArray(size)
    forEachIndexed { i, value -> setNumber(values, i, value.toDouble()) }
    return values
}

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
