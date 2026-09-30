package com.vivenotes.byteink.upstream

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.SortedSet

/**
 * The parts of a 64-bit Windows DLL (PE32+) that decide what it offers and what it needs at load
 * time: its machine, its named exports, and the DLLs it imports, directly or delay-loaded.
 */
internal class PeFile(bytes: ByteArray) {

    private val pe = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    private class Headers(val machine: Int, val directories: List<Pair<Int, Int>>, val sections: List<Section>)

    private val headers: Headers = run {
        require(bytes.size >= 64 && bytes[0] == 'M'.code.toByte() && bytes[1] == 'Z'.code.toByte()) { "Not a PE file" }
        readable {
            val header = pe.getInt(0x3C)
            require(pe.getInt(header) == 0x00004550) { "Not a PE file" }
            val sectionCount = pe.getShort(header + 6).toInt() and 0xffff
            val optionalSize = pe.getShort(header + 20).toInt() and 0xffff
            val optional = header + 24
            require(pe.getShort(optional).toInt() and 0xffff == PE32_PLUS) { "Not a 64-bit (PE32+) file" }
            val table = optional + optionalSize
            Headers(
                machine = pe.getShort(header + 4).toInt() and 0xffff,
                directories = List(pe.getInt(optional + 108)) { index ->
                    pe.getInt(optional + 112 + index * 8) to pe.getInt(optional + 116 + index * 8)
                },
                sections = List(sectionCount) { index -> Section(pe, table + index * 40) },
            )
        }
    }
    private val sections get() = headers.sections
    private val directories get() = headers.directories

    /** The COFF machine type: 0x8664 is x86-64. */
    val machine: Int get() = headers.machine

    /** PE debug-directory kinds. CodeView (2) embeds a PDB GUID/path; REPRO (16) is path-free. */
    val debugKinds: List<Int> by lazy {
        readable {
            val (rva, size) = directories.getOrElse(6) { 0 to 0 }
            if (rva == 0 || size == 0) return@readable emptyList()
            require(size % 28 == 0) { "Invalid PE debug directory size" }
            val start = offset(rva)
            List(size / 28) { pe.getInt(start + it * 28 + 12) }
        }
    }

    /** Every exported name. */
    val exportedNames: SortedSet<String> by lazy {
        readable {
            val (rva, size) = directories.getOrElse(EXPORT) { 0 to 0 }
            if (rva == 0 || size == 0) return@readable sortedSetOf()
            val directory = offset(rva)
            val count = pe.getInt(directory + 24)
            val names = offset(pe.getInt(directory + 32))
            (0 until count).mapTo(sortedSetOf()) { index -> string(offset(pe.getInt(names + index * 4))) }
        }
    }

    /** The DLLs this one imports, as named in its import tables. */
    val importedLibraries: SortedSet<String> by lazy {
        readable { libraries(IMPORT, descriptorSize = 20, nameAt = 12) + libraries(DELAY_IMPORT, descriptorSize = 32, nameAt = 4) }
            .toSortedSet()
    }

    private fun libraries(directory: Int, descriptorSize: Int, nameAt: Int): List<String> {
        val (rva, size) = directories.getOrElse(directory) { 0 to 0 }
        if (rva == 0 || size == 0) return emptyList()
        val libraries = mutableListOf<String>()
        var descriptor = offset(rva)
        while (true) {
            val name = pe.getInt(descriptor + nameAt)
            if (name == 0) break
            libraries += string(offset(name))
            descriptor += descriptorSize
        }
        return libraries
    }

    private class Section(pe: ByteBuffer, header: Int) {
        val virtualSize = pe.getInt(header + 8)
        val virtualAddress = pe.getInt(header + 12)
        val rawSize = pe.getInt(header + 16)
        val rawOffset = pe.getInt(header + 20)
    }

    /** The file offset of a relative virtual address. */
    private fun offset(rva: Int): Int {
        val section = sections.firstOrNull { rva >= it.virtualAddress && rva < it.virtualAddress + maxOf(it.virtualSize, it.rawSize) }
            ?: throw IllegalArgumentException("Address 0x${rva.toString(16)} is in no section")
        return section.rawOffset + (rva - section.virtualAddress)
    }

    private fun string(at: Int): String {
        var end = at
        while (pe.get(end) != 0.toByte()) end++
        return ByteArray(end - at).also { pe.get(at, it) }.decodeToString()
    }

    private inline fun <T> readable(read: () -> T): T = try {
        read()
    } catch (truncated: IndexOutOfBoundsException) {
        throw IllegalArgumentException("Truncated PE file", truncated)
    } catch (truncated: BufferUnderflowException) {
        throw IllegalArgumentException("Truncated PE file", truncated)
    }

    companion object {
        const val MACHINE_AMD64 = 0x8664
        private const val PE32_PLUS = 0x20b
        private const val EXPORT = 0
        private const val IMPORT = 1
        private const val DELAY_IMPORT = 13
    }
}
