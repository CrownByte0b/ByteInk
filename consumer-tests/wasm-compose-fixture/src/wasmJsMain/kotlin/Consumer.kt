@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.*
import androidx.compose.ui.window.ComposeViewport
import com.vivenotes.byteink.compose.*
import com.vivenotes.byteink.wasm.*
import com.vivenotes.byteink.wasm.input.*
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

private fun engineUrl(): String = js("new URL('byteink-engine.mjs', document.baseURI).href")
private fun passed(): Unit = js("{ globalThis.publishedWasmComposePass = true; }")
private fun disposed(): Unit = js("{ globalThis.publishedWasmComposeDisposed = true; }")
private fun cleanup(close: () -> Unit): Unit = js("{ globalThis.closePublishedWasmCompose = close; }")

fun main() { MainScope().launch {
    // Before Skiko's runtime can have loaded: a renderer has to allow being made, and closed unused.
    InkMeshRenderer().close()
    val renderer = InkMeshRenderer()
    val engine = InkWasm.load(engineUrl())
    val brush = InkBrush(InkStockBrush.HIGHLIGHTER, 0x80ff0000.toInt(), 16f)
    val stroke = engine.createStroke(brush, listOf(InkSample(20f, 60f, 0.0), InkSample(160f, 60f, 100.0)))
    // Everything down to ComposeViewport uses Skia outside composition.
    awaitInkRendering()
    val bitmap = ImageBitmap(200, 120)
    renderer.draw(androidx.compose.ui.graphics.Canvas(bitmap), stroke)
    val pixels = IntArray(1); bitmap.readPixels(pixels, 60, 60, 1, 1)
    check(pixels[0] ushr 24 == 128)
    InkPathRenderer().use { paths -> paths.draw(androidx.compose.ui.graphics.Canvas(bitmap), stroke) }
    var revision by mutableIntStateOf(0)
    var closed by mutableStateOf(false)
    val session = BrowserInkAuthoringSession(engine, { InkBrush() }, { _, finished -> finished.close() },
        onChanged = { revision++ }, onRetire = { renderer.release(it) })
    val input = attachBrowserInkInput("ink", { BrowserInkViewport(200f, 120f) }, session::handle)
    ComposeViewport(viewportContainerId = "ink") {
        Canvas(Modifier.fillMaxSize()) {
            if (closed) return@Canvas
            revision
            drawInk(renderer, stroke)
            session.liveStrokes.forEach { drawInk(renderer, it.stroke) }
            passed()
        }
    }
    cleanup { closed = true; input.close(); session.close(); renderer.close(); stroke.close(); bitmap.asSkiaBitmap().close(); disposed() }
} }
