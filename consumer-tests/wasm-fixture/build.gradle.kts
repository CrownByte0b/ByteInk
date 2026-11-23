plugins { kotlin("multiplatform") version "2.4.21" }

// A release run passes byteinkVersion; otherwise this is the checkout's version.
val byteinkVersion = providers.gradleProperty("byteinkVersion").orElse(
    providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText.map {
        val release = Regex("""(?m)^byteinkReleaseVersion\s*[=:]\s*(\S+)""").find(it)?.groupValues?.get(1)
        "${release ?: error("../../gradle.properties has no byteinkReleaseVersion")}-SNAPSHOT"
    }
).get()

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> { download = false }
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs { nodejs(); binaries.executable() }
    sourceSets.wasmJsMain.dependencies {
        implementation("com.vivenotes.byteink:byteink-portable:$byteinkVersion")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    }
}
