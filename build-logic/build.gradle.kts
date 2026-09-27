import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    `kotlin-dsl`
}

// Build logic runs in the Gradle daemon (JDK 27, gradle/gradle-daemon-jvm.properties), which Kotlin
// cannot target yet; compile it for the same release as the libraries instead.
val bytecodeTarget = libs.versions.jvmTarget.get()

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(bytecodeTarget)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release = bytecodeTarget.toInt()
}

dependencies {
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.compose.gradlePlugin)
    implementation(libs.compose.compiler.gradlePlugin)
}
