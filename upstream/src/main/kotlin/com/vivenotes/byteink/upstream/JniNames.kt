package com.vivenotes.byteink.upstream

import java.util.SortedSet

/** A method a class file declares `native`, named as the JVM names it: `a/b/Outer$Inner`, `name`, `(J)V`. */
internal data class NativeMethod(val className: String, val name: String, val descriptor: String)

/**
 * The symbol names the JVM looks up for native methods, per the JNI specification's "Resolving
 * Native Method Names".
 */
internal object JniNames {

    /**
     * The names a native library has to export for [methods]. The JVM tries the short name first,
     * so a method sharing its name with another native in the same class can only bind to its long
     * name — exporting the short one would bind every overload to the same function.
     */
    fun of(methods: Collection<NativeMethod>): SortedSet<String> {
        val overloaded = methods.groupingBy { it.className to it.name }.eachCount()
            .filterValues { it > 1 }.keys
        return methods.mapTo(sortedSetOf()) { method ->
            if (method.className to method.name in overloaded) longName(method) else shortName(method)
        }
    }

    fun shortName(method: NativeMethod): String =
        "Java_${mangle(method.className)}_${mangle(method.name)}"

    fun longName(method: NativeMethod): String {
        val arguments = method.descriptor.substring(1, method.descriptor.indexOf(')'))
        return "${shortName(method)}__${mangle(arguments)}"
    }

    /** Escapes a class name, method name or argument signature into a C identifier. */
    fun mangle(name: String): String = buildString {
        for (char in name) {
            when (char) {
                '/' -> append('_')
                '_' -> append("_1")
                ';' -> append("_2")
                '[' -> append("_3")
                in 'a'..'z', in 'A'..'Z', in '0'..'9' -> append(char)
                else -> append("_0").append("%04x".format(char.code))
            }
        }
    }
}
