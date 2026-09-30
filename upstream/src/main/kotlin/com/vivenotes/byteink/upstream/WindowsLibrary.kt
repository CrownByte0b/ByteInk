package com.vivenotes.byteink.upstream

import java.io.File

/**
 * What a Windows `ink.dll` has to be before byteink ships it: an x86-64 DLL exporting exactly the
 * JNI surface of Google's binary for the pinned release, and importing only what every Windows 10
 * or later provides — no C++ runtime, unwinder or threads library of its own.
 */
internal object WindowsLibrary {

    /** Windows' own libraries, and the Universal C Runtime that ships with Windows 10 and later. */
    private val systemLibrary =
        Regex("(?i)(kernel32|advapi32|bcrypt|dbghelp|ntdll|user32|shell32|ole32|ws2_32)\\.dll|api-ms-win-.+\\.dll")

    /**
     * LLVM's libunwind, linked in statically for C++ exceptions, marks its API dllexport on Windows,
     * and zig's toolchain offers no way to drop exports. Windows scopes exports to their DLL, so
     * these change nothing for anyone else; any other stray export still fails.
     */
    private val unwinderExport = Regex("_Unwind_[A-Za-z]+|unw_[a-z_]+|_GCC_specific_handler")

    fun problems(library: PeFile, expectedExports: Set<String>): List<String> = buildList {
        if (library.machine != PeFile.MACHINE_AMD64) add("It is not an x86-64 DLL (machine 0x${library.machine.toString(16)})")
        if (library.debugKinds.any { it != 16 }) add("It carries path-dependent debug information: ${library.debugKinds}")
        SymbolList.difference(expectedExports, library.exportedNames.filterTo(sortedSetOf()) { it.startsWith("Java_") })
            ?.let { add("Its Java_* exports differ from the pinned surface:\n$it") }
        library.exportedNames.filterNot { it.startsWith("Java_") || it.startsWith("JNI_") || unwinderExport.matches(it) }
            .takeIf { it.isNotEmpty() }
            ?.let { add("It exports more than JNI functions: ${it.take(10).joinToString()}${if (it.size > 10) " …" else ""}") }
        library.importedLibraries.filterNot(systemLibrary::matches)
            .takeIf { it.isNotEmpty() }
            ?.let { add("It needs libraries Windows does not provide: ${it.joinToString()}") }
    }
}

/** `check-windows-library`: fails unless a built `ink.dll` meets [WindowsLibrary]'s requirements. */
internal fun checkWindowsLibraryCommand(arguments: Arguments): Int {
    val binary = File(arguments.required("--binary"))
    val library = PeFile(binary.readBytes())
    println(
        "$binary: ${library.exportedNames.count { it.startsWith("Java_") }} JNI functions; " +
            "imports ${library.importedLibraries.joinToString()}",
    )
    val problems = WindowsLibrary.problems(library, SymbolList.read(File(arguments.required("--exports"))))
    if (problems.isNotEmpty()) {
        throw CheckFailed("$binary is not shippable:\n" + problems.joinToString("\n") { "- $it" })
    }
    return 0
}
