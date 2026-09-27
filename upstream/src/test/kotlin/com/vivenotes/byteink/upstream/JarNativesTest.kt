package com.vivenotes.byteink.upstream

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes

class JarNativesTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun findsNativeMethodsOnlyInTheJarsClasses() {
        val jar = jar(
            "androidx/ink/brush/BrushNative.class" to classFile(
                "androidx/ink/brush/BrushNative",
                native = listOf("create" to "(JIFF)J", "free" to "(J)V"),
                abstract = listOf("describe" to "()Ljava/lang/String;"),
            ),
            "androidx/ink/brush/Brush.class" to classFile(
                "androidx/ink/brush/Brush",
                abstract = listOf("getSize" to "()F"),
            ),
            // Multi-release and other metadata classes are not what the JVM loads here.
            "META-INF/versions/21/androidx/ink/brush/BrushNative.class" to classFile(
                "androidx/ink/brush/BrushNative",
                native = listOf("ignored" to "()V"),
            ),
        )

        assertEquals(
            listOf(
                NativeMethod("androidx/ink/brush/BrushNative", "create", "(JIFF)J"),
                NativeMethod("androidx/ink/brush/BrushNative", "free", "(J)V"),
            ),
            JarNatives.read(jar),
        )
    }

    private fun classFile(
        name: String,
        native: List<Pair<String, String>> = emptyList(),
        abstract: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        val writer = ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, name, null, "java/lang/Object", null)
        native.forEach { (method, descriptor) ->
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_NATIVE, method, descriptor, null, null).visitEnd()
        }
        abstract.forEach { (method, descriptor) ->
            writer.visitMethod(Opcodes.ACC_PUBLIC or Opcodes.ACC_ABSTRACT, method, descriptor, null, null).visitEnd()
        }
        writer.visitEnd()
        return writer.toByteArray()
    }

    private fun jar(vararg entries: Pair<String, ByteArray>): File =
        temporary.newFile("classes.jar").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                entries.forEach { (path, bytes) ->
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
        }
}
