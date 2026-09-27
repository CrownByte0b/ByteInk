package com.vivenotes.byteink.upstream

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ElfFileTest {

    @Test
    fun exportsOnlyDefinedVisibleFunctions() {
        val elf = TestElf(
            symbols = listOf(
                TestElf.Symbol("Java_androidx_ink_brush_BrushNative_create"),
                TestElf.Symbol("weak_function", binding = TestElf.STB_WEAK),
                TestElf.Symbol("protected_function", visibility = TestElf.STV_PROTECTED),
                TestElf.Symbol("Java_hidden", visibility = TestElf.STV_HIDDEN),
                TestElf.Symbol("Java_imported", defined = false),
                TestElf.Symbol("local_function", binding = TestElf.STB_LOCAL),
                TestElf.Symbol("some_data", type = TestElf.STT_OBJECT),
            ),
        ).bytes()

        assertEquals(
            sortedSetOf("Java_androidx_ink_brush_BrushNative_create", "protected_function", "weak_function"),
            ElfFile(elf).exportedFunctions,
        )
    }

    @Test
    fun readsNeededLibrariesAndVersionRequirements() {
        val elf = ElfFile(
            TestElf(
                needed = listOf("libm.so.6", "libc.so.6"),
                versions = mapOf(
                    "libm.so.6" to listOf("GLIBC_2.2.5"),
                    "libc.so.6" to listOf("GLIBC_2.14", "GLIBC_2.2.5", "GLIBC_2.26"),
                ),
            ).bytes(),
        )

        assertEquals(listOf("libm.so.6", "libc.so.6"), elf.neededLibraries)
        assertEquals(
            mapOf(
                "libc.so.6" to sortedSetOf("GLIBC_2.14", "GLIBC_2.2.5", "GLIBC_2.26"),
                "libm.so.6" to sortedSetOf("GLIBC_2.2.5"),
            ),
            elf.versionRequirements,
        )
    }

    @Test
    fun aLibraryWithoutVersionedDependenciesRequiresNone() {
        assertEquals(emptyMap(), ElfFile(TestElf(needed = listOf("libc.so.6")).bytes()).versionRequirements)
    }

    @Test
    fun rejectsWhatItCannotRead() {
        assertFailsWith<IllegalArgumentException> { ElfFile("not a library".toByteArray()) }
        val elf = TestElf(symbols = listOf(TestElf.Symbol("Java_a_B_f"))).bytes()
        assertFailsWith<IllegalArgumentException> { ElfFile(elf.copyOf(elf.size - 100)) }
        val bigEndian = elf.copyOf().also { it[5] = 2 }
        assertFailsWith<IllegalArgumentException> { ElfFile(bigEndian) }
    }
}

/**
 * A minimal ELF64 little-endian shared library image: the header, `.dynsym`, `.dynstr`, `.dynamic`,
 * `.gnu.version_r` and their section headers — everything [ElfFile] reads, and nothing else.
 */
internal class TestElf(
    private val symbols: List<Symbol> = emptyList(),
    private val needed: List<String> = emptyList(),
    private val versions: Map<String, List<String>> = emptyMap(),
) {
    data class Symbol(
        val name: String,
        val binding: Int = STB_GLOBAL,
        val type: Int = STT_FUNC,
        val visibility: Int = STV_DEFAULT,
        val defined: Boolean = true,
    )

    fun bytes(): ByteArray {
        val strings = ByteArrayOutputStream().apply { write(0) }
        val offsets = HashMap<String, Int>()
        fun string(value: String): Int = offsets.getOrPut(value) {
            strings.size().also { strings.write(value.toByteArray()); strings.write(0) }
        }

        val symbolTable = buffer((symbols.size + 1) * 24).apply {
            position(24) // the null symbol
            symbols.forEachIndexed { index, symbol ->
                putInt(string(symbol.name))
                put(((symbol.binding shl 4) or symbol.type).toByte())
                put(symbol.visibility.toByte())
                putShort(if (symbol.defined) 7 else 0)
                putLong(0x1000L + index * 16)
                putLong(16)
            }
        }
        val dynamic = buffer((needed.size + 1) * 16).apply {
            needed.forEach { putLong(1).putLong(string(it).toLong()) }
            putLong(0).putLong(0)
        }
        val versionNeeds = buffer(versions.entries.sumOf { 16 + it.value.size * 16 }).apply {
            versions.entries.forEachIndexed { fileIndex, (file, names) ->
                val last = fileIndex == versions.size - 1
                putShort(1).putShort(names.size.toShort()).putInt(string(file)).putInt(16)
                putInt(if (last) 0 else 16 + names.size * 16)
                names.forEachIndexed { index, name ->
                    putInt(name.hashCode()).putShort(0).putShort((index + 2).toShort()).putInt(string(name))
                    putInt(if (index == names.size - 1) 0 else 16)
                }
            }
        }

        val stringBytes = strings.toByteArray()
        val bodies = listOf(symbolTable.array(), stringBytes, dynamic.array(), versionNeeds.array())
        val starts = bodies.runningFold(64) { at, body -> at + body.size }
        val headersAt = starts.last()
        val image = buffer(headersAt + 5 * 64)
        image.put(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte(), 2, 1, 1))
        image.putShort(0x10, 3) // ET_DYN
        image.putShort(0x12, 62) // EM_X86_64
        image.putLong(0x28, headersAt.toLong())
        image.putShort(0x3A, 64)
        image.putShort(0x3C, 5)
        bodies.forEachIndexed { index, body -> image.put(starts[index], body) }
        fun section(index: Int, type: Int, body: Int, link: Int, entrySize: Int) {
            val at = headersAt + index * 64
            image.putInt(at + 4, type)
            image.putLong(at + 24, starts[body].toLong())
            image.putLong(at + 32, bodies[body].size.toLong())
            image.putInt(at + 40, link)
            image.putLong(at + 56, entrySize.toLong())
        }
        section(1, type = 11, body = 0, link = 2, entrySize = 24) // .dynsym
        section(2, type = 3, body = 1, link = 0, entrySize = 0) // .dynstr
        section(3, type = 6, body = 2, link = 2, entrySize = 16) // .dynamic
        if (versions.isNotEmpty()) section(4, type = 0x6ffffffe, body = 3, link = 2, entrySize = 0) // .gnu.version_r
        return image.array()
    }

    private fun buffer(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    companion object {
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
