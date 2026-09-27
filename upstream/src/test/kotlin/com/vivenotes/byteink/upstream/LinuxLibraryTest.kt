package com.vivenotes.byteink.upstream

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LinuxLibraryTest {

    private val surface = setOf("Java_a_B_create", "Java_a_B_free")

    @Test
    fun aGlibcOnlyLibraryWithTheSurfaceIsShippable() {
        val library = ElfFile(
            TestElf(
                symbols = surface.map { TestElf.Symbol(it) } + TestElf.Symbol("internal_helper"),
                needed = listOf("libm.so.6", "libpthread.so.0", "libc.so.6", "ld-linux-x86-64.so.2"),
                versions = mapOf("libc.so.6" to listOf("GLIBC_2.2.5", "GLIBC_2.26"), "libm.so.6" to listOf("GLIBC_2.2.5")),
            ).bytes(),
        )

        assertEquals(emptyList(), LinuxLibrary.problems(library, surface, maxGlibc = "2.26"))
        assertEquals("2.26", LinuxLibrary.newestGlibc(library))
    }

    @Test
    fun namesEveryReasonALibraryIsNotShippable() {
        val library = ElfFile(
            TestElf(
                symbols = listOf(TestElf.Symbol("Java_a_B_create"), TestElf.Symbol("Java_a_B_extra")),
                needed = listOf("libstdc++.so.6", "libc.so.6"),
                versions = mapOf(
                    "libc.so.6" to listOf("GLIBC_2.34"),
                    "libstdc++.so.6" to listOf("GLIBCXX_3.4.30"),
                ),
            ).bytes(),
        )

        val problems = LinuxLibrary.problems(library, surface, maxGlibc = "2.26")

        assertEquals(4, problems.size, problems.joinToString("\n"))
        assertTrue(problems[0].contains("missing (1):\n    Java_a_B_free") && problems[0].contains("unexpected (1):\n    Java_a_B_extra"))
        assertEquals("It needs libraries beyond glibc: libstdc++.so.6", problems[1])
        assertEquals("It requires GLIBC_2.34 from libc.so.6, newer than GLIBC_2.26", problems[2])
        assertEquals("It requires GLIBCXX_3.4.30 from libstdc++.so.6", problems[3])
    }

    @Test
    fun comparesGlibcVersionsNumerically() {
        assertTrue(LinuxLibrary.compareVersions("2.2.5", "2.26") < 0)
        assertTrue(LinuxLibrary.compareVersions("2.3.4", "2.26") < 0)
        assertTrue(LinuxLibrary.compareVersions("2.34", "2.26") > 0)
        assertEquals(0, LinuxLibrary.compareVersions("2.26", "2.26.0"))
    }

    @Test
    fun googlesOwnBinaryMeetsTheBarItSets() {
        val loader = System.getProperty("byteink.upstream.inkJars").split(File.pathSeparator).map(::File)
            .single { it.name.startsWith("ink-nativeloader-jvm") }
        val library = ElfFile(ZipFile(loader).use { zip ->
            zip.getInputStream(zip.getEntry(Surface.LINUX_BINARY)).use { it.readBytes() }
        })
        val exports = library.exportedFunctions.filterTo(sortedSetOf()) { it.startsWith("Java_") }

        assertEquals(emptyList(), LinuxLibrary.problems(library, exports, maxGlibc = "2.26"))
        assertEquals("2.26", LinuxLibrary.newestGlibc(library))
        assertEquals(
            LinuxLibrary.allowedLibraries,
            library.neededLibraries.toSet(),
            "Google's binary needs exactly the glibc family",
        )
    }
}
