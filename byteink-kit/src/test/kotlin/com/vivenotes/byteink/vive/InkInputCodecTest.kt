package com.vivenotes.byteink.vive

import androidx.ink.brush.InputToolType
import androidx.ink.storage.encode
import androidx.ink.strokes.MutableStrokeInputBatch
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class InkInputCodecTest {
    @Test
    fun rawEncodingMatchesThePinnedUpstreamProtobufAndOwnsItsResult() {
        val inputs = inputs()
        val expected = GZIPInputStream(inputs.encode().inputStream()).use { it.readBytes() }
        val raw = InkInputCodec.encode(inputs)
        assertContentEquals(expected, raw)
        raw.fill(0)
        assertContentEquals(expected, InkInputCodec.encode(inputs))
        val decoded = InkInputCodec.decode(expected, expected.size)
        inputs.clear()
        assertEquals(2, decoded.size)
        assertEquals(InputToolType.STYLUS, decoded[0].toolType)
        assertEquals(0.25f, decoded[0].pressure)
        assertEquals(0x12345678, decoded.getNoiseSeed())
        assertContentEquals(expected, InkInputCodec.encode(decoded))
    }

    @Test
    fun rawDecodeUsesOnlyTheInitializedPrefixAndLeavesTheBufferUnchanged() {
        val raw = InkInputCodec.encode(inputs())
        val capacity = raw + ByteArray(4096) { 0x7f }
        val original = capacity.copyOf()
        val decoded = InkInputCodec.decode(capacity, raw.size)
        assertContentEquals(raw, InkInputCodec.encode(decoded))
        assertContentEquals(original, capacity)
        capacity.fill(0)
        assertContentEquals(raw, InkInputCodec.encode(decoded))
    }

    @Test
    fun invalidLengthsAndProtobufsFailBeforeAllocatingAnInputPeer() {
        val raw = InkInputCodec.encode(inputs())
        for (size in listOf(-1, raw.size + 1, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { InkInputCodec.decode(raw, size) }
        }
        assertFailsWith<IllegalArgumentException> { InkInputCodec.decode(byteArrayOf(0), 1) }
        assertEquals(0, InkInputCodec.decode(byteArrayOf(0x7f), 0).size)
        assertEquals(2, InkInputCodec.decode(raw, raw.size).size)
    }

    @Test
    fun emptyBatchesKeepTheUpstreamRawEncoding() {
        val inputs = MutableStrokeInputBatch()
        val raw = InkInputCodec.encode(inputs)
        assertContentEquals(GZIPInputStream(inputs.encode().inputStream()).use { it.readBytes() }, raw)
        assertEquals(0, InkInputCodec.decode(raw, raw.size).size)
    }

    private fun inputs() = MutableStrokeInputBatch().apply {
        setNoiseSeed(0x12345678)
        add(InputToolType.STYLUS, 1f, 2f, 0L, pressure = 0.25f, tiltRadians = 0.5f, orientationRadians = 1f)
        add(InputToolType.STYLUS, 9f, 10f, 20L, pressure = 0.75f, tiltRadians = 0.8f, orientationRadians = 2f)
    }
}
