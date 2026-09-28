package com.vivenotes.byteink.build

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

/** The JVM bytecode and JDK API level every byteink project compiles to (`jvmTarget` in the catalog). */
val Project.bytecodeTarget: String
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")
        .findVersion("jvmTarget").get().requiredVersion

/** JUnit 4 through kotlin-test, and the JVM flags native code needs. */
fun Project.configureTests() {
    tasks.withType<Test>().configureEach {
        useJUnit()
        // Ink (and later Skiko) load native libraries; JDK 24+ warns about that, and a future release
        // will block it.
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        testLogging {
            events(TestLogEvent.FAILED)
            exceptionFormat = TestExceptionFormat.FULL
        }
    }
}
