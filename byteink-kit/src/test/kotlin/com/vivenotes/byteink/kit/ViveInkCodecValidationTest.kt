package com.vivenotes.byteink.kit

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.management.ManagementFactory
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ViveInkCodecValidationTest {
    @Test
    fun concatenatedMembersDecodeTheSameInputsAsASingleMember() {
        val original = fixture("two-point-unknown.bin")
        val proto = GZIPInputStream(original.inputStream()).use { it.readBytes() }
        val split = proto.size / 2
        val concatenated = gzip(proto.copyOfRange(0, split)) + gzip(proto.copyOfRange(split, proto.size))

        assertTrue(ViveInkCodec.hasValidInputData(concatenated))
        assertContentEquals(
            ViveInkCodec.encodeInputs(ViveInkCodec.decodeInputs(original)),
            ViveInkCodec.encodeInputs(ViveInkCodec.decodeInputs(concatenated)),
        )
    }

    @Test
    fun corruptAndTruncatedInputsDoNotPoisonLaterDecodesOnTheSameThread() {
        val valid = fixture("two-point-unknown.bin")
        val damaged = listOf(
            byteArrayOf(),
            byteArrayOf(1, 2, 3),
            valid.copyOf(9), // Incomplete gzip header.
            valid.copyOf(valid.size - 9), // Truncated compressed payload.
            valid.copyOf(valid.size - 1), // Truncated trailer.
            corrupt(valid, valid.size - 8), // CRC.
            corrupt(valid, valid.size - 4), // Uncompressed size.
            gzip(byteArrayOf(0)), // Valid gzip with an invalid protobuf.
        )
        damaged.forEachIndexed { index, points ->
            assertFalse(ViveInkCodec.hasValidInputData(points), "damaged case $index")
            assertEquals(2, ViveInkCodec.decodeInputs(valid).size, "recovery after case $index")
        }
    }

    @Test
    fun validationReadsThroughLaterMemberTrailersBeforeAcceptingTheInputs() {
        val valid = fixture("two-point-unknown.bin")
        val emptyMember = gzip(byteArrayOf())
        assertTrue(ViveInkCodec.hasValidInputData(valid + emptyMember))
        listOf(
            valid + corrupt(emptyMember, emptyMember.size - 8),
            valid + emptyMember.copyOf(emptyMember.size - 1),
        ).forEach { points ->
            assertFailsWith<IOException> { ViveInkCodec.decodeInputs(points) }
            assertFalse(ViveInkCodec.hasValidInputData(points))
            assertEquals(2, ViveInkCodec.decodeInputs(valid).size)
        }
    }

    @Test
    fun theExpansionLimitIncludesEveryMemberAndStillAllowsRecovery() {
        val halfLimit = ViveInkCodec.MAX_DECOMPRESSED_BYTES / 2
        // Stream into the compressor: constructing the guard test does not need a 64 MiB array.
        val points = gzipZeros(halfLimit) + gzipZeros(halfLimit + 1)
        assertTrue(points.size < 1024 * 1024)
        val failure = assertFailsWith<IOException> { ViveInkCodec.decodeInputs(points) }
        assertEquals("The ink blob expands past ${ViveInkCodec.MAX_DECOMPRESSED_BYTES} bytes", failure.message)
        assertEquals(2, ViveInkCodec.decodeInputs(fixture("two-point-unknown.bin")).size)
    }

    @Test
    fun concurrentWorkersKeepLargeValidAndDamagedDecodesIndependent() {
        val points = listOf(
            fixture("encoding/encoding-stylus-8192.original.pb.gz"),
            fixture("encoding/encoding-mouse-40000.original.pb.gz"),
        )
        val expected = points.map { ViveInkCodec.encodeInputs(ViveInkCodec.decodeInputs(it)) }
        val damaged = points.map { corrupt(it, it.size - 8) }
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 4).map { worker ->
                workers.submit {
                    ready.countDown()
                    check(start.await(30, TimeUnit.SECONDS))
                    repeat(12) { iteration ->
                        val index = (worker + iteration) % points.size
                        assertFalse(ViveInkCodec.hasValidInputData(damaged[index]))
                        assertContentEquals(expected[index], ViveInkCodec.encodeInputs(ViveInkCodec.decodeInputs(points[index])))
                    }
                }
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "workers started")
            start.countDown()
            futures.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            start.countDown()
            workers.shutdownNow()
            assertTrue(workers.awaitTermination(30, TimeUnit.SECONDS), "workers stopped")
        }
    }

    @Test
    fun warmedSmallDecodesDoNotAllocateAnExpandedProtobufArrayPerRow() {
        val allocation = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("JVM exposes per-thread allocated bytes", allocation?.isThreadAllocatedMemorySupported == true)
        val counter = requireNotNull(allocation)
        counter.isThreadAllocatedMemoryEnabled = true
        val points = fixture("two-point-unknown.bin")
        repeat(64) { assertEquals(2, ViveInkCodec.decodeInputs(points).size) }
        val threadId = Thread.currentThread().threadId()
        val before = counter.getThreadAllocatedBytes(threadId)
        var inputsRead = 0
        repeat(64) { inputsRead += ViveInkCodec.decodeInputs(points).size }
        val allocated = counter.getThreadAllocatedBytes(threadId) - before

        assertEquals(128, inputsRead)
        // Broad structural guard: the former upstream 32 KiB inflate array exceeds this budget.
        assertTrue(allocated >= 0 && allocated < 64L * 16 * 1024, "allocated $allocated bytes for 64 small decodes")
    }

    @Test
    fun exactlyTheExpansionLimitStillChecksTheTrailerAndThenParsesTheProtobuf() {
        val points = gzipZeros(ViveInkCodec.MAX_DECOMPRESSED_BYTES)
        // Zero bytes are invalid protobuf, but exactly the expansion cap is permitted.
        assertFailsWith<IllegalArgumentException> { ViveInkCodec.decodeInputs(points) }
        assertFailsWith<IOException> { ViveInkCodec.decodeInputs(corrupt(points, points.size - 8)) }
        assertEquals(2, ViveInkCodec.decodeInputs(fixture("two-point-unknown.bin")).size)
    }

    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResource(
        "/ink/androidx-ink-1.1.0-alpha06/$name")) { "Missing fixture: $name" }.readBytes()

    private fun corrupt(bytes: ByteArray, index: Int): ByteArray = bytes.copyOf().apply {
        this[index] = (this[index].toInt() xor 1).toByte()
    }

    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()

    private fun gzipZeros(count: Int): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { gzip ->
            val buffer = ByteArray(8192)
            var remaining = count
            while (remaining > 0) {
                val written = minOf(remaining, buffer.size)
                gzip.write(buffer, 0, written)
                remaining -= written
            }
        }
    }.toByteArray()
}
