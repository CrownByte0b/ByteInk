plugins { kotlin("multiplatform") version "2.4.21" }

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> { download = false }
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs { nodejs(); binaries.executable() }
    sourceSets.wasmJsMain.dependencies {
        implementation("com.vivenotes.byteink:byteink-portable:0.1.0-SNAPSHOT")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
}
