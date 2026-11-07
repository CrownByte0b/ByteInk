import com.vivenotes.byteink.build.upstreamPins

plugins {
    id("byteink.kotlin-multiplatform-jvm")
    id("byteink.publishing")
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin {
    explicitApi()
    sourceSets {
        commonMain.dependencies { api(project(":byteink-model")) }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies { api(project(":byteink-kit")) }
    }
}

publishing.publications.withType<MavenPublication>().configureEach {
    pom.description = "Common JVM and Wasm ink styles, stored-row codecs and page operation replay on the pinned Ink engine."
    if (name == "wasmJs") pom.properties.set(pom.properties.get().filterKeys { it != "byteink.jvm.target" })
}

val fixture = layout.buildDirectory.file("fixtures/android-kit.json")
val exportFixture = tasks.register<Exec>("exportAndroidKitFixture") {
    val matrix = rootProject.layout.projectDirectory.dir("conformance/android/fixtures/matrix")
    val exporter = rootProject.layout.projectDirectory.file("native/wasm/export-kit-fixtures.py")
    inputs.file(exporter)
    inputs.dir(matrix)
    outputs.file(fixture)
    val python = if (System.getProperty("os.name").startsWith("Windows")) "python" else "python3"
    commandLine(python, exporter.asFile.absolutePath, "--matrix", matrix.asFile.absolutePath,
        "--output", fixture.get().asFile.absolutePath)
}

val roundTripPhase = providers.gradleProperty("byteinkRoundTripPhase").orElse("local")
val roundTripDirectory = rootProject.layout.buildDirectory.dir("wasm-roundtrip")

tasks.withType<Test>().configureEach {
    dependsOn(exportFixture)
    inputs.file(fixture)
    systemProperty("byteink.test.kitFixture", fixture.get().asFile.absolutePath)
    systemProperty("byteink.test.roundTripPhase",roundTripPhase.get())
    systemProperty("byteink.test.roundTripDirectory",roundTripDirectory.get().asFile.absolutePath)
    if (roundTripPhase.get() != "local") {
        // Run the actual exchange and timing workload; cached test reports are not a new run.
        outputs.upToDateWhen { false }
        outputs.cacheIf { false }
    }
    if (roundTripPhase.get() == "seed") outputs.files(roundTripDirectory.map { it.file("jvm.json") },roundTripDirectory.map { it.file("jvm-performance.json") })
    if (roundTripPhase.get() == "return") inputs.files(roundTripDirectory.map { it.file("jvm.json") },roundTripDirectory.map { it.file("wasm.json") })
    if (roundTripPhase.get() == "return") outputs.file(roundTripDirectory.map { it.file("accepted.json") })
    maxHeapSize = "2g"
}

if (providers.gradleProperty("byteinkWasm").orNull == "true") {
    pluginManager.apply("byteink.kotlin-multiplatform-wasm")
    kotlin.sourceSets.named("wasmJsMain") { dependencies { api(project(":byteink-wasm")) } }
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    kotlin.wasmJs {
        browser { testTask { enabled = false } }
        nodejs {
            testTask {
                dependsOn(":byteink-wasm:checkWasmEngine", exportFixture)
                inputs.file(fixture)
                val engine = rootProject.layout.projectDirectory.file(
                    "native/build/out/${upstreamPins().get().getProperty("google.ink.commit")}/wasm/byteink-engine.mjs")
                environment("BYTEINK_WASM_ENGINE", engine.asFile.toURI().toString())
                environment("BYTEINK_KIT_FIXTURE", fixture.get().asFile.absolutePath)
                environment("BYTEINK_ROUND_TRIP_PHASE",roundTripPhase.get())
                environment("BYTEINK_ROUND_TRIP_DIRECTORY",roundTripDirectory.get().asFile.absolutePath)
                inputs.property("roundTripPhase",roundTripPhase)
                if (roundTripPhase.get() != "local") {
                    outputs.upToDateWhen { false }
                    outputs.cacheIf { false }
                }
                if (roundTripPhase.get() == "wasm") inputs.file(roundTripDirectory.map { it.file("jvm.json") })
                if (roundTripPhase.get() == "wasm") outputs.files(roundTripDirectory.map { it.file("wasm.json") },roundTripDirectory.map { it.file("wasm-performance.json") })
            }
        }
    }
}

