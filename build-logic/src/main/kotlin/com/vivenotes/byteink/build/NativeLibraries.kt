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

/**
 * A platform byteink ships an Ink native library for, as the loader fork's `Platform` names it, with
 * the Gradle property that names another build of it and the script that builds it.
 */
enum class NativePlatform(val id: String, val fileName: String, val property: String, val script: String) {
    LINUX_X86_64("linux-x86_64", "libink.so", "byteinkLinuxLibrary", "native/build-linux.sh"),
    LINUX_ARM64("linux-arm64", "libink.so", "byteinkLinuxArm64Library", "native/build-linux-arm64.sh"),
    WINDOWS_X86_64("windows-x86_64", "ink.dll", "byteinkWindowsLibrary", "native/build-windows.sh"),
    WINDOWS_ARM64("windows-arm64", "ink.dll", "byteinkWindowsArm64Library", "native/build-windows.sh arm64"),
    ;

    /** The library's place under native/build/out/<commit>/ and in the loader jar's bundle. */
    val path: String get() = "$id/$fileName"

    val windows: Boolean get() = id.startsWith("windows-")

    companion object {
        fun byId(id: String): NativePlatform = entries.singleOrNull { it.id == id }
            ?: throw GradleException("Unknown native platform \"$id\"; byteink ships ${entries.joinToString { it.id }}")

        /** The platform of a JVM whose `os.name` and `os.arch` are these, as the loader decides it. */
        fun of(osName: String, osArch: String): NativePlatform? {
            val os = osName.lowercase()
            val arch = osArch.lowercase()
            val x8664 = arch in setOf("amd64", "x86_64")
            val arm64 = arch in setOf("aarch64", "arm64")
            return when {
                os.startsWith("linux") && x8664 -> LINUX_X86_64
                os.startsWith("linux") && arm64 -> LINUX_ARM64
                os.startsWith("windows") && x8664 -> WINDOWS_X86_64
                os.startsWith("windows") && arm64 -> WINDOWS_ARM64
                else -> null
            }
        }
    }
}

/** Where Google's `ink-nativeloader-jvm` jar keeps its Linux library: x86_64 only. */
const val LINUX_LIBRARY = "linux-x86_64/libink.so"

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
 * byteink's own library for [platform]: the file named by the platform's property (for example
 * -PbyteinkLinuxArm64Library), or what its script built for the pinned google/ink commit.
 */
fun Project.byteinkLibrary(platform: NativePlatform): Provider<RegularFile> {
    val root = rootProject.layout.projectDirectory
    return providers.gradleProperty(platform.property).map { root.file(it) }
        .orElse(upstreamPins().map { root.file("native/build/out/${it.getProperty("google.ink.commit")}/${platform.path}") })
}

/** byteink's own Linux x86_64 libink.so. */
fun Project.byteinkLinuxLibrary(): Provider<RegularFile> = byteinkLibrary(NativePlatform.LINUX_X86_64)

/** byteink's own Windows x86_64 ink.dll. */
fun Project.byteinkWindowsLibrary(): Provider<RegularFile> = byteinkLibrary(NativePlatform.WINDOWS_X86_64)

/**
 * The platform whose library tests and the oracle exercise: the build JVM's, or the one
 * -PbyteinkHostPlatform names when the test JVM differs, as when an arm64 JDK runs under emulation.
 */
val Project.hostPlatform: NativePlatform
    get() = providers.gradleProperty("byteinkHostPlatform").orNull?.let(NativePlatform::byId)
        ?: NativePlatform.of(System.getProperty("os.name"), System.getProperty("os.arch"))
        ?: error("byteink supports ${NativePlatform.entries.joinToString { it.id }}, not " +
            "${System.getProperty("os.name")} ${System.getProperty("os.arch")}")

/** The binary tests and the oracle must exercise on the build host. */
fun Project.byteinkHostLibrary(): Provider<RegularFile> = byteinkLibrary(hostPlatform)

/**
 * Compose's desktop runtime (with Skiko's natives) for [hostPlatform], as tests need it: what
 * `compose.desktop.currentOs` names on that platform, so it follows -PbyteinkHostPlatform too.
 */
fun Project.composeDesktopRuntime(composeVersion: String): String {
    val os = if (hostPlatform.windows) "windows" else "linux"
    val arch = if (hostPlatform.id.endsWith("-arm64")) "arm64" else "x64"
    return "org.jetbrains.compose.desktop:desktop-jvm-$os-$arch:$composeVersion"
}

/**
 * Whether this host runs Google's own libink.so, which Google ships for Linux x86_64 only (besides
 * macOS arm64), and the x86_64 platform-math oracle baseline built to compare with it.
 */
val Project.runsGoogleLinuxBinary: Boolean
    get() = hostPlatform == NativePlatform.LINUX_X86_64

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
