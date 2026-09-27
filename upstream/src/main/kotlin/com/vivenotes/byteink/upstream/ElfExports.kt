package com.vivenotes.byteink.upstream

import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.SortedSet

/**
 * The functions a 64-bit little-endian ELF shared library exports: defined, global or weak, visible
 * `.dynsym` entries — what `nm -D --defined-only` lists as `T`/`W`. Read from the section headers,
 * which a normal `strip` keeps.
 */
internal object ElfExports {

    private const val SHT_DYNSYM = 11
    private const val SHN_UNDEF = 0
    private const val STB_GLOBAL = 1
    private const val STB_WEAK = 2
    private const val STT_FUNC = 2
    private const val STV_DEFAULT = 0
    private const val STV_PROTECTED = 3
    private const val SYMBOL_SIZE = 24

    fun functions(elf: ByteArray): SortedSet<String> {
        require(
            elf.size >= 64 && elf[0] == 0x7f.toByte() && elf[1] == 'E'.code.toByte() &&
                elf[2] == 'L'.code.toByte() && elf[3] == 'F'.code.toByte(),
        ) { "Not an ELF file" }
        require(elf[4] == 2.toByte()) { "Not a 64-bit ELF file" }
        require(elf[5] == 1.toByte()) { "Not a little-endian ELF file" }
        try {
            return read(ByteBuffer.wrap(elf).order(ByteOrder.LITTLE_ENDIAN))
        } catch (truncated: IndexOutOfBoundsException) {
            throw IllegalArgumentException("Truncated ELF file", truncated)
        } catch (truncated: BufferUnderflowException) {
            throw IllegalArgumentException("Truncated ELF file", truncated)
        }
    }

    private class Section(elf: ByteBuffer, header: Int) {
        val type = elf.getInt(header + 4)
        val offset = elf.getLong(header + 24)
        val size = elf.getLong(header + 32)
        val link = elf.getInt(header + 40)
        val entrySize = elf.getLong(header + 56)
    }

    private fun read(elf: ByteBuffer): SortedSet<String> {
        val headersAt = elf.getLong(0x28)
        val headerSize = elf.getShort(0x3A).toInt() and 0xffff
        val headerCount = elf.getShort(0x3C).toInt() and 0xffff
        require(headersAt > 0 && headerCount > 0) { "ELF file has no section headers" }
        fun section(index: Int) = Section(elf, Math.toIntExact(headersAt + index.toLong() * headerSize))

        val symbols = (0 until headerCount).map(::section).singleOrNull { it.type == SHT_DYNSYM }
            ?: throw IllegalArgumentException("ELF file has no single .dynsym section")
        val names = section(symbols.link)
        val entrySize = if (symbols.entrySize > 0) symbols.entrySize else SYMBOL_SIZE.toLong()
        val count = Math.toIntExact(symbols.size / entrySize)

        val exported = sortedSetOf<String>()
        // Entry 0 is the reserved null symbol.
        for (index in 1 until count) {
            val at = Math.toIntExact(symbols.offset + index * entrySize)
            val info = elf.get(at + 4).toInt() and 0xff
            val visibility = elf.get(at + 5).toInt() and 0x3
            val sectionIndex = elf.getShort(at + 6).toInt() and 0xffff
            val binding = info ushr 4
            if (sectionIndex == SHN_UNDEF || info and 0xf != STT_FUNC) continue
            if (binding != STB_GLOBAL && binding != STB_WEAK) continue
            if (visibility != STV_DEFAULT && visibility != STV_PROTECTED) continue
            exported += cString(elf, Math.toIntExact(names.offset + (elf.getInt(at).toLong() and 0xffffffffL)))
        }
        return exported
    }

    private fun cString(elf: ByteBuffer, start: Int): String {
        var end = start
        while (elf.get(end) != 0.toByte()) end++
        val bytes = ByteArray(end - start)
        elf.get(start, bytes)
        return bytes.decodeToString()
    }
}
