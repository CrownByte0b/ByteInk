package com.vivenotes.byteink.oracle

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ComparisonTest {

    @Test
    fun strippingFieldTenLeavesTheOtherFieldsByteForByte() {
        // A one-input batch as Google's binary and as byteink's encode it (the oracle's dot case).
        val google = hex("0a0d0a0100150000803f1d00002041120d0a0100150000803f1d000020411a080a010015bd37863538014d000000005500000000")
        val public = hex("0a0d0a0100150000803f1d00002041120d0a0100150000803f1d000020411a080a010015bd37863538014d00000000")

        assertContentEquals(public, ProtoFields.without(google, field = 10))
        assertContentEquals(public, ProtoFields.without(public, field = 10))
        assertFailsWith<IllegalArgumentException> { ProtoFields.without(google.copyOf(google.size - 2), field = 10) }
    }

    @Test
    fun floatsRoundTripExactly() {
        val values = floatArrayOf(0f, -0f, 1.5f, -4.0333848f, Float.MIN_VALUE, Float.MAX_VALUE)
        assertContentEquals(values, Floats.parse(Floats.of(*values).toString()))
        assertEquals(null, Floats.parse("n=2 sha256=00"))
    }

    @Test
    fun judgesEachValueByWhatItHolds() {
        val comparison = Comparison(
            dump(
                "library" to "a",
                "case\tcount" to "3",
                "case\tdry.bounds" to Floats.of(0f, 1f).toString(),
                "case\tlive.bounds" to Floats.of(0f, 1f).toString(),
                "case\tinputs.proto" to "00",
                "case\tdry.g0.m0.vertex-buffer" to "n=4 sha256=aa",
                "case\ttopology" to "n=4 sha256=bb",
            ),
            dump(
                "library" to "b",
                "case\tcount" to "3",
                "case\tdry.bounds" to Floats.of(0f, 1.00001f).toString(), // rounding: within tolerance
                "case\tlive.bounds" to Floats.of(0f, 1.1f).toString(), // a real difference
                "case\tinputs.proto" to "01", // compared as inputs.proto.public instead
                "case\tdry.g0.m0.vertex-buffer" to "n=4 sha256=cc", // compared through its positions
                "case\ttopology" to "n=4 sha256=dd",
            ),
        )

        val verdicts = comparison.outcomes.associate { it.key.substringAfter('\t') to it.verdict }
        assertEquals(Comparison.Verdict.EQUAL, verdicts["count"])
        assertEquals(Comparison.Verdict.WITHIN_TOLERANCE, verdicts["dry.bounds"])
        assertEquals(Comparison.Verdict.MISMATCH, verdicts["live.bounds"])
        assertEquals(Comparison.Verdict.INFORMATIONAL, verdicts["inputs.proto"])
        assertEquals(Comparison.Verdict.INFORMATIONAL, verdicts["dry.g0.m0.vertex-buffer"])
        assertEquals(Comparison.Verdict.MISMATCH, verdicts["topology"])
        assertFalse(comparison.passed)
    }

    @Test
    fun aLibraryComparedWithItselfProvesNothing() {
        val same = dump("library" to "a", "case\tcount" to "3")
        val comparison = Comparison(same, same)

        assertTrue(comparison.mismatches.isEmpty())
        assertFalse(comparison.passed)
    }

    @Test
    fun missingValuesAreMismatches() {
        val comparison = Comparison(
            dump("library" to "a", "case\tcount" to "3", "case\textra" to "1"),
            dump("library" to "b", "case\tcount" to "3"),
        )

        assertEquals(listOf("case\textra"), comparison.mismatches.map { it.key })
    }

    @Test
    fun toleranceAdmitsRoundingAndNothingVisible() {
        assertTrue(Tolerance.accepts(1f, 1f + 5e-5f))
        assertTrue(Tolerance.accepts(1000f, 1000.01f)) // relative: 1e-4 + 1e-5 · 1000
        assertFalse(Tolerance.accepts(1f, 1.001f))
        assertFalse(Tolerance.accepts(Float.NaN, 1f))
    }

    private fun dump(vararg entries: Pair<String, String>): Dump.Contents = Dump.Contents(
        header = entries.filter { '\t' !in it.first }.toMap(),
        values = entries.filter { '\t' in it.first }.toMap(),
    )

    private fun hex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
}
