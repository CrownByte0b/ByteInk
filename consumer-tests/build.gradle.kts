// Unpublished. Gradle builds that consume byteink's published modules, run with TestKit against the
// repository the publications go to (build/repo): the dependency graph a consumer really gets.

import com.vivenotes.byteink.build.byteinkLinuxLibrary
import com.vivenotes.byteink.build.byteinkWindowsLibrary
import com.vivenotes.byteink.build.upstreamPins

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

// A nested included build can recompile on another JDK while the outer suites are using its
// classes. Stage only source/configuration and the selected native binaries for a private copy.
val compositeDirectory = layout.buildDirectory.dir("composite-source")
val compositeSource = tasks.register<Sync>("prepareCompositeSource") {
    into(compositeDirectory)
    from(rootProject.layout.projectDirectory) {
        include("settings.gradle.kts", "gradle.properties", "LICENSE", "gradle/**")
    }
    from(rootProject.layout.projectDirectory.dir("build-logic")) {
        into("build-logic")
        include("settings.gradle.kts", "build.gradle.kts", "src/**")
    }
    rootProject.subprojects.forEach { module ->
        from(module.layout.projectDirectory) {
            into(module.path.removePrefix(":").replace(':', '/'))
            include("build.gradle.kts", "src/**", "patches/**", "jni/**", "pins.properties")
        }
    }
    val nativeCommit = upstreamPins().get().getProperty("google.ink.commit")
    from(byteinkLinuxLibrary()) {
        into("native/build/out/$nativeCommit/linux-x86_64")
        rename { "libink.so" }
    }
    from(byteinkWindowsLibrary()) {
        into("native/build/out/$nativeCommit/windows-x86_64")
        rename { "ink.dll" }
    }
}

tasks.test {
    // What is tested is the published metadata, so publish first.
    dependsOn(
        ":ink-nativeloader:publishAllPublicationsToBuildRepository",
        ":byteink-core:publishAllPublicationsToBuildRepository",
        ":byteink-kit:publishAllPublicationsToBuildRepository",
        ":byteink-compose:publishAllPublicationsToBuildRepository",
    )
    systemProperty("byteink.test.repository", rootProject.layout.buildDirectory.dir("repo").get().asFile.path)
    systemProperty("byteink.test.group", publishedGroup)
    systemProperty("byteink.test.version", publishedVersion)
    systemProperty("byteink.test.nativeloaderVersion", publishedLoaderVersion)
    systemProperty("byteink.test.inkVersion", libs.versions.androidx.ink.get())
    systemProperty("byteink.test.kotlinVersion", libs.versions.kotlin.get())
    systemProperty("byteink.test.composeVersion", libs.versions.composeMultiplatform.get())
    dependsOn(compositeSource)
    inputs.dir(compositeDirectory).withPropertyName("compositeSource")
    systemProperty("byteink.test.compositeSource", compositeDirectory.get().asFile.path)
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
