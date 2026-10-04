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
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
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

// Unpublished verification and sample code.
include(":conformance")
include(":conformance:oracle")
include(":consumer-tests")
include(":samples:viewer")
