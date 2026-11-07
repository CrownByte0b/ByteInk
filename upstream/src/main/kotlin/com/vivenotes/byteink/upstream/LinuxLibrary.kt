package com.vivenotes.byteink.upstream

import java.io.File

/**
 * What a Linux `libink.so` has to be before byteink ships it: built for its architecture, the
 * declared JNI surface (the pinned Google release plus explicit ByteInk extensions), and only the C
 * library below a given glibc.
 */
internal object LinuxLibrary {

    private val glibc = setOf("libc.so.6", "libm.so.6", "libpthread.so.0", "libdl.so.2", "librt.so.1")

    /**
     * The glibc family on [architecture], whose dynamic loader is named after it: on x86-64, exactly
     * the libraries Google's own binary needs.
     */
    fun allowedLibraries(architecture: Architecture): Set<String> = glibc + architecture.linuxLoader

    private val glibcVersion = Regex("""GLIBC_(\d+(?:\.\d+)*)""")

    fun problems(
        library: ElfFile,
        expectedExports: Set<String>,
        maxGlibc: String,
        architecture: Architecture = Architecture.X86_64,
    ): List<String> = buildList {
        val allowedLibraries = allowedLibraries(architecture)
        if (library.machine != architecture.elfMachine) {
            add("It is not an ${architecture.displayName} library (ELF machine ${library.machine})")
        }
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
    val problems = LinuxLibrary.problems(library, LibraryExportContract.read(arguments), maxGlibc, Architecture.of(arguments))
    if (problems.isNotEmpty()) {
        throw CheckFailed("$binary is not shippable:\n" + problems.joinToString("\n") { "- $it" })
    }
    return 0
}
