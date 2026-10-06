package com.vivenotes.byteink.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.geometry.ImmutableAffineTransform
import com.vivenotes.byteink.compose.InkMeshRenderer
import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.byteink.kit.automaticColorOr
import java.io.File
import javax.imageio.ImageIO
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Surface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AndroidMeshRenderingTest {
    @Test
    fun fullRendererMatchesTheCommittedAndroidHardwareMatrix() {
        val reference = File(requireNotNull(System.getProperty("byteink.test.matrix")))
        val output = File(requireNotNull(System.getProperty("byteink.test.matrixReports")), "mesh").apply { mkdirs() }
        MatrixComparison.validateManifest(reference)
        AndroidFidelityMatrix.validateProvenance(reference)
        val cases = AndroidFidelityMatrix.cases(reference)
        val failures = mutableListOf<String>()
        val results = mutableListOf<JsonObject>()
        InkMeshRenderer().use { renderer -> ViveNotebook.open(File(reference, "synthetic.vive")).use { notebook ->
            cases.forEach { case ->
                val page = notebook.page(case.pageId)
                val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                assertTrue(loaded.unreadable.isEmpty(), case.id)
                val bytes = Surface.makeRasterN32Premul(case.width, case.height).use { surface ->
                    surface.canvas.clear(0xffffffff.toInt())
                    val canvas = surface.canvas.asComposeCanvas()
                    loaded.strokes.forEach { stroke ->
                        renderer.draw(canvas, stroke.stroke, ImmutableAffineTransform(
                            case.scale * stroke.scaleX, 0f, 16f + case.scale * (stroke.offsetX - case.left),
                            0f, case.scale * stroke.scaleY, 16f + case.scale * (stroke.offsetY - case.top)),
                            Rect(0f, 0f, case.width.toFloat(), case.height.toFloat()),
                            automaticColorOr(stroke.stroke.brush.colorIntArgb, stroke.colorFollowsTheme, case.themeArgb))
                    }
                    surface.makeImageSnapshot().use { image -> requireNotNull(image.encodeToData(EncodedImageFormat.PNG)).use { it.bytes } }
                }
                File(output, "${case.id}.png").writeBytes(bytes)
                val pixels = MatrixComparison.pixels(MatrixComparison.readImage(File(reference, "images/hardware/${case.id}.png")),
                    ImageIO.read(bytes.inputStream()), false)
                val issues = MatrixComparison.hardwareIssues(pixels).toMutableList().apply {
                    if (pixels.mae > 1.0) add("mesh hardware channel MAE ${pixels.mae} > 1.0")
                    if (pixels.ssim < .99) add("mesh hardware SSIM ${pixels.ssim} < .99")
                }
                failures += issues.map { "${case.id}: $it" }
                results += JsonObject(mapOf("id" to JsonPrimitive(case.id), "pixels" to pixels.json(), "issues" to JsonArray(issues.map(::JsonPrimitive))))
            }
        } }
        File(output, "comparison.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(),
            JsonObject(mapOf("caseCount" to JsonPrimitive(results.size), "passed" to JsonPrimitive(failures.isEmpty()),
                "thresholds" to JsonObject(mapOf("maximumMae" to JsonPrimitive(1.0), "minimumSsim" to JsonPrimitive(.99),
                    "maximumUnexplainedInteriorPixels" to JsonPrimitive(0))), "cases" to JsonArray(results)))))
        assertEquals(280, results.size)
        assertTrue(failures.isEmpty(), failures.take(30).joinToString("\n"))
    }
}
