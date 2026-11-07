plugins {
    id("byteink.kotlin-multiplatform-jvm")
    id("byteink.publishing")
}

kotlin {
    explicitApi()
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom.description = "Shared ByteInk stored records, settings, geometry values and wire formats for Kotlin applications."
    if (name == "wasmJs") pom.properties.set(pom.properties.get().filterKeys { it != "byteink.jvm.target" })
}

// JVM remains available to the existing kit; the preview enables the same models for Wasm.
if (providers.gradleProperty("byteinkWasm").orNull == "true") {
    pluginManager.apply("byteink.kotlin-multiplatform-wasm")
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    kotlin.wasmJs().browser { testTask { enabled = false } }
}
