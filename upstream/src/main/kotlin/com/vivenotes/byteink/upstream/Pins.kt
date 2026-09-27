package com.vivenotes.byteink.upstream

import java.io.File
import java.util.Properties

/** upstream/pins.properties, validated. */
internal data class Pins(
    val supportCommit: String,
    val supportRepository: String,
    val googleInkRepository: String,
    val googleInkCommit: String,
    val googleInkCandidates: List<String>,
    val bazelVersion: String,
    val llvmVersion: String,
) {
    companion object {
        private val commitId = Regex("[0-9a-f]{40}")

        fun read(file: File): Pins {
            val properties = Properties().apply { file.reader().use(::load) }
            fun value(key: String): String = properties.getProperty(key)?.trim()
                ?.takeIf(String::isNotEmpty)
                ?: throw IllegalArgumentException("$file: $key is missing")
            fun commit(key: String, value: String = value(key)): String =
                value.also { require(commitId.matches(it)) { "$file: $key is not a full commit id: $it" } }

            val pinned = commit("google.ink.commit")
            val candidates = value("google.ink.candidates").split(',').map(String::trim)
                .filter(String::isNotEmpty).map { commit("google.ink.candidates", it) }
            require(pinned in candidates) { "$file: google.ink.commit is not one of google.ink.candidates" }
            return Pins(
                supportCommit = commit("androidx.support.commit"),
                supportRepository = value("androidx.support.repository"),
                googleInkRepository = value("google.ink.repository"),
                googleInkCommit = pinned,
                googleInkCandidates = candidates,
                bazelVersion = value("bazel.version"),
                llvmVersion = value("llvm.version"),
            )
        }
    }
}
