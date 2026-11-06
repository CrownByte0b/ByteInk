@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.vivenotes.byteink.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.window.ComposeViewport
import com.vivenotes.byteink.compose.*
import com.vivenotes.byteink.wasm.input.*
import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.portable.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private fun moduleUrl(): String = js("new URL('byteink-engine.mjs', document.baseURI).href")
private fun selectedTool(): String = js("document.getElementById('tool').value")
private fun status(text: String): Unit = js("{ document.getElementById('status').textContent = text; }")
private fun now(): Double = js("performance.now()")
private fun verify(): Boolean = js("new URL(location.href).searchParams.has('verify')")
private fun ready(): Unit = js("{ for (const button of document.querySelectorAll('button')) button.disabled = false; globalThis.byteinkReady = true; }")
private fun buttons(clear: () -> Unit, reload: () -> Unit, move: () -> Unit, undo: () -> Unit, dispose: () -> Unit): Unit = js("""{
    document.getElementById('clear').onclick = clear; document.getElementById('reload').onclick = reload;
    document.getElementById('move').onclick = move; document.getElementById('undo').onclick = undo;
    window.addEventListener('pagehide', dispose, {once: true});
}""")
private fun probes(pixel: (Int, Int) -> Int, visible: () -> Int, active: () -> Int, predicted: () -> Int): Unit = js("""{
    globalThis.byteinkPixel = pixel; globalThis.byteinkVisible = visible; globalThis.byteinkActive = active; globalThis.byteinkPredicted = predicted;
}""")
private fun inputReport(count: Int, time: Double, samples: String): Unit = js("""{
    globalThis.byteinkLastInput = {count, time, samples: JSON.parse(samples)};
}""")

