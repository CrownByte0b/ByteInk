plugins {
    id("byteink.kotlin-multiplatform-wasm")
    id("byteink.compose")
    id("byteink.publishing")
}

kotlin {
    explicitApi()
    sourceSets {
        wasmJsMain {
            kotlin.srcDir("../byteink-compose/src/shared/kotlin")
            dependencies {
                api(project(":byteink-portable"))
                api(libs.compose.ui)
                api(libs.compose.runtime)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

@OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
kotlin.wasmJs { browser { testTask { enabled = false } } }

publishing.publications.withType<MavenPublication>().configureEach {
    pom.description = "Compose/Wasm Ink path and mesh rendering, textures, animation and browser pen authoring."
    pom.properties.set(pom.properties.get().filterKeys { it != "byteink.jvm.target" })
}
