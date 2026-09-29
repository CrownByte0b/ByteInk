// Unpublished. Gradle builds that consume byteink's published modules, run with TestKit against the
// repository the publications go to (build/repo): the dependency graph a consumer really gets.

plugins {
    id("byteink.kotlin-jvm")
}

dependencies {
    testImplementation(gradleTestKit())
}

tasks.test {
    // What is tested is the published metadata, so publish first.
    dependsOn(
        ":ink-nativeloader:publishAllPublicationsToBuildRepository",
        ":byteink-core:publishAllPublicationsToBuildRepository",
        ":byteink-vive:publishAllPublicationsToBuildRepository",
    )
    systemProperty("byteink.test.repository", rootProject.layout.buildDirectory.dir("repo").get().asFile.path)
    systemProperty("byteink.test.version", libs.versions.byteink.modules.get())
    systemProperty("byteink.test.nativeloaderVersion", libs.versions.byteink.nativeloader.get())
    systemProperty("byteink.test.inkVersion", libs.versions.androidx.ink.get())
    // Publishing always republishes, so there is never an up-to-date result to reuse.
    outputs.upToDateWhen { false }
    outputs.cacheIf { false }
}
