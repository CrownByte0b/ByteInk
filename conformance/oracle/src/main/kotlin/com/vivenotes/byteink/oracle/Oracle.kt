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
      the float tolerance unless allowed, and always when both dumps come from the same library.
      Dumps from different platforms may also differ in antialiasing derivatives."""

fun main(args: Array<String>) = oracle(args)

/** The oracle's command line; [Preload] calls it too. */
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
            val header = linkedMapOf(
                "label" to required("--label"),
                "library" to loadedLibrarySha256(),
                "platform" to platform(),
                "values" to "${dump.size}",
            )
            dump.write(out, header)
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
    System.getProperty(Preload.LIBRARY_PROPERTY)?.let { return sha256(File(it)) }
    val maps = File("/proc/self/maps")
    if (!maps.isFile) return "unknown"
    val library = maps.readLines()
        .mapNotNull { line -> line.indexOf('/').takeIf { it >= 0 }?.let { File(line.substring(it)) } }
        .filter { "libink" in it.name }
        .distinct()
        .single()
    return sha256(library)
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

private fun sha256(file: File): String =
    MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
