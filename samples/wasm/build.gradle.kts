import com.vivenotes.byteink.build.upstreamPins

plugins {
    id("byteink.kotlin-multiplatform-wasm")
    id("byteink.compose")
}

val engineDirectory = rootProject.layout.projectDirectory.dir(
    "native/build/out/${upstreamPins().get().getProperty("google.ink.commit")}/wasm")

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs { binaries.executable() }
    sourceSets.wasmJsMain {
        dependencies {
            implementation(project(":byteink-portable"))
            implementation(project(":byteink-compose-wasm"))
            implementation(libs.compose.foundation)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        resources.srcDir(engineDirectory)
        resources.srcDir(rootProject.layout.projectDirectory.dir("native/wasm/fixtures"))
    }
}

tasks.named("wasmJsProcessResources") { dependsOn(":byteink-wasm:checkWasmEngine") }
