package wiki

import com.vivenotes.byteink.kit.InkLassoMove
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.PageBounds
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.StoredInkMove
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.moveSelected
import com.vivenotes.byteink.kit.selectInkWithLasso

fun moveWithLasso(page: List<PageStroke>): Pair<List<PageStroke>, StoredInkMove>? {
    val loop = listOf(
        InkPoint(0f, 0f), InkPoint(120f, 0f), InkPoint(120f, 120f),
        InkPoint(0f, 120f), InkPoint(0f, 0f),
    )
    val selection = page.selectInkWithLasso(path = loop) ?: return null
    val delta = PageBounds.clampTranslation(selection.bounds, dx = 20f, dy = 10f)
    val move = InkLassoMove(
        path = selection.path,
        targetIds = selection.targetIds,
        projections = selection.projections,
        dx = delta.x,
        dy = delta.y,
    )
    val row = ViveInkCodec.encodeMove(move, id = "move-1", pageId = "page-1", createdAt = 44L)
    return page.moveSelected(move) to row
}
