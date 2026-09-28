package com.vivenotes.byteink.upstream

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PeFileTest {

    private val surface = setOf("Java_a_B_create", "Java_a_B_free")

    @Test
    fun readsMachineExportsAndEveryKindOfImport() {
        val dll = PeFile(
            TestPe(
                exports = listOf("Java_a_B_free", "Java_a_B_create"),
                imports = listOf("KERNEL32.dll", "api-ms-win-crt-runtime-l1-1-0.dll"),
                delayImports = listOf("bcrypt.dll"),
            ).bytes(),
        )

        assertEquals(PeFile.MACHINE_AMD64, dll.machine)
        assertEquals(sortedSetOf("Java_a_B_create", "Java_a_B_free"), dll.exportedNames)
        assertEquals(sortedSetOf("KERNEL32.dll", "api-ms-win-crt-runtime-l1-1-0.dll", "bcrypt.dll"), dll.importedLibraries)
    }

    @Test
    fun aDllOfJniFunctionsOnWindowsOwnLibrariesIsShippable() {
        val dll = PeFile(
            TestPe(
                exports = surface.toList() + listOf("_Unwind_Resume", "unw_step", "_GCC_specific_handler"),
                imports = listOf("KERNEL32.dll", "dbghelp.dll", "api-ms-win-crt-math-l1-1-0.dll"),
            ).bytes(),
        )

        assertEquals(emptyList(), WindowsLibrary.problems(dll, surface))
    }

    @Test
    fun namesEveryReasonADllIsNotShippable() {
        val dll = PeFile(
            TestPe(
                machine = 0xaa64,
                exports = listOf("Java_a_B_create", "absl_helper"),
                imports = listOf("KERNEL32.dll", "libc++.dll", "libwinpthread-1.dll"),
            ).bytes(),
        )

        val problems = WindowsLibrary.problems(dll, surface)

        assertEquals(4, problems.size, problems.joinToString("\n"))
        assertEquals("It is not an x86-64 DLL (machine 0xaa64)", problems[0])
        assertTrue(problems[1].contains("missing (1):\n    Java_a_B_free"))
        assertEquals("It exports more than JNI functions: absl_helper", problems[2])
        assertEquals("It needs libraries Windows does not provide: libc++.dll, libwinpthread-1.dll", problems[3])
    }

    @Test
    fun rejectsWhatItCannotRead() {
        assertFailsWith<IllegalArgumentException> { PeFile("not a library".toByteArray()) }
        val dll = TestPe(exports = surface.toList()).bytes()
        assertFailsWith<IllegalArgumentException> { PeFile(dll.copyOf(400)).exportedNames }
    }
}

/**
 * A minimal PE32+ DLL image: DOS stub, headers, and one section holding export, import and
 * delay-import tables — everything [PeFile] reads, and nothing else.
 */
internal class TestPe(
    private val machine: Int = PeFile.MACHINE_AMD64,
    private val exports: List<String> = emptyList(),
    private val imports: List<String> = emptyList(),
    private val delayImports: List<String> = emptyList(),
) {
    fun bytes(): ByteArray {
        val sectionRva = 0x1000
        val sectionOffset = 0x200
        val data = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        fun string(value: String): Int = (sectionRva + data.position()).also {
            data.put(value.toByteArray()).put(0)
        }

        // Strings first, then the tables that point at them.
        val exportNames = exports.sorted().map(::string)
        val importNames = imports.map(::string)
        val delayNames = delayImports.map(::string)
        data.position((data.position() + 7) and 7.inv())

        val namePointers = sectionRva + data.position()
        exportNames.forEach { data.putInt(it) }
        val exportDirectory = sectionRva + data.position()
        data.put(ByteArray(24)).putInt(exportNames.size).putInt(0).putInt(namePointers).putInt(0)
        val importDirectory = sectionRva + data.position()
        importNames.forEach { name -> data.put(ByteArray(12)).putInt(name).putInt(0) }
        data.put(ByteArray(20))
        val delayDirectory = sectionRva + data.position()
        delayNames.forEach { name -> data.putInt(1).putInt(name).put(ByteArray(24)) }
        data.put(ByteArray(32))
        val used = data.position()

        val image = ByteBuffer.allocate(sectionOffset + used).order(ByteOrder.LITTLE_ENDIAN)
        image.put(0, 'M'.code.toByte()).put(1, 'Z'.code.toByte()).putInt(0x3C, 64)
        image.putInt(64, 0x00004550)
        image.putShort(68, machine.toShort()).putShort(70, 1).putShort(84, 240)
        val optional = 88
        image.putShort(optional, 0x20b).putInt(optional + 108, 16)
        fun directory(index: Int, rva: Int, size: Int) {
            image.putInt(optional + 112 + index * 8, rva).putInt(optional + 116 + index * 8, size)
        }
        if (exports.isNotEmpty()) directory(0, exportDirectory, 40)
        if (imports.isNotEmpty()) directory(1, importDirectory, 20 * (imports.size + 1))
        if (delayImports.isNotEmpty()) directory(13, delayDirectory, 32 * (delayImports.size + 1))
        val section = optional + 240
        image.put(section, ".rdata".toByteArray())
        image.putInt(section + 8, used).putInt(section + 12, sectionRva).putInt(section + 16, used).putInt(section + 20, sectionOffset)
        image.put(sectionOffset, data.array(), 0, used)
        return image.array()
    }
}
