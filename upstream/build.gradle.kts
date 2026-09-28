// Pins, the JNI surface they imply, and the tooling that proves both. See README.md here.

import com.vivenotes.byteink.build.MAX_GLIBC
import com.vivenotes.byteink.build.byteinkLinuxLibrary
import com.vivenotes.byteink.build.byteinkWindowsLibrary

plugins {
    id("byteink.kotlin-jvm")
    application
}

application {
    mainClass = "com.vivenotes.byteink.upstream.MainKt"
}

// The pinned AndroidX Ink JVM artifacts exactly as Google ships them: their classes declare the
// natives, and ink-nativeloader-jvm carries Google's own libink.so.
val inkJvm = configurations.dependencyScope("inkJvm")
val inkJvmJars = configurations.resolvable("inkJvmJars") {
    extendsFrom(inkJvm.get())
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}

dependencies {
    implementation(libs.asm)
    listOf(
        libs.androidx.ink.brush.jvm,
        libs.androidx.ink.geometry.jvm,
        libs.androidx.ink.nativeloader.jvm,
        libs.androidx.ink.storage.jvm,
        libs.androidx.ink.strokes.jvm,
    ).forEach { "inkJvm"(it) }
}

val inkVersion: String = libs.versions.androidx.ink.get()
val pinsFile: RegularFile = layout.projectDirectory.file("pins.properties")
val nativesFile: RegularFile = layout.projectDirectory.file("jni/androidx-ink-$inkVersion.natives.txt")
val exportsFile: RegularFile = layout.projectDirectory.file("jni/androidx-ink-$inkVersion.exports.txt")

/** The `surface` command over [jars]; everything is passed in so the configuration cache can store it. */
fun surfaceArguments(
    jars: FileCollection,
    version: String,
    natives: RegularFile,
    exports: RegularFile,
    check: Boolean,
) = CommandLineArgumentProvider {
    buildList {
        add("surface")
        if (check) add("--check")
        addAll(listOf("--version", version, "--natives", natives.asFile.path, "--exports", exports.asFile.path))
        jars.forEach { addAll(listOf("--jar", it.path)) }
    }
}

tasks.register<JavaExec>("jniSurface") {
    group = "upstream"
    description = "Regenerates jni/ from the pinned AndroidX Ink JVM artifacts."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    val jars: FileCollection = inkJvmJars.get()
    inputs.files(jars).withPropertyName("inkJars")
    outputs.files(nativesFile, exportsFile)
    argumentProviders.add(surfaceArguments(jars, inkVersion, nativesFile, exportsFile, check = false))
}

val verifyJniSurface = tasks.register<JavaExec>("verifyJniSurface") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that jni/ still describes the pinned AndroidX Ink JVM artifacts."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    val jars: FileCollection = inkJvmJars.get()
    inputs.files(jars).withPropertyName("inkJars")
    inputs.files(nativesFile, exportsFile).withPropertyName("surface")
    val marker = layout.buildDirectory.file("verifyJniSurface/ok")
    outputs.file(marker)
    argumentProviders.add(surfaceArguments(jars, inkVersion, nativesFile, exportsFile, check = true))
    doLast { marker.get().asFile.writeText("ok\n") }
}

tasks.check {
    dependsOn(verifyJniSurface)
}

// byteink's own native builds against the pinned surface and what shipping needs. They check what
// native/build-*.sh produced (or -PbyteinkLinuxLibrary / -PbyteinkWindowsLibrary), so check does
// not run them.
fun registerLibraryCheck(name: String, library: Provider<RegularFile>, command: List<String>) =
    tasks.register<JavaExec>(name) {
        group = "upstream"
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = application.mainClass
        inputs.file(library).withPropertyName("library")
        inputs.file(exportsFile).withPropertyName("exports")
        val marker = layout.buildDirectory.file("$name/ok")
        outputs.file(marker)
        val exports = exportsFile
        argumentProviders.add(CommandLineArgumentProvider {
            listOf(command.first(), "--binary", library.get().asFile.path, "--exports", exports.asFile.path) + command.drop(1)
        })
        doLast { marker.get().asFile.writeText("ok\n") }
    }

registerLibraryCheck("checkLinuxLibrary", byteinkLinuxLibrary(), listOf("check-linux-library", "--max-glibc", MAX_GLIBC))
    .configure { description = "Checks byteink's libink.so: the pinned JNI surface, glibc alone, nothing newer than $MAX_GLIBC." }
registerLibraryCheck("checkWindowsLibrary", byteinkWindowsLibrary(), listOf("check-windows-library"))
    .configure { description = "Checks byteink's ink.dll: x86-64, the pinned JNI surface, only libraries Windows provides." }

tasks.register<JavaExec>("scanGoogleInk") {
    group = "upstream"
    description = "Finds the google/ink commits whose JNI surface matches Google's pinned binary and " +
        "checks the native pin against them. Clones google/ink into build/ unless -PgoogleInkDir " +
        "names a clone; -PscanReferences=label=file,... adds reference lists."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = application.mainClass
    val projectDirectory = layout.projectDirectory
    val repository = providers.gradleProperty("googleInkDir").map { projectDirectory.file(it).asFile.path }
        .orElse(layout.buildDirectory.dir("google-ink").map { it.asFile.path })
    val extraReferences = providers.gradleProperty("scanReferences").orElse("")
    val report = layout.buildDirectory.file("reports/google-ink-jni-scan.md")
    val pinned = inkVersion
    val pins = pinsFile
    val exports = exportsFile
    inputs.files(pins, exports).withPropertyName("pins")
    outputs.file(report)
    // The answer depends on google/ink's history, which moves on without Gradle knowing.
    outputs.upToDateWhen { false }
    argumentProviders.add(CommandLineArgumentProvider {
        buildList {
            addAll(listOf("scan", "--repo", repository.get(), "--pins", pins.asFile.path))
            addAll(listOf("--pinned", pinned, "--reference", "$pinned=${exports.asFile.path}"))
            addAll(listOf("--report", report.get().asFile.path))
            extraReferences.get().split(',').map(String::trim).filter(String::isNotEmpty)
                .forEach { addAll(listOf("--reference", it)) }
        }
    })
}

tasks.test {
    // Tests read Google's artifacts, and create throwaway git repositories for the history scan.
    val jars: FileCollection = inkJvmJars.get()
    inputs.files(jars).withPropertyName("inkJars")
    val version = inkVersion
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dbyteink.upstream.inkJars=${jars.asPath}", "-Dbyteink.upstream.inkVersion=$version")
    })
}
