package wiki

import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.testing.NotebookInkImages
import com.vivenotes.byteink.testing.ViveNotebook
import com.vivenotes.byteink.vive.ViveInkPage
import java.io.File

fun exportInkImages(source: File, outputDirectory: File) {
    val renderer = InkPathRenderer()
    try {
        ViveNotebook.open(source).use { notebook ->
            notebook.pageIds.forEachIndexed { index, id ->
                val page = notebook.page(id)
                val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                NotebookInkImages.writePng(
                    strokes = loaded.strokes,
                    file = File(outputDirectory, "page-${index + 1}.png"),
                    maxDimension = 2048,
                    renderer = renderer,
                )
            }
        }
    } finally {
        renderer.clearCache()
    }
}
