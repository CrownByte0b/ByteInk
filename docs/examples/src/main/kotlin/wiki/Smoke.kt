package wiki

import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.vive.InkPageIndex
import com.vivenotes.byteink.vive.InkPoint
import com.vivenotes.byteink.vive.ViveInkCodec
import java.io.File

/** Executable checks of the wiki's examples using synthetic ink only. */
fun main(args: Array<String>) {
    val output = File(args.single()).apply { mkdirs() }
    val authored = firstStroke()
    check(ViveInkCodec.hasValidInputData(authored.row.points))
    check(authored.canonicalStroke.inputs.size == 2)
    val original = loadPage(listOf(authored.row), emptyList(), emptyList())
    check(original.unreadable.isEmpty() && original.strokes.size == 1)
    val gesture = pointerStroke()
    check(gesture.inputs.size == 3 && gesture.inputs[0].x == 10f)
    val triangles = InkMeshes.triangles(authored.canonicalStroke.shape, group = 0)
    check(triangles.sumOf { it.triangleCount } > 0)

    val erase = partialErase(original.strokes)
    val replayed = loadPage(listOf(authored.row), listOf(erase.operation), emptyList())
    check(replayed.strokes.size == 2)
    check(erase.projections.map { it.pageBounds } == replayed.strokes.map { it.pageBounds })
    val erasedIndex = InkPageIndex(replayed.strokes)
    check(erasedIndex.at(InkPoint(50f, 50f), reach = 1f).isEmpty())
    check(erasedIndex.at(InkPoint(20f, 50f), reach = 1f).isNotEmpty())

    val (moved, moveRow) = requireNotNull(moveWithLasso(original.strokes))
    val movedReload = loadPage(listOf(authored.row), emptyList(), listOf(moveRow))
    check(moved.map { it.pageBounds } == movedReload.strokes.map { it.pageBounds })
    val png = File(output, "partial-erase.png")
    check(renderInk(replayed.strokes, png) == 2)
    check(png.length() > 0L)
    println("ByteInk wiki examples passed: authoring, codec, partial erase/replay, lasso, geometry, PNG")
    println("Native: ${InkRuntime.load()}")
    println("PNG: ${png.absolutePath}")
}
