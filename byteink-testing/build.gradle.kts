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
