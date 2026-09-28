// Maven publications for the modules byteink publishes. Until real repositories are chosen they go to
// build/repo in the root project, which is also where the consumer tests resolve them from.

plugins {
    `maven-publish`
}

group = "com.vivenotes.byteink"
version = extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("byteink-modules").get().requiredVersion

publishing {
    repositories {
        maven {
            name = "build"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}

// Kotlin Multiplatform creates its own publications; a plain library publishes its Java component.
pluginManager.withPlugin("java-library") {
    extensions.configure<JavaPluginExtension> { withSourcesJar() }
    publishing.publications.register<MavenPublication>("library") {
        from(components["java"])
    }
}
