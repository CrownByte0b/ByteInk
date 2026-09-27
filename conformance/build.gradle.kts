// Unpublished. AndroidX Ink's own JVM test suites for the pinned release, compiled against Google's
// unchanged jars and run twice: against Google's libink.so (jvmTest) and against the one
// native/build-linux.sh built (jvmTestByteinkBinary). Their assertion library, AndroidX's
// unpublished kruth, is compiled from the same commit.

import com.vivenotes.byteink.build.ExpectedLibraryFile
import com.vivenotes.byteink.build.ExpectedLibraryResource
import com.vivenotes.byteink.build.GitSparseCheckout
import com.vivenotes.byteink.build.LINUX_LIBRARY
import com.vivenotes.byteink.build.byteinkLinuxLibrary
import com.vivenotes.byteink.build.registerNativesDirectory
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
                // The suites use the loader's internal test helpers, which the other modules only
                // depend on at run time.
                implementation(libs.androidx.ink.nativeloader)
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
    description = "Runs AndroidX Ink's JVM suites against Google's own libink.so."
    jvmArgumentProviders.add(ExpectedLibraryResource(classpath, LINUX_LIBRARY))
}

val byteinkLibrary = byteinkLinuxLibrary()
val byteinkNatives = registerNativesDirectory("byteinkNatives", byteinkLibrary)

tasks.register<Test>("jvmTestByteinkBinary") {
    group = LifecycleBasePlugin.VERIFICATION_GROUP
    description = "Runs AndroidX Ink's JVM suites against byteink's libink.so (native/build-linux.sh)."
    val upstream = jvmTest.get()
    testClassesDirs = upstream.testClassesDirs
    // First on the classpath, so the upstream loader finds byteink's library before Google's.
    classpath = files(byteinkNatives) + upstream.classpath
    jvmArgumentProviders.add(ExpectedLibraryFile(byteinkLibrary))
}
