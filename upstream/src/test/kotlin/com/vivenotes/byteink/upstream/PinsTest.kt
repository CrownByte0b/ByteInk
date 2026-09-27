package com.vivenotes.byteink.upstream

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PinsTest {

    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun theCheckedInPinsAreValid() {
        // Tests run in the project directory, next to the real file.
        val pins = Pins.read(File("pins.properties"))

        assertContains(pins.googleInkCandidates, pins.googleInkCommit)
        assertEquals(pins.googleInkCandidates.size, pins.googleInkCandidates.toSet().size)
    }

    @Test
    fun rejectsAbbreviatedOrUnlistedCommits() {
        assertFailsWith<IllegalArgumentException> { Pins.read(pins(commit = "96e50239e1c8")) }
        assertFailsWith<IllegalArgumentException> { Pins.read(pins(commit = "f".repeat(40))) }
        assertFailsWith<IllegalArgumentException> { Pins.read(pins().also { it.writeText(it.readText().replace("bazel.version=8.7.0\n", "")) }) }
    }

    private fun pins(commit: String = "a".repeat(40)): File = temporary.newFile().also {
        it.writeText(
            """
            androidx.support.commit=${"1".repeat(40)}
            androidx.support.repository=https://example.invalid/support.git
            google.ink.repository=https://example.invalid/ink.git
            google.ink.commit=$commit
            google.ink.candidates=${"a".repeat(40)}, ${"b".repeat(40)}
            bazel.version=8.7.0
            llvm.version=19.1.0

            """.trimIndent(),
        )
    }
}
