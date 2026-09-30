// ViveNotes ink on the real engine: the brush catalog, the stored-row codecs and the replay of
// stored erase, move and resize operations, compatible with the Android app.

plugins {
    id("byteink.library")
}

dependencies {
    api(project(":byteink-core"))
}

tasks.test {
    // FuzzTest runs FuzzMain in a JVM of its own, on this classpath.
    val runtime = sourceSets.test.get().runtimeClasspath
    val iterations = providers.gradleProperty("byteinkFuzzIterations")
    val seed = providers.gradleProperty("byteinkFuzzSeed")
    inputs.property("fuzzIterations", iterations.orElse(""))
    inputs.property("fuzzSeed", seed.orElse(""))
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dbyteink.test.classpath=${runtime.asPath}") +
            iterations.map { listOf("-Dbyteink.test.fuzzIterations=$it") }.getOrElse(emptyList()) +
            seed.map { listOf("-Dbyteink.test.fuzzSeed=$it") }.getOrElse(emptyList())
    })
    // Exact wire encodings captured through the pinned Android app, including gzip transport.
    val matrix = rootProject.layout.projectDirectory.dir("conformance/android/fixtures/matrix")
    inputs.files(fileTree(matrix) {
        include("cases/**/*.inputs.pb.gz", "cases/**/*.roundtrip.pb.gz")
    }).withPropertyName("androidInputEncodingGoldens")
    val goldenPath = matrix.asFile.absolutePath
    jvmArgumentProviders.add(CommandLineArgumentProvider {
        listOf("-Dbyteink.test.androidInputGoldens=$goldenPath")
    })
}
