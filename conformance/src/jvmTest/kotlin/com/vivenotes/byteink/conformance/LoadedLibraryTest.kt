package com.vivenotes.byteink.conformance

import androidx.ink.brush.StockBrushes
import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Which Ink native library the suites in this run exercised: the build names one by its SHA-256,
 * byteink's loader says which it loaded, and on Linux the process's memory map confirms it.
 */
class LoadedLibraryTest {

    @Test
    fun theSuitesRunAgainstTheLibraryTheBuildNamed() {
        val expected = assertNotNull(System.getProperty("byteink.test.expectedInkLibrarySha256"), "The build named no library")

        StockBrushes.marker(StockBrushes.MarkerVersion.V1) // loads the library, if nothing has yet
        val loaded = assertNotNull(InkNativeLibrary.loaded, "byteink's loader loaded nothing: is Google's NativeLoader on the classpath?")

        assertEquals(expected, loaded.sha256)
        assertEquals(expected, sha256(loaded.path.toFile()))
        val maps = File("/proc/self/maps")
        if (maps.isFile) {
            val mapped = maps.readLines()
                .mapNotNull { line -> line.indexOf('/').takeIf { it >= 0 }?.let { File(line.substring(it)) } }
                .filter { it.name == loaded.path.fileName.toString() }
                .toSet()
            assertEquals(setOf(loaded.path.toFile().canonicalFile), mapped.mapTo(mutableSetOf()) { it.canonicalFile })
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
