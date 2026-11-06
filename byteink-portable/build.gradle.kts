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

tasks.withType<Test>().configureEach {
    dependsOn(exportFixture)
    inputs.file(fixture)
    systemProperty("byteink.test.kitFixture", fixture.get().asFile.absolutePath)
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
            }
        }
    }
}

