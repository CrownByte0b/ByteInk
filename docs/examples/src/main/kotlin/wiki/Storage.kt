package wiki

import com.vivenotes.byteink.vive.LoadedInkPage
import com.vivenotes.byteink.vive.StoredInkErase
import com.vivenotes.byteink.vive.StoredInkMove
import com.vivenotes.byteink.vive.StoredInkStroke
import com.vivenotes.byteink.vive.ViveInkPage

fun loadPage(
    strokes: List<StoredInkStroke>,
    erases: List<StoredInkErase>,
    moves: List<StoredInkMove>,
): LoadedInkPage = ViveInkPage.load(
    strokes = strokes,
    erases = erases,
    moves = moves,
)
