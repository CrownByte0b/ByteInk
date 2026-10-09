package wiki

import androidx.compose.runtime.Composable
import androidx.ink.brush.Brush
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkLatencyDiagnostics
import com.vivenotes.byteink.compose.InkLatencySnapshot
import com.vivenotes.byteink.compose.InkLowLatencyPanel
import com.vivenotes.byteink.compose.InkLowLatencySurface
import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path

/** Call on EDT after creating the panel, before the workload to measure. */
fun enableSoftwareTiming(panel: InkLowLatencyPanel): InkLatencyDiagnostics {
    check(EventQueue.isDispatchThread())
    return InkLatencyDiagnostics(capacity = 2048).also { panel.latencyDiagnostics = it }
}

/** Capture on EDT, then pass the immutable snapshot to the application's I/O worker. */
fun captureSoftwareTiming(diagnostics: InkLatencyDiagnostics): InkLatencySnapshot = diagnostics.snapshot()

/** Serialization and disk work happen after capture, outside the timed UI path. */
fun exportSoftwareTiming(snapshot: InkLatencySnapshot, destination: Path) {
    check(!EventQueue.isDispatchThread())
    Files.writeString(destination, snapshot.toJson())
}

/** Construct diagnostics on EDT and pass the same collector while this surface is attached. */
@Composable
fun SoftwareTimedSurface(
    brush: Brush,
    diagnostics: InkLatencyDiagnostics,
    onFinished: (Long, Stroke) -> Unit,
) {
    InkLowLatencySurface(brush = brush, latencyDiagnostics = diagnostics, onStrokeFinished = onFinished)
}
