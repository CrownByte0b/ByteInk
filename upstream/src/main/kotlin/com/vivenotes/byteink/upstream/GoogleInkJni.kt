package com.vivenotes.byteink.upstream

/**
 * The JNI functions google/ink defines. Its JNI glue names every function through the macros in
 * `ink/jni/internal/jni_defines.h`:
 *
 * ```
 * JNI_METHOD(module, clazz, return_type, method_name)
 *     -> Java_androidx_ink_##module##_##clazz##_##method_name
 * JNI_METHOD_INNER(module, clazz, inner_clazz, return_type, method_name)
 *     -> Java_androidx_ink_##module##_##clazz##_00024##inner_clazz##_##method_name
 * ```
 */
internal object GoogleInkJni {

    private val macro = Regex("""\bJNI_METHOD(_INNER)?\s*\(([^()]*)\)""")
    private val identifier = Regex("[A-Za-z0-9_]+")

    /** Whether google/ink builds [path] into its JNI glue: `.cc` files in a `jni` directory, minus tests. */
    fun isJniSource(path: String): Boolean =
        path.startsWith("ink/") && path.endsWith(".cc") && !path.endsWith("_test.cc") && "/jni/" in path

    fun symbols(source: String): Set<String> =
        macro.findAll(codeOnly(source)).mapNotNull { match ->
            val arguments = match.groupValues[2].split(',').map(String::trim)
            val inner = match.groupValues[1].isNotEmpty()
            when {
                !inner && arguments.size == 4 -> listOf(arguments[0], arguments[1], arguments[3])
                inner && arguments.size == 5 ->
                    listOf(arguments[0], "${arguments[1]}_00024${arguments[2]}", arguments[4])
                else -> null
            }?.takeIf { parts -> parts.all(identifier::matches) }
                ?.let { (module, clazz, method) -> "Java_androidx_ink_${module}_${clazz}_$method" }
        }.toSet()

    /**
     * [source] without comments or preprocessor directives, and with string and character literals
     * emptied — so a commented-out function, a macro definition or a macro name quoted in a message
     * is not counted as a function.
     */
    internal fun codeOnly(source: String): String = buildString(source.length) {
        var index = 0
        var lineStart = true
        while (index < source.length) {
            val char = source[index]
            val next = source.getOrNull(index + 1)
            when {
                lineStart && char == '#' -> {
                    // Up to the end of the line, following backslash continuations.
                    while (index < source.length && !(source[index] == '\n' && !continued(source, index))) index++
                }
                char == '/' && next == '/' -> {
                    while (index < source.length && source[index] != '\n') index++
                }
                char == '/' && next == '*' -> {
                    val end = source.indexOf("*/", index + 2)
                    index = if (end < 0) source.length else end + 2
                    append(' ')
                }
                char == '"' || char == '\'' -> {
                    index++
                    while (index < source.length && source[index] != char) {
                        if (source[index] == '\\') index++
                        index++
                    }
                    index = minOf(index + 1, source.length)
                    append(char).append(char)
                    lineStart = false
                }
                else -> {
                    append(char)
                    index++
                    lineStart = char == '\n' || (lineStart && (char == ' ' || char == '\t'))
                }
            }
        }
    }

    /** Whether the line break at [newline] is escaped by a backslash (allowing CRLF). */
    private fun continued(source: String, newline: Int): Boolean {
        var before = newline - 1
        if (source.getOrNull(before) == '\r') before--
        return source.getOrNull(before) == '\\'
    }
}
