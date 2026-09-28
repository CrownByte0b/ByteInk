// Unpublished. AndroidX Ink's own JVM test suites for the pinned release, compiled against Google's
// unchanged jars and byteink's fork of their loader, and run twice: on the library that loader
// bundles (jvmTest), and on Google's own libink.so through the loader's override property
// (jvmTestGoogleBinary). Their assertion library, AndroidX's unpublished kruth, is compiled from the
// same commit.

import com.vivenotes.byteink.build.ExpectedLibraryFile
import com.vivenotes.byteink.build.GitSparseCheckout
import com.vivenotes.byteink.build.InkLibraryOverride
import com.vivenotes.byteink.build.byteinkLinuxLibrary
import com.vivenotes.byteink.build.googleLinuxLibrary
import com.vivenotes.byteink.build.upstreamPins
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("byteink.kotlin-multiplatform-jvm")
}

val inkModules = listOf("ink-nativeloader", "ink-geometry", "ink-brush", "ink-strokes", "ink-storage")

val androidxSources = tasks.register<GitSparseCheckout>("androidxSources") {
    description = "Checks out AndroidX's Ink test suites and kruth at the pinned frameworks/support commit."
    val pins = upstreamPins()
    repository = pins.map { it.getProperty("androidx.support.repository") }
    commit = pins.map { it.getProperty("androidx.support.commit") }
    paths = listOf("kruth/kruth") + inkModules.map { "ink/$it" }
    directory = layout.buildDirectory.dir("androidx")
}

fun androidx(path: String): Provider<Directory> = androidxSources.flatMap { it.directory.dir(path) }

kotlin {
    compilerOptions {
        // kruth declares expect classes, still Beta in Kotlin.
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }
    sourceSets {
        commonMain {
            kotlin.srcDir(androidx("kruth/kruth/src/commonMain/kotlin"))
            dependencies {
                api(kotlin("test"))
            }
        }
        jvmMain {
            // kruth's non-JS actuals and its JVM actuals, without its intermediate source set.
            kotlin.srcDir(androidx("kruth/kruth/src/nonJsCommonMain/kotlin"))
            kotlin.srcDir(androidx("kruth/kruth/src/jvmMain/kotlin"))
            dependencies {
                implementation(kotlin("test-junit"))
                implementation(libs.guava)
                implementation(libs.junit)
            }
        }
        jvmTest {
            for (module in inkModules) {
                for (sourceSet in listOf("commonTest", "jvmAndAndroidTest", "jvmTest")) {
                    kotlin.srcDir(androidx("ink/$module/src/$sourceSet/kotlin"))
                }
            }
            dependencies {
                implementation(project(":byteink-core"))
                // The suites use the loader's internal test helpers: byteink's fork of it.
                implementation(project(":ink-nativeloader"))
                implementation(libs.junit)
                implementation(libs.kotlinx.coroutines.core)
                implementation(libs.okio)
                implementation(libs.truth)
            }
        }
    }
}

tasks.named<KotlinCompile>("compileTestKotlinJvm") {
    // AndroidX compiles each suite inside its module, where it may reach that module's internals.
    val classpath = configurations.named("jvmTestCompileClasspath")
    friendPaths.from(classpath.map { files -> files.filter { it.name.startsWith("ink-") && it.name.endsWith(".jar") } })
}

val jvmTest = tasks.named<Test>("jvmTest") {
    description = "Runs AndroidX Ink's JVM suites on the library byteink's loader bundles."
    jvmArgumentProviders.add(ExpectedLibraryFile(byteinkLinuxLibrary()))
}

val googleLibrary = googleLinuxLibrary()

val jvmTestGoogleBinary = tasks.register<Test>("jvmTestGoogleBinary") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs AndroidX Ink's JVM suites on Google's own libink.so, through byteink's loader."
    val suites = jvmTest.get()
    testClassesDirs = suites.testClassesDirs
    classpath = suites.classpath
    jvmArgumentProviders.add(InkLibraryOverride(googleLibrary))
    jvmArgumentProviders.add(ExpectedLibraryFile(googleLibrary))
}

tasks.named("check") {
    dependsOn(jvmTestGoogleBinary)
}
