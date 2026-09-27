package com.vivenotes.byteink.oracle

import kotlin.math.abs
import kotlin.math.max

/**
 * How a candidate library's dump differs from the reference's, value by value.
 *
 * Floats (`f32` values) pass within [Tolerance]; the largest gap per kind of value is reported.
 * Bit-level forms whose decoded content is compared elsewhere are only informational: packed mesh
 * buffers (their positions and unpacking are compared as floats) and input encodings (their proto
 * without field 10, `inputs.proto.public`, must match exactly). Everything else must be identical.
 */
class Comparison(val reference: Dump.Contents, val candidate: Dump.Contents) {

    enum class Verdict { EQUAL, WITHIN_TOLERANCE, INFORMATIONAL, MISMATCH }

    class Outcome(val key: String, val verdict: Verdict, val gap: Double)

    val sameLibrary: Boolean = reference.header["library"] == candidate.header["library"]

    val outcomes: List<Outcome> = (reference.values.keys + candidate.values.keys).sorted().map(::judge)

    val mismatches: List<Outcome> = outcomes.filter { it.verdict == Verdict.MISMATCH }

    val passed: Boolean = mismatches.isEmpty() && !sameLibrary

    private fun judge(key: String): Outcome {
        val a = reference.values[key]
        val b = candidate.values[key]
        if (a == null || b == null) return Outcome(key, Verdict.MISMATCH, Double.NaN)
        if (a == b) return Outcome(key, Verdict.EQUAL, 0.0)
        val name = key.substringAfter('\t')
        if (name == "inputs.encoded" || name == "inputs.proto" || (name.endsWith(".vertex-buffer") && !a.startsWith("f32 "))) {
            return Outcome(key, Verdict.INFORMATIONAL, Double.NaN)
        }
        val x = Floats.parse(a)
        val y = Floats.parse(b)
        if (x == null || y == null || x.size != y.size) return Outcome(key, Verdict.MISMATCH, Double.NaN)
        var gap = 0.0
        for (index in x.indices) {
            if (x[index].isNaN() && y[index].isNaN()) continue
            if (!Tolerance.accepts(x[index], y[index])) return Outcome(key, Verdict.MISMATCH, abs(x[index].toDouble() - y[index]))
            gap = max(gap, abs(x[index].toDouble() - y[index]))
        }
        return Outcome(key, Verdict.WITHIN_TOLERANCE, gap)
    }

    /** The kind of value a key holds: its key with indices replaced, `dry.g0.m1.vertices` → `dry.g#.m#.vertices`. */
    private fun kind(key: String): String = key.substringAfter('\t').replace(Regex("\\d+"), "#")

    fun markdown(limit: Int = 40): String = buildString {
        append("# Differential oracle\n\n")
        append("| | Reference | Candidate |\n|---|---|---|\n")
        append("| Label | ${reference.header["label"]} | ${candidate.header["label"]} |\n")
        append("| Library sha256 | `${reference.header["library"]}` | `${candidate.header["library"]}` |\n")
        append("| Values | ${reference.values.size} | ${candidate.values.size} |\n\n")
        if (sameLibrary) append("**Both dumps come from the same library; this comparison proves nothing.**\n\n")
        val counts = outcomes.groupingBy { it.verdict }.eachCount()
        append("${outcomes.size} values: ")
        append(Verdict.entries.joinToString(", ") { "${counts[it] ?: 0} ${it.name.lowercase().replace('_', ' ')}" })
        append(". ${Tolerance.DESCRIPTION}\n")
        append(if (passed) "\n**Pass.**\n" else "\n**Fail.**\n")

        val notEqual = outcomes.filter { it.verdict != Verdict.EQUAL }
        if (notEqual.isEmpty()) return@buildString
        append("\n## Values that are not identical, by kind\n\n")
        append("| Kind | Within tolerance | Largest gap | Informational | Mismatched | Of |\n|---|---:|---:|---:|---:|---:|\n")
        val totals = outcomes.groupingBy { kind(it.key) }.eachCount()
        notEqual.groupBy { kind(it.key) }.entries.sortedByDescending { it.value.size }.forEach { (kind, group) ->
            val within = group.filter { it.verdict == Verdict.WITHIN_TOLERANCE }
            append("| `$kind` | ${within.size} | ${within.maxOfOrNull { it.gap }?.let { "%.3g".format(it) } ?: "—"} | ")
            append("${group.count { it.verdict == Verdict.INFORMATIONAL }} | ${group.count { it.verdict == Verdict.MISMATCH }} | ${totals[kind]} |\n")
        }
        if (mismatches.isNotEmpty()) {
            append("\n## Mismatches\n\n| Case | Key | Reference | Candidate |\n|---|---|---|---|\n")
            mismatches.take(limit).forEach { outcome ->
                val key = outcome.key
                append("| `${key.substringBefore('\t')}` | `${key.substringAfter('\t')}` | ")
                append("`${reference.values[key]?.take(80) ?: "—"}` | `${candidate.values[key]?.take(80) ?: "—"}` |\n")
            }
            if (mismatches.size > limit) append("\n…and ${mismatches.size - limit} more.\n")
        }
    }
}

/**
 * How far apart two builds' floats may be: rounding differences between compilers, far below
 * anything visible, pass; different geometry does not.
 */
object Tolerance {
    private const val ABSOLUTE = 1e-4
    private const val RELATIVE = 1e-5

    const val DESCRIPTION = "Floats pass when |a − b| ≤ 1e-4 + 1e-5 · max(|a|, |b|)."

    fun accepts(a: Float, b: Float): Boolean {
        if (a == b) return true
        if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) return false
        return abs(a.toDouble() - b) <= ABSOLUTE + RELATIVE * max(abs(a.toDouble()), abs(b.toDouble()))
    }
}
