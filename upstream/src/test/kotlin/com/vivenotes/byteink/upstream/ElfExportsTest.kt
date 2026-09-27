package com.vivenotes.byteink.upstream

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElfExportsTest {

    @Test
    fun listsDefinedVisibleFunctionsOnly() {
        val elf = sharedLibrary(
            Symbol("Java_androidx_ink_brush_BrushNative_create"),
            Symbol("weak_function", binding = STB_WEAK),
            Symbol("protected_function", visibility = STV_PROTECTED),
            Symbol("Java_hidden", visibility = STV_HIDDEN),
            Symbol("Java_imported", defined = false),
            Symbol("local_function", binding = STB_LOCAL),
            Symbol("some_data", type = STT_OBJECT),
        )

        assertEquals(
            sortedSetOf("Java_androidx_ink_brush_BrushNative_create", "protected_function", "weak_function"),
            ElfExports.functions(elf),
        )
    }

    @Test
    fun rejectsWhatItCannotRead() {
        assertFailsWith<IllegalArgumentException> { ElfExports.functions("not a library".toByteArray()) }
        val elf = sharedLibrary(Symbol("Java_a_B_f"))
        assertFailsWith<IllegalArgumentException> { ElfExports.functions(elf.copyOf(elf.size - 40)) }
        val bigEndian = elf.copyOf().also { it[5] = 2 }
        assertFailsWith<IllegalArgumentException> { ElfExports.functions(bigEndian) }
    }

    private data class Symbol(
        val name: String,
        val binding: Int = STB_GLOBAL,
        val type: Int = STT_FUNC,
        val visibility: Int = STV_DEFAULT,
        val defined: Boolean = true,
    )

    /**
     * A minimal ELF64 little-endian image: header, `.dynsym`, `.dynstr` and the section headers
     * (null, `.dynsym`, `.dynstr`) — everything [ElfExports] reads, and nothing else.
     */
    private fun sharedLibrary(vararg symbols: Symbol): ByteArray {
        val strings = ByteArrayOutputStream().apply { write(0) }
        val nameOffsets = symbols.map { symbol ->
            strings.size().also { strings.write(symbol.name.toByteArray()); strings.write(0) }
        }
        val symbolTable = ByteBuffer.allocate((symbols.size + 1) * 24).order(ByteOrder.LITTLE_ENDIAN)
        symbolTable.position(24) // the null symbol
        symbols.forEachIndexed { index, symbol ->
            symbolTable.putInt(nameOffsets[index])
            symbolTable.put(((symbol.binding shl 4) or symbol.type).toByte())
            symbolTable.put(symbol.visibility.toByte())
            symbolTable.putShort(if (symbol.defined) 7 else 0)
            symbolTable.putLong(0x1000L + index * 16)
            symbolTable.putLong(16)
        }

        val symbolsAt = 64
        val stringsAt = symbolsAt + symbolTable.capacity()
        val headersAt = stringsAt + strings.size()
        val image = ByteBuffer.allocate(headersAt + 3 * 64).order(ByteOrder.LITTLE_ENDIAN)
        image.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        image.putShort(0x10, 3) // ET_DYN
        image.putShort(0x12, 62) // EM_X86_64
        image.putLong(0x28, headersAt.toLong())
        image.putShort(0x3A, 64)
        image.putShort(0x3C, 3)
        image.put(symbolsAt, symbolTable.array())
        image.put(stringsAt, strings.toByteArray())
        fun section(index: Int, type: Int, offset: Int, size: Int, link: Int, entrySize: Int) {
            val at = headersAt + index * 64
            image.putInt(at + 4, type)
            image.putLong(at + 24, offset.toLong())
            image.putLong(at + 32, size.toLong())
            image.putInt(at + 40, link)
            image.putLong(at + 56, entrySize.toLong())
        }
        section(1, type = 11, offset = symbolsAt, size = symbolTable.capacity(), link = 2, entrySize = 24)
        section(2, type = 3, offset = stringsAt, size = strings.size(), link = 0, entrySize = 0)
        return image.array()
    }

    private companion object {
        const val STB_LOCAL = 0
        const val STB_GLOBAL = 1
        const val STB_WEAK = 2
        const val STT_OBJECT = 1
        const val STT_FUNC = 2
        const val STV_DEFAULT = 0
        const val STV_HIDDEN = 2
        const val STV_PROTECTED = 3
    }
}
