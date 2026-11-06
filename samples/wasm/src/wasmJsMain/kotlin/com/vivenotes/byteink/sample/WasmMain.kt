@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.vivenotes.byteink.sample

import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.portable.*
import com.vivenotes.byteink.wasm.InkLiveStroke
import com.vivenotes.byteink.wasm.InkSample
import com.vivenotes.byteink.wasm.InkToolType
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.js.JsAny

private external interface Context2D : JsAny {
    var fillStyle: String
    fun clearRect(x: Double, y: Double, width: Double, height: Double)
    fun beginPath()
    fun moveTo(x: Double, y: Double)
    fun lineTo(x: Double, y: Double)
    fun closePath()
    fun fill()
}

private fun context(): Context2D = js("document.querySelector('canvas').getContext('2d')")
private fun moduleUrl(): String = js("new URL('byteink-engine.mjs', document.baseURI).href")
private fun selectedTool(): String = js("document.getElementById('tool').value")
private fun status(text: String): Unit = js("{ document.getElementById('status').textContent = text; }")
private fun ready(): Unit = js("{ for (const button of document.querySelectorAll('button')) button.disabled = false; globalThis.byteinkReady = true; }")

// A deliberately small pointer adapter for this sample. Production browser input normalization,
// coalesced events, stylus attributes and predictions belong to the next browser authoring stage.
private fun listen(
    down: (Float, Float, Double, Int) -> Unit,
    move: (Float, Float, Double) -> Unit,
    up: (Float, Float, Double) -> Unit,
    cancel: () -> Unit,
    clear: () -> Unit,
    reload: () -> Unit,
    moveInk: () -> Unit,
    undo: () -> Unit,
): Unit = js("""{
    const canvas = document.querySelector('canvas');
    let pointer = null;
    const xy = event => {
        const rect = canvas.getBoundingClientRect();
        return [(event.clientX - rect.left) * canvas.width / rect.width,
                (event.clientY - rect.top) * canvas.height / rect.height];
    };
    canvas.addEventListener('pointerdown', event => {
        if (pointer !== null || event.button !== 0) return;
        event.preventDefault();
        pointer = event.pointerId;
        canvas.setPointerCapture(pointer);
        const [x, y] = xy(event);
        down(x, y, event.timeStamp, event.pointerType === 'pen' ? 3 : event.pointerType === 'touch' ? 2 : 1);
    });
    canvas.addEventListener('pointermove', event => {
        if (event.pointerId !== pointer) return;
        const [x, y] = xy(event);
        move(x, y, event.timeStamp);
    });
    canvas.addEventListener('pointerup', event => {
        if (event.pointerId !== pointer) return;
        pointer = null;
        const [x, y] = xy(event);
        up(x, y, event.timeStamp);
    });
    const cancelled = event => {
        if (event.pointerId !== pointer) return;
        pointer = null;
        cancel();
    };
    canvas.addEventListener('pointercancel', cancelled);
    canvas.addEventListener('lostpointercapture', cancelled);
    document.getElementById('clear').onclick = () => { pointer = null; clear(); };
    document.getElementById('reload').onclick = () => { pointer = null; reload(); };
    document.getElementById('move').onclick = () => { pointer = null; moveInk(); };
    document.getElementById('undo').onclick = () => { pointer = null; undo(); };
    window.addEventListener('pagehide', () => { pointer = null; clear(); });
}""")

