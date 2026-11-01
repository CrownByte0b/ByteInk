package com.vivenotes.byteink.upstream

/** A CPU architecture byteink ships natives for, as ELF and PE headers record it. */
internal enum class Architecture(
    /** The `--architecture` value, as in the platform ids `linux-arm64` and `windows-x86_64`. */
    val id: String,
    val displayName: String,
    val elfMachine: Int,
    val peMachine: Int,
    /** glibc's dynamic loader on this architecture. */
    val linuxLoader: String,
) {
    X86_64("x86_64", "x86-64", elfMachine = 62, peMachine = PeFile.MACHINE_AMD64, linuxLoader = "ld-linux-x86-64.so.2"),
    ARM64("arm64", "ARM64", elfMachine = 183, peMachine = PeFile.MACHINE_ARM64, linuxLoader = "ld-linux-aarch64.so.1"),
    ;

    companion object {
        /** The `--architecture` option's value; x86_64 when absent. */
        fun of(arguments: Arguments): Architecture {
            val id = arguments.optional("--architecture") ?: return X86_64
            return entries.singleOrNull { it.id == id }
                ?: throw UsageError("--architecture must be ${entries.joinToString(" or ") { it.id }}, not $id")
        }
    }
}
