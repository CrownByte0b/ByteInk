package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.PathFillMode
import org.jetbrains.skia.Path as SkiaPath

/** An immutable native snapshot, drawn through Compose so canvas alpha and clipping still apply. */
internal class InkRenderPath(private val snapshot: SkiaPath) : AutoCloseable {
    val path: Path = snapshot.asComposePath()
    val isClosed: Boolean get() = snapshot.isClosed

    override fun close() {
        if (snapshot.isClosed) return
        try {
            // Compose owns an inaccessible builder copied from this snapshot. Reset releases its
            // geometry too; the remaining empty wrapper follows Compose/Skiko's usual cleanup.
            path.reset()
        } finally {
            snapshot.close()
        }
    }
}

internal fun buildInkPath(build: PathBuilder.() -> Unit): InkRenderPath =
    PathBuilder(PathFillMode.WINDING).use { builder ->
        builder.build()
        val snapshot = builder.detach()
        try {
            InkRenderPath(snapshot)
        } catch (failure: Throwable) {
            snapshot.close()
            throw failure
        }
    }

internal fun outlineInkPath(outlines: List<FloatArray>): InkRenderPath = buildInkPath {
    for (points in outlines) if (points.isNotEmpty()) addPoly(points, true)
}
