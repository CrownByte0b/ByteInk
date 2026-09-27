// Conventions for the modules byteink publishes.

plugins {
    id("byteink.kotlin-jvm")
    `java-library`
}

kotlin {
    // As in AndroidX: every public declaration states its visibility and type, so the published API
    // only changes on purpose.
    explicitApi()
}
