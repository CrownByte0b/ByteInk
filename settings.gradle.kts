rootProject.name = "byteink"

pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Kotlin's Binaryen setup registers a project repository. In the opt-in Wasm build,
    // ignore it and resolve the narrowly scoped distribution from settings instead.
    repositoriesMode = if (providers.gradleProperty("byteinkWasm").orNull == "true")
        RepositoriesMode.PREFER_SETTINGS else RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        if (providers.gradleProperty("byteinkWasm").orNull == "true") {
            ivy("https://github.com/WebAssembly/binaryen/releases/download") {
                name = "Binaryen distributions"
                patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
                metadataSources { artifact() }
                content { includeModule("com.github.webassembly", "binaryen") }
            }
        }
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Pins, JNI contract and the tooling that proves them. See upstream/README.md.
include(":upstream")

// Published library modules; each build file says what it holds.
include(":ink-nativeloader")
include(":byteink-core")
include(":byteink-compose")
include(":byteink-kit")
include(":byteink-testing")
// Experimental browser engine/client. Existing desktop builds do not need Emscripten or Node.
if (providers.gradleProperty("byteinkWasm").orNull == "true") {
    include(":byteink-wasm", ":samples:wasm")
}

// Unpublished verification and sample code.
include(":conformance")
include(":conformance:oracle")
include(":consumer-tests")
include(":samples:viewer")
