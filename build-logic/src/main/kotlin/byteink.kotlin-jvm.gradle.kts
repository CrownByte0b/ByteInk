// Kotlin/JVM conventions shared by every byteink project.

import com.vivenotes.byteink.build.bytecodeTarget
import com.vivenotes.byteink.build.configureTests
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.jvm")
}

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

configureTests()
