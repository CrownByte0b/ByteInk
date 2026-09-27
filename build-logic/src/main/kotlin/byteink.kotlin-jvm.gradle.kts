// Kotlin/JVM conventions shared by every byteink project.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

val bytecodeTarget = extensions.getByType<VersionCatalogsExtension>().named("libs")
    .findVersion("jvmTarget").get().requiredVersion

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(bytecodeTarget)
        freeCompilerArgs.add("-Xjdk-release=$bytecodeTarget")
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = bytecodeTarget.toInt()
}

dependencies {
    testImplementation(kotlin("test"))
}

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
