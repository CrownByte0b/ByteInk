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
        // A crash report is kept with the build's reports, which CI uploads.
        val reports = System.getProperty("byteink.test.fuzzReports")?.let(::File) ?: temporary.newFolder("reports")
        reports.mkdirs()
        val process = ProcessBuilder(
            java,
            // A small heap collects often, so finalizers free Ink's native memory as it goes.
            "-Xmx512m",
            "--enable-native-access=ALL-UNNAMED",
            "-XX:ErrorFile=${File(reports, "hs_err_pid%p.log")}",
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
            val crash = File(reports, "hs_err_pid${process.pid()}.log")
            fail(
                "The fuzzer's JVM $what at seed $seed, iteration $last. " +
                    "Rerun with FuzzMain $seed ${last?.toInt()?.plus(1)} to reproduce. Its last output:\n" +
                    output.filterNot { it.startsWith("iteration ") }.takeLast(20).joinToString("\n") +
                    if (crash.isFile) "\n" + crashSummary(crash) else "",
            )
        }
    }

    /**
     * Where a JVM crash report says the process died: the crashing thread's native and Java frames,
     * the signal or exception, and the machine's memory (on Windows, including the commit limit).
     */
    private fun crashSummary(report: File): String {
        val lines = report.readLines()
        val thread = lines.dropWhile { !it.startsWith("Current thread") }
            .takeWhile { !it.startsWith("siginfo:") && !it.startsWith("Registers:") }
            .take(CRASH_STACK_LINES)
        val details = lines.filter { line -> CRASH_DETAILS.any { line.startsWith(it) } }
        return (listOf("Crash report $report:") + thread + details).joinToString("\n")
    }

    private companion object {
        /** Iterations a slow machine runs in a minute, generously: the timeout allows one minute per this many. */
        const val MINUTE_OF_ITERATIONS = 2_000
        const val CRASH_STACK_LINES = 80
        val CRASH_DETAILS = listOf("siginfo:", "Host:", "Time:", "Memory:", "TotalPageFile", "current process")
    }
}
