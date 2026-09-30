package com.vivenotes.byteink.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.storage.encode
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import com.vivenotes.byteink.oracle.MatrixCase
import com.vivenotes.byteink.oracle.OracleProjection
import com.vivenotes.byteink.oracle.SyntheticFidelityMatrix
import com.vivenotes.byteink.oracle.geometryJson
import com.vivenotes.byteink.vive.PageStroke
import com.vivenotes.byteink.vive.ViveInkPage
import com.vivenotes.byteink.vive.automaticColorOr
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.int
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface

/** Android-produced, non-sensitive matrix acceptance that requires no running emulator. */
internal object AndroidFidelityMatrix {
    private val json = Json { prettyPrint = true }

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 3) { "Expected prepare|compare|run REFERENCE_DIRECTORY OUTPUT_DIRECTORY" }
        val reference = File(args[1])
        val output = File(args[2])
        when (args[0]) {
            "prepare" -> prepare(reference, output)
            "compare" -> compare(reference, output)
            "run" -> { prepare(reference, output); compare(reference, output) }
            else -> error("Unknown Android matrix command: ${args[0]}")
        }
    }

    internal fun cases(reference: File): List<MatrixCase> {
        val lines = File(reference, "cases.tsv").readLines()
        require(lines.firstOrNull() == SyntheticFidelityMatrix.header) { "Android matrix schema header differs" }
        val cases = lines.drop(1).filter { it.isNotBlank() }.map(MatrixCase::parse)
        require(cases.map { it.toTsv() } == SyntheticFidelityMatrix.cases.map { it.toTsv() }) {
            "Android matrix is incomplete or differs from deterministic family/stabilization/tool/operation coverage"
        }
        require(cases.map { it.id }.distinct().size == cases.size && cases.map { it.pageId }.distinct().size == cases.size)
        return cases
    }

    internal fun validateProvenance(reference: File) {
        val provenance = Json.parseToJsonElement(File(reference, "provenance.json").readText()).jsonObject
        val expected = mapOf("schemaVersion" to "1", "generator" to "Android", "androidxInk" to "1.1.0-alpha06",
            "appCommit" to "af05908e57f69593e32107b97bcdf19250637fa3", "api" to "36", "abi" to "x86_64",
            "caseCount" to "280", "rawStrokeCount" to "840", "operationCases" to "40")
        expected.forEach { (key, value) ->
            require(provenance[key]?.jsonPrimitive?.content == value) { "Android reference provenance $key must be $value" }
        }
        require(provenance["glesRenderer"]?.jsonPrimitive?.content?.contains("SwiftShader", ignoreCase = true) == true) {
            "Android reference must be captured with the fixed SwiftShader renderer"
        }
        val capture = Json.parseToJsonElement(File(reference, "capture-environment.json").readText()).jsonObject
        require(capture["android_app_commit"]?.jsonPrimitive?.content == expected.getValue("appCommit") &&
            capture["androidx_ink"]?.jsonPrimitive?.content == expected.getValue("androidxInk")) {
            "Android capture environment disagrees with the pinned brush source"
        }
        val environment = capture.getValue("environment").jsonObject
        require(environment["ro.build.version.sdk"]?.jsonPrimitive?.content == "36" &&
            environment["ro.product.cpu.abi"]?.jsonPrimitive?.content == "x86_64" &&
            environment["ro.build.fingerprint"]?.jsonPrimitive?.content == provenance["buildFingerprint"]?.jsonPrimitive?.content &&
            environment["graphics"]?.jsonPrimitive?.content?.contains(provenance.getValue("glesRenderer").jsonPrimitive.content) == true &&
            environment["emulator"]?.jsonPrimitive?.content?.startsWith("Android emulator version ") == true) {
            "Android capture environment disagrees with the instrumented device/GLES provenance"
        }
        val sources = capture.getValue("sources").jsonObject
        val expectedSources = setOf(
            "conformance/android/src/com/vivenotes/byteink/AndroidNotebookOracleTest.kt",
            "conformance/android/src/com/vivenotes/byteink/AndroidSyntheticMatrix.kt",
            "conformance/android/src/com/vivenotes/byteink/AndroidTriangleReference.kt",
            "conformance/android/shared/com/vivenotes/byteink/oracle/MatrixGeometry.kt",
            "conformance/android/shared/com/vivenotes/byteink/oracle/StrokeDiagnostics.kt",
            "conformance/android/shared/com/vivenotes/byteink/oracle/SyntheticFidelityMatrix.kt",
        )
        require(sources.keys == expectedSources && sources.values.all { it.jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")) }) {
            "Incomplete Android capture source hash inventory"
        }
        val apks = capture.getValue("apk_sha256").jsonObject
        require(apks.keys == setOf("app-debug.apk", "app-debug-androidTest.apk") &&
            apks.values.all { it.jsonPrimitive.content.matches(Regex("[0-9a-f]{64}")) }) { "Incomplete Android capture APK hash inventory" }
    }

    internal fun validateArtifactInventory(paths: Set<String>, cases: List<MatrixCase>) {
        val expected = buildSet {
            addAll(listOf("cases.tsv", "synthetic.vive", "provenance.json", "capture-environment.json"))
            cases.forEach { case ->
                add("cases/${case.id}/geometry.json")
                add("cases/${case.id}/family.pb")
                add("cases/${case.id}/family.pb.gz")
                repeat(3) { variant ->
                    val id = SyntheticFidelityMatrix.strokeId(case, variant)
                    add("cases/${case.id}/$id.inputs.pb.gz")
                    add("cases/${case.id}/$id.roundtrip.pb.gz")
                }
                listOf("hardware", "path", "software", "softwareTriangles").forEach { mode -> add("images/$mode/${case.id}.png") }
            }
        }
        require(paths == expected) { "Android reference artifact coverage differs: missing=${expected - paths}; unexpected=${paths - expected}" }
    }

    internal fun prepare(reference: File, output: File) {
        require(!output.canonicalFile.toPath().startsWith(reference.canonicalFile.toPath())) {
            "Desktop reports must be outside immutable Android fixtures"
        }
        val manifest = MatrixComparison.validateManifest(reference)
        validateProvenance(reference)
        val cases = cases(reference)
        validateArtifactInventory(manifest.keys, cases)
        output.mkdirs()
        val desktop = File(output, "desktop").apply { mkdirs() }
        val native = InkNativeLibrary.load()
        val runtime = JsonObject(mapOf(
            "os" to JsonPrimitive(System.getProperty("os.name")),
            "architecture" to JsonPrimitive(System.getProperty("os.arch")),
            "javaVendor" to JsonPrimitive(System.getProperty("java.vendor")),
            "javaVersion" to JsonPrimitive(System.getProperty("java.version")),
            "nativeSha256" to JsonPrimitive(native.sha256),
            "referenceManifestSha256" to JsonPrimitive(MatrixComparison.sha256(File(reference, "manifest.sha256"))),
            "androidProvenance" to Json.parseToJsonElement(File(reference, "provenance.json").readText()),
            "androidCaptureEnvironment" to Json.parseToJsonElement(File(reference, "capture-environment.json").readText()),
        ))
        File(desktop, "runtime.json").writeText(json.encodeToString(JsonElement.serializer(), runtime))
        ViveNotebook.open(File(reference, "synthetic.vive")).use { notebook ->
            require(notebook.pageIds.toSet() == cases.map { it.pageId }.toSet()) { "Synthetic notebook page inventory differs" }
            val renderer = InkPathRenderer()
            cases.forEach { case ->
                val page = notebook.page(case.pageId)
                require(page.strokes.size == 3 && page.strokes.none { it.deletedAt != null }) {
                    "Matrix must contain three live size/colour variants: ${case.id}"
                }
                page.strokes.forEach { row ->
                    val variant = row.seq
                    require(variant in 0..2 && row.id == SyntheticFidelityMatrix.strokeId(case, variant) &&
                        row.brushFamily == case.family && row.brushVersion == 1 && row.stabilization == case.stabilization &&
                        row.sizeDp == SyntheticFidelityMatrix.sizes[variant] && row.colorArgb == SyntheticFidelityMatrix.colors[variant] &&
                        row.colorFollowsTheme == (variant == 2) && row.epsilon == 0.25f) {
                        "Android fixture row does not cover its declared matrix variant: ${case.id}/${row.id}"
                    }
                }
                require(page.strokes.map { it.seq }.toSet() == setOf(0, 1, 2)) { "Missing matrix stroke variant: ${case.id}" }
                if (case.scenario == "operations") {
                    require(page.erases.size == 2 && page.erases.map { it.mode }.toSet() == setOf("Normal", "Object") &&
                        page.moves.size == 2 && page.moves.any { it.dxDp != 0f || it.dyDp != 0f } &&
                        page.moves.any { it.scaleX != 1f || it.scaleY != 1f }) { "Missing matrix replay operation: ${case.id}" }
                } else require(page.erases.isEmpty() && page.moves.isEmpty()) { "Unexpected operations on brush matrix case: ${case.id}" }
                val raw = page.strokes.filter { it.deletedAt == null }.sortedWith(compareBy({ it.seq }, { it.id })).map { row ->
                    requireNotNull(ViveInkPage.decode(row)) { "Unreadable Android input: ${case.id}/${row.id}" }
                }
                require(raw.all { projection ->
                    projection.stroke.inputs.size == SyntheticFidelityMatrix.inputs(SyntheticFidelityMatrix.tool(case.tool), 0).size &&
                        projection.stroke.inputs[0].toolType == SyntheticFidelityMatrix.tool(case.tool)
                }) { "Android fixture does not contain its declared input tool and sample count: ${case.id}" }
                require(raw.isNotEmpty()) { "Android matrix page has no synthetic strokes: ${case.id}" }
                val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                require(loaded.unreadable.isEmpty()) { "Unreadable matrix strokes in ${case.id}: ${loaded.unreadable}" }
                val directory = File(desktop, "cases/${case.id}").apply { mkdirs() }
                File(directory, "geometry.json").writeText(geometryJson(raw.map(::projection), loaded.strokes.map(::projection)))
                raw.forEachIndexed { index, stroke ->
                    if (index == 0) {
                        val family = encoded { stroke.stroke.brush.family.encode(it) }
                        File(directory, "family.pb.gz").writeBytes(family)
                        File(directory, "family.pb").writeBytes(gunzip(family))
                    }
                    val row = page.strokes.single { it.id == stroke.id }
                    // The source blob is copied only as evidence; geometry is independently rebuilt
                    // using the desktop catalog and decoded Android input bytes.
                    File(directory, "${stroke.id}.inputs.pb.gz").writeBytes(row.points)
                    File(directory, "${stroke.id}.roundtrip.pb.gz").writeBytes(encoded { stroke.stroke.inputs.encode(it) })
                }
                draw(case, loaded.strokes, File(desktop, "images/${case.id}.png"), renderer)
            }
        }
        println("Prepared ${cases.size} Android matrix cases on ${System.getProperty("os.name")} using Ink ${native.sha256}")
    }

    private fun encoded(write: (ByteArrayOutputStream) -> Unit): ByteArray = ByteArrayOutputStream().use { output ->
        write(output)
        output.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(bytes.inputStream()).use { it.readBytes() }

    private fun projection(stroke: PageStroke): OracleProjection = OracleProjection(
        stroke.id, stroke.stroke, stroke.offsetX, stroke.offsetY, stroke.scaleX, stroke.scaleY, stroke.colorFollowsTheme,
    )

    private fun draw(case: MatrixCase, strokes: List<PageStroke>, file: File, renderer: InkPathRenderer) {
        Surface.makeRasterN32Premul(case.width, case.height).use { surface ->
            surface.canvas.clear(0xffffffff.toInt())
            val canvas = surface.canvas.asComposeCanvas()
            val viewport = Rect(0f, 0f, case.width.toFloat(), case.height.toFloat())
            strokes.forEach { stroke ->
                val transform = ImmutableAffineTransform(
                    case.scale * stroke.scaleX, 0f, 16f + case.scale * (stroke.offsetX - case.left),
                    0f, case.scale * stroke.scaleY, 16f + case.scale * (stroke.offsetY - case.top),
                )
                renderer.draw(canvas, stroke.stroke, transform, viewport,
                    automaticColorOr(stroke.stroke.brush.colorIntArgb, stroke.colorFollowsTheme, case.themeArgb))
            }
            surface.makeImageSnapshot().use { image ->
                requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { data ->
                    file.parentFile.mkdirs()
                    file.writeBytes(data.bytes)
                }
            }
        }
    }

    data class Encoding(val name: String, val compressedIdentical: Boolean, val protoIdentical: Boolean,
        val publicProtoIdentical: Boolean, val sourceBytes: Boolean) {
        val passed: Boolean get() = if (sourceBytes) compressedIdentical else publicProtoIdentical
        fun json(): JsonObject = JsonObject(mapOf("file" to JsonPrimitive(name),
            "rawBytesIdentical" to JsonPrimitive(compressedIdentical), "protobufIdentical" to JsonPrimitive(protoIdentical),
            "publicProtobufIdentical" to JsonPrimitive(publicProtoIdentical), "passed" to JsonPrimitive(passed),
            "acceptedDifference" to JsonPrimitive(when {
                compressedIdentical -> "none"
                protoIdentical -> "JVM/Android deflate stream differs; protobuf bytes identical"
                publicProtoIdentical -> "Google internal animation-phase field 10 differs; all public protobuf bytes identical"
                else -> "unaccepted"
            })))
    }

    data class CaseResult(
        val case: MatrixCase, val geometry: MatrixComparison.Geometry, val encodingChecks: Int,
        val encodings: List<Encoding>,
        val software: MatrixComparison.Pixels, val hardware: MatrixComparison.Pixels, val path: MatrixComparison.Pixels,
        val originalSoftware: MatrixComparison.Pixels, val triangleFallbackGroups: Int,
        val issues: List<String>,
    ) {
        fun json(): JsonObject = JsonObject(mapOf(
            "case" to JsonPrimitive(case.id), "family" to JsonPrimitive(case.family),
            "stabilization" to JsonPrimitive(case.stabilization), "tool" to JsonPrimitive(case.tool),
            "scenario" to JsonPrimitive(case.scenario), "encodingChecks" to JsonPrimitive(encodingChecks),
            "encodings" to JsonArray(encodings.map { it.json() }),
            "geometry" to JsonObject(mapOf("exactIntegerValues" to JsonPrimitive(geometry.integers),
                "floatValues" to JsonPrimitive(geometry.floats), "maxFloatGap" to JsonPrimitive(geometry.maximumGap),
                "issues" to JsonArray(geometry.issues.map(::JsonPrimitive)))),
            "softwarePath" to software.json(), "hardwareDefault" to hardware.json(), "hardwareForcedPath" to path.json(),
            "androidOriginalSoftwarePath" to originalSoftware.json(), "triangleFallbackGroups" to JsonPrimitive(triangleFallbackGroups),
            "softwareAcceptanceReference" to JsonPrimitive(if (triangleFallbackGroups > 0) "Android software triangle union" else "Android CanvasStrokeRenderer software path"),
            "passed" to JsonPrimitive(issues.isEmpty()), "issues" to JsonArray(issues.map(::JsonPrimitive)),
        ))
    }

    internal fun compare(reference: File, output: File): List<CaseResult> {
        val manifest = MatrixComparison.validateManifest(reference)
        validateProvenance(reference)
        val cases = cases(reference)
        validateArtifactInventory(manifest.keys, cases)
        val desktop = File(output, "desktop")
        val results = cases.map { case ->
            val androidCase = File(reference, "cases/${case.id}")
            val desktopCase = File(desktop, "cases/${case.id}")
            val androidGeometry = Json.parseToJsonElement(File(androidCase, "geometry.json").readText())
            val geometry = MatrixComparison.geometry(
                androidGeometry,
                Json.parseToJsonElement(File(desktopCase, "geometry.json").readText()),
            )
            val fallbackGroups = androidGeometry.jsonObject.getValue("replayed").jsonArray.sumOf { stroke ->
                stroke.jsonObject.getValue("groups").jsonArray.count { group ->
                    group.jsonObject.getValue("outlineCount").jsonPrimitive.int == 0 &&
                        group.jsonObject.getValue("meshes").jsonArray.any { mesh -> mesh.jsonObject.getValue("triangleCount").jsonPrimitive.int > 0 }
                }
            }
            require(fallbackGroups == 0 || case.scenario == "operations") {
                "A brush-only case unexpectedly needs a split-mesh rendering exception: ${case.id}"
            }
            val issues = geometry.issues.toMutableList()
            val encodingFiles = androidCase.listFiles()!!.filter { it.name.endsWith(".pb.gz") || it.name == "family.pb" }.sortedBy { it.name }
            require(encodingFiles.any { it.name == "family.pb.gz" } && encodingFiles.any { it.name == "family.pb" }) {
                "Missing authoritative uncompressed Android brush family encoding for ${case.id}"
            }
            val encodings = encodingFiles.map { file ->
                val candidate = File(desktopCase, file.name)
                require(candidate.isFile) { "Missing desktop encoding artifact: $candidate" }
                val a = file.readBytes()
                val b = candidate.readBytes()
                val firstProto = if (file.name.endsWith(".gz")) gunzip(a) else a
                val secondProto = if (file.name.endsWith(".gz")) gunzip(b) else b
                val protoIdentical = firstProto.contentEquals(secondProto)
                val publicIdentical = if (file.name.endsWith(".roundtrip.pb.gz")) {
                    MatrixComparison.publicInputProto(firstProto).contentEquals(MatrixComparison.publicInputProto(secondProto))
                } else protoIdentical
                Encoding(file.name, a.contentEquals(b), protoIdentical, publicIdentical, file.name.endsWith(".inputs.pb.gz"))
                    .also { if (!it.passed) issues += "Encoding public/source bytes differ: ${file.name}" }
            }
            val image = MatrixComparison.readImage(File(desktop, "images/${case.id}.png"))
            val originalSoftware = MatrixComparison.pixels(MatrixComparison.readImage(File(reference, "images/software/${case.id}.png")), image, false)
            val software = if (fallbackGroups == 0) originalSoftware else MatrixComparison.pixels(
                MatrixComparison.readImage(File(reference, "images/softwareTriangles/${case.id}.png")), image, false)
            val hardware = MatrixComparison.pixels(MatrixComparison.readImage(File(reference, "images/hardware/${case.id}.png")), image,
                case.family != "highlighter", compositeOnWhite(SyntheticFidelityMatrix.colors[1]))
            val path = MatrixComparison.pixels(MatrixComparison.readImage(File(reference, "images/path/${case.id}.png")), image, false)
            if (software.maximum > 2) issues += "Software path maximum RGB delta ${software.maximum} exceeds 2"
            issues += MatrixComparison.hardwareIssues(hardware)
            // Forced-path hardware capture separates mesh/self-overlap from hardware rasterization.
            if (fallbackGroups == 0) issues += MatrixComparison.hardwareIssues(path).map { "forced path: $it" }
            CaseResult(case, geometry, encodingFiles.size, encodings, software, hardware, path, originalSoftware, fallbackGroups, issues)
        }
        val report = JsonObject(mapOf(
            "schemaVersion" to JsonPrimitive(1),
            "runtime" to Json.parseToJsonElement(File(desktop, "runtime.json").readText()),
            "caseCount" to JsonPrimitive(results.size), "passed" to JsonPrimitive(results.all { it.issues.isEmpty() }),
            "thresholds" to JsonObject(mapOf(
                "softwareMaxChannelDelta" to JsonPrimitive(2), "floatAbsolute" to JsonPrimitive(1e-4), "floatRelative" to JsonPrimitive(1e-5),
                "hardwareMinimumSsim" to JsonPrimitive(0.95), "hardwareMaximumMae" to JsonPrimitive(5.0),
                "hardwareMinimumInkAreaRatio" to JsonPrimitive(0.85), "hardwareMaximumInkAreaRatio" to JsonPrimitive(1.15),
                "hardwareMaximumUnexplainedInteriorPixels" to JsonPrimitive(0),
            )),
            "cases" to JsonArray(results.map { it.json() }),
        ))
        File(output, "comparison.json").writeText(json.encodeToString(JsonElement.serializer(), report))
        File(output, "comparison.md").writeText(markdown(results, File(desktop, "runtime.json").readText()))
        val illustrations = (results.filter { it.issues.isNotEmpty() } + results.sortedBy { it.hardware.ssim }.take(10))
            .distinctBy { it.case.id }.take(30)
        illustrations.forEach { result ->
            val candidate = MatrixComparison.readImage(File(desktop, "images/${result.case.id}.png"))
            val android = MatrixComparison.readImage(File(reference, "images/hardware/${result.case.id}.png"))
            val difference = AndroidNotebookFidelity.pixels(candidate, android).difference
            val destination = File(output, "illustrations/${result.case.id}-difference.png").apply { parentFile.mkdirs() }
            ImageIO.write(difference, "PNG", destination)
        }
        println("Android matrix: ${results.size} cases, ${results.sumOf { it.encodingChecks }} encoding checks, " +
            "${results.count { it.issues.isNotEmpty() }} failing cases; ${File(output, "comparison.md").absolutePath}")
        check(results.all { it.issues.isEmpty() }) {
            "Android matrix fidelity failed: " + results.filter { it.issues.isNotEmpty() }.take(5)
                .joinToString("; ") { "${it.case.id}: ${it.issues.take(3).joinToString()}" } +
                "; see ${File(output, "comparison.md").absolutePath}"
        }
        return results
    }

    private fun markdown(results: List<CaseResult>, runtime: String): String = buildString {
        append("# Android / desktop brush fidelity matrix\n\n")
        append("${results.size} deterministic Android-produced cases; ${results.count { it.issues.isEmpty() }} pass. " +
            "Each brush page contains size 2 opaque, size 8 translucent self-overlap and size 20 automatic-theme strokes. " +
            "Additional pages apply Normal/Object erases, move and resize through the Android app's actual replay.\n\n")
        append("```json\n$runtime\n```\n\n")
        append("The archive and every Android reference pass the committed SHA-256 inventory. " +
            "Desktop rebuilds stored inputs using its own brush catalog and replay; the source input bytes, " +
            "independently encoded brush family protobufs and public Android-versus-desktop input decode/encode protobufs must match byte for byte. " +
            "Gzip/deflate streams are measured separately: Android and JVM zlib produce different compressed bytes. " +
            "Input re-encoding also measures the pinned engine's private animation-phase field 10 separately, as the existing native oracle does; " +
            "every remaining protobuf byte must match. Source Android input blobs stay byte-identical. " +
            "All geometry JSON fields are compared: integer counts and triangle indices, identities, input tool/times, " +
            "coverage threshold booleans and null geometry are exact; floating point values pass only within " +
            "1e-4 + 1e-5 × max(abs(a), abs(b)).\n\n")
        append("Software path acceptance requires maximum RGB channel delta ≤2 at every pixel. " +
            "Hardware default and forced-path captures require SSIM ≥0.95, mean absolute RGB channel error ≤5, " +
            "ink area ratio 0.85–1.15 and zero unexplained interior pixels with delta >16. " +
            "Existing accepted hardware deviations are restricted to a two-pixel RGB/coverage-edge band, including internal overlap edges, " +
            "and (only for translucent ANY brushes) hardware darkening along the same RGB hue direction " +
            "with ratio 0.95–2.1 and residual ≤5 channels; the path pixel must match the known translucent composite within two RGB levels. " +
            "Opaque ink and DISCARD highlighter have no overlap exception. " +
            "These gates reject missing ink, wrong colours, changed scale/position and unrelated interior changes.\n\n")
        append("The pinned Android CanvasPathRenderer omits split meshes with triangles but no outlines. " +
            "Desktop intentionally renders those pieces through its triangle union fallback. For only these geometry-identified cases, " +
            "the strict ≤2 software gate uses an independent Android Canvas triangle-union reference; default hardware still covers " +
            "every piece. Original software and forced-path hardware deltas remain reported. " +
            "Their omission is a documented Android reference limitation, so forced-path hardware is informational for these cases.\n\n")
        append("SSIM uses 8×8 luminance windows and population variance, C1=(0.01×255)², C2=(0.03×255)². " +
            "The percent differing counts all pixels with any channel delta; ink >16 counts the union of pixels " +
            "with any RGB channel below 250. Hardware/path images distinguish mesh/overlap semantics from AA.\n\n")
        append("| Case | Exact integers | Floats | Largest gap | Encodings | Software max | Software diff % | Software SSIM | Hardware max | Hardware diff % | Hardware SSIM | Hardware interior | Forced path max | Forced path SSIM | Original software max | Triangle groups | Pass |\n")
        append("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n")
        results.forEach { r ->
            append("| ${r.case.id} | ${r.geometry.integers} | ${r.geometry.floats} | ${number(r.geometry.maximumGap)} | ${r.encodingChecks} | " +
                "${r.software.maximum} | ${number(r.software.differingPercent)} | ${number(r.software.ssim)} | ${r.hardware.maximum} | " +
                "${number(r.hardware.differingPercent)} | ${number(r.hardware.ssim)} | ${r.hardware.unexplainedInterior} | " +
                "${r.path.maximum} | ${number(r.path.ssim)} | ${r.originalSoftware.maximum} | ${r.triangleFallbackGroups} | ${r.issues.isEmpty()} |\n")
        }
        val failures = results.filter { it.issues.isNotEmpty() }
        if (failures.isNotEmpty()) {
            append("\n## Failed acceptance\n\n")
            failures.forEach { r -> append("- ${r.case.id}: ${r.issues.joinToString("; ")}\n") }
        }
        append("\nFull per-case MAE, max channel delta, differing percentages, SSIM, ink counts and diagnostics are in comparison.json. " +
            "Difference PNGs amplify deltas four times for failing and lowest-SSIM cases.\n")
    }

    private fun number(value: Double): String = String.format(Locale.ROOT, "%.6f", value)

    private fun compositeOnWhite(argb: Int): Int {
        val alpha = (argb ushr 24) and 255
        var rgb = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val channel = (argb ushr shift) and 255
            rgb = rgb or (((channel * alpha + 255 * (255 - alpha) + 127) / 255) shl shift)
        }
        return rgb
    }
}
