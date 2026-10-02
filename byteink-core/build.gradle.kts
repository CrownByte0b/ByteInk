// Desktop foundation over AndroidX Ink. It re-exports the pinned upstream JVM modules unchanged and
// adds the mesh-geometry adapter, a spatial index for hit testing and the native-loader check.

import com.vivenotes.byteink.build.googleNativeLoaderJar

plugins {
    id("byteink.library")
}

// byteink-core brings byteink's fork of Google's native loader, so it claims that loader's
// capabilities: a build that also pulls in Google's loader fails to resolve rather than carrying two
// NativeLoader classes. (The fork cannot claim them itself: its Kotlin Multiplatform publication
// would then conflict with its own -jvm module.) Declaring capabilities replaces the implicit one, so
// byteink-core's own is listed too.
val inkVersion = libs.versions.androidx.ink.get()
for (elements in listOf(configurations.apiElements, configurations.runtimeElements)) {
    elements.configure {
        outgoing {
            capability("$group:${project.name}:$version")
            capability("androidx.ink:ink-nativeloader:$inkVersion")
            capability("androidx.ink:ink-nativeloader-jvm:$inkVersion")
        }
    }
}

publishing.publications.named<MavenPublication>("library") {
    // A POM cannot express capabilities; Maven consumers still get the exclusions below.
    suppressPomMetadataWarningsFor("apiElements")
    suppressPomMetadataWarningsFor("runtimeElements")
}

dependencies {
    // Google's Ink JVM modules as published, but with byteink's fork of their native loader in place
    // of Google's, which knows no Windows (ink-nativeloader/PATCHES.md).
    api(project(":ink-nativeloader"))
    for (module in listOf(libs.androidx.ink.brush, libs.androidx.ink.geometry, libs.androidx.ink.storage, libs.androidx.ink.strokes)) {
        api(module) {
            exclude(group = "androidx.ink", module = "ink-nativeloader")
            exclude(group = "androidx.ink", module = "ink-nativeloader-jvm")
        }
    }
}

tasks.test {
    // Check primitive-array releases and local references in the owned geometry JNI bridge.
    jvmArgs("-Xcheck:jni")
    // InkRuntimeTest starts a JVM with Google's loader ahead of byteink's on the classpath.
    val google = googleNativeLoaderJar()
    val runtime = sourceSets.test.get().runtimeClasspath
    inputs.files(google).withPropertyName("googleNativeLoaderJar")
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dbyteink.test.googleNativeLoaderJar=${google.singleFile}", "-Dbyteink.test.classpath=${runtime.asPath}")
    })
}
