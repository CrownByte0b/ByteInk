// Compose Desktop/Skia rendering of finished and in-progress strokes, and live stroke authoring.

plugins {
    id("byteink.library")
    id("byteink.compose")
}

dependencies {
    api(project(":byteink-core"))
    api(libs.compose.runtime)
    api(libs.compose.ui)
    implementation(libs.compose.foundation)
}