fun main() {
    MainScope().launch {
        try {
            val engine = WasmInkEngine.load(moduleUrl())
            val kit = InkKit(engine)
            val canvas = context()
            val rows = mutableListOf<StoredInkStroke>()
            val erases = mutableListOf<StoredInkErase>()
            val moves = mutableListOf<StoredInkMove>()
            var page = kit.load("sample", rows)
            var sequence = 0
            var live: InkLiveStroke? = null
            var startTime = 0.0
            var elapsed = 0.0
            var tool = InkToolType.MOUSE
            var gesture = "marker"
            var style = InkStyle()

            fun draw(groups: List<InkGeometry>, color: Int, transform: InkTransform = InkTransform()) {
                canvas.fillStyle = "rgba(${color ushr 16 and 255},${color ushr 8 and 255},${color and 255},${(color ushr 24) / 255.0})"
                fun x(value: Float): Double = (value * transform.scaleX + transform.offsetX).toDouble()
                fun y(value: Float): Double = (value * transform.scaleY + transform.offsetY).toDouble()
                for (group in groups) {
                    canvas.beginPath()
                    for (outline in group.outlines) {
                        if (outline.size < 6) continue
                        canvas.moveTo(x(outline[0]), y(outline[1]))
                        for (i in 2 until outline.size step 2) canvas.lineTo(x(outline[i]), y(outline[i + 1]))
                        canvas.closePath()
                    }
                    // Split opaque components have meshes without outlines. Fill their triangles
                    // together; DISCARD highlighters retain the engine's compound outlines.
                    if (group.outlines.isEmpty()) for (mesh in group.meshes) for (i in mesh.triangles.indices step 3) {
                        for (j in 0..2) {
                            val vertex = mesh.triangles[i + j] * 2
                            if (j == 0) canvas.moveTo(x(mesh.positions[vertex]), y(mesh.positions[vertex + 1]))
                            else canvas.lineTo(x(mesh.positions[vertex]), y(mesh.positions[vertex + 1]))
                        }
                        canvas.closePath()
                    }
                    canvas.fill()
                }
            }

            fun redraw() {
                canvas.clearRect(0.0, 0.0, 960.0, 500.0)
                page.strokes.forEach { draw(it.stroke.geometry(), it.stroke.style.colorArgb, it.transform) }
                live?.let { wet -> draw(wet.geometry().map { group ->
                    InkGeometry(group.meshes.map { InkMesh(it.positions, it.triangles) }, group.outlines)
                }, if (gesture == "erase" || gesture == "object") 0x88555555.toInt() else style.colorArgb) }
            }

            fun cancel() { live?.close(); live = null; redraw() }
            fun replay(reloaded: Boolean = false) {
                val next = kit.load("sample", rows, erases, moves)
                page.close()
                page = next
                redraw()
                val operations = erases.count { it.deletedAt == null } + moves.count { it.deletedAt == null }
                status("${rows.size} strokes${if (reloaded) " reloaded" else ""}, $operations operations, ${page.strokes.size} pieces")
            }
            fun update(x: Float, y: Float, time: Double) {
                elapsed = maxOf(elapsed, time - startTime)
                live?.enqueue(listOf(InkSample(x, y, elapsed, tool)))
                live?.update(elapsed)
            }

            listen(
                down = { x, y, time, kind ->
                    cancel()
                    tool = InkToolType.entries[kind]
                    startTime = time
                    elapsed = 0.0
                    gesture = selectedTool()
                    style = when (gesture) {
                        "highlighter" -> InkStyle(InkBrushCatalog.HIGHLIGHTER, colorArgb = 0x66ffcb3b, size = 22f)
                        "erase", "object" -> InkStyle(InkBrushCatalog.MARKER, 1, size = 26f)
                        else -> InkStyle(colorArgb = 0xff252f68.toInt(), size = 4f)
                    }
                    live = engine.wasm.startStroke(engine.wasm.catalogBrush(style.familyId, style.stabilization,
                        style.colorArgb, style.size, style.epsilon))
                    update(x, y, time)
                    redraw()
                },
                move = { x, y, time -> update(x, y, time); redraw() },
                up = { x, y, time ->
                    update(x, y, time)
                    val wet = live
                    try {
                        wet?.finish(elapsed)?.use { native ->
                            // Store and prove operations using the quantized form replay will read.
                            engine.decodeStroke(style, native.encodeAndroidInputs()).use { stroke ->
                                val order = ++sequence
                                if (gesture == "erase" || gesture == "object") {
                                    val targets = page.strokes.filter { it.stroke.intersects(stroke, it.transform) }.map { it.id }.distinct()
                                    if (targets.isNotEmpty()) erases += kit.encodeErase(stroke, "erase-$order", "sample",
                                        if (gesture == "object") InkEraseMode.Object else InkEraseMode.Normal, order.toLong(), targets)
                                } else rows += kit.encodeStroke(stroke, "stroke-$order", "sample", order,
                                    style.familyId, style.stabilization, false, order.toLong())
                            }
                        }
                    } finally { wet?.close(); live = null }
                    replay()
                },
                cancel = ::cancel,
                clear = {
                    cancel()
                    rows.clear(); erases.clear(); moves.clear()
                    replay()
                },
                reload = {
                    cancel()
                    replay(reloaded = true)
                },
                moveInk = {
                    cancel()
                    val bounds = page.strokes.mapNotNull { it.pageBounds }.unionBounds()
                    if (bounds != null) {
                        val path = listOf(InkPoint(bounds.left - 8, bounds.top - 8), InkPoint(bounds.right + 8, bounds.top - 8),
                            InkPoint(bounds.right + 8, bounds.bottom + 8), InkPoint(bounds.left - 8, bounds.bottom + 8))
                        val order = ++sequence
                        moves += kit.encodeMove(InkLassoMove(path, page.strokes.map { it.id }.toSet(), emptySet(), 40f, 0f),
                            "move-$order", "sample", order.toLong())
                        replay()
                    }
                },
                undo = {
                    cancel()
                    val latest = (erases.filter { it.deletedAt == null }.map { it.createdAt } +
                        moves.filter { it.deletedAt == null }.map { it.createdAt }).maxOrNull()
                    for (i in erases.indices) if (erases[i].createdAt == latest) erases[i] = erases[i].copy(deletedAt = sequence.toLong())
                    for (i in moves.indices) if (moves[i].createdAt == latest) moves[i] = moves[i].copy(deletedAt = sequence.toLong())
                    replay()
                },
            )
            ready()
            status("Ready to draw")
        } catch (error: Throwable) {
            status("Unable to load ink: ${error.message}")
            throw error
        }
    }
}
