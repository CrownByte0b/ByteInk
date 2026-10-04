package wiki

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.vive.AuthoredViveStroke
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkTool

fun firstStroke(): AuthoredViveStroke {
    InkRuntime.load()
    val tool = ViveInkTool(
        familyId = ViveBrushes.MARKER,
        sizeDp = 6f,
        colorArgb = 0xff202020.toInt(),
        colorFollowsTheme = false,
    )
    val inputs = MutableStrokeInputBatch().apply {
        add(type = InputToolType.MOUSE, x = 10f, y = 50f, elapsedTimeMillis = 0L)
        add(type = InputToolType.MOUSE, x = 90f, y = 50f, elapsedTimeMillis = 80L)
    }
    return tool.complete(
        stroke = Stroke(tool.brush, inputs),
        id = "stroke-1",
        pageId = "page-1",
        seq = 1,
        createdAt = 42L,
    )
}
