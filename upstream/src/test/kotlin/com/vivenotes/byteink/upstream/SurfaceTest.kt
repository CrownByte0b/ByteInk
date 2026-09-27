package com.vivenotes.byteink.upstream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Against the pinned AndroidX Ink JVM artifacts, which the build hands to the test JVM. */
class SurfaceTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val version = System.getProperty("byteink.upstream.inkVersion")
    private val jars = System.getProperty("byteink.upstream.inkJars").split(File.pathSeparator).map(::File)

    @Test
    fun googlesBinaryExportsEveryNativeTheJarsDeclare() {
        val surface = Surface.read(version, jars)

        assertTrue(surface.natives.size > 100, "${surface.natives.size} natives")
        assertEquals(emptySet(), surface.natives - surface.exports)
        // Upstream never overloads a native, so each binds by its short name.
        assertTrue(surface.natives.none { "__" in it })
    }

    @Test
    fun checkPassesOnFreshListsAndNamesWhatChanged() {
        val natives = File(temporary.root, "natives.txt")
        val exports = File(temporary.root, "exports.txt")
        fun surface(vararg options: String): Int = surfaceCommand(
            Arguments(
                listOf("surface", "--version", version, "--natives", natives.path, "--exports", exports.path) +
                    jars.flatMap { listOf("--jar", it.path) } + options,
            ),
        )

        assertEquals(0, surface())
        assertEquals(0, surface("--check"))

        val lines = natives.readLines()
        natives.writeText(lines.dropLast(1).joinToString("\n", postfix = "\n"))
        val failure = assertFailsWith<CheckFailed> { surface("--check") }
        assertContains(failure.message.orEmpty(), "missing (1):\n    ${lines.last()}")
    }
}
