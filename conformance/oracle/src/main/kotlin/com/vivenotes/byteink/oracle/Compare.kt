package com.vivenotes.byteink.oracle

import kotlin.math.abs
import kotlin.math.max

/**
 * How a candidate library's dump differs from the reference's, value by value.
 *
 * Floats (`f32` values) pass within [Tolerance]; the largest gap per kind of value is reported.
 * Outlines are closed loops, so one that starts at another vertex of the same loop is equivalent.
 * Bit-level forms whose decoded content is compared elsewhere are only informational: packed mesh
 * buffers (their positions and unpacking are compared as floats) and gzip encodings (their protos
 * must match exactly; for inputs, without Google's internal field 10). Everything else must be
 * identical.
 *
 * Dumps from different platforms, angle- or magnitude-math profiles, or C math libraries use math
 * implementations that differ in last bits. That stays within tolerance except in antialiasing
 * derivatives: Ink averages them through atan2, sin and cos, and rounding can swing the average where
 * a vertex's triangles point in nearly opposite directions. Across math implementations, derivatives
 * beyond tolerance are informational.
 *
 * Google's binary carries its own float math (it imports only `pow` from libm), so even on the same
 * host its derivatives round differently from a build that calls the host's C library, by an amount
 * that depends on that library's version: glibc 2.39 swings one past tolerance, glibc 2.43 none.
 */
class Comparison(val reference: Dump.Contents, val candidate: Dump.Contents) {

    enum class Verdict { EQUAL, WITHIN_TOLERANCE, EQUIVALENT, INFORMATIONAL, MISMATCH }

    class Outcome(val key: String, val verdict: Verdict, val gap: Double)

    val sameLibrary: Boolean = reference.header["library"] == candidate.header["library"]

    /** Whether both dumps say where they ran, and it differs. */
    val crossPlatform: Boolean = run {
        val here = reference.header["platform"]
        val there = candidate.header["platform"]
        here != null && there != null && here != there
    }

    /** A scoped Android angle implementation or a bundled C math library also changes derivative rounding on the same OS. */
    val differentMath: Boolean = crossPlatform ||
        listOf("angle-math", "magnitude-math", "math-library").any {
            (reference.header[it] ?: "platform") != (candidate.header[it] ?: "platform")
        }

    /** A Google pin proof must compare the unmodified arithmetic baseline, never the shipped Android adaptations. */
    fun requirePlatformBaseline() {
        require(candidate.header["library-role"] == "validation-baseline" && reference.header["library-role"] == "google-reference") {
            "Strict Google validation requires a verified baseline and the pinned Google reference"
        }
        require(!crossPlatform && listOf(reference, candidate).all {
            it.header["angle-math"] == "platform" && it.header["magnitude-math"] == "platform"
        }) { "Strict Google validation requires the same platform arithmetic" }
        require(reference.header["google-ink-commit"] != null && reference.header["google-ink-commit"] != "unspecified" &&
            reference.header["google-ink-commit"] == candidate.header["google-ink-commit"]) { "Google baseline source pins differ" }
    }

    val outcomes: List<Outcome> = (reference.values.keys + candidate.values.keys).sorted().map(::judge)

    val mismatches: List<Outcome> = outcomes.filter { it.verdict == Verdict.MISMATCH }

    val passed: Boolean = mismatches.isEmpty() && !sameLibrary

    private fun judge(key: String): Outcome {
        val a = reference.values[key]
        val b = candidate.values[key]
        if (a == null || b == null) return Outcome(key, Verdict.MISMATCH, Double.NaN)
        if (a == b) return Outcome(key, Verdict.EQUAL, 0.0)
        val name = key.substringAfter('\t')
        if (name in COMPRESSED_OR_INTERNAL || (name.endsWith(".vertex-buffer") && !a.startsWith("f32 "))) {
            return Outcome(key, Verdict.INFORMATIONAL, Double.NaN)
        }
        val x = Floats.parse(a)
        val y = Floats.parse(b)
        if (x == null || y == null || x.size != y.size) return Outcome(key, Verdict.MISMATCH, Double.NaN)
        var gap = 0.0
        var beyond = false
        for (index in x.indices) {
            if (x[index].isNaN() && y[index].isNaN()) continue
            if (!Tolerance.accepts(x[index], y[index])) beyond = true
            gap = max(gap, abs(x[index].toDouble() - y[index]))
        }
        if (!beyond) return Outcome(key, Verdict.WITHIN_TOLERANCE, gap)
        if (name.endsWith(".outline-positions")) rotatedGap(key, x, y)?.let { return Outcome(key, Verdict.EQUIVALENT, it) }
        if (differentMath && (name.endsWith(".derivatives") || name.endsWith(".derivative-unpacking"))) {
            return Outcome(key, Verdict.INFORMATIONAL, gap)
        }
        return Outcome(key, Verdict.MISMATCH, gap)
    }

