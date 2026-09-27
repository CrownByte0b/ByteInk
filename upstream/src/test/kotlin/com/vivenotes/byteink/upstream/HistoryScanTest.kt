package com.vivenotes.byteink.upstream

import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class HistoryScanTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private lateinit var repository: File
    private lateinit var isolatedConfig: File
    private var clock = 1_760_000_000L

    private val create = "Java_androidx_ink_brush_BrushNative_create"
    private val free = "Java_androidx_ink_brush_BrushNative_free"
    private val vertexCount = "Java_androidx_ink_geometry_MeshNative_getVertexCount"
    private val release = Reference("release", setOf(create, free, vertexCount))

    @BeforeTest
    fun createRepository() {
        repository = temporary.newFolder("google-ink")
        isolatedConfig = temporary.newFile("gitconfig")
        git("init", "--quiet", "--initial-branch=main")
    }

    @Test
    fun comparesEachMainlineCommitWithTheReference() {
        val brush = "ink/brush/internal/jni/brush_jni.cc"
        val first = commit(
            brush to glue("brush", "BrushNative", "create", "free"),
            // Outside a jni directory: not glue, whatever it contains.
            "ink/brush/brush.cc" to glue("brush", "BrushNative", "notGlue"),
        )
        val second = commit("ink/geometry/internal/jni/mesh_jni.cc" to glue("geometry", "MeshNative", "getVertexCount"))
        git("switch", "--quiet", "-c", "side")
        commit("ink/geometry/internal/jni/extra_jni.cc" to glue("geometry", "MeshNative", "sideOnly"))
        git("switch", "--quiet", "main")
        val third = commit("ink/geometry/internal/jni/mesh_jni_test.cc" to glue("geometry", "MeshNative", "testOnly"))
        clock += DAY
        git("merge", "--quiet", "--no-ff", "--no-verify", "-m", "Merge side", "side")
        val merge = git("rev-parse", "HEAD")
        val last = commit(
            brush to glue("brush", "BrushNative", "create", "release"),
            "ink/geometry/internal/jni/extra_jni.cc" to null,
        )

        val commits = HistoryScan(Git(repository)).scan("HEAD")

        // First-parent history: the merge, not the side branch's own commit.
        assertEquals(listOf(first, second, third, merge, last), commits.map(ScannedCommit::id))
        assertEquals(
            listOf("-1 +0", "exact", "exact", "-0 +1", "-1 +1"),
            commits.map { Match.of(it, release).toString() },
        )
        assertContains(
            ScanReport(commits, listOf(release)).exactMatches(),
            // The side branch's commit took 2025-10-12.
            "`${second.take(12)}` 2025-10-11 … `${third.take(12)}` 2025-10-13 (2 commits)",
        )
    }

    @Test
    fun theScanCommandHoldsEveryCandidateToThePinnedReference() {
        val before = commit("ink/brush/internal/jni/brush_jni.cc" to glue("brush", "BrushNative", "create", "free"))
        val matching = commit("ink/geometry/internal/jni/mesh_jni.cc" to glue("geometry", "MeshNative", "getVertexCount"))
        val referenceFile = File(temporary.root, "release.txt")
            .apply { writeText(SymbolList.render(listOf("test reference"), release.symbols)) }
        val report = File(temporary.root, "report.md")
        fun scan(pinned: String, candidates: List<String>): Int = scanCommand(
            Arguments(
                listOf(
                    "scan", "--repo", repository.path, "--pins", pins(pinned, candidates).path,
                    "--pinned", "release", "--reference", "release=${referenceFile.path}",
                    "--rev", "HEAD", "--report", report.path, "--offline",
                ),
            ),
        )

        assertEquals(0, scan(matching, listOf(matching)))
        assertContains(report.readText(), "| `${matching.take(12)}` 2025-10-11 | google.ink.commit | exact |")

        val failure = assertFailsWith<CheckFailed> { scan(matching, listOf(before, matching)) }
        assertContains(failure.message.orEmpty(), "`${before.take(12)}` 2025-10-10 does not match release: -1 +0")
    }

    private fun glue(module: String, clazz: String, vararg methods: String): String =
        methods.joinToString("\n") { "JNI_METHOD($module, $clazz, void, $it)(JNIEnv*, jobject) {}" }

    /** Writes (or, for null contents, deletes) files and commits them a day after the last commit. */
    private fun commit(vararg files: Pair<String, String?>): String {
        files.forEach { (path, contents) ->
            val file = File(repository, path)
            if (contents == null) {
                file.delete()
            } else {
                file.parentFile.mkdirs()
                file.writeText(contents)
            }
        }
        git("add", "--all")
        clock += DAY
        git("commit", "--quiet", "--no-verify", "-m", "Change ${files.first().first}")
        return git("rev-parse", "HEAD")
    }

    private fun pins(commit: String, candidates: List<String>): File = temporary.newFile().also {
        it.writeText(
            """
            androidx.support.commit=${"1".repeat(40)}
            androidx.support.repository=https://example.invalid/support.git
            google.ink.repository=https://example.invalid/ink.git
            google.ink.commit=$commit
            google.ink.candidates=${candidates.joinToString(",")}
            bazel.version=8.7.0
            llvm.version=19.1.0
            """.trimIndent(),
        )
    }

    private fun git(vararg arguments: String): String {
        val process = ProcessBuilder(listOf("git", "-C", repository.path) + arguments)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    // Keep the developer's own configuration — signing, hooks, templates — out of the fixture.
                    put("GIT_CONFIG_GLOBAL", isolatedConfig.path)
                    put("GIT_CONFIG_NOSYSTEM", "1")
                    put("GIT_AUTHOR_NAME", "Fixture")
                    put("GIT_AUTHOR_EMAIL", "fixture@example.invalid")
                    put("GIT_COMMITTER_NAME", "Fixture")
                    put("GIT_COMMITTER_EMAIL", "fixture@example.invalid")
                    put("GIT_AUTHOR_DATE", "@$clock +0000")
                    put("GIT_COMMITTER_DATE", "@$clock +0000")
                }
            }
            .start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "git ${arguments.joinToString(" ")} failed: $output" }
        return output.trim()
    }

    private companion object {
        const val DAY = 86_400L
    }
}
