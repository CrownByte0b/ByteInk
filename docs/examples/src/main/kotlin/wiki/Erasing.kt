package wiki

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.byteink.vive.InkEraseMode
import com.vivenotes.byteink.vive.InkPageIndex
import com.vivenotes.byteink.vive.PageStroke
import com.vivenotes.byteink.vive.StoredInkErase
import com.vivenotes.byteink.vive.ViveInkCodec
import com.vivenotes.byteink.vive.subtract

data class ErasePreview(val operation: StoredInkErase, val projections: List<PageStroke>)

fun partialErase(page: List<PageStroke>): ErasePreview {
    val inputs = MutableStrokeInputBatch().apply {
        add(type = InputToolType.MOUSE, x = 50f, y = 30f, elapsedTimeMillis = 0L)
        add(type = InputToolType.MOUSE, x = 50f, y = 70f, elapsedTimeMillis = 40L)
    }
    val mask = requireNotNull(ViveInkCodec.reloadedEraseMask(inputs, sizeDp = 18f))
    val targets = InkPageIndex(page).targetsFor(mask)
    val operation = ViveInkCodec.encodeErase(
        mask = mask,
        id = "erase-1",
        pageId = "page-1",
        mode = InkEraseMode.Normal,
        createdAt = 43L,
        targetIds = targets,
    )
    return ErasePreview(operation, page.subtract(mask, targets))
}
