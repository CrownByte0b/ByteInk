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
    exclude("**/DesktopPenIntegrationTest.class")
    exclude("**/WaylandPenIntegrationTest.class")
    val reports = layout.buildDirectory.dir("reports/performance")
    val report = layout.buildDirectory.file("reports/performance/interaction.json")
    outputs.dir(reports)
    systemProperty("byteink.test.interactionReport", report.get().asFile.absolutePath)
}

// Run through src/test/wayland/run.sh: a private compositor and JBR's native Wayland toolkit.
tasks.register<Test>("waylandPenTest") {
    group = "verification"
    description = "Verifies tablet-v2, touch, surface lifecycle and immediate Skia rendering on native Wayland."
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("com.vivenotes.byteink.compose.WaylandPenIntegrationTest")
    systemProperty("java.awt.headless", "false")
    systemProperty("awt.toolkit.name", "WLToolkit")
    jvmArgs("--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED")
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Wayland verification depends on the current compositor and runtime") { true }
}

// Requires a real AWT/native window. Linux CI uses its private Xvfb display; Windows uses Win32.
tasks.register<Test>("desktopPenTest") {
    group = "verification"
    description = "Exercises native desktop input capture and immediate Skia authoring. Linux: run under xvfb-run."
    dependsOn(tasks.testClasses)
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter.includeTestsMatching("com.vivenotes.byteink.compose.DesktopPenIntegrationTest")
    systemProperty("java.awt.headless", "false")
    systemProperty("byteink.test.nativePen", "true")
    outputs.upToDateWhen { false }
    outputs.doNotCacheIf("Native input and presentation depend on the current desktop") { true }
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
