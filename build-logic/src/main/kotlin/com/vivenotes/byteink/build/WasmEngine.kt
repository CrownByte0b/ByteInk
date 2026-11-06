package com.vivenotes.byteink.build

import java.security.MessageDigest
import java.util.Properties
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class CheckWasmEngine : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val engineFiles: ConfigurableFileCollection
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val bridgeSource: RegularFileProperty
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val patches: ConfigurableFileCollection
    @get:Input abstract val engineCommit: Property<String>
    @get:Input abstract val emscriptenVersion: Property<String>

    @TaskAction fun checkEngine() {
        val files = engineFiles.files.associateBy { it.name }
        val names = listOf("byteink-engine.mjs", "byteink-engine.wasm", "build.properties")
        for (name in names) {
            check(files[name]?.isFile == true) { "Missing $name; run native/setup-wasm.sh and native/build-wasm.sh" }
        }
        val metadata = Properties().apply { files.getValue("build.properties").inputStream().use(::load) }
        check(metadata.getProperty("google.ink.commit") == engineCommit.get()) { "Wasm engine pin mismatch" }
        check(metadata.getProperty("emscripten.version") == emscriptenVersion.get()) { "Wasm toolchain pin mismatch" }
        check(metadata.getProperty("bridge.abi") == "2") { "Wasm bridge ABI mismatch" }
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(metadata.getProperty("bridge.source.sha256") == sha256(bridgeSource.get().asFile.readBytes())) {
            "Wasm bridge source has changed; rerun native/build-wasm.sh"
        }
        for (patch in patches.files) {
            check(metadata.getProperty("patch.${patch.name}") == sha256(patch.readBytes())) {
                "Wasm engine patch ${patch.name} has changed; rerun native/build-wasm.sh"
            }
        }
        for (name in names.take(2)) {
            val digest = sha256(files.getValue(name).readBytes())
            check(metadata.getProperty("$name.sha256") == digest) { "Wasm engine checksum mismatch: $name" }
        }
    }
}

abstract class GenerateWasmMetadata : DefaultTask() {
    @get:Input abstract val engineCommit: Property<String>
    @get:OutputDirectory abstract val directory: DirectoryProperty

    @TaskAction fun generate() {
        val commit = engineCommit.get()
        require(commit.matches(Regex("[0-9a-f]{40}"))) { "Invalid engine commit" }
        val target = directory.file("com/vivenotes/byteink/wasm/EnginePin.kt").get().asFile
        target.parentFile.mkdirs()
        target.writeText("package com.vivenotes.byteink.wasm\n\ninternal const val ENGINE_COMMIT: String = \"$commit\"\n")
    }
}
