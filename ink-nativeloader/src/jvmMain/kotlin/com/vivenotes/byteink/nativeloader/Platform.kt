package com.vivenotes.byteink.nativeloader

import java.util.Locale

/**
 * A platform byteink bundles the Ink native library for, and that library's file name there.
 *
 * Ids follow Google's own `ink-nativeloader-jvm` (`linux-x86_64`, `macos-arm64`) and Skiko's
 * `linux-arm64`/`windows-arm64`.
 */
internal enum class Platform(val id: String, val fileName: String, val displayName: String, val windows: Boolean) {
    LINUX_X86_64("linux-x86_64", "libink.so", "Linux x86_64", windows = false),
    LINUX_ARM64("linux-arm64", "libink.so", "Linux arm64", windows = false),
    WINDOWS_X86_64("windows-x86_64", "ink.dll", "Windows x86_64", windows = true),
    WINDOWS_ARM64("windows-arm64", "ink.dll", "Windows arm64", windows = true),
    ;

    companion object {
        /** Every bundled platform by name, for messages: "Linux x86_64, Linux arm64, … and Windows arm64". */
        val names: String =
            entries.dropLast(1).joinToString(", ") { it.displayName } + " and " + entries.last().displayName

        /** The platform of a JVM whose `os.name` and `os.arch` are these, or null if unsupported. */
        fun of(osName: String, osArch: String): Platform? {
            val os = osName.lowercase(Locale.ROOT)
            val arch = osArch.lowercase(Locale.ROOT)
            // HotSpot reports amd64 and aarch64; other JVMs have used x86_64 and arm64.
            val x8664 = arch in setOf("amd64", "x86_64")
            val arm64 = arch in setOf("aarch64", "arm64")
            return when {
                os.startsWith("linux") && x8664 -> LINUX_X86_64
                os.startsWith("linux") && arm64 -> LINUX_ARM64
                os.startsWith("windows") && x8664 -> WINDOWS_X86_64
                os.startsWith("windows") && arm64 -> WINDOWS_ARM64
                else -> null
            }
        }
    }
}
