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
    val reports = layout.buildDirectory.dir("reports/performance")
    val report = layout.buildDirectory.file("reports/performance/interaction.json")
    outputs.dir(reports)
    systemProperty("byteink.test.interactionReport", report.get().asFile.absolutePath)
}
