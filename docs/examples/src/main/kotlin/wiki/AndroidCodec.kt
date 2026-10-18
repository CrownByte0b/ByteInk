package wiki

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.kit.PenKind
import com.vivenotes.byteink.kit.PenPreset
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveInkCodec as InkCodec

fun androidCodecStroke(): StoredInkStroke {
    val pen = PenPreset(kind = PenKind.Calligraphy, pressure = 3, stabilization = 1)
    val brush = InkCodec.brushFor(pen)
    val inputs = MutableStrokeInputBatch().apply {
        add(InputToolType.STYLUS, 10f, 50f, 0L, pressure = 0.5f)
        add(InputToolType.STYLUS, 90f, 50f, 80L, pressure = 0.5f)
    }
    val finished = Stroke(brush, inputs)
    return InkCodec.encode(
        stroke = finished,
        pageId = "page-1",
        seq = 42,
        pen = pen,
        // Omit now to use the current wall clock.
        now = 42L,
    )
}
