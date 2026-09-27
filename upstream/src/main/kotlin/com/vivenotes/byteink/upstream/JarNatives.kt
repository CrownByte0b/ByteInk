package com.vivenotes.byteink.upstream

import java.io.File
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/** Finds the `native` methods compiled into a jar. */
internal object JarNatives {

    fun read(jar: File): List<NativeMethod> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.endsWith(".class") && !it.name.startsWith("META-INF/") }
            .flatMap { entry -> nativesOf(zip.getInputStream(entry).use { it.readBytes() }) }
            .toList()
    }

    fun nativesOf(classFile: ByteArray): List<NativeMethod> {
        val natives = mutableListOf<NativeMethod>()
        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            private lateinit var owner: String

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                owner = name
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (access and Opcodes.ACC_NATIVE != 0) natives += NativeMethod(owner, name, descriptor)
                return null
            }
        }
        ClassReader(classFile)
            .accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return natives
    }
}
