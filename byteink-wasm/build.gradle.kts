import com.vivenotes.byteink.build.upstreamPins
import com.vivenotes.byteink.build.CheckWasmEngine
import com.vivenotes.byteink.build.GenerateWasmMetadata

plugins {
    id("byteink.kotlin-multiplatform-wasm")
    id("byteink.publishing")
}

val pinnedEngineCommit = upstreamPins().map { it.getProperty("google.ink.commit") }
val engineDirectory = rootProject.layout.projectDirectory.dir("native/build/out/${pinnedEngineCommit.get()}/wasm")
val engineFileNames = listOf("byteink-engine.mjs", "byteink-engine.wasm", "build.properties")

// Deliberately consume a prebuilt engine, as the JVM loader does for desktop natives. Ordinary
// Kotlin compilation does not download SDKs, run CMake or require a native build.
val checkWasmEngine = tasks.register<CheckWasmEngine>("checkWasmEngine") {
    group = "verification"
    description = "Checks that the pinned Wasm engine has been built by native/build-wasm.sh."
    engineFiles.from(engineFileNames.map(engineDirectory::file))
    engineCommit = pinnedEngineCommit
    bridgeSource = rootProject.layout.projectDirectory.file("native/wasm/bridge.cc")
    floatMathSource = rootProject.layout.projectDirectory.file("native/wasm/float_math.cc")
    patches.from(rootProject.fileTree("native/patches") { include("*.patch") })
    emscriptenVersion = upstreamPins().map { it.getProperty("wasm.emscripten.version") }
}

val generateWasmMetadata = tasks.register<GenerateWasmMetadata>("generateWasmMetadata") {
    engineCommit = pinnedEngineCommit
    directory = layout.buildDirectory.dir("generated/engine-pin")
}

val wasmEngineZip = tasks.register<Zip>("wasmEngineZip") {
    group = "distribution"
    description = "Packages the ES module and Wasm to serve alongside a browser application."
    dependsOn(checkWasmEngine)
    archiveClassifier = "engine"
    from(engineDirectory) { include(engineFileNames); include("licenses/**") }
    from(rootProject.layout.projectDirectory.file("LICENSE"))
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom.description = "The pinned Google Ink stroke engine for Kotlin/Wasm browser and Node applications."
    pom.properties.set(pom.properties.get().filterKeys { it != "byteink.jvm.target" } + mapOf(
        "byteink.wasm.bridge.abi" to "4",
        "byteink.wasm.float.math" to "bionic-acosf-v1",
        "byteink.wasm.emscripten.version" to upstreamPins().get().getProperty("wasm.emscripten.version"),
    ))
    if (name == "kotlinMultiplatform") artifact(wasmEngineZip)
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    explicitApi()
    sourceSets {
        wasmJsMain { kotlin.srcDir(generateWasmMetadata.flatMap { it.directory }) }
        wasmJsMain.dependencies { implementation(libs.kotlinx.coroutines.core); api(project(":byteink-model")) }
        wasmJsTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
    wasmJs {
        nodejs {
            testTask {
                dependsOn(checkWasmEngine)
                environment("BYTEINK_WASM_ENGINE", engineDirectory.file("byteink-engine.mjs").asFile.toURI().toString())
                val family = rootProject.layout.projectDirectory.file(
                    "conformance/android/fixtures/matrix/cases/marker-s1-mouse-brush/family.pb")
                inputs.file(family).withPropertyName("androidBrushFamily")
                environment("BYTEINK_WASM_FAMILY", family.asFile.absolutePath)
            }
        }
        // Browser engine loading and drawing are checked separately by native/wasm/browser-test.
        browser { testTask { enabled = false } }
    }
}
