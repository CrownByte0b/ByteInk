// Compose Desktop/Skia rendering of finished and in-progress strokes, and live stroke authoring.

plugins {
    id("byteink.library")
    id("byteink.compose")
}

dependencies {
    api(project(":byteink-core"))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(project(":byteink-kit"))
    testImplementation(compose.desktop.currentOs)
    testImplementation(libs.compose.ui.test)
}

tasks.test {
    exclude("**/InkMeshGpuTest.class")
    val reports = layout.buildDirectory.dir("reports/performance")
    val report = layout.buildDirectory.file("reports/performance/interaction.json")
    outputs.dir(reports)
    systemProperty("byteink.test.interactionReport", report.get().asFile.absolutePath)
}

// Kept separate from headless tests and their reports. Linux CI supplies a private Xvfb/GLX display.
tasks.register<Test>("meshGpuTest") {
    group = "verification"
    description = "Verifies the mesh runtime shader on a real Linux OpenGL context. Run with xvfb-run."
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("com.vivenotes.byteink.compose.InkMeshGpuTest")
    systemProperty("byteink.test.gpu", "true")
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("OpenGL verification depends on the current display and driver") { true }
}
