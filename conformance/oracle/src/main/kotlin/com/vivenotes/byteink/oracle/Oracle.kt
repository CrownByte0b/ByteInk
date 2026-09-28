package com.vivenotes.byteink.oracle

import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import java.io.File
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  oracle dump --out FILE --label LABEL [--fixtures DIR] [--detail PREFIX]
      Runs every case against the Ink library byteink's loader loads (the one its jar bundles, or
      the one -Dbyteink.ink.library names) and writes the results, headed by that library's
      sha256. With --detail, keeps only the cases whose names start with PREFIX and writes their
      buffers in full.
  oracle compare --reference FILE --candidate FILE --report FILE [--allow-differences]
      Compares two dumps value by value and writes a markdown report; fails on any mismatch beyond
      the float tolerance unless allowed, and always when both dumps come from the same library.
      Dumps from different platforms may also differ in antialiasing derivatives."""

fun main(args: Array<String>) = oracle(args)

/** The oracle's command line. */
fun oracle(args: Array<String>) {
    val options = mutableMapOf<String, String>()
    val flags = mutableSetOf<String>()
    var index = 1
    while (index < args.size) {
        val name = args[index++]
        when {
            name == "--allow-differences" -> flags += name
            name.startsWith("--") && index < args.size -> options[name] = args[index++]
            else -> usage("Unexpected argument $name")
        }
    }
    fun required(name: String): String = options[name] ?: usage("$name is required")
    when (args.firstOrNull()) {
        "dump" -> {
            val dump = Dump(detail = options["--detail"])
            dump.syntheticCases()
            options["--fixtures"]?.let { dump.fixtureCases(File(it)) }
            val out = File(required("--out"))
            val library = InkNativeLibrary.load()
            val header = linkedMapOf(
                "label" to required("--label"),
                "library" to library.sha256,
                "platform" to platform(),
                "values" to "${dump.size}",
            )
            dump.write(out, header)
            println("${dump.size} values from $library in $out")
        }
        "compare" -> {
            val comparison = Comparison(Dump.read(File(required("--reference"))), Dump.read(File(required("--candidate"))))
            val report = File(required("--report")).apply { parentFile?.mkdirs(); writeText(comparison.markdown()) }
            println(report.readLines().first { it.contains(" values: ") })
            println("Report: $report")
            if (comparison.sameLibrary) exitProcess(1)
            if (!comparison.passed && "--allow-differences" !in flags) exitProcess(1)
        }
        else -> usage("Unknown command ${args.firstOrNull()}")
    }
}

private fun usage(problem: String): Nothing {
    System.err.println("$problem\n\n$USAGE")
    exitProcess(2)
}

/**
 * Where the dump ran, as `<os>-<arch>`: the engine calls the platform's C math library (glibc on
 * Linux; the Universal C Runtime and compiler-rt on Windows), so dumps from different platforms
 * differ in last bits.
 */
private fun platform(): String {
    val os = System.getProperty("os.name").lowercase()
    val family = listOf("linux", "windows", "mac").firstOrNull { os.startsWith(it) } ?: os.replace(' ', '_')
    val arch = System.getProperty("os.arch").let { if (it == "amd64") "x86_64" else it }
    return "$family-$arch"
}
