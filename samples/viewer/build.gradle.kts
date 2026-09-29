// Unpublished ink viewer and offscreen PNG exporter for .vive notebooks.

plugins {
    id("byteink.kotlin-jvm")
    id("byteink.compose")
}

dependencies {
    implementation(project(":byteink-compose"))
    implementation(project(":byteink-vive"))
    implementation(project(":byteink-testing"))
    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.compose.ui.test)
}

compose.desktop {
    application {
        mainClass = "com.vivenotes.byteink.viewer.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
