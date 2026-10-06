package wiki

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkDrawingSurface
import com.vivenotes.byteink.compose.InkTextureStore
import com.vivenotes.byteink.compose.rememberInkAuthoringController
import com.vivenotes.byteink.compose.rememberInkMeshRenderer
import org.jetbrains.skia.Image

@Composable
fun TexturedDrawingExample(
    brush: Brush,
    images: Map<String, Image>,
    modifier: Modifier,
    onStrokeFinished: (Stroke) -> Unit,
    drawContent: DrawScope.() -> Unit = {},
) {
    val store = remember(images) { InkTextureStore { id -> images[id] } }
    val renderer = rememberInkMeshRenderer(store)
    val controller = rememberInkAuthoringController()
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(renderer) {
        val start = withFrameNanos { it }
        while (true) {
            withFrameNanos { renderer.animationTimeMillis = (it - start) / 1_000_000L }
        }
    }
    InkDrawingSurface(
        controller = controller,
        brush = brush,
        modifier = modifier,
        renderer = renderer,
        onStrokeFinished = onStrokeFinished,
        drawContent = drawContent,
    )
}
