package com.vivenotes.byteink.core

import androidx.ink.geometry.Box
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import java.lang.management.ManagementFactory
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assume.assumeTrue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpatialIndexTest {

    private class Item(val name: Int, val box: Box?)

    @Test
    fun findsExactlyWhatAScanFindsInListOrder() {
        val random = Random(11)
        val items = List(5_000) { index ->
            when (index % 50) {
                // Some with no geometry, and some spanning most of the page.
                0 -> Item(index, null)
                1 -> Item(index, box(random.nextFloat() * 100f, random.nextFloat() * 100f, 4000f + random.nextFloat() * 2000f, 5000f))
                else -> {
                    val x = random.nextFloat() * 6000f - 200f
                    val y = random.nextFloat() * 6000f - 200f
                    Item(index, box(x, y, x + random.nextFloat() * 40f, y + random.nextFloat() * 40f))
                }
            }
        }
        val index = SpatialIndex.of(items) { it.box }

        repeat(2_000) { query ->
            val x = random.nextFloat() * 7000f - 500f
            val y = random.nextFloat() * 7000f - 500f
            // Mostly eraser-sized questions, sometimes one wider than the page.
            val size = if (query % 100 == 0) 20_000f else random.nextFloat() * 60f
            val found = index.query(x, y, x + size, y + size).map { it.name }

            assertEquals(scan(items, x, y, x + size, y + size), found, "query $query at ($x, $y) size $size")
        }
    }

    @Test
    fun touchingBoxesMeet() {
        val items = listOf(Item(0, box(0f, 0f, 10f, 10f)))
        val index = SpatialIndex.of(items) { it.box }

        assertEquals(listOf(0), index.query(10f, 10f, 20f, 20f).map { it.name })
        assertEquals(emptyList(), index.query(10.001f, 10.001f, 20f, 20f).map { it.name })
    }

    @Test
    fun refusesACellThatIsNotASize() {
        assertFailsWith<IllegalArgumentException> { SpatialIndex.of(emptyList<Item>(), cellSize = 0f) { it.box } }
    }

    @org.junit.Test(timeout = 5_000)
    fun saturatedCellRangesUseTheBoundedFallbackWithoutOverflow() {
        val items = listOf(
            Item(0, box(-1e20f, -1e20f, 1e20f, 1e20f)),
            Item(1, box(0f, 0f, 10f, 10f)),
        )
        val index = SpatialIndex.of(items) { it.box }
        assertEquals(listOf(0, 1), index.query(-1e20f, -1e20f, 1e20f, 1e20f).map { it.name })
        assertEquals(listOf(0), index.query(1000f, 1000f, 1001f, 1001f).map { it.name })
    }

    @Test
    fun repeatedBucketMembershipAndNegativeCellsStillReturnOneHitInSourceOrder() {
        val items = List(150) { Item(it, box(-240f, -240f, 240f, 240f)) } +
            Item(150, box(-1e20f, -1e20f, 1e20f, 1e20f))
        val index = SpatialIndex.of(items) { it.box }
        for (view in listOf(floatArrayOf(-128f, -128f, 128f, 128f), floatArrayOf(-1f, -1f, 1f, 1f),
            floatArrayOf(240f, 240f, 260f, 260f), floatArrayOf(500f, 500f, 501f, 501f))) {
            assertEquals(scan(items, view[0], view[1], view[2], view[3]),
                index.query(view[0], view[1], view[2], view[3]).map { it.name })
        }
    }

    @Test
    fun immutableQueriesRemainIndependentAcrossWorkers() {
        val items = List(5000) { Item(it, box(it % 100 * 64f - 1000f, it / 100 * 64f - 500f,
            it % 100 * 64f - 980f, it / 100 * 64f - 480f)) }
        val index = SpatialIndex.of(items) { it.box }
        val workers = Executors.newFixedThreadPool(4)
        try {
            val jobs = List(4) { worker -> Callable {
                repeat(250) { query ->
                    val x = (query + worker * 17) % 100 * 64f - 1000f
                    val y = (query + worker * 13) % 50 * 64f - 500f
                    assertEquals(scan(items, x, y, x + 128f, y + 128f),
                        index.query(x, y, x + 128f, y + 128f).map { it.name })
                    assertTrue(index.query(-2000f, -2000f, -1900f, -1900f).isEmpty())
                }
            } }
            workers.invokeAll(jobs).forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS))
        }
    }

    @Test
    fun emptyQueryAllocationDoesNotGrowWithThePage() {
        val allocation = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue(allocation?.isThreadAllocatedMemorySupported == true)
        val counter = requireNotNull(allocation)
        counter.isThreadAllocatedMemoryEnabled = true
        val bounds = box(0f, 0f, 1f, 1f)
        val small = SpatialIndex.of(List(40_000) { it }) { bounds }
        val large = SpatialIndex.of(List(200_000) { it }) { bounds }
        fun measure(index: SpatialIndex<Int>): Long {
            repeat(256) { assertTrue(index.query(-128f, -128f, -127f, -127f).isEmpty()) }
            val thread = Thread.currentThread().threadId()
            val before = counter.getThreadAllocatedBytes(thread)
            repeat(256) { assertTrue(index.query(-128f, -128f, -127f, -127f).isEmpty()) }
            return counter.getThreadAllocatedBytes(thread) - before
        }
        val smallBytes = measure(small)
        val largeBytes = measure(large)
        assertTrue(smallBytes >= 0 && largeBytes >= 0)
        assertTrue(largeBytes <= smallBytes + 256 * 256, "40k=$smallBytes bytes, 200k=$largeBytes bytes")
    }

    private fun scan(items: List<Item>, xMin: Float, yMin: Float, xMax: Float, yMax: Float): List<Int> =
        items.filter { item ->
            val box = item.box ?: return@filter false
            box.xMin <= xMax && box.xMax >= xMin && box.yMin <= yMax && box.yMax >= yMin
        }.map { it.name }

    private fun box(x0: Float, y0: Float, x1: Float, y1: Float): Box = ImmutableBox.fromTwoPoints(ImmutableVec(x0, y0), ImmutableVec(x1, y1))
}
