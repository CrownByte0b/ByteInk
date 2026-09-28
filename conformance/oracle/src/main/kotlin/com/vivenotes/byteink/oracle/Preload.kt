@file:OptIn(InkInternalOnlyApi::class)

package com.vivenotes.byteink.oracle

import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.nativeloader.NativeLoader
import java.io.File

/**
 * Runs the oracle on a native library upstream's JVM loader will not load — it knows only Linux and
 * macOS — such as a Windows `ink.dll`: loads the library itself, then marks upstream's loader done.
 * Only for trying out byteink's own builds before its own loader replaces upstream's.
 *
 * Usage: Preload <library> <oracle arguments...>
 */
object Preload {
    const val LIBRARY_PROPERTY = "byteink.oracle.library"

    @JvmStatic
    fun main(args: Array<String>) {
        val library = File(args.first()).absoluteFile
        System.load(library.path)
        System.setProperty(LIBRARY_PROPERTY, library.path)
        NativeLoader::class.java.getDeclaredField("loaded").apply { isAccessible = true }.setBoolean(null, true)
        oracle(args.drop(1).toTypedArray())
    }
}
