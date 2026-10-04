import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    application
}

kotlin.compilerOptions {
    jvmTarget = JvmTarget.JVM_25
    freeCompilerArgs.add("-Xjdk-release=25")
}

val desktopOs = when {
    System.getProperty("os.name").startsWith("Linux") -> "linux"
    System.getProperty("os.name").startsWith("Windows") -> "windows"
    else -> error("The examples require Linux or Windows x86_64")
}

dependencies {
    implementation("com.vivenotes.byteink:byteink-compose:0.1.0-SNAPSHOT")
    implementation("com.vivenotes.byteink:byteink-vive:0.1.0-SNAPSHOT")
    implementation("com.vivenotes.byteink:byteink-testing:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.compose.desktop:desktop-jvm-$desktopOs-x64:1.12.1")
}

application {
    mainClass = "wiki.SmokeKt"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "-Djava.awt.headless=true",
    )
}

tasks.named<JavaExec>("run") {
    args(layout.buildDirectory.dir("example-output").get().asFile.absolutePath)
}
