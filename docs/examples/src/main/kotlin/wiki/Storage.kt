package wiki

import com.vivenotes.byteink.kit.LoadedInkPage
import com.vivenotes.byteink.kit.StoredInkErase
import com.vivenotes.byteink.kit.StoredInkMove
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveInkPage

fun loadPage(
    strokes: List<StoredInkStroke>,
    erases: List<StoredInkErase>,
    moves: List<StoredInkMove>,
): LoadedInkPage = ViveInkPage.load(
    strokes = strokes,
    erases = erases,
    moves = moves,
)
