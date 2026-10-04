package com.vivenotes.byteink.kit

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The index answers exactly what the unindexed scans answer, on every kind of projection. */
class InkPageIndexTest {

    private val random = Random(23)

    /** Strokes, moved and resized and mirrored ones, cut pieces with no outlines, highlighters, and an empty one. */
    private val page: List<PageStroke> = buildList {
        repeat(240) { index ->
            val x = random.nextFloat() * 900f
            val y = random.nextFloat() * 900f
            val stroke = if (index % 7 == 0) {
                Stroke(ViveBrushes.highlighter(0x66FFEB3B, 12f), line(x to y, x + 40f to y + 5f))
            } else {
                Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 1, BLACK, 3f), line(x to y, x + 30f to y + 20f, x + 10f to y + 35f))
            }
            add(
                when (index % 5) {
                    1 -> PageStroke("row$index", stroke, offsetX = 50f, offsetY = -20f)
                    2 -> PageStroke("row$index", stroke, scaleX = 1.5f, scaleY = 0.75f, offsetX = -100f)
                    3 -> PageStroke("row$index", stroke, scaleX = -1f, offsetX = 1000f)
                    else -> PageStroke("row$index", stroke)
                },
            )
        }
        add(PageStroke("empty", Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 1, BLACK, 3f), MutableStrokeInputBatch().toImmutable())))
    }.let { strokes ->
        // Cut some in two, so the page also holds pieces that carry no outlines.
        val cuts = (0 until 30).map { ViveBrushes.eraseMask(line(random.nextFloat() * 900f to 0f, random.nextFloat() * 900f to 1000f), 8f) }
        cuts.fold(strokes) { current, mask -> current.subtract(mask, current.targetsFor(mask)) }
    }

    private val index = InkPageIndex(page)

    @Test
    fun anEraserMaskTouchesWhatTheScanFinds() {
        assertTrue(page.size > 250, "the cuts left pieces")
        repeat(300) { attempt ->
            val x = random.nextFloat() * 1100f - 100f
            val y = random.nextFloat() * 1100f - 100f
            val mask = ViveBrushes.eraseMask(
                line(x to y, x + random.nextFloat() * 80f to y + random.nextFloat() * 80f),
                sizeDp = 2f + random.nextFloat() * 30f,
            )

            assertEquals(page.targetsFor(mask), index.targetsFor(mask), "mask $attempt")
            assertEquals(page.filter { it.touches(mask) }.map { it.projectionKey }, index.touching(mask).map { it.projectionKey }, "mask $attempt")
        }
    }

    @Test
    fun aLassoSelectsWhatTheScanSelects() {
        var selections = 0
        repeat(300) { attempt ->
            val x = random.nextFloat() * 1000f - 50f
            val y = random.nextFloat() * 1000f - 50f
            val w = 20f + random.nextFloat() * 300f
            val h = 20f + random.nextFloat() * 300f
            // Concave, so the exact test and the mesh fallback are both reached.
            val path = listOf(
                InkPoint(x, y), InkPoint(x + w, y), InkPoint(x + w - 3f, y + h / 2f), InkPoint(x + w, y + h), InkPoint(x, y + h),
            )

            val scanned = page.selectWithLasso(path)
            assertEquals(scanned, index.selectWithLasso(path), "lasso $attempt")
            if (scanned != null) selections++
        }
        assertTrue(selections > 20, "the lassos caught something often enough to mean anything: $selections")
    }

    @Test
    fun aPointAndASegmentFindTheInkUnderThem() {
        val target = page.first { it.id == "row0" }
        val onInk = checkNotNull(target.pointOnInk())

        assertTrue(target.projectionKey in index.at(onInk, reach = 0.5f).map { it.projectionKey })
        assertTrue(index.at(InkPoint(-500f, -500f), reach = 2f).isEmpty())

        val across = index.crossing(InkPoint(onInk.x - 20f, onInk.y), InkPoint(onInk.x + 20f, onInk.y), width = 2f)
        assertTrue(target.projectionKey in across.map { it.projectionKey })
        assertTrue(index.crossing(InkPoint(-500f, -500f), InkPoint(-400f, -500f), width = 2f).isEmpty())
    }

    private fun line(vararg points: Pair<Float, Float>): StrokeInputBatch = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }.toImmutable()

    private companion object {
        const val BLACK = 0xFF000000.toInt()
    }
}
