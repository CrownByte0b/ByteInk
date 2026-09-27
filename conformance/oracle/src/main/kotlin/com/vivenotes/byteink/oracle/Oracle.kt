package com.vivenotes.byteink.oracle

import androidx.ink.brush.StockBrushes
import java.io.File
import java.security.MessageDigest
import kotlin.system.exitProcess

private const val USAGE = """Usage:
  oracle dump --out FILE --label LABEL [--fixtures DIR] [--detail PREFIX]
      Runs every case against whichever libink.so the upstream loader finds first on the classpath
      and writes the results, headed by that library's sha256. With --detail, keeps only the cases
      whose names start with PREFIX and writes their buffers in full.
  oracle compare --reference FILE --candidate FILE --report FILE [--allow-differences]
      Compares two dumps value by value and writes a markdown report; fails on any mismatch beyond
      the float tolerance unless allowed, and always when both dumps come from the same library."""

fun main(args: Array<String>) {
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
            dump.write(out, linkedMapOf("label" to required("--label"), "library" to loadedLibrarySha256(), "values" to "${dump.size}"))
            println("${dump.size} values from ${loadedLibrarySha256()} in $out")
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
 * The sha256 of the libink the process has loaded, found through its memory map: the upstream
 * loader loads a temporary copy of whichever library it found on the classpath.
 */
private fun loadedLibrarySha256(): String {
    StockBrushes.marker(StockBrushes.MarkerVersion.V1) // loads the library, if nothing has yet
    val maps = File("/proc/self/maps")
    if (!maps.isFile) return "unknown"
    val library = maps.readLines()
        .mapNotNull { line -> line.indexOf('/').takeIf { it >= 0 }?.let { File(line.substring(it)) } }
        .filter { "libink" in it.name }
        .distinct()
        .single()
    return MessageDigest.getInstance("SHA-256").digest(library.readBytes()).joinToString("") { "%02x".format(it) }
}
