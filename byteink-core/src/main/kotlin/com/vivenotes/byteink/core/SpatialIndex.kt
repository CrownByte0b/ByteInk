package com.vivenotes.byteink.core

import androidx.ink.geometry.Box
import java.util.Arrays
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
    private val cells: Cells,
    /** Items spanning too many cells to enter in each, tested on every query instead. */
    private val large: IntArray,
) {
    public val size: Int get() = items.size

    /** The items whose boxes meet [box], in list order. */
    public fun query(box: Box): List<T> = query(box.xMin, box.yMin, box.xMax, box.yMax)

    /** The items whose boxes meet the rectangle ([xMin], [yMin])–([xMax], [yMax]), in list order. */
    public fun query(xMin: Float, yMin: Float, xMax: Float, yMax: Float): List<T> {
        val firstColumn = cellOf(xMin, cellSize)
        val lastColumn = cellOf(xMax, cellSize)
        val firstRow = cellOf(yMin, cellSize)
        val lastRow = cellOf(yMax, cellSize)
        if (spansMoreCellsThan(firstColumn, lastColumn, firstRow, lastRow, cells.size.toLong())) {
            // A query this wide would visit more cells than hold anything: test every item instead.
            return scan(xMin, yMin, xMax, yMax)
        }
        // Query-local primitive storage keeps sparse allocation independent of page size and
        // makes concurrent/reentrant queries safe. Sort/dedup before testing any candidate bounds.
        val candidates = Indices()
        val scanThreshold = maxOf(64, items.size / 2)
        for (row in firstRow..lastRow) {
            for (column in firstColumn..lastColumn) {
                val bucket = cells[key(column, row)] ?: continue
                if (bucket.size > scanThreshold - candidates.size) return scan(xMin, yMin, xMax, yMax)
                candidates.addAll(bucket)
            }
        }
        if (large.size > scanThreshold - candidates.size) return scan(xMin, yMin, xMax, yMax)
        candidates.addAll(large)
        if (candidates.size == 0) return emptyList()
        Arrays.sort(candidates.values, 0, candidates.size)
        var previous = -1
        var hitCount = 0
        for (at in 0 until candidates.size) {
            val index = candidates.values[at]
            if (index != previous && meets(index, xMin, yMin, xMax, yMax)) candidates.values[hitCount++] = index
            previous = index
        }
        if (hitCount == 0) return emptyList()
        return buildList(hitCount) {
            for (at in 0 until hitCount) add(items[candidates.values[at]])
        }
    }

    private fun scan(xMin: Float, yMin: Float, xMax: Float, yMax: Float): List<T> {
        // Dense results use one bit per hit, then allocate their result list exactly once.
        // Lazy BitSet growth also keeps a wide empty scan from allocating page-sized storage.
        val hits = BitSet()
        for (index in items.indices) if (meets(index, xMin, yMin, xMax, yMax)) hits.set(index)
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
            val cells = CellBuilder()
            val large = Indices()
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
                    large.add(index)
                } else {
                    for (column in firstColumn..lastColumn) {
                        for (row in firstRow..lastRow) cells.add(key(column, row), index)
                    }
                }
            }
            return SpatialIndex(
                items = items,
                boxes = boxes,
                cellSize = cellSize,
                cells = cells.freeze(),
                large = large.values.copyOf(large.size),
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

    private class Indices {
        var values = IntArray(0)
            private set
        var size = 0
            private set

        fun add(value: Int) {
            reserve(1)
            values[size++] = value
        }

        fun addAll(source: IntArray) {
            if (source.isEmpty()) return
            reserve(source.size)
            source.copyInto(values, size)
            size += source.size
        }

        private fun reserve(extra: Int) {
            if (extra <= values.size - size) return
            values = values.copyOf(maxOf(4, size + extra, values.size * 2))
        }
    }

    /** Open-addressed primitive cell keys; null buckets mark vacancy, including key zero. */
    private class Cells(val keys: LongArray, val buckets: Array<IntArray?>, val size: Int) {
        operator fun get(key: Long): IntArray? {
            var slot = slot(key, keys.size)
            while (buckets[slot] != null) {
                if (keys[slot] == key) return buckets[slot]
                slot = (slot + 1) and (keys.size - 1)
            }
            return null
        }
    }

    private class CellBuilder {
        private var keys = LongArray(16)
        private var buckets = arrayOfNulls<Indices>(16)
        private var size = 0

        fun add(key: Long, index: Int) {
            if (size >= keys.size / 2) grow()
            var slot = slot(key, keys.size)
            while (buckets[slot] != null && keys[slot] != key) slot = (slot + 1) and (keys.size - 1)
            val bucket = buckets[slot] ?: Indices().also {
                keys[slot] = key
                buckets[slot] = it
                size++
            }
            bucket.add(index)
        }

        fun freeze(): Cells = Cells(keys, Array(buckets.size) { at ->
            buckets[at]?.let { it.values.copyOf(it.size) }
        }, size)

        private fun grow() {
            val oldKeys = keys
            val oldBuckets = buckets
            keys = LongArray(oldKeys.size * 2)
            buckets = arrayOfNulls(keys.size)
            oldBuckets.forEachIndexed { at, bucket ->
                if (bucket != null) {
                    var slot = slot(oldKeys[at], keys.size)
                    while (buckets[slot] != null) slot = (slot + 1) and (keys.size - 1)
                    keys[slot] = oldKeys[at]
                    buckets[slot] = bucket
                }
            }
        }
    }
}

// Mix both packed coordinates; Long.hashCode's column xor row collides along grid diagonals.
private fun slot(key: Long, capacity: Int): Int {
    var mixed = key
    mixed = (mixed xor (mixed ushr 33)) * -49064778989728563L
    mixed = (mixed xor (mixed ushr 33)) * -4265267296055464877L
    return (mixed xor (mixed ushr 33)).toInt() and (capacity - 1)
}
