import com.vivenotes.byteink.build.upstreamPins

plugins {
    id("byteink.kotlin-multiplatform-wasm")
}

val engineDirectory = rootProject.layout.projectDirectory.dir(
    "native/build/out/${upstreamPins().get().getProperty("google.ink.commit")}/wasm")

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs { binaries.executable() }
    sourceSets.wasmJsMain {
        dependencies {
            implementation(project(":byteink-portable"))
            implementation(libs.kotlinx.coroutines.core)
        }
        resources.srcDir(engineDirectory)
    }
}

tasks.named("wasmJsProcessResources") { dependsOn(":byteink-wasm:checkWasmEngine") }
