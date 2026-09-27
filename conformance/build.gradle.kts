// Unpublished. To hold AndroidX Ink's own JVM test suites for the pinned release, run against
// the natives byteink ships, plus the checks that compare them with Google's binary.

plugins {
    id("byteink.kotlin-jvm")
}

dependencies {
    testImplementation(project(":byteink-core"))
}
