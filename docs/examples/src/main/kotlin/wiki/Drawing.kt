package wiki

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.InkDrawingSurface
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.drawInk
import com.vivenotes.byteink.compose.rememberInkAuthoringController
import com.vivenotes.byteink.kit.AuthoredViveStroke
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveInkTool
import java.util.UUID

@Composable
fun DrawingExample(onRowReady: (StoredInkStroke) -> Unit) {
    val tool = remember { ViveInkTool(sizeDp = 3f) }
    val controller = rememberInkAuthoringController()
    val renderer = remember { InkPathRenderer() }
    val completed = remember { mutableStateListOf<AuthoredViveStroke>() }
    val density = LocalDensity.current.density
    val pageToPixels = remember(density) {
        ImmutableAffineTransform(density, 0f, 0f, 0f, density, 0f)
    }
    DisposableEffect(controller, renderer) {
        onDispose { controller.close(); renderer.clearCache() }
    }
    InkDrawingSurface(
        controller = controller,
        brush = tool.brush,
        modifier = Modifier.fillMaxSize(),
        strokeToView = pageToPixels,
        renderer = renderer,
        onStrokeFinished = { finished ->
            val authored = tool.complete(
                stroke = finished,
                id = UUID.randomUUID().toString(),
                pageId = "page-1",
                seq = completed.size + 1, // Demo sequence; a repository allocates real values.
                createdAt = System.currentTimeMillis(),
            )
            completed.add(authored)
            onRowReady(authored.row)
        },
        drawContent = {
            completed.forEach { drawInk(renderer, it.canonicalStroke, pageToPixels) }
        },
    )
}
