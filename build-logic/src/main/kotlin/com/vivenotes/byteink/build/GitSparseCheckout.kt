package com.vivenotes.byteink.build

import java.io.File
import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

/**
 * Checks out [paths] of [repository] at exactly [commit] into [directory]: a shallow, blobless,
 * sparse fetch of that one commit, so even a repository the size of AndroidX costs seconds.
 */
abstract class GitSparseCheckout @Inject constructor(private val exec: ExecOperations) : DefaultTask() {

    @get:Input
    abstract val repository: Property<String>

    @get:Input
    abstract val commit: Property<String>

    /** Directories to check out, relative to the repository root. */
    @get:Input
    abstract val paths: ListProperty<String>

    @get:OutputDirectory
    abstract val directory: DirectoryProperty

    @TaskAction
    fun checkout() {
        val target = directory.get().asFile
        target.deleteRecursively()
        target.mkdirs()
        // A hooks directory that does not exist: the developer's own hooks have no business here.
        val noHooks = File(temporaryDir, "no-hooks").path
        fun git(vararg arguments: String) {
            exec.exec {
                commandLine(listOf("git", "-C", target.path,
                    "-c", "core.hooksPath=$noHooks", "-c", "core.longpaths=true",
                    "-c", "core.autocrlf=false", "-c", "core.eol=lf") + arguments)
            }
        }
        git("init", "--quiet")
        git("remote", "add", "origin", repository.get())
        git("sparse-checkout", "set", "--cone", *paths.get().toTypedArray())
        git("fetch", "--quiet", "--depth", "1", "--filter=blob:none", "origin", commit.get())
        git("-c", "advice.detachedHead=false", "checkout", "--quiet", "--detach", "FETCH_HEAD")
    }
}
