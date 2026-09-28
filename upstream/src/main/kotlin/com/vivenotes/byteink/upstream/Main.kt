package com.vivenotes.byteink.upstream

import java.io.File
import kotlin.system.exitProcess

private val USAGE = """
    Usage: upstream <command> [options]

      surface --version V --natives FILE --exports FILE --jar JAR... [--check]
          Derives the JNI surface of an AndroidX Ink release from its JVM jars: the natives their
          classes declare, and the Java_* functions Google's linux-x86_64/libink.so exports. Writes
          both lists, or with --check fails if the files no longer match.

      exports --binary FILE
          Prints the Java_* functions an ELF shared library exports.

      check-linux-library --binary FILE --exports FILE --max-glibc VERSION
          Fails unless a Linux libink.so exports exactly the Java_* functions listed in FILE, loads
          nothing but glibc libraries, and needs no glibc symbol newer than VERSION.

      check-windows-library --binary FILE --exports FILE
          Fails unless a Windows ink.dll is x86-64, exports exactly the Java_* functions listed in
          FILE and otherwise only LLVM's unwinder API, and imports only libraries Windows 10 and
          later provide.

      source-symbols --repo DIR [--rev REV]
          Prints the JNI functions google/ink's JNI_METHOD macros define at REV (default HEAD).

      scan --repo DIR --pins FILE --pinned LABEL --reference LABEL=FILE... [--rev REV]
           [--report FILE] [--offline]
          Compares every commit on google/ink's first-parent history of REV (default origin/main)
          with each reference list, and fails unless the pinned commit and its candidates match the
          pinned reference exactly. Clones google/ink from the pins file when DIR does not exist,
          and fetches it otherwise unless --offline.
""".trimIndent()

/** A check this tool exists to make did not pass. */
internal class CheckFailed(message: String) : Exception(message)

/** The command line was wrong. */
internal class UsageError(message: String) : Exception(message)

internal class Arguments(arguments: List<String>) {
    val command: String = arguments.firstOrNull() ?: throw UsageError("No command given")
    private val values = mutableMapOf<String, MutableList<String>>()
    private val flags = mutableSetOf<String>()

    init {
        var index = 1
        while (index < arguments.size) {
            val name = arguments[index]
            when {
                !name.startsWith("--") -> throw UsageError("Unexpected argument: $name")
                name in FLAGS -> flags += name
                else -> values.getOrPut(name) { mutableListOf() } +=
                    arguments.getOrNull(++index) ?: throw UsageError("$name needs a value")
            }
            index++
        }
    }

    fun flag(name: String): Boolean = name in flags

    fun optional(name: String): String? = values[name]?.let { given ->
        if (given.size > 1) throw UsageError("$name is given more than once")
        given.single()
    }

    fun required(name: String): String = optional(name) ?: throw UsageError("$name is required")

    fun all(name: String): List<String> = values[name].orEmpty()

    private companion object {
        val FLAGS = setOf("--check", "--offline")
    }
}

fun main(args: Array<String>) {
    val exitCode = try {
        run(Arguments(args.toList()))
    } catch (error: UsageError) {
        System.err.println("${error.message}\n\n$USAGE")
        2
    } catch (failure: CheckFailed) {
        System.err.println(failure.message)
        1
    }
    exitProcess(exitCode)
}

internal fun run(arguments: Arguments): Int = when (arguments.command) {
    "surface" -> surfaceCommand(arguments)
    "scan" -> scanCommand(arguments)
    "exports" -> {
        ElfFile(File(arguments.required("--binary")).readBytes()).exportedFunctions
            .filter { it.startsWith("Java_") }
            .forEach(::println)
        0
    }
    "check-linux-library" -> checkLinuxLibraryCommand(arguments)
    "check-windows-library" -> checkWindowsLibraryCommand(arguments)
    "source-symbols" -> {
        HistoryScan(Git(File(arguments.required("--repo"))))
            .symbolsAt(arguments.optional("--rev") ?: "HEAD")
            .forEach(::println)
        0
    }
    else -> throw UsageError("Unknown command: ${arguments.command}")
}
