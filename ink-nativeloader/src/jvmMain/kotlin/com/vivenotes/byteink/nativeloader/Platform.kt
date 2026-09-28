package com.vivenotes.byteink.nativeloader

import java.util.Locale

/** A platform byteink bundles the Ink native library for, and that library's file name there. */
internal enum class Platform(val id: String, val fileName: String) {
    LINUX_X86_64("linux-x86_64", "libink.so"),
    WINDOWS_X86_64("windows-x86_64", "ink.dll"),
    ;

    companion object {
        /** The platform of a JVM whose `os.name` and `os.arch` are these, or null if unsupported. */
        fun of(osName: String, osArch: String): Platform? {
            val os = osName.lowercase(Locale.ROOT)
            val x8664 = osArch.lowercase(Locale.ROOT) in setOf("amd64", "x86_64")
            return when {
                os.startsWith("linux") && x8664 -> LINUX_X86_64
                os.startsWith("windows") && x8664 -> WINDOWS_X86_64
                else -> null
            }
        }
    }
}
