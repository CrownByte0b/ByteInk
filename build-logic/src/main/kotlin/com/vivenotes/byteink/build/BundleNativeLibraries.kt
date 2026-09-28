package com.vivenotes.byteink.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Lays byteink's native libraries out as resources of the loader fork: one directory per platform
 * under [BUNDLE_BASE], and a manifest of their SHA-256 that the loader checks what it extracts
 * against. The loader (`NativeBundle`) reads exactly this layout.
 */
abstract class BundleNativeLibraries : DefaultTask() {

    /** Linux x86_64's `libink.so`. A missing file fails with advice, so it is not an `@InputFile`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linux: ConfigurableFileCollection

    /** Windows x86_64's `ink.dll`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val windows: ConfigurableFileCollection

    @get:Input
    abstract val googleInkCommit: Property<String>

    @get:OutputDirectory
    abstract val directory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val base = directory.get().asFile.resolve(BUNDLE_BASE)
        base.deleteRecursively()
        val manifest = mutableListOf("google.ink.commit=${googleInkCommit.get()}")
        for (library in listOf(
            Library("linux-x86_64", "libink.so", linux, "native/build-linux.sh", "byteinkLinuxLibrary"),
            Library("windows-x86_64", "ink.dll", windows, "native/build-windows.sh", "byteinkWindowsLibrary"),
        )) {
            val file = library.files.singleFile
            if (!file.isFile) {
                throw GradleException(
                    "$file does not exist: build it with ${library.script}, or name another with " +
                        "-P${library.property}=<file>",
                )
            }
            file.copyTo(base.resolve("${library.platform}/${library.fileName}"))
            manifest += "${library.platform}.sha256=${sha256(file.inputStream())}"
        }
        // Written by hand rather than with Properties.store, which adds the date.
        base.resolve(MANIFEST).writeText(
            "# The Ink native libraries in this jar, by platform, as byteink's build bundled them.\n" +
                manifest.sorted().joinToString("\n", postfix = "\n"),
        )
    }

    private class Library(
        val platform: String,
        val fileName: String,
        val files: ConfigurableFileCollection,
        val script: String,
        val property: String,
    )

    companion object {
        const val BUNDLE_BASE = "com/vivenotes/byteink/nativeloader"
        const val MANIFEST = "natives.properties"
    }
}
