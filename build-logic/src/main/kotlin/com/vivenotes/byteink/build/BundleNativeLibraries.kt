package com.vivenotes.byteink.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
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

    /** Linux arm64's `libink.so`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val linuxArm64: ConfigurableFileCollection

    /** Windows x86_64's `ink.dll`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val windows: ConfigurableFileCollection

    /** Windows arm64's `ink.dll`. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val windowsArm64: ConfigurableFileCollection

    @get:Input
    abstract val googleInkCommit: Property<String>

    /**
     * The platforms to bundle, by id (`linux-x86_64`, `linux-arm64`, `windows-x86_64`,
     * `windows-arm64`): all four for the published jar. A package for one platform, such as a Linux
     * Flatpak, bundles only its own and needs no other.
     */
    @get:Input
    abstract val platforms: SetProperty<String>

    @get:OutputDirectory
    abstract val directory: DirectoryProperty

    @TaskAction
    fun bundle() {
        val base = directory.get().asFile.resolve(BUNDLE_BASE)
        base.deleteRecursively()
        val manifest = mutableListOf("google.ink.commit=${googleInkCommit.get()}")
        val libraries = mapOf(
            NativePlatform.LINUX_X86_64 to linux,
            NativePlatform.LINUX_ARM64 to linuxArm64,
            NativePlatform.WINDOWS_X86_64 to windows,
            NativePlatform.WINDOWS_ARM64 to windowsArm64,
        )
        val selected = platforms.get().map(NativePlatform::byId).toSet()
        for ((platform, files) in libraries.filterKeys { it in selected }) {
            val file = files.singleFile
            if (!file.isFile) {
                throw GradleException(
                    "$file does not exist: build it with ${platform.script}, or name another with " +
                        "-P${platform.property}=<file>, or bundle fewer platforms with " +
                        "-PbyteinkBundledPlatforms=<ids>",
                )
            }
            file.copyTo(base.resolve(platform.path))
            manifest += "${platform.id}.sha256=${sha256(file.inputStream())}"
        }
        // Written by hand rather than with Properties.store, which adds the date.
        base.resolve(MANIFEST).writeText(
            "# The Ink native libraries in this jar, by platform, as byteink's build bundled them.\n" +
                manifest.sorted().joinToString("\n", postfix = "\n"),
        )
    }

    companion object {
        const val BUNDLE_BASE = "com/vivenotes/byteink/nativeloader"
        const val MANIFEST = "natives.properties"
    }
}
