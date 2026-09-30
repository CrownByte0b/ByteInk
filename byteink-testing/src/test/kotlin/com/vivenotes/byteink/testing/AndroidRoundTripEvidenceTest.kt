package com.vivenotes.byteink.testing

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Lightweight evidence lifecycle tests: no native engine, notebook preparation or emulator. */
class AndroidRoundTripEvidenceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun successfulCaptureIsBoundToItsExactInputsAndArtifacts() {
        val fixture = fixture()
        val captured = AndroidNotebookRoundTrip.validateCapturedEvidence(fixture.root, fixture.expected)
        assertEquals(JsonPrimitive(true), captured["instrumentation_passed"])
        // A desktop-generated verification copy belongs outside the captured Android inventory.
        File(fixture.root, "unknown-desktop-copy.vive").writeText("desktop verification output")
        AndroidNotebookRoundTrip.validateCapturedEvidence(fixture.root, fixture.expected)
    }

    @Test
    fun failedInstrumentationAndUnpinnedReferencesCannotCertifyStaleArtifacts() {
        listOf(
            "instrumentation_passed" to JsonPrimitive(false),
            "android_app_commit" to JsonPrimitive("a different app"),
            "androidx_ink" to JsonPrimitive("1.1.0-alpha07"),
        ).forEach { (field, value) ->
            val fixture = fixture()
            val capture = readCapture(fixture.root)
            writeCapture(fixture.root, JsonObject(capture + (field to value)))
            refreshManifest(fixture.root)
            assertFailsWith<IllegalArgumentException>(field) {
                AndroidNotebookRoundTrip.validateCapturedEvidence(fixture.root, fixture.expected)
            }
        }
        listOf("ro.build.version.sdk" to "35", "ro.product.cpu.abi" to "arm64-v8a", "graphics" to "GLES: host GPU")
            .forEach { (field, value) ->
                val fixture = fixture()
                val capture = readCapture(fixture.root)
                writeCapture(fixture.root, JsonObject(capture + ("environment" to JsonObject(
                    capture.getValue("environment").jsonObject + (field to JsonPrimitive(value))))))
                refreshManifest(fixture.root)
                assertFailsWith<IllegalArgumentException>(field) {
                    AndroidNotebookRoundTrip.validateCapturedEvidence(fixture.root, fixture.expected)
                }
            }
    }

    @Test
    fun changedOrUnlistedPreparedInputsAreRejected() {
        val changed = fixture()
        File(changed.root, "desktop.vive").appendText("changed after Android capture")
        val error = assertFailsWith<IllegalArgumentException> {
            AndroidNotebookRoundTrip.validateCapturedEvidence(changed.root, changed.expected)
        }
        assertTrue(error.message!!.contains("input checksum differs: desktop.vive"))
        val omitted = fixture()
        val capture = readCapture(omitted.root)
        writeCapture(omitted.root, JsonObject(capture + ("roundtrip_inputs_sha256" to JsonObject(
            capture.getValue("roundtrip_inputs_sha256").jsonObject - "unknown-enc.vive"))))
        refreshManifest(omitted.root)
        assertFailsWith<IllegalArgumentException> {
            AndroidNotebookRoundTrip.validateCapturedEvidence(omitted.root, omitted.expected)
        }
    }

    @Test
    fun changedMissingOrExtraCapturedFilesAreRejected() {
        val mutations: List<(File) -> Unit> = listOf(
            { File(it, "android/observed.json").appendText("tampered") },
            { File(it, "android/reexport.vive").delete() },
            { File(it, "android/unlisted.vive").writeText("stale retry output") },
            { File(it, "android/unknown-desktop-copy.vive").writeText("misplaced generated output") },
            { File(it, "android/manifest.sha256").appendText(File(it, "android/manifest.sha256").readLines().first() + "\n") },
            { root ->
                val manifest = File(root, "android/manifest.sha256")
                manifest.writeText(manifest.readLines().filterNot { it.endsWith("  observed.json") }.joinToString("\n", postfix = "\n"))
            },
        )
        mutations.forEach { mutation ->
            val fixture = fixture()
            mutation(fixture.root)
            assertFailsWith<IllegalArgumentException> {
                AndroidNotebookRoundTrip.validateCapturedEvidence(fixture.root, fixture.expected)
            }
        }
    }

    @Test
    fun traversalPathsAndDuplicateOwnedIdsAreRejectedBeforeReadingEvidence() {
        val fixture = fixture()
        listOf("../outside.json", "/outside.json", "C:/outside.json", "pages\\page\\geometry.json").forEach { unsafe ->
            val page = JsonObject(mapOf("id" to JsonPrimitive("page"), "geometry" to JsonPrimitive(unsafe)))
            val expected = JsonObject(fixture.expected + ("pages" to JsonArray(listOf(page))))
            assertFailsWith<IllegalArgumentException> { AndroidNotebookRoundTrip.validateCapturedEvidence(fixture.root, expected) }
        }
        val duplicate = JsonObject(fixture.expected + ("encodingProbes" to fixture.expected.getValue("authoredRows")))
        assertFailsWith<IllegalArgumentException> { AndroidNotebookRoundTrip.validateCapturedEvidence(fixture.root, duplicate) }
    }

    private data class Fixture(val root: File, val expected: JsonObject)

    private fun fixture(): Fixture {
        val root = temporary.newFolder()
        val expected = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1),
            "pages" to JsonArray(emptyList()),
            "authoredRows" to JsonArray(listOf(JsonObject(mapOf("id" to JsonPrimitive("stroke"))))),
            "erases" to JsonArray(emptyList()), "encodingProbes" to JsonArray(emptyList())))
        File(root, "expectations.json").writeText(expected.toString())
        File(root, "desktop.vive").writeText("synthetic desktop input")
        File(root, "unknown-enc.vive").writeText("synthetic opaque input")
        val android = File(root, "android").apply { mkdirs() }
        listOf("observed.json", "reexport.vive", "reimport-reexport.vive", "unknown-android.vive").forEach {
            File(android, it).writeText("synthetic captured artifact $it")
        }
        File(android, "encoding").mkdirs()
        listOf("desktop", "original", "reencoded").forEach { name ->
            listOf("pb", "pb.gz").forEach { extension ->
                File(android, "encoding/stroke.$name.$extension").writeText("synthetic encoder artifact $name")
            }
        }
        val hashes = listOf("expectations.json", "desktop.vive", "unknown-enc.vive").associateWith {
            JsonPrimitive(MatrixComparison.sha256(File(root, it)))
        }
        writeCapture(root, JsonObject(mapOf("instrumentation_passed" to JsonPrimitive(true),
            "android_app_commit" to JsonPrimitive("af05908e57f69593e32107b97bcdf19250637fa3"),
            "androidx_ink" to JsonPrimitive("1.1.0-alpha06"),
            "environment" to JsonObject(mapOf("ro.build.version.sdk" to JsonPrimitive("36"),
                "ro.product.cpu.abi" to JsonPrimitive("x86_64"), "graphics" to JsonPrimitive("GLES: Google SwiftShader"))),
            "roundtrip_inputs_sha256" to JsonObject(hashes))))
        refreshManifest(root)
        AndroidNotebookRoundTrip.validateCapturedEvidence(root, expected)
        return Fixture(root, expected)
    }

    private fun readCapture(root: File): JsonObject = Json.parseToJsonElement(File(root, "android/capture-environment.json").readText()).jsonObject
    private fun writeCapture(root: File, value: JsonObject) { File(root, "android/capture-environment.json").writeText(value.toString()) }
    private fun refreshManifest(root: File) {
        val android = File(root, "android")
        val manifest = File(android, "manifest.sha256")
        val files = android.walkTopDown().filter { it.isFile && it != manifest }.sortedBy { it.relativeTo(android).invariantSeparatorsPath }
        manifest.writeText(files.joinToString("\n", postfix = "\n") {
            "${MatrixComparison.sha256(it)}  ${it.relativeTo(android).invariantSeparatorsPath}"
        })
    }
}
