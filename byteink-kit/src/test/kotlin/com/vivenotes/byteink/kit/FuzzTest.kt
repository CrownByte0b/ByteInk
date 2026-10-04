package com.vivenotes.byteink.kit

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.fail
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * Runs [main] in FuzzMain.kt in a JVM of its own, where a native abort costs that JVM rather than the
 * test run, and reports the seed and iteration it died on. `-PbyteinkFuzzIterations` and
 * `-PbyteinkFuzzSeed` run longer or different sequences.
 */
class FuzzTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun randomInkNeverAbortsTheProcess() {
        val seed = System.getProperty("byteink.test.fuzzSeed")?.toLong() ?: 1L
        val iterations = System.getProperty("byteink.test.fuzzIterations")?.toInt() ?: 500
        val java = File(System.getProperty("java.home"), "bin/java").path
        // The output goes to a file rather than a pipe, so the timeout below holds even if the
        // fuzzer hangs, and a long run can be followed with tail.
        val log = temporary.newFile("fuzz.log")
        val process = ProcessBuilder(
            java,
            // A small heap collects often, so finalizers free Ink's native memory as it goes.
            "-Xmx512m",
            "--enable-native-access=ALL-UNNAMED",
            "-Dbyteink.ink.cache=${temporary.newFolder("cache")}",
            "-cp", System.getProperty("byteink.test.classpath"),
            "com.vivenotes.byteink.kit.FuzzMainKt",
            seed.toString(),
            iterations.toString(),
        ).redirectErrorStream(true).redirectOutput(log).start()
        val minutes = 10L + iterations / MINUTE_OF_ITERATIONS
        val finished = process.waitFor(minutes, TimeUnit.MINUTES)
        if (!finished) process.destroyForcibly().waitFor()
        val output = log.readLines()

        if (!finished || process.exitValue() != 0 || output.lastOrNull() != "done $iterations") {
            val last = output.lastOrNull { it.startsWith("iteration ") }?.removePrefix("iteration ")
            val what = if (finished) "died (exit ${process.exitValue()})" else "was still running after $minutes minutes"
            fail(
                "The fuzzer's JVM $what at seed $seed, iteration $last. " +
                    "Rerun with FuzzMain $seed ${last?.toInt()?.plus(1)} to reproduce. Its last output:\n" +
                    output.filterNot { it.startsWith("iteration ") }.takeLast(20).joinToString("\n"),
            )
        }
    }

    private companion object {
        /** Iterations a slow machine runs in a minute, generously: the timeout allows one minute per this many. */
        const val MINUTE_OF_ITERATIONS = 2_000
    }
}
