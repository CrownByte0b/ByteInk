// The narrow fork of androidx.ink:ink-nativeloader for the JVM. The pinned release's sources are
// copied verbatim from AndroidX; only NativeLoader.jvm.kt is patched, to load the native libraries
// this jar bundles (see PATCHES.md). Google's other Ink JVM jars bind to these classes unchanged.

import com.vivenotes.byteink.build.BundleNativeLibraries
import com.vivenotes.byteink.build.GitSparseCheckout
import com.vivenotes.byteink.build.PatchedSources
import com.vivenotes.byteink.build.SyncForkSources
import com.vivenotes.byteink.build.VerifyForkSources
import com.vivenotes.byteink.build.byteinkLinuxLibrary
import com.vivenotes.byteink.build.byteinkWindowsLibrary
import com.vivenotes.byteink.build.bytecodeTarget
import com.vivenotes.byteink.build.googleNativeLoaderJar
import com.vivenotes.byteink.build.registerTestsOnJdk
import com.vivenotes.byteink.build.upstreamPins

plugins {
    id("byteink.kotlin-multiplatform-jvm")
    id("byteink.publishing")
}

// Versioned after the upstream release it forks (byteink-nativeloader in the catalog).
version = libs.versions.byteink.nativeloader.get()
check(version.toString().startsWith("${libs.versions.androidx.ink.get()}-byteink.")) {
    "byteink-nativeloader ($version) must be androidx-ink's version followed by -byteink.<N>"
}

val bundledNatives = tasks.register<BundleNativeLibraries>("bundleNativeLibraries") {
    description = "Lays byteink's native libraries and their manifest out as resources of this jar."
    linux.from(byteinkLinuxLibrary())
    windows.from(byteinkWindowsLibrary())
    googleInkCommit = upstreamPins().map { it.getProperty("google.ink.commit") }
    directory = layout.buildDirectory.dir("natives")
}

kotlin {
    explicitApi()
    compilerOptions {
        // Upstream declares expect classes and objects, still Beta in Kotlin.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    jvm().compilations.named("main") {
        // Upstream's module name, which Kotlin writes into the jar and into internal members' names.
        compileTaskProvider.configure { compilerOptions.moduleName = "ink-nativeloader" }
    }
    sourceSets {
        commonMain.dependencies {
            // What Google's ink-nativeloader-jvm declares at run time.
            implementation(libs.androidx.annotation)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain {
            // Upstream's actuals for the JVM and Android, without its intermediate source set: only
            // the JVM is built here.
            kotlin.srcDir("src/jvmAndAndroidMain/kotlin")
            resources.srcDir(bundledNatives.flatMap { it.directory })
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The oldest JDK byteink supports runs the tests too; Gradle's own JDK runs jvmTest.
val jvmTestOnOldestJdk = registerTestsOnJdk("jvmTestJdk$bytecodeTarget", tasks.named<Test>("jvmTest"), bytecodeTarget.toInt())

tasks.withType<Test>().configureEach {
    val probeClasspath = classpath
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dbyteink.test.loaderClasspath=${probeClasspath.asPath}")
    })
}

// The fork's upstream sources are the pinned commit's, with patches/ applied. verifyForkSources
// checks that; syncForkSources rewrites them after the pin or a patch changes.
val forkedSourceSets = listOf("commonMain", "jvmAndAndroidMain", "jvmMain")
val byteinkPackage = "com/vivenotes/byteink"

val upstreamSources = tasks.register<GitSparseCheckout>("upstreamSources") {
    description = "Checks out AndroidX's ink-nativeloader at the pinned frameworks/support commit."
    val pins = upstreamPins()
    repository = pins.map { it.getProperty("androidx.support.repository") }
    commit = pins.map { it.getProperty("androidx.support.commit") }
    paths = listOf("ink/ink-nativeloader")
    directory = layout.buildDirectory.dir("upstream")
}

val patchedSources = tasks.register<PatchedSources>("patchedSources") {
    description = "Applies patches/ to the pinned upstream sources: what src/ must hold."
    upstream = upstreamSources.flatMap { it.directory.dir("ink/ink-nativeloader/src") }
    sourceSets = forkedSourceSets
    patches.from(fileTree("patches") { include("*.patch") })
    directory = layout.buildDirectory.dir("patched-upstream")
}

val verifyForkSources = tasks.register<VerifyForkSources>("verifyForkSources") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that src/ holds the pinned upstream sources with patches/ applied, and nothing else of upstream's."
    expected = patchedSources.flatMap { it.directory }
    fork = layout.projectDirectory.dir("src")
    sourceSets = forkedSourceSets
    ownPackage = byteinkPackage
    marker = layout.buildDirectory.file("verifyForkSources/ok")
}

tasks.register<SyncForkSources>("syncForkSources") {
    group = "upstream"
    description = "Rewrites src/'s upstream sources from the pinned commit with patches/ applied."
    expected = patchedSources.flatMap { it.directory }
    fork = layout.projectDirectory.dir("src")
    sourceSets = forkedSourceSets
    ownPackage = byteinkPackage
}

// The compiled classes must offer exactly what Google's ink-nativeloader-jvm does, so Google's
// other jars link against them unchanged.
val upstreamTool = configurations.dependencyScope("upstreamTool")
val upstreamToolClasspath = configurations.resolvable("upstreamToolClasspath") {
    extendsFrom(upstreamTool.get())
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
    }
}
dependencies {
    "upstreamTool"(project(":upstream"))
}

val verifyUpstreamAbi = tasks.register<JavaExec>("verifyUpstreamAbi") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Checks that the fork's androidx.ink.nativeloader classes offer exactly what Google's do."
    classpath = files(upstreamToolClasspath)
    mainClass = "com.vivenotes.byteink.upstream.MainKt"
    val reference: FileCollection = googleNativeLoaderJar()
    val candidate = tasks.named<Jar>("jvmJar").flatMap { it.archiveFile }
    inputs.files(reference).withPropertyName("reference")
    inputs.file(candidate).withPropertyName("candidate")
    val marker = layout.buildDirectory.file("verifyUpstreamAbi/ok")
    outputs.file(marker)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "compare-abi",
            "--reference", reference.singleFile.path,
            "--candidate", candidate.get().asFile.path,
            "--package", "androidx.ink.nativeloader",
        )
    })
    doLast { marker.get().asFile.writeText("ok\n") }
}

tasks.named("check") {
    dependsOn(verifyForkSources, verifyUpstreamAbi, jvmTestOnOldestJdk)
}
