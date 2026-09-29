package com.vivenotes.byteink.build

import java.util.Properties
import java.util.zip.ZipFile
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register

/** The Linux library's place under native/build/out/<commit>/, and in Google's loader jar. */
const val LINUX_LIBRARY = "linux-x86_64/libink.so"

/** The Windows library's place under native/build/out/<commit>/. */
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

/** Google's own `ink-nativeloader-jvm` jar for the pinned release, which carries its `libink.so`. */
fun Project.googleNativeLoaderJar(): FileCollection {
    val name = "googleNativeLoader"
    if (name !in configurations.names) {
        val scope = configurations.dependencyScope(name)
        configurations.resolvable("${name}Jar") {
            extendsFrom(scope.get())
            isTransitive = false
            attributes {
                attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
                attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
                attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            }
        }
        val catalog = extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies.addProvider(name, catalog.findLibrary("androidx-ink-nativeloader-jvm").get())
    }
    return files(configurations.named("${name}Jar"))
}

/**
 * Google's own `linux-x86_64/libink.so` for the pinned release, taken out of its
 * `ink-nativeloader-jvm` jar: the reference that byteink's library is compared with.
 */
fun Project.googleLinuxLibrary(): Provider<RegularFile> {
    val jar = googleNativeLoaderJar()
    return tasks.register<ExtractJarEntry>("googleLinuxLibrary") {
        description = "Extracts Google's linux-x86_64 libink.so from its ink-nativeloader-jvm jar."
        this.jar.from(jar)
        entry.set(LINUX_LIBRARY)
        file.set(layout.buildDirectory.file("google/$LINUX_LIBRARY"))
    }.flatMap { it.file }
}

/** Copies one entry out of a jar. */
abstract class ExtractJarEntry : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val jar: ConfigurableFileCollection

    @get:Input
    abstract val entry: Property<String>

    @get:OutputFile
    abstract val file: RegularFileProperty

    @TaskAction
    fun extract() {
        ZipFile(jar.singleFile).use { zip ->
            val found = zip.getEntry(entry.get()) ?: throw GradleException("${jar.singleFile} has no ${entry.get()}")
            zip.getInputStream(found).use { input -> file.get().asFile.outputStream().use { input.copyTo(it) } }
        }
    }
}

