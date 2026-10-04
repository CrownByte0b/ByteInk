package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import kotlin.random.Random

/**
 * Random strokes, masks and lassos through every operation byteink runs on stored ink, for
 * [FuzzTest] to run in a JVM of its own: Ink reports some failures by aborting the process, which
 * no exception handler can catch. Before each iteration it prints `iteration <n>`, so the parent
 * knows which one a crash took.
 *
 * Usage: FuzzMain <seed> <iterations>
 */
fun main(arguments: Array<String>) {
    val seed = arguments[0].toLong()
    val iterations = arguments[1].toInt()
    repeat(iterations) { iteration ->
        println("iteration $iteration")
        System.out.flush()
        Fuzz(Random(seed * 1_000_003L + iteration)).run()
    }
    println("done $iterations")
}

private class Fuzz(private val random: Random) {

    fun run() {
        val page = List(1 + random.nextInt(6)) { index -> attempt { projection("row${index % 4}") } }.filterNotNull()
        val mask = attempt { ViveBrushes.eraseMask(inputs(), sizeDp = size()) } ?: return
        // Every operation a page goes through; exceptions are the engine refusing, which is fine.
        attempt { page.targetsFor(mask) }
        val cut = attempt { page.subtract(mask, page.map { it.id }) } ?: page
        attempt { cut.eraseObjects(mask, cut.map { it.id }) }
        attempt { InkPageIndex(cut).touching(mask) }
        val path = path()
        attempt { cut.selectWithLasso(path, edgeTolerance = random.nextFloat() * 8f) }
        attempt { cut.selectInkWithLasso(path) }
        attempt { cut.replayMove(path, cut.map { it.id }, random.nextFloat() * 50f - 25f, random.nextFloat() * 50f - 25f) }
        attempt { cut.replayResize(path, cut.map { it.id }, point(), 0.1f + random.nextFloat() * 3f, 0.1f + random.nextFloat() * 3f) }
        attempt { cut.planProjectionDelete(cut.filter { random.nextBoolean() }.map { it.projectionKey }.toSet()) }
        cut.forEach { stroke ->
            attempt { stroke.pointOnInk() }
            attempt { stroke.translatedCopy(random.nextFloat() * 10f, random.nextFloat() * 10f) }
        }
        // Stored forms: round trips, and damaged blobs.
        page.forEach { stroke ->
            val row = attempt {
                ViveInkCodec.encodeStroke(stroke.stroke, "row", "page", 0, stroke.brushFamily, stroke.stabilization, null, 0L)
            } ?: return@forEach
            attempt { ViveInkCodec.decode(row) }
            attempt { ViveInkCodec.decode(row.copy(points = damaged(row.points))) }
        }
    }

    private fun projection(id: String): PageStroke {
        val family = listOf(
            ViveBrushes.MARKER,
            ViveBrushes.DASHED_LINE,
            ViveBrushes.HIGHLIGHTER,
            ViveBrushes.PRESSURE_PEN,
            ViveBrushes.calligraphy(random.nextInt(6)),
        ).random(random)
        val brush = ViveBrushes.brush(family, random.nextInt(6), random.nextInt(), size())
        val scale = if (random.nextInt(4) == 0) random.nextFloat() * 4f - 2f else 1f
        return PageStroke(
            id = id,
            stroke = Stroke(brush, inputs()),
            offsetX = random.nextFloat() * 40f - 20f,
            offsetY = random.nextFloat() * 40f - 20f,
            scaleX = scale.takeIf { it != 0f } ?: 1f,
            scaleY = scale.takeIf { it != 0f } ?: 1f,
            brushFamily = family,
        )
    }

    /** A batch of inputs the engine accepts: mostly sensible, sometimes a dot, a line folding back, or huge. */
    private fun inputs(): StrokeInputBatch {
        val batch = MutableStrokeInputBatch()
        val tool = TOOLS.random(random)
        val count = when (random.nextInt(5)) {
            0 -> 1
            1 -> 2
            else -> 3 + random.nextInt(40)
        }
        val scale = if (random.nextInt(10) == 0) 10_000f else 100f
        var time = 0L
        var x = random.nextFloat() * scale
        var y = random.nextFloat() * scale
        repeat(count) {
            // Some samples repeat the previous position, which a hand at rest produces.
            if (random.nextInt(6) != 0) {
                x += random.nextFloat() * 20f - 10f
                y += random.nextFloat() * 20f - 10f
            }
            time += random.nextInt(0, 30).toLong()
            try {
                if (tool == InputToolType.STYLUS) {
                    batch.add(tool, x, y, time, pressure = random.nextFloat(), tiltRadians = random.nextFloat() * 1.5f, orientationRadians = random.nextFloat() * 6f)
                } else {
                    batch.add(tool, x, y, time)
                }
            } catch (refused: Exception) {
                // A duplicate the batch refuses; the next sample moves on.
            }
        }
        return batch.toImmutable()
    }

    private fun path(): List<InkPoint> = List(random.nextInt(1, 12)) { point() }.let { points ->
        // Sometimes every point on one line, or all the same point: loops with no area.
        when (random.nextInt(8)) {
            0 -> points.map { InkPoint(it.x, points.first().y) }
            1 -> points.map { points.first() }
            else -> points
        }
    }

    private fun point() = InkPoint(random.nextFloat() * 160f - 30f, random.nextFloat() * 160f - 30f)

    /** A brush size: the engine's least ([ViveBrushes.EPSILON]) upward, sometimes very large. */
    private fun size(): Float = when (random.nextInt(8)) {
        0 -> ViveBrushes.EPSILON + random.nextFloat() * 0.25f
        1 -> 50f + random.nextFloat() * 200f
        else -> 1f + random.nextFloat() * 20f
    }

    private fun damaged(bytes: ByteArray): ByteArray = when (random.nextInt(3)) {
        0 -> bytes.copyOf(random.nextInt(bytes.size + 1))
        1 -> bytes.copyOf().also { if (it.isNotEmpty()) it[random.nextInt(it.size)] = random.nextInt().toByte() }
        else -> bytes + ByteArray(random.nextInt(1, 20)) { random.nextInt().toByte() }
    }

    private inline fun <T> attempt(operation: () -> T): T? = try {
        operation()
    } catch (refused: Exception) {
        null
    }
}

private val TOOLS = listOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH, InputToolType.STYLUS)
