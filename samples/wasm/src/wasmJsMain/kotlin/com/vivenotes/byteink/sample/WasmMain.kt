@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package com.vivenotes.byteink.sample

import com.vivenotes.byteink.wasm.InkBrush
import com.vivenotes.byteink.wasm.InkGeometryGroup
import com.vivenotes.byteink.wasm.InkLiveStroke
import com.vivenotes.byteink.wasm.InkSample
import com.vivenotes.byteink.wasm.InkStockBrush
import com.vivenotes.byteink.wasm.InkStroke
import com.vivenotes.byteink.wasm.InkToolType
import com.vivenotes.byteink.wasm.InkWasm
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
    window.addEventListener('pagehide', () => { pointer = null; clear(); });
}""")

fun main() {
    MainScope().launch {
        try {
            val engine = InkWasm.load(moduleUrl())
            val canvas = context()
            val finished = mutableListOf<InkStroke>()
            var live: InkLiveStroke? = null
            var startTime = 0.0
            var elapsed = 0.0
            var tool = InkToolType.MOUSE

            fun draw(groups: List<InkGeometryGroup>, brush: InkBrush) {
                val color = brush.colorArgb
                canvas.fillStyle = "rgba(${color ushr 16 and 255},${color ushr 8 and 255},${color and 255},${(color ushr 24) / 255.0})"
                for (group in groups) {
                    canvas.beginPath()
                    for (outline in group.outlines) {
                        if (outline.size < 6) continue
                        canvas.moveTo(outline[0].toDouble(), outline[1].toDouble())
                        for (i in 2 until outline.size step 2) canvas.lineTo(outline[i].toDouble(), outline[i + 1].toDouble())
                        canvas.closePath()
                    }
                    canvas.fill()
                }
            }

            fun redraw() {
                canvas.clearRect(0.0, 0.0, 960.0, 500.0)
                finished.forEach { draw(it.geometry(), it.brush) }
                live?.let { draw(it.geometry(), it.brush) }
            }

            fun cancel() { live?.close(); live = null; redraw() }
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
                    val brush = if (selectedTool() == "highlighter") InkBrush(InkStockBrush.HIGHLIGHTER, 0x66ffcb3b, 22f)
                        else InkBrush(colorArgb = 0xff252f68.toInt(), size = 4f)
                    live = engine.startStroke(brush)
                    update(x, y, time)
                    redraw()
                },
                move = { x, y, time -> update(x, y, time); redraw() },
                up = { x, y, time ->
                    update(x, y, time)
                    live?.let { finished.add(it.finish(elapsed)); it.close() }
                    live = null
                    redraw()
                    status("${finished.size} strokes")
                },
                cancel = ::cancel,
                clear = {
                    cancel()
                    finished.forEach(InkStroke::close)
                    finished.clear()
                    redraw()
                    status("0 strokes")
                },
                reload = {
                    cancel()
                    val restored = mutableListOf<InkStroke>()
                    try {
                        finished.forEach { restored += engine.decodeAndroidStroke(it.brush, it.encodeAndroidInputs()) }
                    } catch (error: Throwable) {
                        restored.forEach(InkStroke::close)
                        throw error
                    }
                    finished.forEach(InkStroke::close)
                    finished.clear()
                    finished.addAll(restored)
                    redraw()
                    status("${finished.size} strokes reloaded from Android input blobs")
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
