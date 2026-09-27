package com.vivenotes.byteink.upstream

import java.io.File
import java.util.SortedSet

/** A sorted, one-per-line list of symbols under a `#` comment header, as kept in upstream/jni/. */
internal object SymbolList {

    fun read(file: File): SortedSet<String> = parse(file.readText())

    fun parse(text: String): SortedSet<String> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toSortedSet()

    fun render(header: List<String>, symbols: Collection<String>): String = buildString {
        header.forEach { append("# ").append(it).append('\n') }
        symbols.sorted().forEach { append(it).append('\n') }
    }

    /** A short description of how [actual] differs from [expected], or null when they are equal. */
    fun difference(expected: Set<String>, actual: Set<String>, limit: Int = 20): String? {
        val missing = (expected - actual).sorted()
        val extra = (actual - expected).sorted()
        if (missing.isEmpty() && extra.isEmpty()) return null
        return buildString {
            fun section(title: String, names: List<String>) {
                if (names.isEmpty()) return
                append("  $title (${names.size}):\n")
                names.take(limit).forEach { append("    ").append(it).append('\n') }
                if (names.size > limit) append("    …\n")
            }
            section("missing", missing)
            section("unexpected", extra)
        }
    }
}
