package com.vivenotes.byteink.conformance

import androidx.ink.brush.StockBrushes
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Assume.assumeTrue

/**
 * Which libink.so the suites in this run exercised: the build names one, and the process's memory
 * map shows which was loaded. The upstream loader copies the library to a temporary file first, so
 * the two are compared by content.
 */
class LoadedLibraryTest {

    @Test
    fun theSuitesRunAgainstTheLibraryTheBuildNamed() {
        val expected = assertNotNull(System.getProperty("byteink.expectedLibinkSha256"), "The build named no library")
        val maps = File("/proc/self/maps")
        assumeTrue("Only Linux shows its memory map this way", maps.isFile)

        StockBrushes.marker(StockBrushes.MarkerVersion.V1) // loads the library, if nothing has yet
        val loaded = maps.readLines()
            .mapNotNull { line -> line.indexOf('/').takeIf { it >= 0 }?.let { File(line.substring(it)) } }
            .filter { "libink" in it.name }
            .distinct()

        assertEquals(1, loaded.size, "libink is mapped from $loaded")
        assertEquals(expected, sha256(loaded.single()))
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }
}
