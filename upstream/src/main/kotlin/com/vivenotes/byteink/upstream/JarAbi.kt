package com.vivenotes.byteink.upstream

import java.io.File
import java.util.zip.ZipFile
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

/**
 * What class files in other packages can link against in a jar: each named class's kind,
 * supertypes, and public and protected fields and methods. Two jars with equal interfaces are
 * interchangeable for code compiled against either. Everything else, anonymous classes included, is
 * the implementation.
 */
internal object JarAbi {

    data class ClassAbi(
        val name: String,
        val access: Int,
        val superName: String?,
        val interfaces: List<String>,
        val members: Set<Member>,
    )

    data class Member(val kind: Kind, val name: String, val descriptor: String, val access: Int) {
        override fun toString(): String = "${kind.name.lowercase()} $name$descriptor (${flags(access)})"
    }

    enum class Kind { FIELD, METHOD }

    /** The flags that change how other classes may link against a class or member. */
    private const val CLASS_FLAGS = Opcodes.ACC_PUBLIC or Opcodes.ACC_FINAL or Opcodes.ACC_INTERFACE or
        Opcodes.ACC_ABSTRACT or Opcodes.ACC_ANNOTATION or Opcodes.ACC_ENUM
    private const val MEMBER_FLAGS = Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED or Opcodes.ACC_STATIC or
        Opcodes.ACC_FINAL or Opcodes.ACC_ABSTRACT or Opcodes.ACC_NATIVE or Opcodes.ACC_VARARGS

    /** The classes in [jar] whose names start with [prefix] (a package path such as `a/b/`). */
    fun read(jar: File, prefix: String): Map<String, ClassAbi> = ZipFile(jar).use { zip ->
        zip.entries().asSequence()
            .filter { !it.isDirectory && it.name.startsWith(prefix) && it.name.endsWith(".class") }
            .mapNotNull { entry -> of(zip.getInputStream(entry).use { it.readBytes() }) }
            .associateBy { it.name }
    }

    /** The class's interface, or null for an anonymous class. */
    fun of(classFile: ByteArray): ClassAbi? {
        var abi: ClassAbi? = null
        val members = mutableSetOf<Member>()
        var anonymous = false
        val visitor = object : ClassVisitor(Opcodes.ASM9) {
            private lateinit var name: String

            override fun visit(
                version: Int,
                access: Int,
                name: String,
                signature: String?,
                superName: String?,
                interfaces: Array<out String>?,
            ) {
                this.name = name
                abi = ClassAbi(name, access and CLASS_FLAGS, superName, interfaces?.toList().orEmpty(), members)
            }

            override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
                if (name == this.name && innerName == null) anonymous = true
            }

            override fun visitField(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                value: Any?,
            ): FieldVisitor? {
                if (linkable(access)) members += Member(Kind.FIELD, name, descriptor, access and MEMBER_FLAGS)
                return null
            }

            override fun visitMethod(
                access: Int,
                name: String,
                descriptor: String,
                signature: String?,
                exceptions: Array<out String>?,
            ): MethodVisitor? {
                if (linkable(access)) members += Member(Kind.METHOD, name, descriptor, access and MEMBER_FLAGS)
                return null
            }
        }
        ClassReader(classFile).accept(visitor, ClassReader.SKIP_CODE or ClassReader.SKIP_DEBUG or ClassReader.SKIP_FRAMES)
        return abi.takeUnless { anonymous }
    }

    /**
     * Public and protected members, synthetic ones included: callers elsewhere link to Kotlin's
     * `$default` methods, for example. Package-private ones serve only the package itself.
     */
    private fun linkable(access: Int): Boolean = access and (Opcodes.ACC_PUBLIC or Opcodes.ACC_PROTECTED) != 0

    /** How [candidate] differs from [reference], one line per class or member; empty when equal. */
    fun differences(reference: Map<String, ClassAbi>, candidate: Map<String, ClassAbi>): List<String> = buildList {
        for (name in (reference.keys + candidate.keys).sorted()) {
            val expected = reference[name]
            val actual = candidate[name]
            when {
                expected == null -> add("$name: only in the candidate")
                actual == null -> add("$name: missing")
                else -> {
                    if (expected.access != actual.access) {
                        add("$name: class flags ${flags(expected.access)} became ${flags(actual.access)}")
                    }
                    if (expected.superName != actual.superName || expected.interfaces != actual.interfaces) {
                        add("$name: supertypes ${expected.superName} ${expected.interfaces} became ${actual.superName} ${actual.interfaces}")
                    }
                    (expected.members - actual.members).sortedBy { it.toString() }.forEach { add("$name: missing $it") }
                    (actual.members - expected.members).sortedBy { it.toString() }.forEach { add("$name: only in the candidate: $it") }
                }
            }
        }
    }

    private fun flags(access: Int): String = listOfNotNull(
        "public".takeIf { access and Opcodes.ACC_PUBLIC != 0 },
        "protected".takeIf { access and Opcodes.ACC_PROTECTED != 0 },
        "static".takeIf { access and Opcodes.ACC_STATIC != 0 },
        "final".takeIf { access and Opcodes.ACC_FINAL != 0 },
        "abstract".takeIf { access and Opcodes.ACC_ABSTRACT != 0 },
        "native".takeIf { access and Opcodes.ACC_NATIVE != 0 },
        "varargs".takeIf { access and Opcodes.ACC_VARARGS != 0 },
        "interface".takeIf { access and Opcodes.ACC_INTERFACE != 0 },
        "annotation".takeIf { access and Opcodes.ACC_ANNOTATION != 0 },
        "enum".takeIf { access and Opcodes.ACC_ENUM != 0 },
    ).joinToString(" ").ifEmpty { "package-private" }
}

/**
 * `compare-abi`: fails unless the candidate jar's classes under a package offer exactly what the
 * reference jar's do, so either jar can stand in for the other.
 */
internal fun compareAbiCommand(arguments: Arguments): Int {
    val reference = File(arguments.required("--reference"))
    val candidate = File(arguments.required("--candidate"))
    val prefix = arguments.required("--package").replace('.', '/').trimEnd('/') + "/"
    val expected = JarAbi.read(reference, prefix)
    check(expected.isNotEmpty()) { "$reference has no classes under $prefix" }
    val differences = JarAbi.differences(expected, JarAbi.read(candidate, prefix))
    if (differences.isNotEmpty()) {
        throw CheckFailed(
            "$candidate does not offer what $reference does under $prefix:\n" +
                differences.joinToString("\n") { "- $it" },
        )
    }
    println("$candidate offers exactly what $reference does under $prefix: ${expected.size} classes")
    return 0
}
