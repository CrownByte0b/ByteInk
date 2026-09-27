package com.vivenotes.byteink.upstream

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.SortedSet

/**
 * The parts of a 64-bit little-endian ELF shared library that decide what it offers and what it
 * needs at load time, read from its section headers (which a normal `strip` keeps).
 */
internal class ElfFile(bytes: ByteArray) {

    private val elf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private val sections: List<Section>

    init {
        require(
            bytes.size >= 64 && bytes[0] == 0x7f.toByte() && bytes[1] == 'E'.code.toByte() &&
                bytes[2] == 'L'.code.toByte() && bytes[3] == 'F'.code.toByte(),
        ) { "Not an ELF file" }
        require(bytes[4] == 2.toByte()) { "Not a 64-bit ELF file" }
        require(bytes[5] == 1.toByte()) { "Not a little-endian ELF file" }
        sections = readable {
            val headersAt = elf.getLong(0x28)
            val headerSize = elf.getShort(0x3A).toInt() and 0xffff
            val headerCount = elf.getShort(0x3C).toInt() and 0xffff
            require(headersAt > 0 && headerCount > 0) { "ELF file has no section headers" }
            List(headerCount) { index -> Section(elf, Math.toIntExact(headersAt + index.toLong() * headerSize)) }
        }
    }

    /**
     * Defined, global or weak, visible functions in `.dynsym`: what `nm -D --defined-only` lists as
     * `T` or `W`.
     */
    val exportedFunctions: SortedSet<String> by lazy {
        readable {
            val symbols = single(SHT_DYNSYM, ".dynsym")
            val names = sections[symbols.link]
            val entrySize = if (symbols.entrySize > 0) symbols.entrySize else SYMBOL_SIZE.toLong()
            val exported = sortedSetOf<String>()
            // Entry 0 is the reserved null symbol.
            for (index in 1 until Math.toIntExact(symbols.size / entrySize)) {
                val at = Math.toIntExact(symbols.offset + index * entrySize)
                val info = elf.get(at + 4).toInt() and 0xff
                val visibility = elf.get(at + 5).toInt() and 0x3
                val binding = info ushr 4
                if (elf.getShort(at + 6).toInt() and 0xffff == SHN_UNDEF || info and 0xf != STT_FUNC) continue
                if (binding != STB_GLOBAL && binding != STB_WEAK) continue
                if (visibility != STV_DEFAULT && visibility != STV_PROTECTED) continue
                exported += string(names, elf.getInt(at))
            }
            exported
        }
    }

    /** The `DT_NEEDED` libraries of `.dynamic`, in load order. */
    val neededLibraries: List<String> by lazy {
        readable {
            val dynamic = single(SHT_DYNAMIC, ".dynamic")
            val names = sections[dynamic.link]
            val needed = mutableListOf<String>()
            var at = Math.toIntExact(dynamic.offset)
            val end = at + dynamic.size
            while (at < end) {
                val tag = elf.getLong(at)
                if (tag == DT_NULL) break
                if (tag == DT_NEEDED) needed += string(names, elf.getLong(at + 8).toInt())
                at += DYNAMIC_ENTRY_SIZE
            }
            needed
        }
    }

    /**
     * The symbol versions required from each library (`.gnu.version_r`), such as
     * `libc.so.6` to `GLIBC_2.2.5` and `GLIBC_2.14`. Empty when nothing is versioned.
     */
    val versionRequirements: Map<String, SortedSet<String>> by lazy {
        readable {
            val needs = sections.singleOrNull { it.type == SHT_GNU_VERNEED } ?: return@readable emptyMap()
            val names = sections[needs.link]
            val requirements = sortedMapOf<String, SortedSet<String>>()
            var need = Math.toIntExact(needs.offset)
            while (true) {
                // Elf64_Verneed: vn_version, vn_cnt, vn_file, vn_aux, vn_next.
                val count = elf.getShort(need + 2).toInt() and 0xffff
                val versions = requirements.getOrPut(string(names, elf.getInt(need + 4))) { sortedSetOf() }
                var aux = need + elf.getInt(need + 8)
                repeat(count) {
                    // Elf64_Vernaux: vna_hash, vna_flags, vna_other, vna_name, vna_next.
                    versions += string(names, elf.getInt(aux + 8))
                    aux += elf.getInt(aux + 12)
                }
                val next = elf.getInt(need + 12)
                if (next == 0) break
                need += next
            }
            requirements
        }
    }

    private class Section(elf: ByteBuffer, header: Int) {
        val type = elf.getInt(header + 4)
        val offset = elf.getLong(header + 24)
        val size = elf.getLong(header + 32)
        val link = elf.getInt(header + 40)
        val entrySize = elf.getLong(header + 56)
    }

    private fun single(type: Int, name: String): Section =
        sections.singleOrNull { it.type == type } ?: throw IllegalArgumentException("ELF file has no single $name section")

    private fun string(table: Section, offset: Int): String {
        val start = Math.toIntExact(table.offset + (offset.toLong() and 0xffffffffL))
        var end = start
        while (elf.get(end) != 0.toByte()) end++
        return ByteArray(end - start).also { elf.get(start, it) }.decodeToString()
    }

    private inline fun <T> readable(read: () -> T): T = try {
        read()
    } catch (truncated: IndexOutOfBoundsException) {
        throw IllegalArgumentException("Truncated ELF file", truncated)
    } catch (truncated: BufferUnderflowException) {
        throw IllegalArgumentException("Truncated ELF file", truncated)
    }

    private companion object {
        const val SHT_DYNAMIC = 6
        const val SHT_DYNSYM = 11
        const val SHT_GNU_VERNEED = 0x6ffffffe
        const val SHN_UNDEF = 0
        const val STB_GLOBAL = 1
        const val STB_WEAK = 2
        const val STT_FUNC = 2
        const val STV_DEFAULT = 0
        const val STV_PROTECTED = 3
        const val SYMBOL_SIZE = 24
        const val DT_NULL = 0L
        const val DT_NEEDED = 1L
        const val DYNAMIC_ENTRY_SIZE = 16
    }
}
