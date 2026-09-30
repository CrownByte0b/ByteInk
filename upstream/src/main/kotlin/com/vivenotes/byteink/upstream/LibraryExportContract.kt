package com.vivenotes.byteink.upstream

import java.io.File

/** Exact upstream exports, with explicitly declared ByteInk-owned JNI extensions when shipping. */
internal object LibraryExportContract {
    fun read(upstream: File, extensions: File? = null): Set<String> {
        val original = SymbolList.read(upstream)
        if (extensions == null) return original
        val owned = SymbolList.read(extensions)
        if (owned.isEmpty()) throw UsageError("An explicit native extension contract must not be empty")
        val invalid = owned.filterNot { it.startsWith("Java_com_vivenotes_byteink_") }
        if (invalid.isNotEmpty()) throw UsageError("Native extensions must be ByteInk-owned Java_* symbols: ${invalid.joinToString()}")
        val duplicate = original intersect owned
        if (duplicate.isNotEmpty()) throw UsageError("Native extensions overlap the upstream JNI contract: ${duplicate.joinToString()}")
        return original + owned
    }

    fun read(arguments: Arguments): Set<String> = read(File(arguments.required("--exports")),
        arguments.optional("--extensions")?.let(::File))
}
