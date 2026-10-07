package wiki

import androidx.ink.brush.InputToolType
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.kit.InkPageIndex
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkPage
import java.io.File
import kotlin.math.abs

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
    val concurrent = simultaneousPointerStrokes()
    check(concurrent.keys == setOf(11L, 22L)) { "Expected both completed pointers, got ${concurrent.keys}" }
    val pen = concurrent.getValue(11L)
    val touch = concurrent.getValue(22L)
    for ((authoredPointer, realX, realY) in listOf(
        Triple(pen, listOf(10f, 20f, 30f, 35f, 45f), 20f),
        Triple(touch, listOf(10f, 20f, 30f), 50f),
    )) {
        val rowId = authoredPointer.row.id
        check(ViveInkCodec.hasValidInputData(authoredPointer.row.points)) { "$rowId has unreadable saved inputs" }
        val native = authoredPointer.stroke.inputs
        check(native.size == realX.size) { "$rowId native real count: expected ${realX.size}, got ${native.size}" }
        val nativeX = (0 until native.size).map { native[it].x }
        check(nativeX == realX) { "$rowId native real coordinates: expected $realX, got $nativeX" }
        val decoded = authoredPointer.canonicalStroke.inputs
        check(decoded.size == realX.size) { "$rowId saved real count: expected ${realX.size}, got ${decoded.size}" }
        // The pinned Ink encoder rounds x to 4096 intervals across the real-input envelope.
        // Allow half an interval plus four float ULPs for arithmetic in this small fixture.
        val xTolerance = (realX.max() - realX.min()) / 8192f + 4f * Math.ulp(realX.maxOf { abs(it) })
        realX.forEachIndexed { index, expectedX ->
            val source = native[index]
            val saved = decoded[index]
            check(abs(saved.x - expectedX) <= xTolerance) {
                "$rowId saved x[$index]: expected $expectedX +/- $xTolerance, got ${saved.x}"
            }
            // Constant y has a zero-height envelope and is exactly representable here.
            check(source.y == realY && saved.y == realY) {
                "$rowId y[$index]: expected $realY, native=${source.y}, saved=${saved.y}"
            }
            check(saved.toolType == source.toolType) {
                "$rowId tool[$index]: native=${source.toolType}, saved=${saved.toolType}"
            }
        }
    }
    check(pen.canonicalStroke.inputs[0].toolType == InputToolType.STYLUS) { "Pen tool identity was lost" }
    // 0.5 is exactly 2048/4096 on the pinned pressure grid, so equality is valid for this fixture.
    check(pen.stroke.inputs[0].pressure == .5f && pen.canonicalStroke.inputs[0].pressure == .5f) {
        "Pen pressure: expected 0.5, native=${pen.stroke.inputs[0].pressure}, saved=${pen.canonicalStroke.inputs[0].pressure}"
    }
    check(touch.canonicalStroke.inputs[0].toolType == InputToolType.TOUCH) { "Touch tool identity was lost" }
    val triangles = InkMeshes.triangles(authored.canonicalStroke.shape, group = 0)
    check(triangles.sumOf { it.triangleCount } > 0)
    val meshes = InkMeshes.rendering(authored.canonicalStroke.shape, group = 0)
    check(meshes.sumOf { it.vertexCount } > 0)
    check(meshes.sumOf { it.triangleCount } == triangles.sumOf { it.triangleCount })

    val erase = partialErase(original.strokes)
    val replayed = loadPage(listOf(authored.row), listOf(erase.operation), emptyList())
    check(replayed.strokes.size == 2)
    check(erase.projections.map { it.pageBounds } == replayed.strokes.map { it.pageBounds })
    val decodedReplay = ViveInkPage.replay(replayed.sourceStrokes, replayed.operations)
    check(decodedReplay.map { it.pageBounds } == replayed.strokes.map { it.pageBounds })
    val undone = ViveInkPage.replay(replayed.sourceStrokes, emptyList())
    check(undone.map { it.pageBounds } == original.strokes.map { it.pageBounds })
    val erasedIndex = InkPageIndex(replayed.strokes)
    check(erasedIndex.at(InkPoint(50f, 50f), reach = 1f).isEmpty())
    check(erasedIndex.at(InkPoint(20f, 50f), reach = 1f).isNotEmpty())

    val (moved, moveRow) = requireNotNull(moveWithLasso(original.strokes))
    val movedReload = loadPage(listOf(authored.row), emptyList(), listOf(moveRow))
    check(moved.map { it.pageBounds } == movedReload.strokes.map { it.pageBounds })
    val png = File(output, "partial-erase.png")
    check(renderInk(replayed.strokes, png) == 2)
    check(png.length() > 0L)
    println("ByteInk wiki examples passed: authoring, simultaneous pointers/predictions, codec, partial erase/decoded replay/undo, lasso, mesh attributes, mesh PNG")
    println("Native: ${InkRuntime.load()}")
    println("PNG: ${png.absolutePath}")
}
