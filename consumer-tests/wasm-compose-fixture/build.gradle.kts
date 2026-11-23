plugins {
    kotlin("multiplatform") version "2.4.21"
    kotlin("plugin.compose") version "2.4.21"
    id("org.jetbrains.compose") version "1.12.2"
}

plugins.withType<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsPlugin> {
    extensions.configure<org.jetbrains.kotlin.gradle.targets.wasm.nodejs.WasmNodeJsEnvSpec> { download = false }
}
val engineAssets by configurations.creating
// A release run passes byteinkVersion; otherwise this is the checkout's version.
val byteinkVersion = providers.gradleProperty("byteinkVersion").orElse(
    providers.fileContents(layout.projectDirectory.file("../../gradle.properties")).asText.map {
        val release = Regex("""(?m)^byteinkReleaseVersion\s*[=:]\s*(\S+)""").find(it)?.groupValues?.get(1)
        "${release ?: error("../../gradle.properties has no byteinkReleaseVersion")}-SNAPSHOT"
    }
).get()
dependencies { engineAssets("com.vivenotes.byteink:byteink-wasm:$byteinkVersion:engine@zip") }
val unpackEngine by tasks.registering(Sync::class) {
    from(engineAssets.map { zipTree(it) }); into(layout.buildDirectory.dir("engine-assets"))
}
@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    wasmJs { browser(); binaries.executable() }
    sourceSets.wasmJsMain {
        resources.srcDir(unpackEngine)
        dependencies {
            implementation("com.vivenotes.byteink:byteink-compose-wasm:$byteinkVersion")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
    }
}
