package com.vivenotes.byteink.nativeloader

import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.nativeloader.StatusNative
import java.io.File
import java.nio.file.Files
import java.nio.charset.Charset
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Actual separate JVMs exercise extraction races and Windows' loaded-DLL locks. */
class NativeLibraryProcessesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun concurrentProcessesLoadOneLibraryFromSpacesAndUnicodePaths() {
        // Standard OpenJDK loads Windows DLLs through the native code page. Exercise non-ASCII
        // names this JVM can represent; JBR's UTF-8 loader is exercised separately below.
        val nativeEncoding = Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"))
        val name = listOf("José", "日本語", "Ж", "Ω").firstOrNull { nativeEncoding.newEncoder().canEncode(it) } ?: "user"
        val cache = temporary.newFolder("user cache $name").resolve("natives")
        val gate = File(temporary.root, "start")
        val probes = (1..4).map { probe(cache, gate, "race$it") }
        try {
            gate.writeText("start")
            val libraries = probes.map(::awaitLoaded)
            assertEquals(1, libraries.toSet().size)
            val library = File(libraries.first().lines()[1])
            assertTrue(library.toPath().startsWith(cache.toPath()))
            assertEquals(listOf(library.name), library.parentFile.list()!!.toList())
            probes.forEach(::finish)
        } finally {
            probes.forEach { it.process.destroyForcibly() }
        }
    }

    @Test
    fun jbrLoadsUnicodePathsOutsideTheWindowsNativeCodePage() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        assumeTrue(System.getProperty("java.vendor").contains("JetBrains"))
        val cache = temporary.newFolder("user cache José 日本語").resolve("natives")
        val gate = File(temporary.root, "unicode-start").apply { writeText("start") }
        val probe = probe(cache, gate, "unicode")
        try {
            val loaded = File(awaitLoaded(probe).lines()[1])
            assertTrue(loaded.toPath().startsWith(cache.toPath()))
            finish(probe)
        } finally {
            probe.process.destroyForcibly()
        }
    }

    @Test
    fun warmCacheIsReusedWhileAnotherProcessHoldsTheLibrary() {
        val cache = temporary.newFolder("warm cache").resolve("natives")
        val gate = File(temporary.root, "start").apply { writeText("start") }
        val first = probe(cache, gate, "first")
        var second: Probe? = null
        try {
            val loaded = awaitLoaded(first)
            val library = File(loaded.lines()[1])
            val time = Files.getLastModifiedTime(library.toPath())
            second = probe(cache, gate, "second")
            assertEquals(loaded, awaitLoaded(second))
            assertEquals(time, Files.getLastModifiedTime(library.toPath()))
            assertEquals(listOf(library.name), library.parentFile.list()!!.toList())
            finish(second)
            finish(first)
        } finally {
            first.process.destroyForcibly()
            second?.process?.destroyForcibly()
        }
    }

    @Test
    fun aLoadedWindowsDllIsLockedAndReusable() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val cache = temporary.newFolder("locked dll cache").resolve("natives")
        val gate = File(temporary.root, "start").apply { writeText("start") }
        val holder = probe(cache, gate, "holder")
        try {
            val loaded = awaitLoaded(holder)
            val library = File(loaded.lines()[1]).toPath()
            assertFailsWith<java.io.IOException> { Files.delete(library) }
            val reader = probe(cache, gate, "reader")
            try {
                assertEquals(loaded, awaitLoaded(reader))
                finish(reader)
            } finally {
                reader.process.destroyForcibly()
            }
            finish(holder)
            Files.delete(library) // After process exit, the file is no longer locked.
        } finally {
            holder.process.destroyForcibly()
        }
    }

    private data class Probe(val process: Process, val result: File, val log: File)

    private fun probe(cache: File, gate: File, name: String): Probe {
        val result = File(temporary.root, "$name.result")
        val log = File(temporary.root, "$name.log")
        val config = File(temporary.root, "$name.properties")
        // Properties escapes Unicode, so a Windows launcher's legacy command-line code page
        // cannot change the path before the Java filesystem/loader gets to exercise it.
        config.outputStream().use { out ->
            Properties().apply { setProperty(InkNativeLibrary.CACHE_PROPERTY, cache.absolutePath) }.store(out, null)
        }
        val java = File(System.getProperty("java.home"), "bin/" + if (System.getProperty("os.name").startsWith("Windows")) "java.exe" else "java")
        val process = ProcessBuilder(
            java.path, "--enable-native-access=ALL-UNNAMED",
            "-cp", requireNotNull(System.getProperty("byteink.test.loaderClasspath")),
            NativeLibraryProcessProbe::class.java.name, gate.absolutePath, result.absolutePath, config.absolutePath,
        ).redirectErrorStream(true).redirectOutput(log).start()
        return Probe(process, result, log)
    }

    private fun awaitLoaded(probe: Probe): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!probe.result.isFile && probe.process.isAlive && System.nanoTime() < deadline) Thread.sleep(20)
        assertTrue(probe.result.isFile, "Probe failed or timed out: ${probe.log.readText()}")
        return probe.result.readText()
    }

    private fun finish(probe: Probe) {
        probe.process.outputStream.close()
        assertTrue(probe.process.waitFor(30, TimeUnit.SECONDS), "Probe did not exit: ${probe.log.readText()}")
        assertEquals(0, probe.process.exitValue(), probe.log.readText())
    }
}

/** Loads real JNI, writes an atomic result, then holds the library until the parent closes stdin. */
@OptIn(InkInternalOnlyApi::class)
object NativeLibraryProcessProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val gate = File(args[0])
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!gate.isFile && System.nanoTime() < deadline) Thread.sleep(20)
        check(gate.isFile) { "Parent did not open the start gate" }
        val properties = Properties().apply { File(args[2]).inputStream().use { load(it) } }
        System.setProperty(InkNativeLibrary.CACHE_PROPERTY, properties.getProperty(InkNativeLibrary.CACHE_PROPERTY))
        val library = InkNativeLibrary.load()
        check(StatusNative.statusCodeOk() == 0)
        val result = File(args[1])
        val partial = File(args[1] + ".part")
        partial.writeText("${library.sha256}\n${library.path}")
        Files.move(partial.toPath(), result.toPath())
        System.`in`.read()
    }
}
