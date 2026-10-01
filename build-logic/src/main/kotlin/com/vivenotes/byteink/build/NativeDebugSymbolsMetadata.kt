package com.vivenotes.byteink.build

import java.util.Properties
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/** Records which stripped ELF the separately downloaded debug ELF belongs to. */
abstract class NativeDebugSymbolsMetadata : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val library: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val symbols: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val buildMetadata: RegularFileProperty

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun record() {
        val metadata = Properties().apply { buildMetadata.get().asFile.inputStream().use(::load) }
        val libraryHash = sha256(library.get().asFile.inputStream())
        val symbolsHash = sha256(symbols.get().asFile.inputStream())
        if (metadata.getProperty("libink.so.sha256") != libraryHash) {
            throw GradleException("Linux library does not match its native build.properties")
        }
        val recordedSymbolsHash = metadata.getProperty("libink.so.debug.sha256")
        if (recordedSymbolsHash != null && recordedSymbolsHash != symbolsHash) {
            throw GradleException("Linux debug symbols do not match their native build.properties")
        }
        val buildId = elfBuildId(library.get().asFile)
        if (elfBuildId(symbols.get().asFile) != buildId) {
            throw GradleException("Linux library and debug symbols have different ELF build IDs")
        }
        metadata.setProperty("libink.so.debug.sha256", symbolsHash)
        metadata.setProperty("libink.so.build-id", buildId)
        manifest.get().asFile.apply {
            parentFile.mkdirs()
            writeText(metadata.stringPropertyNames().sorted().joinToString("\n", postfix = "\n") {
                "$it=${metadata.getProperty(it)}"
            })
        }
    }

    // Read ELF64 PT_NOTE entries directly so publication also works on a Windows build host.
    private fun elfBuildId(file: File): String = RandomAccessFile(file, "r").use { elf ->
        fun bytes(offset: Long, size: Int): ByteBuffer {
            if (offset < 0 || size < 0 || offset > elf.length() - size) {
                throw GradleException("Invalid ELF note range in $file")
            }
            val value = ByteArray(size)
            elf.seek(offset)
            elf.readFully(value)
            return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN)
        }
        val header = bytes(0, 64)
        if (header.getInt(0) != 0x464c457f || header.get(4).toInt() != 2 || header.get(5).toInt() != 1) {
            throw GradleException("Expected a little-endian ELF64 Linux library: $file")
        }
        val programOffset = header.getLong(32)
        val entrySize = header.getShort(54).toInt() and 0xffff
        val entries = header.getShort(56).toInt() and 0xffff
        if (entrySize < 56) throw GradleException("Invalid ELF program header size in $file")
        for (index in 0 until entries) {
            val program = bytes(programOffset + index.toLong() * entrySize, entrySize)
            if (program.getInt(0) != 4) continue // PT_NOTE
            var offset = program.getLong(8)
            val end = offset + program.getLong(32)
            if (end < offset || end > elf.length()) throw GradleException("Invalid ELF note segment in $file")
            while (offset <= end - 12) {
                val note = bytes(offset, 12)
                val nameSize = note.getInt(0)
                val valueSize = note.getInt(4)
                val type = note.getInt(8)
                if (nameSize < 0 || valueSize < 0) throw GradleException("Invalid ELF note length in $file")
                val nameOffset = offset + 12
                val valueOffset = nameOffset + ((nameSize.toLong() + 3) and -4L)
                val nextOffset = valueOffset + ((valueSize.toLong() + 3) and -4L)
                if (nextOffset > end) throw GradleException("Invalid ELF note contents in $file")
                if (type == 3 && nameSize == 4 && bytes(nameOffset, 4).getInt(0) == 0x00554e47) {
                    return@use bytes(valueOffset, valueSize).array().joinToString("") { "%02x".format(it) }
                }
                offset = nextOffset
            }
        }
        throw GradleException("Missing GNU ELF build ID in $file")
    }
}
