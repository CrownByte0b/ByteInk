package com.vivenotes.byteink.portable

import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.wasm.InkWasm
import com.vivenotes.byteink.wasm.InkStroke as NativeStroke
import com.vivenotes.byteink.wasm.InkSample as NativeSample
import com.vivenotes.byteink.wasm.InkToolType as NativeTool
import kotlin.math.hypot

/** Wasm adapter to the same common row/replay API used on the JVM. Initialize the engine once. */
public class WasmInkEngine(public val wasm: InkWasm) : InkEngine {
    override public fun familyProto(style: InkStyle): ByteArray = wasm.catalogFamily(style.familyId, style.stabilization)
    public companion object {
        public suspend fun load(moduleUrl: String): WasmInkEngine = WasmInkEngine(InkWasm.load(moduleUrl))
    }

    override public fun createStroke(style: InkStyle, inputs: List<InkSample>): InkStroke = WasmStroke(style,
        wasm.createStroke(wasm.catalogBrush(style.familyId, style.stabilization, style.colorArgb, style.size, style.epsilon),
            inputs.map { NativeSample(it.x, it.y, it.elapsedTimeMillis.toDouble(), NativeTool.entries[it.tool.ordinal],
                it.pressure, it.tiltRadians, it.orientationRadians, it.strokeUnitLengthCm) }), this)

    override public fun decodeStroke(style: InkStyle, androidInputs: ByteArray): InkStroke = WasmStroke(style,
        wasm.decodeAndroidStroke(wasm.catalogBrush(style.familyId, style.stabilization, style.colorArgb, style.size, style.epsilon), androidInputs), this)

    /** Borrow the Wasm engine stroke while the portable owner is open; do not close the borrowed handle. */
    public fun nativeStroke(stroke: InkStroke): NativeStroke = checked(stroke).value

    private fun checked(stroke: InkStroke): WasmStroke {
        require(stroke is WasmStroke && stroke.owner === this) { "Stroke belongs to another engine" }
        return stroke
    }

    private class WasmStroke(style: InkStyle, native: NativeStroke, val owner: WasmInkEngine) : InkStroke(style) {
        private var peer: NativeStroke? = native
        val value: NativeStroke get() = checkNotNull(peer) { "Stroke is closed" }
        override val bounds: InkBounds? get() = value.bounds
        override fun geometry(): List<InkGeometry> = value.geometry().map { group ->
            InkGeometry(group.meshes.map { InkMesh(it.positions, it.triangles) }, group.outlines) }
        override fun inputs(): List<InkSample> = value.inputs().map { InkSample(it.x, it.y, it.elapsedTimeMillis.toLong(),
            InkTool.entries[it.toolType.ordinal], it.pressure, it.tiltRadians, it.orientationRadians, it.strokeUnitLengthCm) }
        override fun encodeInputs(): ByteArray = value.encodeAndroidInputs()
        override fun contains(x: Float, y: Float): Boolean = value.contains(x, y)
        override fun coverage(box: InkBounds): Float = value.coverage(box.left,box.top,box.right,box.bottom)
        override fun copy(): InkStroke = WasmStroke(style, value.copy(), owner)
        override fun intersects(other: InkStroke, thisToPage: InkTransform, otherToPage: InkTransform): Boolean =
            value.intersects(owner.checked(other).value, thisToPage.values(), otherToPage.values())
        override fun subtract(mask: InkStroke, thisToPage: InkTransform): InkStroke =
            WasmStroke(style, value.subtract(owner.checked(mask).value, thisToPage.values()), owner)
        override fun split(thisToPage: InkTransform): List<InkStroke> = value.split(thisToPage.values()).map { WasmStroke(style, it, owner) }
        override fun enclosedBy(path: List<InkPoint>, thisToPage: InkTransform, edgeTolerance: Float): Boolean {
            if (path.size < 3) return false
            val box = bounds?.let(thisToPage::apply) ?: return false
            if (box.left < path.minOf { it.x } - edgeTolerance || box.top < path.minOf { it.y } - edgeTolerance ||
                box.right > path.maxOf { it.x } + edgeTolerance || box.bottom > path.maxOf { it.y } + edgeTolerance) return false
            val corners = listOf(InkPoint(box.left, box.top), InkPoint(box.right, box.top),
                InkPoint(box.right, box.bottom), InkPoint(box.left, box.bottom))
            if (InkPolygonMath.convex(path) && corners.all { InkPolygonMath.inside(it, path) }) return true
            var vertices = 0
            for (group in value.geometry()) for (outline in group.outlines) for (i in outline.indices step 2) {
                vertices++
                if (!InkPolygonMath.near(thisToPage.apply(InkPoint(outline[i], outline[i + 1])), path, edgeTolerance)) return false
            }
            if (vertices > 0) return true
            val transform = thisToPage.values()
            val flat = FloatArray(path.size * 2) { i -> if (i % 2 == 0) path[i / 2].x else path[i / 2].y }
            if (!value.overlapsPolygon(flat, transform)) return false
            // Erased/split components have no outlines. Match the JVM's native region/edge-band test.
            var previous = path.last()
            for (current in path) {
                val from = previous; previous = current
                if (minOf(from.x, current.x) - .5f > box.right || maxOf(from.x, current.x) + .5f < box.left ||
                    minOf(from.y, current.y) - .5f > box.bottom || maxOf(from.y, current.y) + .5f < box.top) continue
                val length = hypot(current.x - from.x, current.y - from.y)
                if (length == 0f) continue
                val nx = -(current.y - from.y) / length * .5f; val ny = (current.x - from.x) / length * .5f
                val a = InkPoint(from.x + nx, from.y + ny); val b = InkPoint(current.x + nx, current.y + ny)
                val c = InkPoint(current.x - nx, current.y - ny); val d = InkPoint(from.x - nx, from.y - ny)
                if (value.touchesTriangle(floatArrayOf(a.x,a.y,b.x,b.y,c.x,c.y), transform) ||
                    value.touchesTriangle(floatArrayOf(a.x,a.y,c.x,c.y,d.x,d.y), transform)) return false
            }
            return true
        }
        override fun close() { peer?.close(); peer = null }
    }
}

private fun InkTransform.values(): FloatArray = floatArrayOf(scaleX, scaleY, offsetX, offsetY)
