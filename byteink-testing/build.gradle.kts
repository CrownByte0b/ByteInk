// Test support shared by byteink and its consumers: fixtures, a .vive reader for tests, geometry
// dumps and image comparison.

plugins {
    id("byteink.library")
    id("byteink.compose")
}

dependencies {
    api(project(":byteink-vive"))
    api(project(":byteink-compose"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(compose.desktop.currentOs)
}

kotlin.sourceSets.named("test") {
    kotlin.srcDir(rootProject.layout.projectDirectory.dir("conformance/android/shared"))
}
tasks.named<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>("compileTestKotlin") {
    // The JVM loader marks raw mesh APIs with this opt-in; Android's annotation is absent.
    compilerOptions.optIn.add("androidx.ink.nativeloader.InkInternalOnlyApi")
}

tasks.test {
    // -PbyteinkNotebooks=<directory of .vive files> replays real notebooks; they are personal, so
    // nothing names them by default and the test skips.
    val notebooks = providers.gradleProperty("byteinkNotebooks").orElse(providers.environmentVariable("BYTEINK_NOTEBOOKS"))
    val reports = layout.buildDirectory.dir("reports/notebooks")
    inputs.property("notebooks", notebooks.orElse(""))
    // Their contents too, so a changed notebook is replayed again.
    inputs.files(notebooks.map { listOf(it) }.orElse(emptyList())).withPropertyName("notebookFiles")
    outputs.dir(reports)
    maxHeapSize = "2g"
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        notebooks.map { listOf("-Dbyteink.test.notebooks=$it", "-Dbyteink.test.notebookReports=${reports.get().asFile}") }
            .getOrElse(emptyList())
    })
}

// Optional Android notebook oracle. Test runtime supplies Skiko's platform library.
val fidelityDirectory = providers.gradleProperty("byteinkFidelityDirectory")
    .orElse(rootProject.layout.projectDirectory.dir("conformance/android/build/results").asFile.absolutePath)
tasks.register<JavaExec>("prepareAndroidOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vivenotes.byteink.testing.AndroidNotebookFidelity")
    maxHeapSize = "2g"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val notebooks = providers.gradleProperty("byteinkNotebooks").orElse(providers.environmentVariable("BYTEINK_NOTEBOOKS"))
    val output = fidelityDirectory
    val nativeLibrary = providers.gradleProperty("byteinkFidelityNative")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        nativeLibrary.map { listOf("-Dbyteink.ink.library=$it") }.getOrElse(emptyList())
    })
    argumentProviders.add(CommandLineArgumentProvider { listOf("prepare", notebooks.get(), output.get()) })
}
tasks.register<JavaExec>("compareAndroidOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vivenotes.byteink.testing.AndroidNotebookFidelity")
    maxHeapSize = "2g"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val output = fidelityDirectory
    argumentProviders.add(CommandLineArgumentProvider { listOf("compare", output.get()) })
}

tasks.register<JavaExec>("diagnoseAndroidOracle") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vivenotes.byteink.testing.AndroidNotebookFidelity")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val output = fidelityDirectory
    val nativeLibrary = providers.gradleProperty("byteinkFidelityNative")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        nativeLibrary.map { listOf("-Dbyteink.ink.library=$it") }.getOrElse(emptyList())
    })
    argumentProviders.add(CommandLineArgumentProvider { listOf("diagnose", output.get()) })
}
