package com.vivenotes.byteink.oracle

import java.io.File
import java.util.Properties

/** Build provenance for an explicitly selected native; validation binaries never enter the loader jar. */
internal object NativeProfile {
    fun read(file: File, expectedCommit: String, loadedSha256: String, baseline: Boolean): Map<String, String> {
        require(file.isFile) { "Missing native oracle metadata: $file; build native/build-oracle-baseline.sh first for baseline validation" }
        val properties = Properties().apply { file.inputStream().use(::load) }
        require(properties.getProperty("google.ink.commit") == expectedCommit) { "Oracle native source commit differs from the pin" }
        val binaryHash = properties.getProperty("libink.so.sha256") ?: properties.getProperty("ink.dll.sha256")
        require(binaryHash == loadedSha256) { "Oracle loaded a different native than its build metadata describes" }
        val angle = properties.getProperty("float.angle.math") ?: error("Oracle native metadata has no angle profile")
        val magnitude = properties.getProperty("float.magnitude.math") ?: error("Oracle native metadata has no magnitude profile")
        if (baseline) {
            require(properties.getProperty("validation.only") == "true" && angle == "platform" && magnitude == "platform") {
                "Google pin validation requires an unshipped platform-math baseline; production Android math is checked by the Android matrix"
            }
            require(properties.stringPropertyNames().none {
                it.startsWith("patch.0003-") || it.startsWith("patch.0004-")
            }) { "Oracle baseline metadata applies an Android arithmetic patch" }
            listOf("0003-use-android-float-angle-arithmetic.patch", "0004-use-android-float-magnitude-arithmetic.patch").forEach { patch ->
                require(properties.getProperty("omitted.patch.$patch")?.matches(Regex("[0-9a-f]{64}")) == true) {
                    "Oracle baseline metadata does not record its omitted arithmetic patch: $patch"
                }
            }
        } else require(properties.getProperty("validation.only") != "true") { "Production oracle must use the shipped native" }
        return mapOf("google-ink-commit" to expectedCommit, "angle-math" to angle, "magnitude-math" to magnitude,
            "library-role" to if (baseline) "validation-baseline" else "production")
    }
}
