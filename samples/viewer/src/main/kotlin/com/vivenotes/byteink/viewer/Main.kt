package com.vivenotes.byteink.viewer

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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkDrawingSurface
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.compose.InkSceneRasterCache
import com.vivenotes.byteink.compose.drawCachedInkScene
import com.vivenotes.byteink.compose.rememberInkSceneRasterCache
import com.vivenotes.byteink.compose.InkAuthoringController
import com.vivenotes.byteink.testing.NotebookInkImages
import com.vivenotes.byteink.testing.ViveNotebook
import com.vivenotes.byteink.vive.PageStroke
import com.vivenotes.byteink.vive.ViveInkPage
import com.vivenotes.byteink.vive.AUTOMATIC_DARK
import com.vivenotes.byteink.vive.automaticColorOr
import com.vivenotes.byteink.vive.AuthoredViveStroke
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkTool
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.util.UUID
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

private data class ViewerPage(val id: String, val strokes: List<PageStroke>, val unreadable: Int)

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
                        ViewerPage(id, loaded.strokes, loaded.unreadable.size)
                    } }
                }
                message = if (pages.isEmpty()) "This notebook has no pages" else file.name
            } catch (failure: Exception) { message = failure.message ?: "Unable to open notebook" }
        }
    }
    var zoom by remember(file, pageIndex) { mutableStateOf(1f) }
    var mode by remember(file) { mutableStateOf(if (file == null) "Pen" else "Pan") }
    var stabilization by remember { mutableStateOf(0) }
    var authoredCount by remember(file, pageIndex) { mutableStateOf(0) }
    val tool = remember(mode, stabilization) { when (mode) {
        "Pen" -> ViveInkTool(stabilization = stabilization)
        "Calligraphy" -> ViveInkTool(ViveBrushes.calligraphy(3), stabilization, sizeDp = 8f)
        "Highlighter" -> ViveInkTool(ViveBrushes.HIGHLIGHTER, colorArgb = 0x80ffe000.toInt(), sizeDp = 18f)
        else -> null
    } }
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
        Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("Pan", "Pen", "Calligraphy", "Highlighter").forEach { name ->
                Control(if (mode == name) "[$name]" else name) { mode = name }
            }
            Control("Smoothing: $stabilization", enabled = mode == "Pen" || mode == "Calligraphy") {
                stabilization = (stabilization + 1) % 6
            }
            if (authoredCount > 0) BasicText("$authoredCount unsaved strokes", style = textStyle)
        }
        BasicText(message, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = textStyle)
        val page = pages.getOrNull(pageIndex)
        if (page != null || file == null) {
            BasicText(if (page == null) "Draw on the blank page" else
                "${page.strokes.size} ink projections · ${page.unreadable} unreadable rows", Modifier.padding(12.dp), style = textStyle)
            key(file, pageIndex) {
                InkPreview(page?.strokes ?: emptyList(), zoom, Modifier.weight(1f).fillMaxSize(), tool,
                    page?.id ?: "scratch") { authoredCount++ }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun InkPreview(
    strokes: List<PageStroke>,
    zoom: Float,
    modifier: Modifier = Modifier,
    tool: ViveInkTool? = null,
    pageId: String = "preview",
    onStrokeFinished: (AuthoredViveStroke) -> Unit = {},
) {
    val renderer = remember(strokes, pageId) { InkPathRenderer() }
    val controller = remember(strokes, pageId) { InkAuthoringController() }
    DisposableEffect(renderer, controller) {
        onDispose { controller.close(); renderer.clearCache() }
    }
    val authored = remember(strokes, pageId) { mutableStateListOf<AuthoredViveStroke>() }
    val scene = remember(strokes) { InkScene(strokes.map { projection ->
        InkSceneStroke(projection.stroke, projection.strokeToPageTransform(),
            automaticColorOr(projection.stroke.brush.colorIntArgb, projection.colorFollowsTheme, AUTOMATIC_DARK))
    }) }
    val authoredSnapshot = authored.toList()
    val additions = remember(authoredSnapshot) { InkScene(authoredSnapshot.map { InkSceneStroke(it.stroke) }) }
    val pageRaster = key(strokes, pageId) { rememberInkSceneRasterCache(4, InkSceneRasterCache.DEFAULT_PIXEL_BUDGET_BYTES) }
    val additionsRaster = key(strokes, pageId) { rememberInkSceneRasterCache(4, InkSceneRasterCache.DEFAULT_PIXEL_BUDGET_BYTES) }
    var pan by remember(strokes, zoom) { mutableStateOf(Offset.Zero) }
    var scrollZoom by remember(strokes, zoom) { mutableStateOf(1f) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val extent = remember(strokes) {
        val boxes = strokes.mapNotNull { it.pageBounds }
        if (boxes.isEmpty()) null else androidx.compose.ui.geometry.Rect(
            boxes.minOf { it.left }, boxes.minOf { it.top }, boxes.maxOf { it.right }, boxes.maxOf { it.bottom })
    }
    val left = extent?.left ?: 0f
    val top = extent?.top ?: 0f
    val width = extent?.width ?: 0f
    val height = extent?.height ?: 0f
    val fit = if (extent == null) 1f else minOf((canvasSize.width - 32f) / maxOf(1f, width),
        (canvasSize.height - 32f) / maxOf(1f, height)).coerceAtLeast(0.001f)
    val scale = fit * zoom * scrollZoom
    val transform = ImmutableAffineTransform(scale, 0f, 16f + pan.x - scale * left,
        0f, scale, 16f + pan.y - scale * top)
    val panModifier = if (tool == null) Modifier.pointerInput(strokes, zoom) {
        detectDragGestures { change, drag -> change.consume(); pan += drag }
    } else Modifier
    val canvasModifier = modifier.background(Color.White).onSizeChanged { canvasSize = it }.then(panModifier)
        .onPointerEvent(PointerEventType.Scroll) { event ->
            if (controller.isDrawing) return@onPointerEvent
            val change = event.changes.first()
            val next = (scrollZoom * if (change.scrollDelta.y < 0) 1.1f else 1 / 1.1f).coerceIn(0.1f, 20f)
            val anchor = change.position - Offset(16f, 16f)
            pan = anchor - (anchor - pan) * (next / scrollZoom)
            scrollZoom = next
        }
    InkDrawingSurface(controller, tool?.brush ?: remember { ViveInkTool().brush }, canvasModifier,
        strokeToView = transform, renderer = renderer, enabled = tool != null,
        onStrokeFinished = { stroke ->
            val completed = requireNotNull(tool).complete(stroke, UUID.randomUUID().toString(), pageId, authored.size, System.currentTimeMillis())
            authored.add(completed)
            onStrokeFinished(completed)
        }) {
        drawCachedInkScene(pageRaster, scene, renderer, transform)
        if (additions.strokes.isNotEmpty()) drawCachedInkScene(additionsRaster, additions, renderer, transform)
    }
}

private val textStyle = TextStyle(color = Color(0xff202020), fontSize = 14.sp)

@Composable
private fun Control(label: String, enabled: Boolean = true, action: () -> Unit) {
    BasicText(label, Modifier.clickable(enabled = enabled, onClick = action).padding(4.dp),
        style = textStyle.copy(color = if (enabled) Color(0xff204a87) else Color.Gray))
}
