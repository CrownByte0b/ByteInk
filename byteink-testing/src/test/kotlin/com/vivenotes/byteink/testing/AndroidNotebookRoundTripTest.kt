package com.vivenotes.byteink.testing

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkCodec
import com.vivenotes.byteink.vive.ViveInkPage
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AndroidNotebookRoundTripTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun preparedArchiveCoversControllerAuthoringReplayAndOpaquePreservation() {
        val output = temporary.newFolder("roundtrip")
        val reference = File(requireNotNull(System.getProperty("byteink.test.matrix")))
        AndroidNotebookRoundTrip.prepare(output, reference)
        val expectations = Json.parseToJsonElement(File(output, "expectations.json").readText()).jsonObject
        val authored = expectations.getValue("authoredRows").jsonArray.map { it.jsonObject }
        val probes = expectations.getValue("encodingProbes").jsonArray.map { it.jsonObject }
        assertEquals(listOf(0, 1, 8192, 40000), probes.map { it.getValue("originalInputs").jsonArray.size })
        assertTrue(probes.drop(1).all { it.getValue("noiseSeed").jsonPrimitive.content.toInt() != 0 })
        val families = authored.groupBy { it.getValue("brushFamily").jsonPrimitive.content }
        assertEquals(10, families.size)
        families.forEach { (family, rows) ->
            assertEquals(if (family == "highlighter") (0..0).toSet() else (0..5).toSet(),
                rows.map { it.getValue("stabilization").jsonPrimitive.content.toInt() }.toSet())
            assertTrue(rows.all { it.getValue("originalInputs").jsonArray.size == 25 })
        }
        val archive = File(output, "desktop.vive")
        ViveNotebook.open(archive).use { notebook ->
            assertEquals(897, notebook.pageIds.sumOf { notebook.page(it).strokes.size })
            val all = notebook.pageIds.flatMap { notebook.page(it).strokes }
            assertNotNull(ViveInkCodec.decode(all.single { it.id == AndroidNotebookRoundTrip.unknownBrushId }))
            assertNotNull(all.single { it.id == AndroidNotebookRoundTrip.tombstoneId }.deletedAt)
            expectations.getValue("erases").jsonArray.forEach { erase ->
                val e = erase.jsonObject
                val page = notebook.page(e.getValue("pageId").jsonPrimitive.content)
                val loaded = ViveInkPage.load(page.strokes, page.erases, page.moves)
                assertTrue(e.getValue("targetIds").jsonArray.all { it.jsonPrimitive.content in loaded.erasedAway })
            }
        }
        ViveNotebook.open(File(output, "unknown-enc.vive")).use { notebook ->
            val all = notebook.pageIds.flatMap { notebook.page(it).strokes }
            val opaque = all.single { it.id == AndroidNotebookRoundTrip.unknownEncodingId }
            assertNull(ViveInkCodec.decode(opaque))
            val page = notebook.page(opaque.pageId)
            assertTrue(opaque.id in ViveInkPage.load(page.strokes, page.erases, page.moves).unreadable)
        }
        AndroidNotebookRoundTrip.assertOriginalRowsPreserved(File(output, "source.vive"), archive)
        AndroidNotebookRoundTrip.assertOriginalRowsPreserved(File(output, "source-unknown-enc.vive"), File(output, "unknown-enc.vive"))
        assertFailsWith<IllegalArgumentException> { AndroidNotebookRoundTrip.prepare(output, reference) }
    }

    @Test
    fun preservationSnapshotRefusesAddedOrRemovedInkRows() {
        val source = syntheticNotebook(temporary.root)
        val alteredDirectory = temporary.newFolder("altered")
        val altered = syntheticNotebook(alteredDirectory)
        val seed = newStroke("extra", "page", 5)
        val changed = File(temporary.root, "changed.vive")
        ViveNotebook.open(altered).use { it.writeCopyWithStrokes(changed, listOf(seed)) }
        // Whole-database checks must reject added rows as well as changed data.
        assertFailsWith<IllegalStateException> { AndroidNotebookRoundTrip.assertSameRows(source, changed) }
        AndroidNotebookRoundTrip.assertOriginalRowsPreserved(source, changed)
        val empty = syntheticNotebook(temporary.newFolder("empty"), emptyPages = listOf("page", "empty"))
        assertFailsWith<IllegalStateException> { AndroidNotebookRoundTrip.assertOriginalRowsPreserved(source, empty) }
    }

    @Test
    fun longProbeJsonRetainsTheExactInputsRequiredByAnIndependentEncoder() {
        val brush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff173b83.toInt(), 8f)
        val shape = Stroke(brush, MutableStrokeInputBatch()).shape
        AndroidNotebookRoundTrip.encodingProbes().forEach { element ->
            val probe = element.jsonObject
            val batch = MutableStrokeInputBatch().apply {
                probe.getValue("originalInputs").jsonArray.forEach { input ->
                    val sample = input.jsonObject
                    fun f(field: String) = sample.getValue(field).jsonPrimitive.content.toFloat()
                    val tool = listOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH, InputToolType.STYLUS)
                        .single { it.toString() == sample.getValue("tool").jsonPrimitive.content }
                    add(tool, f("x"), f("y"), sample.getValue("elapsedTimeMillis").jsonPrimitive.content.toLong(),
                        strokeUnitLengthCm = f("strokeUnitLengthCm"), pressure = f("pressure"),
                        tiltRadians = f("tiltRadians"), orientationRadians = f("orientationRadians"))
                }
                setNoiseSeed(probe.getValue("noiseSeed").jsonPrimitive.content.toInt())
            }
            val id = probe.getValue("id").jsonPrimitive.content
            val encoded = ViveInkCodec.encodeStroke(Stroke(brush, batch, shape), id, "probe", 0,
                ViveBrushes.MARKER, 0, false, 0L).points
            assertTrue(encoded.contentEquals(Base64.getDecoder().decode(probe.getValue("pointsBase64").jsonPrimitive.content)), id)
        }
    }
}
