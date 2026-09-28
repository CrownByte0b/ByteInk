package com.vivenotes.byteink.upstream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes.ACC_FINAL
import org.objectweb.asm.Opcodes.ACC_NATIVE
import org.objectweb.asm.Opcodes.ACC_PRIVATE
import org.objectweb.asm.Opcodes.ACC_PUBLIC
import org.objectweb.asm.Opcodes.ACC_STATIC
import org.objectweb.asm.Opcodes.ACC_SUPER
import org.objectweb.asm.Opcodes.ACC_SYNTHETIC
import org.objectweb.asm.Opcodes.V11

class JarAbiTest {

    private val loader = "a/b/Loader"

    @Test
    fun onlyWhatOtherPackagesLinkAgainstCounts() {
        val abi = JarAbi.of(
            classFile(
                loader,
                "load" to ("()V" to (ACC_PUBLIC or ACC_FINAL)),
                "helper\$default" to ("(ILjava/lang/Object;)V" to (ACC_PUBLIC or ACC_STATIC or ACC_SYNTHETIC)),
                "loaded" to ("()Z" to ACC_PRIVATE),
                "access\$get" to ("()Z" to (ACC_STATIC or ACC_SYNTHETIC)),
            ),
        )!!

        assertEquals(
            setOf("load()V", "helper\$default(ILjava/lang/Object;)V"),
            abi.members.map { it.name + it.descriptor }.toSet(),
        )
    }

    @Test
    fun anonymousClassesAreImplementation() {
        val lambda = ClassWriter(0).apply {
            visit(V11, ACC_FINAL or ACC_SUPER, "$loader\$load\$1", null, "java/lang/Object", null)
            visitInnerClass("$loader\$load\$1", null, null, ACC_FINAL)
            visitEnd()
        }.toByteArray()

        assertNull(JarAbi.of(lambda))
    }

    @Test
    fun namesEveryDifference() {
        val reference = mapOf(
            loader to JarAbi.of(
                classFile(loader, "load" to ("()V" to (ACC_PUBLIC or ACC_FINAL)), "free" to ("(J)V" to (ACC_PUBLIC or ACC_NATIVE))),
            )!!,
            "a/b/Gone" to JarAbi.of(classFile("a/b/Gone"))!!,
        )
        val candidate = mapOf(
            loader to JarAbi.of(
                classFile(loader, "load" to ("()V" to ACC_PUBLIC), "free" to ("(J)V" to (ACC_PUBLIC or ACC_NATIVE))),
            )!!,
            "a/b/Extra" to JarAbi.of(classFile("a/b/Extra"))!!,
        )

        assertEquals(
            listOf(
                "a/b/Extra: only in the candidate",
                "a/b/Gone: missing",
                "a/b/Loader: missing method load()V (public final)",
                "a/b/Loader: only in the candidate: method load()V (public)",
            ),
            JarAbi.differences(reference, candidate),
        )
        assertEquals(emptyList(), JarAbi.differences(reference, reference))
    }

    private fun classFile(name: String, vararg methods: Pair<String, Pair<String, Int>>): ByteArray =
        ClassWriter(0).apply {
            visit(V11, ACC_PUBLIC or ACC_FINAL or ACC_SUPER, name, null, "java/lang/Object", null)
            for ((method, shape) in methods) visitMethod(shape.second, method, shape.first, null, null).visitEnd()
            visitEnd()
        }.toByteArray()
}
