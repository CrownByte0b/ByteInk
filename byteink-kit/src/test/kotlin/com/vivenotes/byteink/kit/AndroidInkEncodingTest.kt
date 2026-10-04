package com.vivenotes.byteink.kit

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Exact Android alpha06 input protobufs and gzip bytes, without field or transport exceptions. */
class AndroidInkEncodingTest {
    @Test
    fun largeAndBoundaryBatchesMatchAndroidCompressionAndReencodingExactly() {
        val cases = mapOf("encoding-empty" to 0, "encoding-single-noise" to 1,
            "encoding-stylus-8192" to 8192, "encoding-mouse-40000" to 40000)
        cases.forEach { (name, count) ->
            val proto = fixture("$name.original.pb")
            val original = fixture("$name.original.pb.gz")
            assertContentEquals(proto, GZIPInputStream(original.inputStream()).use { it.readBytes() }, name)
            assertContentEquals(original, AndroidInkCompression.gzip(proto), "$name original compression")
            val decoded = ViveInkCodec.decodeInputs(original)
            assertEquals(count, decoded.size, name)
            assertContentEquals(fixture("$name.reencoded.pb.gz"), ViveInkCodec.encodeInputs(decoded), "$name decoded reencoding")
            if (name == "encoding-stylus-8192") {
                assertTrue(proto.size > 65536, "Exercise compressor window and block boundaries")
                assertTrue(decoded.hasPressure() && decoded.hasTilt() && decoded.hasOrientation())
            }
        }
        // Compression itself accepts empty bytes; the row encoder adds its own proto fields.
        assertContentEquals(byteArrayOf(0x1f, 0x8b.toByte(), 0x08, 0, 0, 0, 0, 0, 0, 0xff.toByte(),
            0x03, 0, 0, 0, 0, 0, 0, 0, 0, 0), AndroidInkCompression.gzip(byteArrayOf()))
    }

    @Test
    fun everyAndroidDecodedInputBatchReencodesToTheExactAndroidBlob() {
        val directory = File(requireNotNull(System.getProperty("byteink.test.androidInputGoldens")))
        val inputs = File(directory, "cases").walkTopDown()
            .filter { it.isFile && it.name.endsWith(".inputs.pb.gz") }.sortedBy { it.path }.toList()
        assertEquals(840, inputs.size, "Every brush, stabilization, tool and size variant must run")
        inputs.forEach { source ->
            val original = source.readBytes()
            val decoded = ViveInkCodec.decodeInputs(original)
            val expected = File(source.parentFile, source.name.removeSuffix(".inputs.pb.gz") + ".roundtrip.pb.gz")
            assertContentEquals(expected.readBytes(), ViveInkCodec.encodeInputs(decoded), source.path)
            // Reading leaves the original bytes intact; a lossy decode is never a storage rewrite.
            assertContentEquals(original, source.readBytes(), source.path)
        }
    }

    @Test
    fun phaseIsAfterTheNoiseSeedAndNativeNoiseAndInputsSurvive() {
        val inputs = MutableStrokeInputBatch().apply {
            setNoiseSeed(0x12345678)
            add(InputToolType.STYLUS, 1f, 2f, 0L, pressure = 0.5f)
            add(InputToolType.STYLUS, 5f, 6f, 10L, pressure = 1f)
        }
        val encoded = ViveInkCodec.encodeInputs(inputs)
        val proto = GZIPInputStream(encoded.inputStream()).use { it.readBytes() }
        assertContentEquals(byteArrayOf(0x4d, 0x78, 0x56, 0x34, 0x12, 0x55, 0, 0, 0, 0), proto.takeLast(10).toByteArray())
        val decoded = ViveInkCodec.decodeInputs(encoded)
        assertEquals(0x12345678, decoded.getNoiseSeed())
        assertEquals(2, decoded.size)
        assertEquals(InputToolType.STYLUS, decoded[0].toolType)
        assertEquals(0.5f, decoded[0].pressure)
        assertEquals(1f, decoded[1].pressure)
    }

    @Test
    fun existingPrivatePhaseAndUnrecognizedFieldsAreNeverChangedOrDuplicated() {
        // field 1 contains bytes that resemble field 10; only top-level fields count.
        val nested = byteArrayOf(0x0a, 0x05, 0x55, 0, 0, 0, 0)
        val phase = byteArrayOf(0x55, 0, 0, 0x80.toByte(), 0x3f)
        val later = byteArrayOf(0x60, 0x01)
        assertContentEquals(nested + phase + later, ViveInkCodec.withAndroidAnimationPhase(nested + phase + later))
        assertContentEquals(nested + byteArrayOf(0x55, 0, 0, 0, 0) + later,
            ViveInkCodec.withAndroidAnimationPhase(nested + later))
        assertContentEquals(byteArrayOf(0x55, 0, 0, 0, 0), ViveInkCodec.withAndroidAnimationPhase(byteArrayOf()))
    }

    @Test
    fun malformedNativeProtobufsCannotBeEmittedAsValidInk() {
        listOf(byteArrayOf(0), byteArrayOf(0x0a, 5, 1), byteArrayOf(0x80.toByte()), byteArrayOf(0x0b))
            .forEach { assertFailsWith<IllegalArgumentException> { ViveInkCodec.withAndroidAnimationPhase(it) } }
        assertTrue(ViveInkCodec.hasValidInputData(ViveInkCodec.encodeInputs(MutableStrokeInputBatch())))
    }

    private fun fixture(name: String): ByteArray = requireNotNull(javaClass.getResource(
        "/ink/androidx-ink-1.1.0-alpha06/encoding/$name")) { "Missing Android encoding fixture: $name" }.readBytes()
}
