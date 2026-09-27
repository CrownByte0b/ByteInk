@file:OptIn(ExperimentalInkEraserApi::class)

package com.vivenotes.byteink.oracle

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.geometry.AffineTransform
import androidx.ink.storage.decode
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.util.zip.ZipFile

/**
 * Real ViveNotes ink: every live stroke in each `.vive` notebook under [directory], rebuilt the way
 * the Android app rebuilds it, and every stored partial erase replayed against its targets in order.
 * Notebooks are personal, so their dumps stay in the build directory.
 */
fun Dump.fixtureCases(directory: File) {
    val notebooks = directory.listFiles { file -> file.name.endsWith(".vive") }.orEmpty().sortedBy { it.name }
    require(notebooks.isNotEmpty()) { "No .vive notebooks in $directory" }
    notebooks.forEach { fixture(it) }
}

private fun Dump.fixture(notebook: File) {
    val name = notebook.nameWithoutExtension.replace(Regex("[^A-Za-z0-9._-]"), "_")
    val database = Files.createTempFile("oracle-", ".sqlite").toFile().apply { deleteOnExit() }
    ZipFile(notebook).use { zip ->
        zip.getInputStream(zip.getEntry("notebook.sqlite")).use { input -> database.outputStream().use(input::copyTo) }
    }
    DriverManager.getConnection("jdbc:sqlite:${database.path}").use { connection ->
        val strokes = linkedMapOf<String, Stroke>()
        connection.createStatement().use { statement ->
            val rows = statement.executeQuery(
                "SELECT id, brushFamily, sizeDp, colorArgb, epsilon, stabilization, minX, minY, maxX, maxY, points " +
                    "FROM ink_strokes WHERE deletedAt IS NULL AND enc = 'ink/androidx1' ORDER BY pageId, seq, id",
            )
            while (rows.next()) {
                val id = rows.getString("id")
                val case = "fixture/$name/stroke/$id"
                val brush = Brush.createWithColorIntArgb(
                    family = ViveFamilies.family(rows.getString("brushFamily"), rows.getInt("stabilization")),
                    colorIntArgb = rows.getInt("colorArgb"),
                    size = rows.getFloat("sizeDp"),
                    epsilon = rows.getFloat("epsilon"),
                )
                val stroke = Stroke(brush, StrokeInputBatch.decode(rows.getBytes("points")))
                // What the Android app computed when it saved the row.
                this[case, "stored-bounds"] = Floats.of(
                    rows.getFloat("minX"), rows.getFloat("minY"), rows.getFloat("maxX"), rows.getFloat("maxY"),
                )
                shape(case, "dry", stroke.shape)
                strokes[id] = stroke
            }
        }
        connection.createStatement().use { statement ->
            val rows = statement.executeQuery(
                "SELECT e.id, e.sizeDp, e.points, t.strokeId FROM ink_erases e " +
                    "JOIN ink_erase_targets t ON t.eraseId = e.id " +
                    "WHERE e.mode = 'Normal' AND e.deletedAt IS NULL AND e.enc = 'ink/androidx1' " +
                    "ORDER BY e.createdAt, e.id, t.strokeId",
            )
            while (rows.next()) {
                val target = rows.getString("strokeId")
                val stroke = strokes[target] ?: continue
                val mask = eraseMask(StrokeInputBatch.decode(rows.getBytes("points")), rows.getFloat("sizeDp"))
                val cut = stroke.subtract(mask.shape, AffineTransform.IDENTITY, AffineTransform.IDENTITY)
                val case = "fixture/$name/erase/${rows.getString("id")}/$target"
                shape(case, "cut", cut.shape)
                this[case, "pieces"] = cut.split(AffineTransform.IDENTITY, 0f).size
                strokes[target] = cut
            }
        }
    }
    database.delete()
}

/** The Android app's family ids and stabilization levels (ink/InkCodec.kt), for stored rows. */
object ViveFamilies {
    fun family(id: String, stabilization: Int): BrushFamily {
        val family = when {
            id in setOf("marker", "dashed-line", "highlighter", "pressure-pen") -> Families.named(id)
            id.startsWith("calligraphy-v1-p") -> Families.named(
                "calligraphy-v1-p" + (id.removePrefix("calligraphy-v1-p").toIntOrNull() ?: 3).coerceIn(0, 5),
            )
            // Legacy and unknown ids draw as the pressure pen, as on Android.
            else -> Families.named("pressure-pen")
        }
        if (id == "highlighter") return family
        val level = stabilization.coerceIn(0, 5)
        return family.copy(inputModel = Families.inputModels[level].second)
    }
}
