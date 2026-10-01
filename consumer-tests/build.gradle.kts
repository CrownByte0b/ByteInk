// Unpublished. Gradle builds that consume byteink's published modules, run with TestKit against the
// repository the publications go to (build/repo): the dependency graph a consumer really gets.

plugins {
    id("byteink.kotlin-jvm")
}

dependencies {
    testImplementation(gradleTestKit())
}

// These projects own the resolved publication coordinates, including any release overrides.
evaluationDependsOn(":byteink-core")
evaluationDependsOn(":ink-nativeloader")
val publishedGroup = project(":byteink-core").group.toString()
val publishedVersion = project(":byteink-core").version.toString()
val publishedLoaderVersion = project(":ink-nativeloader").version.toString()

tasks.test {
    // What is tested is the published metadata, so publish first.
    dependsOn(
        ":ink-nativeloader:publishAllPublicationsToBuildRepository",
        ":byteink-core:publishAllPublicationsToBuildRepository",
        ":byteink-vive:publishAllPublicationsToBuildRepository",
        ":byteink-compose:publishAllPublicationsToBuildRepository",
    )
    systemProperty("byteink.test.repository", rootProject.layout.buildDirectory.dir("repo").get().asFile.path)
    systemProperty("byteink.test.group", publishedGroup)
    systemProperty("byteink.test.version", publishedVersion)
    systemProperty("byteink.test.nativeloaderVersion", publishedLoaderVersion)
    systemProperty("byteink.test.inkVersion", libs.versions.androidx.ink.get())
    systemProperty("byteink.test.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("byteink.test.composeVersion", libs.versions.composeMultiplatform.get())
    systemProperty("byteink.test.root", rootProject.layout.projectDirectory.asFile.path)
    val fixture = layout.projectDirectory.dir("fixture")
    inputs.files(fileTree(fixture) {
        include("*.gradle.kts", "gradle.properties", "src/**")
    }).withPropertyName("consumerFixture")
    systemProperty("byteink.test.fixture", fixture.asFile.path)
    val reports = layout.buildDirectory.dir("reports/consumer")
    outputs.dir(reports)
    systemProperty("byteink.test.consumerReports", reports.get().asFile.path)
    // Publishing always republishes, so there is never an up-to-date result to reuse.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
