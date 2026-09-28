package com.vivenotes.byteink.core

import com.vivenotes.byteink.nativeloader.LoadedInkLibrary.Origin
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class InkRuntimeTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun loadsTheLibraryByteinksLoaderBundles() {
        val library = InkRuntime.load()

        assertEquals(Origin.BUNDLED, library.origin)
        assertSame(library, InkRuntime.load())
    }

    @Test
    fun failsFastWhenGooglesLoaderShadowsByteinks() {
        val google = System.getProperty("byteink.test.googleNativeLoaderJar")
        val classpath = System.getProperty("byteink.test.classpath")

        val output = run(ShadowedLoaderProbe::class.java.name, google + File.pathSeparator + classpath)

        assertContains(output, "IllegalStateException")
        assertContains(output, "comes from ${File(google).toURI()}")
        assertContains(output, "not from byteink's ink-nativeloader")
    }

    /** Runs [main] in a new JVM on [classpath] and returns what it printed. */
    private fun run(main: String, classpath: String): String {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val process = ProcessBuilder(
            java,
            "--enable-native-access=ALL-UNNAMED",
            "-Dbyteink.ink.cache=${temporary.newFolder("cache")}",
            "-cp", classpath,
            main,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the probe did not finish:\n$output")
        return output
    }
}

/** Calls [InkRuntime.load] and prints what happened, for a JVM whose classpath the test chose. */
object ShadowedLoaderProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        try {
            println("loaded ${InkRuntime.load()}")
        } catch (failure: Throwable) {
            println("failed: ${failure.javaClass.name}: ${failure.message}")
        }
    }
}
