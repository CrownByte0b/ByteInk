package com.vivenotes.byteink.upstream

import java.io.File

/**
 * What a Linux `libink.so` has to be before byteink ships it: the same JNI surface as Google's
 * binary for the pinned release, and nothing to load but the C library, no newer than a given glibc.
 */
internal object LinuxLibrary {

    /** The glibc family, and the only libraries Google's own binary needs. */
    val allowedLibraries = setOf(
        "libc.so.6",
        "libm.so.6",
        "libpthread.so.0",
        "libdl.so.2",
        "librt.so.1",
        "ld-linux-x86-64.so.2",
    )

    private val glibcVersion = Regex("""GLIBC_(\d+(?:\.\d+)*)""")

    fun problems(library: ElfFile, expectedExports: Set<String>, maxGlibc: String): List<String> = buildList {
        SymbolList.difference(expectedExports, library.exportedFunctions.filterTo(sortedSetOf()) { it.startsWith("Java_") })
            ?.let { add("Its Java_* exports differ from the pinned surface:\n$it") }
        (library.neededLibraries - allowedLibraries).takeIf { it.isNotEmpty() }
            ?.let { add("It needs libraries beyond glibc: ${it.joinToString()}") }
        library.versionRequirements.forEach { (file, versions) ->
            versions.forEach { version ->
                val number = glibcVersion.matchEntire(version)?.groupValues?.get(1)
                when {
                    file !in allowedLibraries || number == null -> add("It requires $version from $file")
                    compareVersions(number, maxGlibc) > 0 -> add("It requires $version from $file, newer than GLIBC_$maxGlibc")
                }
            }
        }
    }

    /** The newest glibc version [library] requires, or null if it requires none. */
    fun newestGlibc(library: ElfFile): String? = library.versionRequirements.values.flatten()
        .mapNotNull { glibcVersion.matchEntire(it)?.groupValues?.get(1) }
        .maxWithOrNull(::compareVersions)

    fun compareVersions(first: String, second: String): Int {
        val a = first.split('.').map(String::toInt)
        val b = second.split('.').map(String::toInt)
        for (index in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(index) { 0 } - b.getOrElse(index) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }
}

/** `check-linux-library`: fails unless a built `libink.so` meets [LinuxLibrary]'s requirements. */
internal fun checkLinuxLibraryCommand(arguments: Arguments): Int {
    val binary = File(arguments.required("--binary"))
    val library = ElfFile(binary.readBytes())
    val maxGlibc = arguments.required("--max-glibc")
    println(
        "$binary: ${library.exportedFunctions.count { it.startsWith("Java_") }} JNI functions; " +
            "needs ${library.neededLibraries.joinToString()}; " +
            "newest glibc symbol ${LinuxLibrary.newestGlibc(library)?.let { "GLIBC_$it" } ?: "none"}",
    )
    val problems = LinuxLibrary.problems(library, SymbolList.read(File(arguments.required("--exports"))), maxGlibc)
    if (problems.isNotEmpty()) {
        throw CheckFailed("$binary is not shippable:\n" + problems.joinToString("\n") { "- $it" })
    }
    return 0
}
