package com.vivenotes.byteink.build

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * What a fork's upstream sources must be: the upstream module's [sourceSets] at the pinned commit,
 * with the fork's [patches] applied in file-name order.
 */
abstract class PatchedSources @Inject constructor(
    private val exec: ExecOperations,
    private val files: FileSystemOperations,
) : DefaultTask() {

    /** The upstream module's `src` directory, checked out at the pinned commit. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val upstream: DirectoryProperty

    @get:Input
    abstract val sourceSets: ListProperty<String>

    /** Unified diffs with paths relative to `src`, such as `a/jvmMain/kotlin/…`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val patches: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val directory: DirectoryProperty

    @TaskAction
    fun patch() {
        val target = directory.get().asFile
        val kept = sourceSets.get()
        files.sync {
            from(upstream) { kept.forEach { include("$it/**") } }
            into(target)
        }
        for (patch in patches.files.sortedBy { it.name }) {
            exec.exec {
                workingDir = target
                // Stop git from finding the repository around the build directory: the patch's paths
                // are relative to this directory, not to that repository's root.
                environment("GIT_CEILING_DIRECTORIES", target.parentFile.path)
                commandLine("git", "apply", patch.path)
            }
        }
    }
}

/**
 * Fails unless a fork's upstream sources — everything in its [sourceSets] outside byteink's own
 * package — are exactly [expected], byte for byte.
 */
abstract class VerifyForkSources : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expected: DirectoryProperty

    /** The fork's `src` directory. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fork: DirectoryProperty

    @get:Input
    abstract val sourceSets: ListProperty<String>

    /** The package path byteink's own sources use, which upstream's do not: `com/vivenotes/byteink`. */
    @get:Input
    abstract val ownPackage: Property<String>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun verify() {
        val expectedRoot = expected.get().asFile
        val forkRoot = fork.get().asFile
        val kept = sourceSets.get()
        val own = ownPackage.get()
        val expectedFiles = relativeFiles(expectedRoot)
        val forkFiles = relativeFiles(forkRoot).filterTo(sortedSetOf()) { path ->
            kept.any { path.startsWith("$it/") } && "/$own/" !in "/$path"
        }
        val problems = buildList {
            (expectedFiles - forkFiles).forEach { add("missing: $it") }
            (forkFiles - expectedFiles).forEach { add("not upstream's: $it") }
            (expectedFiles intersect forkFiles)
                .filterNot { expectedRoot.resolve(it).readBytes().contentEquals(forkRoot.resolve(it).readBytes()) }
                .forEach { add("differs: $it") }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "The fork's upstream sources are not upstream's with patches/ applied:\n" +
                    problems.joinToString("\n") { "- $it" } +
                    "\nMove a change into patches/, or run syncForkSources to rewrite them (see PATCHES.md).",
            )
        }
        marker.get().asFile.writeText("ok\n")
    }

    private fun relativeFiles(root: File) = root.walk().filter { it.isFile }
        .map { it.relativeTo(root).invariantSeparatorsPath }
        .toSortedSet()
}

/**
 * Rewrites a fork's upstream sources as [expected] has them, leaving byteink's own package alone:
 * the step after moving the pinned upstream commit or changing a patch.
 */
abstract class SyncForkSources @Inject constructor(private val files: FileSystemOperations) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val expected: DirectoryProperty

    /** The fork's `src` directory. */
    @get:Internal
    abstract val fork: DirectoryProperty

    @get:Input
    abstract val sourceSets: ListProperty<String>

    @get:Input
    abstract val ownPackage: Property<String>

    init {
        // It edits source files; nothing about it is up to date or cacheable.
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun sync() {
        val root = fork.get().asFile
        val own = ownPackage.get()
        val stale = sourceSets.get().flatMap { set ->
            root.resolve(set).walk().filter { it.isFile && "/$own/" !in it.invariantSeparatorsPath }.toList()
        }
        files.delete { delete(stale) }
        files.copy {
            from(expected)
            into(root)
        }
    }
}
