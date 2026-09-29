package com.vivenotes.byteink.vive

import androidx.ink.strokes.StrokeInput
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * byteink's reading of point blobs, held to what AndroidX Ink 1.1.0-alpha06 itself did with them on
 * Google's own native library (see `resources/ink/androidx-ink-1.1.0-alpha06/README.md`).
 */
class AndroidBlobsTest {

    @Test
    fun blobsAndroidWroteDecodeToWhatItReadBack() {
        GOLDEN.forEach { name ->
            val decoded = ViveInkCodec.decodeInputs(resource("$name.bin").readBytes())
            val expected = resource("$name.txt").readText().trim().lines()
            val header = expected.first().split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
            val inputs = expected.drop(1).map { line -> line.split(' ') }

            assertEquals(inputs.size, decoded.size, name)
            if (decoded.size > 0) assertEquals(header.getValue("tool"), decoded.getToolType().toString(), name)
            assertEquals(header.getValue("sul").toFloat(), decoded.getStrokeUnitLengthCm(), name)
            assertEquals(header.getValue("hasP").toBoolean(), decoded.hasPressure(), name)
            assertEquals(header.getValue("hasT").toBoolean(), decoded.hasTilt(), name)
            assertEquals(header.getValue("hasO").toBoolean(), decoded.hasOrientation(), name)
            val input = StrokeInput()
            inputs.forEachIndexed { index, values ->
                decoded.populate(index, input)
                assertEquals(values[0].toFloat(), input.x, "$name x[$index]")
                assertEquals(values[1].toFloat(), input.y, "$name y[$index]")
                assertEquals(values[2].toLong(), input.elapsedTimeMillis, "$name t[$index]")
                assertEquals(values[3].toFloat(), input.pressure, "$name pressure[$index]")
                assertEquals(values[4].toFloat(), input.tiltRadians, "$name tilt[$index]")
                assertEquals(values[5].toFloat(), input.orientationRadians, "$name orientation[$index]")
            }
        }
    }

    @Test
    fun boundaryCasesGetTheLibrarysVerdict() {
        val cases = verdicts("edges.txt")
        assertTrue(cases.size > 30)
        cases.forEach { (accepted, proto, name) ->
            assertEquals(accepted, ViveInkCodec.hasValidInputData(gzip(proto)), name)
        }
    }

    @Test
    fun craftedAndDamagedMessagesGetTheLibrarysVerdict() {
        val cases = verdicts("verdicts.txt")
        assertTrue(cases.count { it.first } > 50 && cases.count { !it.first } > 50, "the sample covers both verdicts")
        cases.forEachIndexed { line, (accepted, proto, _) ->
            assertEquals(accepted, ViveInkCodec.hasValidInputData(gzip(proto)), "verdicts.txt line ${line + 1}")
        }
    }

    @Test
    fun anInputRepeatingThePreviousPositionAndTimeIsDroppedNotRefused() {
        val proto = verdicts("edges.txt").single { it.third == "duplicate input dropped" }.second

        val decoded = ViveInkCodec.decodeInputs(gzip(proto))

        assertEquals(listOf(1f, 3f), (0 until decoded.size).map { decoded[it].x })
    }

    @Test
    fun whatIsNotGzipOrExpandsTooFarIsRefused() {
        assertFalse(ViveInkCodec.hasValidInputData(byteArrayOf(1, 2, 3)))
        assertFalse(ViveInkCodec.hasValidInputData(ByteArray(0)))
        // Zeros compress to almost nothing; AndroidX would try to hold all of them.
        val bomb = gzip(ByteArray(ViveInkCodec.MAX_DECOMPRESSED_BYTES + 1))
        assertTrue(bomb.size < 1024 * 1024)
        assertFalse(ViveInkCodec.hasValidInputData(bomb))
    }

    @Test
    fun aMovePathIsReadExactlyAsAndroidWritesIt() {
        val path = listOf(InkPoint(5f, 5f), InkPoint(35f, 5f), InkPoint(35f, 45f))

        assertEquals(path, ViveInkCodec.decodeMove(move(path.size, path.flatMap { listOf(it.x, it.y) })))
    }

    @Test
    fun aMovePathThatIsShortDamagedOrOtherwiseEncodedIsNotRead() {
        assertNull(ViveInkCodec.decodeMove(move(2, listOf(0f, 0f, 1f, 1f))), "fewer than three points")
        assertNull(ViveInkCodec.decodeMove(move(3, listOf(0f, 0f, 1f, 1f, 2f))), "count and data disagree")
        assertNull(ViveInkCodec.decodeMove(move(3, listOf(0f, 0f, 1f, Float.NaN, 2f, 2f))), "non-finite point")
        assertNull(ViveInkCodec.decodeMove(move(3, listOf(0f, 0f, 1f, 1f, 2f, 2f)).copy(enc = ViveInkCodec.ENCODING)))
    }

    private fun move(count: Int, values: List<Float>) = StoredInkMove(
        id = "move",
        pageId = "page",
        dxDp = 1f,
        dyDp = 2f,
        points = ByteBuffer.allocate(4 + values.size * 4).order(ByteOrder.LITTLE_ENDIAN).putInt(count)
            .apply { values.forEach(::putFloat) }.array(),
        enc = ViveInkCodec.MOVE_ENCODING,
        createdAt = 1L,
        targetIds = emptyList(),
    )

    private fun resource(name: String) = checkNotNull(javaClass.getResource("$FIXTURES/$name")) { name }

    /** Lines of `accept|reject <hex message> [name]`. */
    private fun verdicts(name: String): List<Triple<Boolean, ByteArray, String>> =
        resource(name).readText().lines().filter { it.isNotBlank() }.map { line ->
            val parts = line.split(' ', limit = 3)
            Triple(
                parts[0] == "accept",
                parts[1].chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
                parts.getOrElse(2) { "" },
            )
        }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    private companion object {
        const val FIXTURES = "/ink/androidx-ink-1.1.0-alpha06"
        val GOLDEN = listOf("two-point-unknown", "stylus-pressure-tilt-orientation", "touch-single", "mouse-long", "empty")
    }
}
