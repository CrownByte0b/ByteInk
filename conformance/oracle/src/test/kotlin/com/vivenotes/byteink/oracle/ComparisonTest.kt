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
    fun anOutlineStartingElsewhereOnItsLoopIsEquivalentButNotOneRunBackwards() {
        val square = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val rotated = floatArrayOf(0f, 1f, 0f, 0f, 1f, 0f, 1f, 1f)
        val reversed = floatArrayOf(0f, 0f, 0f, 1f, 1f, 1f, 1f, 0f)
        val segment = floatArrayOf(5f, 5f, 6f, 5f)
        val comparison = Comparison(
            dump(
                "library" to "a",
                "rotated\tdry.g0.outline-sizes" to "4 2",
                "rotated\tdry.g0.outline-positions" to Floats.of(*square, *segment).toString(),
                "reversed\tdry.g0.outline-sizes" to "4",
                "reversed\tdry.g0.outline-positions" to Floats.of(*square).toString(),
            ),
            dump(
                "library" to "b",
                "rotated\tdry.g0.outline-sizes" to "4 2",
                "rotated\tdry.g0.outline-positions" to Floats.of(*rotated, *segment).toString(),
                "reversed\tdry.g0.outline-sizes" to "4",
                "reversed\tdry.g0.outline-positions" to Floats.of(*reversed).toString(),
            ),
        )

        val verdicts = comparison.outcomes.associate { it.key to it.verdict }
        assertEquals(Comparison.Verdict.EQUIVALENT, verdicts["rotated\tdry.g0.outline-positions"])
        assertEquals(Comparison.Verdict.MISMATCH, verdicts["reversed\tdry.g0.outline-positions"])
    }

    @Test
    fun acrossPlatformsOnlyDerivativesMayGoBeyondTolerance() {
        fun comparison(candidatePlatform: String) = Comparison(
            dump(
                "library" to "a",
                "platform" to "linux-x86_64",
                "case\tlive.step0.c0.p0.derivatives" to Floats.of(-0.95f).toString(),
                "case\tdry.g0.m0.derivative-unpacking" to Floats.of(-0.95f, 0.0007f).toString(),
                "case\tlive.step0.c0.p0.vertex-buffer" to Floats.of(1f).toString(),
            ),
            dump(
                "library" to "b",
                "platform" to candidatePlatform,
                "case\tlive.step0.c0.p0.derivatives" to Floats.of(-0.17f).toString(),
                "case\tdry.g0.m0.derivative-unpacking" to Floats.of(-0.17f, 0.0005f).toString(),
                "case\tlive.step0.c0.p0.vertex-buffer" to Floats.of(1.1f).toString(),
            ),
        )

        val across = comparison("windows-x86_64")
        assertTrue(across.crossPlatform)
        assertEquals(listOf("case\tlive.step0.c0.p0.vertex-buffer"), across.mismatches.map { it.key })
        val same = comparison("linux-x86_64")
        assertFalse(same.crossPlatform)
        assertEquals(3, same.mismatches.size)
    }

    @Test
    fun differentAngleMathKeepsGeometryStrictAndReportsDerivativeGaps() {
        val values = listOf(
            "case\tlive.step0.c0.p0.derivatives" to Floats.of(0.5f).toString(),
            "case\tdry.bounds" to Floats.of(1f).toString(),
        )
        val a = dump("library" to "a", "platform" to "linux-x86_64", *values.toTypedArray())
        val b = dump("library" to "b", "platform" to "linux-x86_64", "angle-math" to "android-bionic-test",
            "case\tlive.step0.c0.p0.derivatives" to Floats.of(0.6f).toString(),
            "case\tdry.bounds" to Floats.of(1.01f).toString())
        val comparison = Comparison(a, b)
        assertFalse(comparison.crossPlatform)
        assertTrue(comparison.differentMath)
        assertEquals(listOf("case\tdry.bounds"), comparison.mismatches.map { it.key })
        assertTrue(comparison.markdown().contains("0.100"))
        val sameMath = Comparison(a, dump("library" to "b", "platform" to "linux-x86_64",
            "case\tlive.step0.c0.p0.derivatives" to Floats.of(0.6f).toString(), *values.drop(1).toTypedArray()))
        assertFalse(sameMath.differentMath)
        assertEquals(1, sameMath.mismatches.size)
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
