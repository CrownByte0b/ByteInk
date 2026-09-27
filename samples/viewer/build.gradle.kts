// Unpublished sample, still to come: opens a .vive notebook, renders its ink and lets you draw.

plugins {
    id("byteink.kotlin-jvm")
    id("byteink.compose")
}

dependencies {
    implementation(project(":byteink-compose"))
    implementation(project(":byteink-vive"))
}
