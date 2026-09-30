package com.vivenotes.byteink.core

import androidx.ink.geometry.Box
import java.util.BitSet
import kotlin.math.floor

/**
 * Items on a page, found by where they are: a uniform grid over their bounding boxes, so a question
 * about one place visits the items near it rather than every item on the page.
 *
 * Built once for a list of items and not changed afterwards; a page that changes builds a new one,
 * which for 40,000 items is a few milliseconds. Queries are conservative — an item is returned when
 * its box meets the query's, touching included — and the caller makes the exact test on what comes
 * back. Items come back in list order.
 */
public class SpatialIndex<T> private constructor(
    private val items: List<T>,
    /** xMin, yMin, xMax, yMax for each item; NaN for an item without bounds, which nothing finds. */
    private val boxes: FloatArray,
    private val cellSize: Float,
    private val cells: Map<Long, IntArray>,
    /** Items spanning too many cells to enter in each, tested on every query instead. */
    private val large: IntArray,
) {
    public val size: Int get() = items.size

    /** The items whose boxes meet [box], in list order. */
    public fun query(box: Box): List<T> = query(box.xMin, box.yMin, box.xMax, box.yMax)

    /** The items whose boxes meet the rectangle ([xMin], [yMin])–([xMax], [yMax]), in list order. */
    public fun query(xMin: Float, yMin: Float, xMax: Float, yMax: Float): List<T> {
        val hits = BitSet(items.size)
        val firstColumn = cellOf(xMin, cellSize)
        val lastColumn = cellOf(xMax, cellSize)
        val firstRow = cellOf(yMin, cellSize)
        val lastRow = cellOf(yMax, cellSize)
        if (spansMoreCellsThan(firstColumn, lastColumn, firstRow, lastRow, cells.size.toLong())) {
            // A query this wide would visit more cells than hold anything: test every item instead.
            for (index in items.indices) if (meets(index, xMin, yMin, xMax, yMax)) hits.set(index)
        } else {
            for (column in firstColumn..lastColumn) {
                for (row in firstRow..lastRow) {
                    cells[key(column, row)]?.forEach { index -> if (meets(index, xMin, yMin, xMax, yMax)) hits.set(index) }
                }
            }
            large.forEach { index -> if (meets(index, xMin, yMin, xMax, yMax)) hits.set(index) }
        }
        return buildList(hits.cardinality()) {
            var index = hits.nextSetBit(0)
            while (index >= 0) {
                add(items[index])
                index = hits.nextSetBit(index + 1)
            }
        }
    }

    private fun meets(index: Int, xMin: Float, yMin: Float, xMax: Float, yMax: Float): Boolean {
        val base = index * 4
        val left = boxes[base]
        if (left.isNaN()) return false
        return left <= xMax && boxes[base + 2] >= xMin && boxes[base + 1] <= yMax && boxes[base + 3] >= yMin
    }

    public companion object {
        /**
         * The default cell size in page units: a few times a handwritten stroke's usual extent, so
         * most items fall in one to four cells.
         */
        public const val DEFAULT_CELL_SIZE: Float = 64f

        /** An item spanning more cells than this goes on the list every query tests instead. */
        private const val MAX_CELLS_PER_ITEM = 256L

        /** Indexes [items] by the boxes [bounds] gives them; an item with none is never found. */
        public fun <T> of(items: List<T>, cellSize: Float = DEFAULT_CELL_SIZE, bounds: (T) -> Box?): SpatialIndex<T> {
            require(cellSize > 0f && cellSize.isFinite()) { "cellSize must be positive, not $cellSize" }
            val boxes = FloatArray(items.size * 4)
            val cells = HashMap<Long, MutableList<Int>>()
            val large = ArrayList<Int>()
            items.forEachIndexed { index, item ->
                val base = index * 4
                val box = bounds(item)
                if (box == null) {
                    boxes[base] = Float.NaN
                    return@forEachIndexed
                }
                val xMin = box.xMin
                val xMax = box.xMax
                val yMin = box.yMin
                val yMax = box.yMax
                boxes[base] = xMin
                boxes[base + 1] = yMin
                boxes[base + 2] = xMax
                boxes[base + 3] = yMax
                val firstColumn = cellOf(xMin, cellSize)
                val lastColumn = cellOf(xMax, cellSize)
                val firstRow = cellOf(yMin, cellSize)
                val lastRow = cellOf(yMax, cellSize)
                if (spansMoreCellsThan(firstColumn, lastColumn, firstRow, lastRow, MAX_CELLS_PER_ITEM)) {
                    large += index
                } else {
                    for (column in firstColumn..lastColumn) {
                        for (row in firstRow..lastRow) cells.getOrPut(key(column, row)) { ArrayList(4) } += index
                    }
                }
            }
            return SpatialIndex(
                items = items,
                boxes = boxes,
                cellSize = cellSize,
                cells = cells.mapValues { (_, indices) -> indices.toIntArray() },
                large = large.toIntArray(),
            )
        }

        /** The cell a coordinate falls in; `toInt` saturates, so no coordinate overflows. */
        private fun cellOf(coordinate: Float, cellSize: Float): Int = floor(coordinate.toDouble() / cellSize).toInt()

        /** Test each dimension before multiplying: saturated cell coordinates can span 2^32 cells. */
        private fun spansMoreCellsThan(firstColumn: Int, lastColumn: Int, firstRow: Int, lastRow: Int, limit: Long): Boolean {
            val columns = lastColumn.toLong() - firstColumn + 1
            val rows = lastRow.toLong() - firstRow + 1
            return columns > limit || rows > limit || columns * rows > limit
        }

        private fun key(column: Int, row: Int): Long = (column.toLong() shl 32) or (row.toLong() and 0xFFFF_FFFFL)
    }
}
