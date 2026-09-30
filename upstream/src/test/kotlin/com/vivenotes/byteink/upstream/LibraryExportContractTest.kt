package com.vivenotes.byteink.upstream

import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LibraryExportContractTest {
    @get:Rule val temporary = TemporaryFolder()
    private val upstream = setOf("Java_a_B_create", "Java_a_B_free")
    private val owned = "Java_com_vivenotes_byteink_vive_AndroidInkCompressionNative_gzip"

    @Test
    fun theBaselineContractAndProductionExtensionAreExplicitlyDistinct() {
        val exports = symbols("upstream", upstream)
        assertFailsWith<UsageError> { LibraryExportContract.read(exports, symbols("empty", emptySet())) }
        val extensions = symbols("extensions", setOf(owned))
        val baseline = binary("baseline", upstream)
        val production = binary("production", upstream + owned)
        listOf("check-linux-library" to ".so", "check-windows-library" to ".dll").forEach { (command, suffix) ->
            val args = listOf(command, "--binary", baseline.path + suffix, "--exports", exports.path) +
                if (suffix == ".so") listOf("--max-glibc", "2.28") else emptyList()
            assertEquals(0, run(Arguments(args)))
            assertFailsWith<CheckFailed> { run(Arguments(args + listOf("--extensions", extensions.path))) }
            val productionArgs = args.map { if (it == baseline.path + suffix) production.path + suffix else it }
            assertFailsWith<CheckFailed> { run(Arguments(productionArgs)) }
            assertEquals(0, run(Arguments(productionArgs + listOf("--extensions", extensions.path))))
        }
    }

    @Test
    fun anUndeclaredOwnedSymbolIsRejectedByBothPlatforms() {
        val exports = symbols("upstream", upstream)
        val extensions = symbols("extensions", setOf(owned))
        val extra = "Java_com_vivenotes_byteink_unreviewed_extra"
        val production = binary("production", upstream + owned + extra)
        assertFailsWith<CheckFailed> { run(Arguments(listOf("check-linux-library", "--binary", production.path + ".so",
            "--exports", exports.path, "--extensions", extensions.path, "--max-glibc", "2.28"))) }
        assertFailsWith<CheckFailed> { run(Arguments(listOf("check-windows-library", "--binary", production.path + ".dll",
            "--exports", exports.path, "--extensions", extensions.path))) }
    }

    @Test
    fun extensionsCannotRelaxTheAndroidxContractOrDuplicateIt() {
        val exports = symbols("upstream", upstream)
        assertFailsWith<UsageError> { LibraryExportContract.read(exports, symbols("not-owned", setOf("Java_androidx_ink_extra"))) }
        assertFailsWith<UsageError> { LibraryExportContract.read(exports, symbols("not-jni", setOf("absl_internal"))) }
        assertFailsWith<UsageError> { LibraryExportContract.read(symbols("owned-upstream", upstream + owned), symbols("duplicate", setOf(owned))) }
    }

    private fun symbols(name: String, values: Set<String>): File = temporary.newFile("$name.txt").apply {
        writeText(SymbolList.render(listOf("Synthetic export contract"), values))
    }

    private fun binary(name: String, values: Set<String>): File {
        File(temporary.root, "$name.so").writeBytes(TestElf(symbols = values.map { TestElf.Symbol(it) }).bytes())
        File(temporary.root, "$name.dll").writeBytes(TestPe(exports = values.toList()).bytes())
        return File(temporary.root, name)
    }
}