fun main() {
    MainScope().launch {
        try {
            val engine = WasmInkEngine.load(moduleUrl())
            if (verify()) checkRendering(engine.wasm)
            val kit = InkKit(engine)
            val renderer = InkMeshRenderer()
            val rows = mutableListOf<StoredInkStroke>()
            val erases = mutableListOf<StoredInkErase>()
            val moves = mutableListOf<StoredInkMove>()
            var page = kit.load("sample", rows)
            var sequence = 0
            var revision by mutableIntStateOf(0)
            val bitmap = ImageBitmap(960, 500)
            val probeCanvas = androidx.compose.ui.graphics.Canvas(bitmap)
            val settings = mutableMapOf<Int, Pair<String, InkStyle>>()
            lateinit var session: BrowserInkAuthoringSession
            var disposed = false
            fun paint(canvas: androidx.compose.ui.graphics.Canvas) {
                page.strokes.forEach { renderer.draw(canvas, engine.nativeStroke(it.stroke), it.transform) }
                session.liveStrokes.forEach { renderer.draw(canvas, it.stroke) }
            }
            fun redraw() {
                if (disposed) return
                probeCanvas.drawRect(0f, 0f, 960f, 500f, Paint().apply { blendMode = BlendMode.Clear })
                paint(probeCanvas); revision++
            }
            fun replay(reloaded: Boolean = false) {
                val next = kit.load("sample", rows, erases, moves)
                renderer.clearCache(); page.close(); page = next; redraw()
                val operations = erases.count { it.deletedAt == null } + moves.count { it.deletedAt == null }
                status("${rows.size} strokes${if (reloaded) " reloaded" else ""}, $operations operations, ${page.strokes.size} pieces")
            }
            session = BrowserInkAuthoringSession(engine.wasm,
                brushFor = { begin ->
                    val tool = if (begin.eraser) "erase" else selectedTool()
                    val style = when (tool) {
                        "highlighter" -> InkStyle(InkBrushCatalog.HIGHLIGHTER, colorArgb = 0x66ffcb3b, size = 22f)
                        "erase", "object" -> InkStyle(InkBrushCatalog.MARKER, 1, colorArgb = 0x88555555.toInt(), size = 26f)
                        else -> InkStyle(colorArgb = 0xff252f68.toInt(), size = 4f)
                    }
                    settings[begin.pointerId] = tool to style
                    engine.wasm.catalogBrush(style.familyId, style.stabilization, style.colorArgb, style.size, style.epsilon)
                },
                onFinished = { id, native ->
                    native.use {
                        val (tool, style) = settings.remove(id)!!
                        val inputs = native.inputs()
                        inputs.lastOrNull()?.let { last -> inputReport(inputs.size, last.elapsedTimeMillis,
                            inputs.joinToString(prefix = "[", postfix = "]") {
                                "[${it.x},${it.y},${it.elapsedTimeMillis},${it.pressure ?: "null"},${it.tiltRadians ?: "null"},${it.orientationRadians ?: "null"}]"
                            }) }
                        engine.decodeStroke(style, native.encodeAndroidInputs()).use { stroke ->
                            val order = ++sequence
                            if (tool == "erase" || tool == "object") {
                                val targets = page.strokes.filter { it.stroke.intersects(stroke, it.transform) }.map { it.id }.distinct()
                                if (targets.isNotEmpty()) erases += kit.encodeErase(stroke, "erase-$order", "sample",
                                    if (tool == "object") InkEraseMode.Object else InkEraseMode.Normal, order.toLong(), targets)
                            } else rows += kit.encodeStroke(stroke, "stroke-$order", "sample", order,
                                style.familyId, style.stabilization, false, order.toLong())
                        }
                    }
                    replay()
                }, onChanged = ::redraw, onRetire = { renderer.release(it) })
            val input = attachBrowserInkInput("ink-surface", { BrowserInkViewport(960f, 500f) }) { event ->
                session.handle(event)
                if (event is BrowserInkEvent.Cancel) settings.remove(event.pointerId)
            }
            fun cancel() { session.cancelAll(); settings.clear() }
            buttons(
                clear = { cancel(); rows.clear(); erases.clear(); moves.clear(); replay() },
                reload = { cancel(); replay(true) },
                move = {
                    cancel()
                    val bounds = page.strokes.mapNotNull { it.pageBounds }.unionBounds()
                    if (bounds != null) {
                        val path = listOf(InkPoint(bounds.left - 10, bounds.top - 10), InkPoint(bounds.right + 10, bounds.top - 10),
                            InkPoint(bounds.right + 10, bounds.bottom + 10), InkPoint(bounds.left - 10, bounds.bottom + 10))
                        moves += kit.encodeMove(InkLassoMove(path, page.strokes.map { it.id }.toSet(), emptySet(), 40f, 0f), "move-${++sequence}", "sample", sequence.toLong())
                    }
                    replay()
                },
                undo = {
                    cancel()
                    val latest = (erases.filter { it.deletedAt == null }.map { it.createdAt } +
                        moves.filter { it.deletedAt == null }.map { it.createdAt }).maxOrNull()
                    for (i in erases.indices) if (erases[i].createdAt == latest) erases[i] = erases[i].copy(deletedAt = (++sequence).toLong())
                    for (i in moves.indices) if (moves[i].createdAt == latest) moves[i] = moves[i].copy(deletedAt = (++sequence).toLong())
                    replay()
                }, dispose = { input.close(); session.close(); page.close(); renderer.close(); disposed = true
                    probeCanvas.skiaCanvas.close(); bitmap.asSkiaBitmap().close() })
            probes(pixel = { x, y -> IntArray(1).also { bitmap.readPixels(it, x, y, 1, 1) }[0] },
                visible = { IntArray(960 * 500).also { bitmap.readPixels(it) }.count { it ushr 24 != 0 } },
                active = { session.liveStrokes.size }, predicted = { session.liveStrokes.sumOf { it.stroke.predictedInputCount } })
            ComposeViewport(viewportContainerId = "ink-surface") {
                LaunchedEffect(revision) {
                    while (!disposed && session.needsAnimationFrame) withFrameNanos { session.advance(now()) }
                }
                Canvas(Modifier.fillMaxSize()) {
                    if (disposed) return@Canvas
                    revision
                    scale(size.width / 960f, size.height / 500f, pivot = androidx.compose.ui.geometry.Offset.Zero) { paint(drawContext.canvas) }
                }
            }
            ready(); status("Ready to draw")
        } catch (error: Throwable) { status("Unable to load ink: ${error.message}"); throw error }
    }
}
