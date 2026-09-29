package com.vivenotes.byteink.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.drawInk
import com.vivenotes.byteink.testing.NotebookInkImages
import com.vivenotes.byteink.testing.ViveNotebook
import com.vivenotes.byteink.vive.PageStroke
import com.vivenotes.byteink.vive.ViveInkPage
import com.vivenotes.byteink.vive.AUTOMATIC_DARK
import com.vivenotes.byteink.vive.automaticColorOr
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Opens a notebook viewer, or exports ink-only page PNGs with --render-all <notebook> <directory>. */
fun main(args: Array<String>) {
    if (args.firstOrNull() == "--render-all") {
        require(args.size == 3) { "Usage: --render-all <notebook.vive> <output-directory>" }
        exportPages(File(args[1]), File(args[2]))
        return
    }
    require(args.size <= 1) { "Usage: [notebook.vive] or --render-all <notebook.vive> <output-directory>" }
    application {
        var file by remember { mutableStateOf(args.firstOrNull()?.let(::File)) }
        Window(onCloseRequest = ::exitApplication, title = "ByteInk notebook viewer") {
            NotebookViewer(file, onOpen = { file = chooseNotebook() ?: file })
        }
    }
}

private fun chooseNotebook(): File? = FileDialog(null as Frame?, "Open a ViveNotes notebook", FileDialog.LOAD).let { dialog ->
    try {
        dialog.isVisible = true
        dialog.file?.let { File(dialog.directory, it) }
    } finally { dialog.dispose() }
}

internal fun exportPages(source: File, directory: File) {
    ViveNotebook.open(source).use { notebook ->
        directory.mkdirs()
        notebook.pageIds.forEachIndexed { i, id ->
            val page = notebook.page(id)
            val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
            val output = File(directory, "page-${i + 1}.png")
            val report = NotebookInkImages.writePng(loaded.strokes, output)
            println("${output.name}: ${report.drawn} projections, ${loaded.unreadable.size} unreadable rows, ${report.width}×${report.height}")
        }
    }
}

private data class ViewerPage(val strokes: List<PageStroke>, val unreadable: Int)

@Composable
internal fun NotebookViewer(file: File?, onOpen: () -> Unit) {
    var pages by remember { mutableStateOf<List<ViewerPage>>(emptyList()) }
    var pageIndex by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("Open a .vive notebook to view its ink") }
    LaunchedEffect(file) {
        pages = emptyList()
        pageIndex = 0
        if (file != null) {
            message = "Loading ${file.name}…"
            try {
                pages = withContext(Dispatchers.IO) {
                    ViveNotebook.open(file).use { notebook -> notebook.pageIds.map { id ->
                        val page = notebook.page(id)
                        val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                        ViewerPage(loaded.strokes, loaded.unreadable.size)
                    } }
                }
                message = if (pages.isEmpty()) "This notebook has no pages" else file.name
            } catch (failure: Exception) { message = failure.message ?: "Unable to open notebook" }
        }
    }
    var zoom by remember(file, pageIndex) { mutableStateOf(1f) }
    Column(Modifier.fillMaxSize().background(Color(0xffeeeeee))) {
        Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Control("Open", action = onOpen)
            Control("Previous", enabled = pageIndex > 0) { pageIndex-- }
            Control("Next", enabled = pageIndex + 1 < pages.size) { pageIndex++ }
            Control("−") { zoom = (zoom / 1.25f).coerceAtLeast(0.1f) }
            Control("+") { zoom = (zoom * 1.25f).coerceAtMost(20f) }
            Control("Fit") { zoom = 1f }
            BasicText("${if (pages.isEmpty()) 0 else pageIndex + 1}/${pages.size}   ${(zoom * 100).toInt()}%", style = textStyle)
        }
        BasicText(message, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = textStyle)
        pages.getOrNull(pageIndex)?.let { page ->
            BasicText("${page.strokes.size} ink projections · ${page.unreadable} unreadable rows · Drag to pan", Modifier.padding(12.dp), style = textStyle)
            InkPreview(page.strokes, zoom, Modifier.weight(1f).fillMaxSize())
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun InkPreview(strokes: List<PageStroke>, zoom: Float, modifier: Modifier = Modifier) {
    val renderer = remember(strokes) { InkPathRenderer() }
    var pan by remember(strokes, zoom) { mutableStateOf(Offset.Zero) }
    var scrollZoom by remember(strokes, zoom) { mutableStateOf(1f) }
    val boxes = remember(strokes) { strokes.mapNotNull { it.pageBounds } }
    val left = boxes.minOfOrNull { it.left } ?: 0f
    val top = boxes.minOfOrNull { it.top } ?: 0f
    val width = (boxes.maxOfOrNull { it.right } ?: left) - left
    val height = (boxes.maxOfOrNull { it.bottom } ?: top) - top
    Canvas(modifier.background(Color.White)
        .pointerInput(strokes, zoom) { detectDragGestures { change, drag -> change.consume(); pan += drag } }
        .onPointerEvent(PointerEventType.Scroll) { event ->
            val change = event.changes.first()
            val next = (scrollZoom * if (change.scrollDelta.y < 0) 1.1f else 1 / 1.1f).coerceIn(0.1f, 20f)
            val anchor = change.position - Offset(16f, 16f)
            pan = anchor - (anchor - pan) * (next / scrollZoom)
            scrollZoom = next
        }) {
        val fit = minOf((size.width - 32f) / maxOf(1f, width), (size.height - 32f) / maxOf(1f, height)).coerceAtLeast(0.001f)
        val scale = fit * zoom * scrollZoom
        strokes.forEach { projection ->
            drawInk(renderer, projection.stroke, ImmutableAffineTransform(
                scale * projection.scaleX, 0f, 16f + pan.x + scale * (projection.offsetX - left),
                0f, scale * projection.scaleY, 16f + pan.y + scale * (projection.offsetY - top),
            ), colorArgb = automaticColorOr(projection.stroke.brush.colorIntArgb, projection.colorFollowsTheme, AUTOMATIC_DARK))
        }
    }
}

private val textStyle = TextStyle(color = Color(0xff202020), fontSize = 14.sp)

@Composable
private fun Control(label: String, enabled: Boolean = true, action: () -> Unit) {
    BasicText(label, Modifier.clickable(enabled = enabled, onClick = action).padding(4.dp),
        style = textStyle.copy(color = if (enabled) Color(0xff204a87) else Color.Gray))
}
