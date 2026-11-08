plugins {
    id("org.jetbrains.kotlin.multiplatform")
}

for (targetProject in listOf(rootProject, project).distinct()) {
    targetProject.plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
        targetProject.extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> {
            download = false
        }
    }
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs {
        browser()
        nodejs()
        // These npm packages only link the modules into the local npm workspace and are never
        // published. A fixed version keeps kotlin-js-store/wasm/package-lock.json the same under
        // the development version and a release's -PbyteinkVersion, which kotlinWasmStorePackageLock
        // requires; with the project version, every release run failed that check.
        compilations.configureEach { packageJson { version = "0.0.0-unspecified" } }
    }
}
