import java.io.File

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
    val matrixProjectRoot = rootProject.layout.projectDirectory
    val matrixReference = providers.gradleProperty("byteinkMatrixDirectory")
        .map(matrixProjectRoot::dir).map { it.asFile.absolutePath }
        .orElse(rootProject.layout.projectDirectory.dir("conformance/android/fixtures/matrix").asFile.absolutePath)
    val matrixReports = layout.buildDirectory.dir("reports/android-matrix-tests")
    inputs.dir(matrixReference).withPropertyName("androidMatrixReference")
    outputs.dir(matrixReports)
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dbyteink.test.matrix=${matrixReference.get()}",
            "-Dbyteink.test.matrixReports=${matrixReports.get().asFile.absolutePath}")
    })
}

// Committed Android-produced goldens are always runnable without an SDK, device or private notes.
val matrixProjectRoot = rootProject.layout.projectDirectory
val matrixReference = providers.gradleProperty("byteinkMatrixDirectory")
    .map(matrixProjectRoot::dir).map { it.asFile.absolutePath }
    .orElse(rootProject.layout.projectDirectory.dir("conformance/android/fixtures/matrix").asFile.absolutePath)
val matrixOutput = providers.gradleProperty("byteinkMatrixOutput")
    .map(matrixProjectRoot::dir).map { it.asFile.absolutePath }
    .orElse(layout.buildDirectory.dir("reports/android-matrix").map { it.asFile.absolutePath })
val matrixNative = providers.gradleProperty("byteinkFidelityNative")
val prepareAndroidMatrix = tasks.register<JavaExec>("prepareAndroidMatrix") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vivenotes.byteink.testing.AndroidFidelityMatrix")
    maxHeapSize = "2g"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    val reference = matrixReference
    val output = matrixOutput
    val native = matrixNative
    inputs.dir(reference).withPropertyName("androidMatrixReference")
    inputs.files(native.map { listOf(it) }.orElse(emptyList())).withPropertyName("matrixNativeOverride")
    outputs.dir(output.map { File(it).resolve("desktop") })
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        native.map { listOf("-Dbyteink.ink.library=$it") }.getOrElse(emptyList())
    })
    argumentProviders.add(CommandLineArgumentProvider { listOf("prepare", reference.get(), output.get()) })
}
tasks.register<JavaExec>("compareAndroidMatrix") {
    dependsOn(prepareAndroidMatrix)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vivenotes.byteink.testing.AndroidFidelityMatrix")
    maxHeapSize = "2g"
    val reference = matrixReference
    val output = matrixOutput
    inputs.dir(reference).withPropertyName("androidMatrixReference")
    argumentProviders.add(CommandLineArgumentProvider { listOf("compare", reference.get(), output.get()) })
}
tasks.register("androidFidelityMatrix") {
    group = "verification"
    description = "Rebuilds every committed Android brush, tool, stabilization and replay case and checks its fidelity."
    dependsOn("compareAndroidMatrix")
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

val parityProjectRoot = rootProject.layout.projectDirectory
val parityDirectory = providers.gradleProperty("byteinkParityDirectory").map(parityProjectRoot::dir).map { it.asFile.absolutePath }
    .orElse(layout.buildDirectory.dir("desktop-parity").map { it.asFile.absolutePath })
val parityReference = providers.gradleProperty("byteinkParityReference").map(parityProjectRoot::dir).map { it.asFile.absolutePath }
tasks.register<JavaExec>("desktopParityDump") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vivenotes.byteink.testing.DesktopParity")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    val output = parityDirectory
    val fixtures = providers.gradleProperty("byteinkParityFixtures").map(parityProjectRoot::dir).map { it.asFile.absolutePath }
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("dump", output.get()) + fixtures.map { listOf(it) }.getOrElse(emptyList())
    })
}
tasks.register<JavaExec>("desktopParityCompare") {
    dependsOn("desktopParityDump")
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vivenotes.byteink.testing.DesktopParity")
    val reference = parityReference
    val candidate = parityDirectory
    argumentProviders.add(CommandLineArgumentProvider { listOf("compare", reference.get(), candidate.get()) })
}

// Independent JVM measurements avoid test-order effects in timings and native peer accounting.
// Reports contain aggregate measurements only; personal notebook inputs remain opt-in and private.
val performanceReports = layout.buildDirectory.dir("reports/performance")
val performanceNotebook = providers.gradleProperty("byteinkPerformanceNotebook")
    .orElse(providers.environmentVariable("BYTEINK_PERFORMANCE_NOTEBOOK"))
val performanceCheck = tasks.register<JavaExec>("performanceCheck") {
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.vivenotes.byteink.testing.InkPerformance")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    maxHeapSize = "2g"
    outputs.dir(performanceReports)
    // Measurements describe this execution, so cached reports from an earlier JVM are insufficient.
    outputs.upToDateWhen { false }
    val reports = performanceReports
    val notebook = performanceNotebook
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(reports.get().asFile.absolutePath) + notebook.map { listOf(it) }.getOrElse(emptyList())
    })
}
tasks.named("check") { dependsOn(performanceCheck) }
