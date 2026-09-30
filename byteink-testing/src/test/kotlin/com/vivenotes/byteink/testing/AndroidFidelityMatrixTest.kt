package com.vivenotes.byteink.testing

import com.vivenotes.byteink.oracle.SyntheticFidelityMatrix
import java.awt.image.BufferedImage
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class AndroidFidelityMatrixTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun deterministicMatrixCoversEveryFamilyStabilizationToolAndReplay() {
        val cases = SyntheticFidelityMatrix.cases
        assertEquals(280, cases.size)
        assertEquals(280, cases.map { it.id }.distinct().size)
        assertEquals(280, cases.map { it.pageId }.distinct().size)
        for (family in SyntheticFidelityMatrix.families) for (level in 0..5) for (tool in SyntheticFidelityMatrix.tools) {
            val name = tool.toString().substringAfterLast('.').lowercase()
            assertEquals(1, cases.count { it.family == family && it.stabilization == level && it.tool == name && it.scenario == "brush" })
        }
        assertEquals(40, cases.count { it.scenario == "operations" })
        assertEquals(listOf(2f, 8f, 20f), SyntheticFidelityMatrix.sizes)
        assertEquals(setOf(255, 128), SyntheticFidelityMatrix.colors.map { (it ushr 24) and 255 }.toSet())
        for (tool in SyntheticFidelityMatrix.tools) for (variant in 0..2) {
            val inputs = SyntheticFidelityMatrix.inputs(tool, variant)
            assertEquals(61, inputs.size)
            assertEquals(tool, inputs[0].toolType)
            assertTrue(inputs[inputs.size - 1].elapsedTimeMillis > inputs[0].elapsedTimeMillis)
        }
    }

    @Test
    fun committedAndroidSyntheticFixturePassesCompleteDesktopAcceptance() {
        val reference = File(requireNotNull(System.getProperty("byteink.test.matrix")) { "Gradle must provide the committed Android matrix" })
        val reports = File(requireNotNull(System.getProperty("byteink.test.matrixReports")))
        // Missing fixtures are a failure: Android availability is never a condition for this test.
        AndroidFidelityMatrix.prepare(reference, reports)
        val results = AndroidFidelityMatrix.compare(reference, reports)
        assertEquals(SyntheticFidelityMatrix.cases.size, results.size)
        assertTrue(results.all { it.geometry.integers > 0 && it.geometry.floats > 0 })
    }

    @Test
    fun topologyAndCountsStayExactAtMagnitudesWhereFloatToleranceWouldHideErrors() {
        val reference = Json.parseToJsonElement("""{"vertexCount":100000,"triangleIndices":[0,1,2],"bounds":[500.0]}""")
        val changed = Json.parseToJsonElement("""{"vertexCount":100001,"triangleIndices":[0,2,1],"bounds":[500.001]}""")
        val result = MatrixComparison.geometry(reference, changed)
        assertEquals(4, result.integers)
        assertEquals(1, result.floats)
        assertEquals(3, result.issues.size)
        assertTrue(result.issues.all { it.contains("exact integer") })
    }

    @Test
    fun geometryCatchesMissingFieldsNullCoverageAndCoordinateChanges() {
        val result = MatrixComparison.geometry(
            Json.parseToJsonElement("""{"bounds":null,"coverage":{"above50":true,"value":0.25},"position":[1.0,2.0]}"""),
            Json.parseToJsonElement("""{"bounds":[0.0],"coverage":{"above50":false},"position":[1.0,2.01]}"""),
        )
        assertEquals(4, result.issues.size)
        assertTrue(result.issues.any { "missing field" in it })
        assertTrue(result.issues.any { "coverage.above50" in it })
        assertTrue(result.issues.any { "position[1]" in it })
    }

    @Test
    fun manifestRejectsTamperingStaleArtifactsAndTraversal() {
        val directory = temporary.newFolder("manifest")
        val artifact = File(directory, "synthetic.vive").apply { writeText("non-sensitive fixture") }
        val manifest = File(directory, "manifest.sha256").apply { writeText("${MatrixComparison.sha256(artifact)}  synthetic.vive\n") }
        MatrixComparison.validateManifest(directory)
        artifact.appendText("changed")
        assertFailsWith<IllegalArgumentException> { MatrixComparison.validateManifest(directory) }
        manifest.writeText("${MatrixComparison.sha256(artifact)}  synthetic.vive\n")
        File(directory, "stale.png").writeText("stale")
        assertFailsWith<IllegalArgumentException> { MatrixComparison.validateManifest(directory) }
        manifest.writeText("${MatrixComparison.sha256(artifact)}  ../synthetic.vive\n")
        assertFailsWith<IllegalArgumentException> { MatrixComparison.validateManifest(directory) }
    }

    @Test
    fun inputProtobufComparisonExcludesOnlyThePinnedPrivateAnimationField() {
        val original = byteArrayOf(8, 3, 82, 2, 1, 2, 21, 0, 0, 0, 0)
        val changedPrivate = byteArrayOf(8, 3, 82, 1, 9, 21, 0, 0, 0, 0)
        assertTrue(MatrixComparison.publicInputProto(original).contentEquals(MatrixComparison.publicInputProto(changedPrivate)))
        val changedPublic = byteArrayOf(8, 2, 82, 1, 9, 21, 0, 0, 0, 0)
        assertTrue(!MatrixComparison.publicInputProto(original).contentEquals(MatrixComparison.publicInputProto(changedPublic)))
        assertFailsWith<IllegalArgumentException> { MatrixComparison.publicInputProto(byteArrayOf(82, 20, 1)) }
    }

    @Test
    fun provenanceRequiresThePinnedAndroidDeviceAndBrushSource() {
        val directory = temporary.newFolder("provenance")
        val expected = mapOf("schemaVersion" to "1", "generator" to "Android", "androidxInk" to "1.1.0-alpha06",
            "appCommit" to "af05908e57f69593e32107b97bcdf19250637fa3", "api" to "36", "abi" to "x86_64",
            "caseCount" to "280", "rawStrokeCount" to "840", "operationCases" to "40", "glesRenderer" to "SwiftShader",
            "buildFingerprint" to "test-device-build")
        val file = File(directory, "provenance.json")
        fun write(values: Map<String, String>) { file.writeText(JsonObject(values.mapValues { JsonPrimitive(it.value) }).toString()) }
        val captureFile = File(directory, "capture-environment.json")
        val capturedSources = listOf("src/com/vivenotes/byteink/AndroidNotebookOracleTest.kt",
            "src/com/vivenotes/byteink/AndroidSyntheticMatrix.kt", "src/com/vivenotes/byteink/AndroidTriangleReference.kt",
            "shared/com/vivenotes/byteink/oracle/MatrixGeometry.kt", "shared/com/vivenotes/byteink/oracle/StrokeDiagnostics.kt",
            "shared/com/vivenotes/byteink/oracle/SyntheticFidelityMatrix.kt")
        val environment = JsonObject(mapOf("ro.build.version.sdk" to JsonPrimitive("36"), "ro.product.cpu.abi" to JsonPrimitive("x86_64"),
            "ro.build.fingerprint" to JsonPrimitive("test-device-build"), "graphics" to JsonPrimitive("GLES: SwiftShader"),
            "emulator" to JsonPrimitive("Android emulator version 37.1.11.0")))
        val capture = JsonObject(mapOf("android_app_commit" to JsonPrimitive(expected.getValue("appCommit")),
            "androidx_ink" to JsonPrimitive(expected.getValue("androidxInk")), "environment" to environment,
            "sources" to JsonObject(capturedSources.associate { "conformance/android/$it" to JsonPrimitive("a".repeat(64)) }),
            "apk_sha256" to JsonObject(mapOf("app-debug.apk" to JsonPrimitive("b".repeat(64)),
                "app-debug-androidTest.apk" to JsonPrimitive("c".repeat(64))))))
        captureFile.writeText(capture.toString())
        write(expected)
        AndroidFidelityMatrix.validateProvenance(directory)
        for ((key, value) in mapOf("generator" to "Desktop", "api" to "35", "abi" to "arm64-v8a", "glesRenderer" to "host GPU",
            "androidxInk" to "1.1.0-alpha09", "rawStrokeCount" to "839")) {
            write(expected + (key to value))
            assertFailsWith<IllegalArgumentException> { AndroidFidelityMatrix.validateProvenance(directory) }
        }
        write(expected)
        captureFile.writeText(JsonObject(capture + ("environment" to JsonObject(environment + ("ro.build.version.sdk" to JsonPrimitive("35"))))).toString())
        assertFailsWith<IllegalArgumentException> { AndroidFidelityMatrix.validateProvenance(directory) }
        captureFile.writeText(JsonObject(capture + ("apk_sha256" to JsonObject(mapOf("app-debug.apk" to JsonPrimitive("missing test APK"))))).toString())
        assertFailsWith<IllegalArgumentException> { AndroidFidelityMatrix.validateProvenance(directory) }
    }

    @Test
    fun referenceInventoryCannotSilentlyLoseAnInputRoundtripOrSupplementalImage() {
        val reference = File(requireNotNull(System.getProperty("byteink.test.matrix")))
        val paths = MatrixComparison.validateManifest(reference).keys
        val cases = AndroidFidelityMatrix.cases(reference)
        AndroidFidelityMatrix.validateArtifactInventory(paths, cases)
        for (artifact in listOf(paths.first { it.endsWith(".roundtrip.pb.gz") },
            paths.first { it.startsWith("images/softwareTriangles/") }, paths.first { it.endsWith("family.pb") })) {
            assertFailsWith<IllegalArgumentException> { AndroidFidelityMatrix.validateArtifactInventory(paths - artifact, cases) }
        }
        assertFailsWith<IllegalArgumentException> { AndroidFidelityMatrix.validateArtifactInventory(paths + "stale.png", cases) }
    }

    @Test
    fun hardwareDeviationGateAdmitsHueAccumulationButRejectsWrongInkAndMissingStrokes() {
        fun image(color: Int): BufferedImage = BufferedImage(96, 96, BufferedImage.TYPE_INT_RGB).apply {
            for (y in 0 until height) for (x in 0 until width) setRGB(x, y, 0xffffff)
            for (y in 36..59) for (x in 36..59) setRGB(x, y, color)
        }
        val path = image(0xbf9fdf)
        val accumulated = image(0x9f6fcf)
        val allowed = MatrixComparison.pixels(accumulated, path, true, 0xbf9fdf)
        assertEquals(0, allowed.unexplainedInterior)
        assertTrue(MatrixComparison.hardwareIssues(allowed).isEmpty())
        val discard = MatrixComparison.pixels(accumulated, path, false)
        assertTrue(discard.unexplainedInterior > 0)
        val wrong = MatrixComparison.pixels(image(0xffff00), path, true)
        assertTrue(wrong.unexplainedInterior > 0)
        assertTrue(MatrixComparison.hardwareIssues(wrong).isNotEmpty())
        val missing = MatrixComparison.pixels(path, image(0xffffff), true)
        assertTrue(MatrixComparison.hardwareIssues(missing).isNotEmpty())
        val opaqueDarkening = MatrixComparison.pixels(image(0x00277a), image(0x173b83), true, 0xbf9fdf)
        assertTrue(opaqueDarkening.unexplainedInterior > 0)
        assertTrue(MatrixComparison.hardwareIssues(opaqueDarkening).isNotEmpty())
        val exact = MatrixComparison.pixels(path, path, false)
        assertEquals(0, exact.maximum)
        assertEquals(0.0, exact.differingPercent)
        assertEquals(1.0, exact.ssim)
    }
}
