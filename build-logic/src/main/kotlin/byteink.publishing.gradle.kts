import com.vivenotes.byteink.build.upstreamPins

// GitHub Packages is the remote destination; build/repo also exercises the exact consumer metadata.

plugins {
    `maven-publish`
}

group = providers.gradleProperty("byteinkGroup").getOrElse("com.vivenotes.byteink")
version = providers.gradleProperty("byteinkVersion").getOrElse(
    extensions.getByType<VersionCatalogsExtension>().named("libs").findVersion("byteink-modules").get().requiredVersion
)

val githubRepository = providers.gradleProperty("byteinkGitHubRepository")
    .orElse(providers.environmentVariable("GITHUB_REPOSITORY"))
    .getOrElse("CrownByte0b/ByteInk")
check(githubRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
    "byteinkGitHubRepository must be an owner/repository name"
}
val projectUrl = "https://github.com/$githubRepository"
val upstream = upstreamPins().get()
val inkVersion = extensions.getByType<VersionCatalogsExtension>().named("libs")
    .findVersion("androidx-ink").get().requiredVersion

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/${githubRepository.lowercase()}")
            credentials {
                username = providers.gradleProperty("GitHubPackagesUsername")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("GitHubPackagesPassword")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
        maven {
            name = "build"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
    publications.withType<MavenPublication>().configureEach {
        pom {
            name = "ByteInk ${project.name}"
            description = "AndroidX Ink's pinned stroke engine for Kotlin desktop applications on Linux and Windows."
            url = projectUrl
            licenses {
                license {
                    name = "Apache License, Version 2.0"
                    url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    distribution = "repo"
                }
            }
            scm {
                url = projectUrl
                connection = "scm:git:$projectUrl.git"
                developerConnection = "scm:git:ssh://git@github.com/$githubRepository.git"
            }
            properties.putAll(mapOf(
                "byteink.androidx.ink.version" to inkVersion,
                "byteink.androidx.support.commit" to upstream.getProperty("androidx.support.commit"),
                "byteink.google.ink.commit" to upstream.getProperty("google.ink.commit"),
                "byteink.bionic.math.commit" to upstream.getProperty("bionic.math.commit"),
                "byteink.jvm.target" to extensions.getByType<VersionCatalogsExtension>().named("libs")
                    .findVersion("jvmTarget").get().requiredVersion,
            ))
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