    /**
     * The largest gap between two groups of outlines when each of the candidate's may start at
     * another vertex of its loop, or null when one matches no rotation of the reference's.
     */
    private fun rotatedGap(key: String, x: FloatArray, y: FloatArray): Double? {
        val sizesKey = key.removeSuffix("outline-positions") + "outline-sizes"
        val sizes = reference.values[sizesKey]
        if (sizes == null || sizes != candidate.values[sizesKey]) return null
        val counts = if (sizes == "none") emptyList() else sizes.split(' ').map { it.toIntOrNull() ?: return null }
        if (2 * counts.sum() != x.size) return null
        var start = 0
        var largest = 0.0
        for (count in counts) {
            val best = (0 until count).mapNotNull { shift -> outlineGap(x, y, start, count, shift) }.minOrNull() ?: return null
            largest = max(largest, best)
            start += count
        }
        return largest
    }

    /**
     * The largest gap between the reference's outline of [count] vertices from vertex [start] and
     * the candidate's read from [shift] vertices later, or null if any is beyond tolerance.
     */
    private fun outlineGap(x: FloatArray, y: FloatArray, start: Int, count: Int, shift: Int): Double? {
        var largest = 0.0
        for (vertex in 0 until count) {
            val shifted = start + (vertex + shift) % count
            for (axis in 0..1) {
                val a = x[2 * (start + vertex) + axis]
                val b = y[2 * shifted + axis]
                if (!Tolerance.accepts(a, b)) return null
                largest = max(largest, abs(a.toDouble() - b))
            }
        }
        return largest
    }

    private companion object {
        /**
         * Compared through something else: gzip output depends on the JVM's zlib, so encodings are
         * compared as their protobufs (`family.proto`, and `inputs.proto.public`, which also leaves
         * out Google's internal field 10).
         */
        val COMPRESSED_OR_INTERNAL = setOf("inputs.encoded", "inputs.proto", "family.encoded")
    }

    /** The kind of value a key holds: its key with indices replaced, `dry.g0.m1.vertices` → `dry.g#.m#.vertices`. */
    private fun kind(key: String): String = key.substringAfter('\t').replace(Regex("\\d+"), "#")

    fun markdown(limit: Int = 40): String = buildString {
        append("# Differential oracle\n\n")
        append("| | Reference | Candidate |\n|---|---|---|\n")
        append("| Label | ${reference.header["label"]} | ${candidate.header["label"]} |\n")
        append("| Library sha256 | `${reference.header["library"]}` | `${candidate.header["library"]}` |\n")
        append("| Platform | ${reference.header["platform"] ?: "—"} | ${candidate.header["platform"] ?: "—"} |\n")
        append("| Angle math | ${reference.header["angle-math"] ?: "platform"} | ${candidate.header["angle-math"] ?: "platform"} |\n")
        append("| Magnitude math | ${reference.header["magnitude-math"] ?: "platform"} | ${candidate.header["magnitude-math"] ?: "platform"} |\n")
        append("| Math library | ${reference.header["math-library"] ?: "platform"} | ${candidate.header["math-library"] ?: "platform"} |\n")
        append("| Library role | ${reference.header["library-role"] ?: "unspecified"} | ${candidate.header["library-role"] ?: "unspecified"} |\n")
        append("| Source pin | ${reference.header["google-ink-commit"] ?: "unspecified"} | ${candidate.header["google-ink-commit"] ?: "unspecified"} |\n")
        append("| Values | ${reference.values.size} | ${candidate.values.size} |\n\n")
        if (sameLibrary) append("**Both dumps come from the same library; this comparison proves nothing.**\n\n")
        if (differentMath) {
            append("The dumps use different math implementations, whose rounding differs in last bits, ")
            append("so antialiasing derivatives beyond tolerance are informational.\n\n")
        }
        val counts = outcomes.groupingBy { it.verdict }.eachCount()
        append("${outcomes.size} values: ")
        append(Verdict.entries.joinToString(", ") { "${counts[it] ?: 0} ${it.name.lowercase().replace('_', ' ')}" })
        append(". ${Tolerance.DESCRIPTION}\n")
        append(if (passed) "\n**Pass.**\n" else "\n**Fail.**\n")

        val notEqual = outcomes.filter { it.verdict != Verdict.EQUAL }
        if (notEqual.isEmpty()) return@buildString
        append("\n## Values that are not identical, by kind\n\n")
        append("The largest gap is over the kind's floats that are not mismatched.\n\n")
        append("| Kind | Within tolerance | Equivalent | Informational | Largest gap | Mismatched | Of |\n")
        append("|---|---:|---:|---:|---:|---:|---:|\n")
        val totals = outcomes.groupingBy { kind(it.key) }.eachCount()
        notEqual.groupBy { kind(it.key) }.entries.sortedByDescending { it.value.size }.forEach { (kind, group) ->
            val largest = group.filter { it.verdict != Verdict.MISMATCH && !it.gap.isNaN() }.maxOfOrNull { it.gap }
            append("| `$kind` | ${group.count { it.verdict == Verdict.WITHIN_TOLERANCE }} | ")
            append("${group.count { it.verdict == Verdict.EQUIVALENT }} | ${group.count { it.verdict == Verdict.INFORMATIONAL }} | ")
            append("${largest?.let { "%.3g".format(it) } ?: "—"} | ${group.count { it.verdict == Verdict.MISMATCH }} | ${totals[kind]} |\n")
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
