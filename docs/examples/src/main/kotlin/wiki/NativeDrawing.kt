package wiki

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.RenderSettings
import androidx.ink.geometry.AffineTransform
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkLowLatencySurface
import com.vivenotes.byteink.compose.InkMeshRenderer
import com.vivenotes.byteink.compose.InkRenderer
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveInkTool
import java.awt.Toolkit
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.util.UUID
import javax.swing.JFrame
import javax.swing.SwingUtilities
import javax.swing.WindowConstants

/** The host owns [finishedRenderer]; the surface owns its separate default wet mesh renderer. */
@Composable
fun NativeDrawingExample(
    finishedRenderer: InkRenderer,
    onRowReady: (StoredInkStroke) -> Unit,
) {
    val tool = remember { ViveInkTool(sizeDp = 3f) }
    val finished = remember { mutableStateListOf<Stroke>() }
    // Native panels use AWT logical units. Skia applies device scaling itself; do not multiply
    // this transform by LocalDensity as the regular Compose Canvas example does.
    val pageToAwtLogical = AffineTransform.IDENTITY
    InkLowLatencySurface(
        brush = tool.brush,
        modifier = Modifier.fillMaxSize(),
        strokeToView = pageToAwtLogical,
        // Omitted renderer/inputSource select the owned mesh renderer and built-in native capture.
        onStrokeFinished = { _, realStroke ->
            val authored = tool.complete(
                stroke = realStroke,
                id = UUID.randomUUID().toString(),
                pageId = "page-1",
                seq = finished.size + 1, // Demo order; a repository allocates real values.
                createdAt = System.currentTimeMillis(),
            )
            // Keep save/reload geometry visible synchronously before the immediate next draw.
            finished.add(authored.canonicalStroke)
            onRowReady(authored.row)
        },
        drawContent = { canvas, _, _ ->
            finished.forEach { finishedRenderer.render(canvas, it, pageToAwtLogical) }
        },
    )
}

/**
 * Call from a desktop entry point, never the headless smoke runner. Start JBR 25 with
 * -Dawt.toolkit.name=WLToolkit, --add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED and
 * --enable-native-access=ALL-UNNAMED before any AWT initialization.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun showNativeWaylandExample(onRowReady: (StoredInkStroke) -> Unit = {}) {
    SwingUtilities.invokeLater {
        check(Toolkit.getDefaultToolkit().javaClass.name == "sun.awt.wl.WLToolkit") {
            "Launch on JBR 25 with WLToolkit and the native Wayland JVM flags"
        }
        InkRuntime.load()
        val finishedRenderer = InkMeshRenderer()
        val content = ComposePanel(renderSettings = RenderSettings.SwingGraphics())
        // The host disposes detached content before closing its borrowed finished renderer.
        content.isDisposeOnRemove = false
        content.setContent { NativeDrawingExample(finishedRenderer, onRowReady) }
        val frame = JFrame("ByteInk native Wayland")
        frame.defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosed(event: WindowEvent) {
                try { content.dispose() } finally { finishedRenderer.close() }
            }
        })
        frame.contentPane.add(content)
        frame.setSize(900, 650)
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}
