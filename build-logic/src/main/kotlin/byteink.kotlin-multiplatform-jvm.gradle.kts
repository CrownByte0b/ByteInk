// Kotlin Multiplatform with only a JVM target, for sources that rely on expect/actual.

import com.vivenotes.byteink.build.bytecodeTarget
import com.vivenotes.byteink.build.configureTests
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.fromTarget(bytecodeTarget)
            freeCompilerArgs.add("-Xjdk-release=$bytecodeTarget")
        }
    }
}

configureTests()
