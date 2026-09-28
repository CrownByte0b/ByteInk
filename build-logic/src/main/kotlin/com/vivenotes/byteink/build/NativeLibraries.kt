package com.vivenotes.byteink.build

import java.util.Properties
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.kotlin.dsl.register

/** Where upstream's JVM loader looks for the Linux library among classpath resources. */
const val LINUX_LIBRARY = "linux-x86_64/libink.so"

/** Where native/build-windows.sh puts the Windows library, beside the Linux one. */
const val WINDOWS_LIBRARY = "windows-x86_64/ink.dll"

/**
 * The newest glibc byteink's libink.so may need: the manylinux_2_28 baseline, which Debian 10,
 * Ubuntu 18.10, RHEL 8 and their successors meet.
 */
const val MAX_GLIBC = "2.28"

/** upstream/pins.properties. */
fun Project.upstreamPins(): Provider<Properties> = providers
    .fileContents(rootProject.layout.projectDirectory.file("upstream/pins.properties")).asText
    .map { text -> Properties().apply { load(text.reader()) } }

/**
 * byteink's own Linux libink.so: the file named by -PbyteinkLinuxLibrary, or what
 * native/build-linux.sh built for the pinned google/ink commit.
 */
fun Project.byteinkLinuxLibrary(): Provider<RegularFile> = byteinkLibrary("byteinkLinuxLibrary", LINUX_LIBRARY)

/**
 * byteink's own Windows ink.dll: the file named by -PbyteinkWindowsLibrary, or what
 * native/build-windows.sh built for the pinned google/ink commit.
 */
fun Project.byteinkWindowsLibrary(): Provider<RegularFile> = byteinkLibrary("byteinkWindowsLibrary", WINDOWS_LIBRARY)

private fun Project.byteinkLibrary(property: String, path: String): Provider<RegularFile> {
    val root = rootProject.layout.projectDirectory
    return providers.gradleProperty(property).map { root.file(it) }
        .orElse(upstreamPins().map { root.file("native/build/out/${it.getProperty("google.ink.commit")}/$path") })
}

/**
 * Lays [library] out as the classpath resource upstream's loader looks for. Put first on a
 * classpath, the directory makes that loader pick [library] over the one in Google's jar.
 */
fun Project.registerNativesDirectory(name: String, library: Provider<RegularFile>): TaskProvider<Sync> =
    tasks.register<Sync>(name) {
        description = "Lays byteink's libink.so out as a classpath resource, where the upstream loader looks."
        from(library) { into(LINUX_LIBRARY.substringBefore('/')) }
        into(layout.buildDirectory.dir(name))
    }
