package wiki

import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkAuthoringController
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.kit.ViveInkTool

fun pointerStroke(): Stroke = InkAuthoringController().use { controller ->
    val tool = ViveInkTool(sizeDp = 3f)
    controller.begin(
        brush = tool.brush,
        sample = InkPointerSample(x = 20f, y = 100f, uptimeMillis = 1_000L),
        strokeToView = ImmutableAffineTransform(2f, 0f, 0f, 0f, 2f, 0f),
    )
    controller.append(InkPointerSample(x = 80f, y = 100f, uptimeMillis = 1_016L))
    if (controller.isUpdateNeeded()) controller.advance(uptimeMillis = 1_016L)
    requireNotNull(controller.finish(
        InkPointerSample(x = 180f, y = 100f, uptimeMillis = 1_032L),
    ))
}
