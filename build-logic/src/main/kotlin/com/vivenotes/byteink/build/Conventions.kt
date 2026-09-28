package com.vivenotes.byteink.build

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** The JVM bytecode and JDK API level every byteink project compiles to (`jvmTarget` in the catalog). */
val Project.bytecodeTarget: String
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
        .findVersion("jvmTarget").get().requiredVersion

/**
 * JUnit 4 through kotlin-test, the JVM flags native code needs, and a cache of byteink's native
 * library under the build directory, emptied before each run, rather than the user's.
 */
fun Project.configureTests() {
    tasks.withType<Test>().configureEach {
        useJUnit()
        val cache = layout.buildDirectory.dir("ink-cache/$name")
        systemProperty("byteink.ink.cache", cache.get().asFile.path)
        doFirst { cache.get().asFile.deleteRecursively() }
        // Ink (and later Skiko) load native libraries; JDK 24+ warns about that, and a future release
        // will block it.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}

/**
 * Registers [name]: [tests]' classes run again on a JDK [version] toolchain instead of the JDK
 * Gradle runs on, so behaviour that differs between JDKs (finalization, native access) is covered
 * on each byteink supports.
 */
fun Project.registerTestsOnJdk(name: String, tests: TaskProvider<out Test>, version: Int): TaskProvider<Test> {
    val toolchains = extensions.getByType<JavaToolchainService>()
    return tasks.register<Test>(name) {
        group = LifecycleBasePlugin.VERIFICATION_GROUP
        description = "Runs ${tests.name}'s tests on JDK $version."
        testClassesDirs = files(tests.map { it.testClassesDirs })
        classpath = files(tests.map { it.classpath })
        javaLauncher.set(toolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(version)) })
    }
}

