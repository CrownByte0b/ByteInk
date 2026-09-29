package com.vivenotes.byteink.vive

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ToolTypeFilterNode
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Android app's `InkCodecTest`, on the JVM. Its pens become catalog calls with the same values:
 * `PenPreset()` is a fountain pen (the marker family) at stabilization 1, a calligraphy pen is
 * [ViveBrushes.calligraphy] at its pressure, and `HighlighterSettings()` is #66FFEB3B at 18 dp.
 */
class ViveInkCodecTest {

    @Test
    fun calligraphyUsesAFixedBroadEdgeNibEvenWithPressureOff() {
        val tip = tipFor(pressure = 0)

        assertEquals(1f, tip.scaleX, 0.001f)
        assertEquals(0.22f, tip.scaleY, 0.001f)
        assertEquals(0.2f, tip.cornerRounding, 0.001f)
        assertEquals(45f, tip.rotationDegrees, 0.001f)
        assertTrue(tip.behaviors.isEmpty())
    }

    @Test
    fun maximumSensitivityMapsStylusPressureAcrossTheFullFlexRange() {
        val behavior = tipFor(ViveBrushes.MAX_CALLIGRAPHY_PRESSURE).behaviors.first()
        val target = behavior.terminalNodes.single() as TargetNode
        val tools = target.input as ToolTypeFilterNode
        val damping = tools.input as DampingNode
        val source = damping.input as SourceNode

        assertEquals(TargetNode.Target.SIZE_MULTIPLIER, target.target)
        assertEquals(0.45f, target.targetModifierRangeStart, 0.001f)
        assertEquals(1.6f, target.targetModifierRangeEnd, 0.001f)
        assertEquals(setOf(InputToolType.STYLUS), tools.enabledToolTypes)
        assertEquals(SourceNode.Source.NORMALIZED_PRESSURE, source.source)
    }

    @Test
    fun touchAndMouseUseSpeedAsThePressureFallback() {
        val behavior = tipFor(ViveBrushes.MAX_CALLIGRAPHY_PRESSURE).behaviors[1]
        val target = behavior.terminalNodes.single() as TargetNode
        val tools = target.input as ToolTypeFilterNode
        val source = (tools.input as DampingNode).input as SourceNode

        assertEquals(1.6f, target.targetModifierRangeStart, 0.001f)
        assertEquals(0.45f, target.targetModifierRangeEnd, 0.001f)
        assertEquals(setOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH), tools.enabledToolTypes)
        assertEquals(SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND, source.source)
    }

    @Test
    fun storedCalligraphyFamilyKeepsItsPressureLevel() {
        val family = ViveBrushes.penFamilyId(solidLine = true, fountain = false, pressure = 4)
        val stroke = Stroke(ViveBrushes.brush(family, 1, BLACK, 1.5f), MutableStrokeInputBatch())

        val row = encode(stroke, family, stabilization = 1)

        assertEquals("calligraphy-v1-p4", row.brushFamily)
        val restored = assertNotNull(ViveInkCodec.decode(row))
        assertEquals(45f, restored.brush.family.coats.single().tip.rotationDegrees, 0.001f)
    }

    @Test
    fun penSettingsChooseTheFamilyAsAndroidDoes() {
        assertEquals(ViveBrushes.DASHED_LINE, ViveBrushes.penFamilyId(solidLine = false, fountain = true, pressure = 3))
        assertEquals(ViveBrushes.MARKER, ViveBrushes.penFamilyId(solidLine = true, fountain = true, pressure = 3))
        assertEquals("calligraphy-v1-p5", ViveBrushes.penFamilyId(solidLine = true, fountain = false, pressure = 9))
        assertEquals("calligraphy-v1-p0", ViveBrushes.penFamilyId(solidLine = true, fountain = false, pressure = -1))
    }

    // --- the highlighter -----------------------------------------------------------------------

    @Test
    fun theHighlighterDrawsWithATranslucentInk() {
        val brush = ViveBrushes.highlighter(HIGHLIGHTER_COLOR, HIGHLIGHTER_SIZE)

        assertEquals(HIGHLIGHTER_COLOR, brush.colorIntArgb)
        assertTrue(brush.colorIntArgb ushr 24 in 1..254, "the ink is opaque")
        assertEquals(HIGHLIGHTER_SIZE, brush.size, 0.001f)
    }

    @Test
    fun theHighlighterDiscardsItsOwnOverlap() {
        assertEquals(SelfOverlap.DISCARD, selfOverlapOf(ViveBrushes.highlighter(HIGHLIGHTER_COLOR, HIGHLIGHTER_SIZE)))
    }

    @Test
    fun aHighlighterStrokeIsStoredAsOneAndComesBack() {
        val stroke = Stroke(ViveBrushes.highlighter(HIGHLIGHTER_COLOR, HIGHLIGHTER_SIZE), MutableStrokeInputBatch())

        val row = ViveInkCodec.encodeHighlighter(stroke, id = "row", pageId = "page", seq = 0, createdAt = 1L)

        assertEquals("highlighter", row.brushFamily)
        assertEquals(HIGHLIGHTER_COLOR, row.colorArgb)
        // The highlighter has no stabilization setting, so 0 is the truth about the stroke.
        assertEquals(0, row.stabilization)
        assertEquals(false, row.colorFollowsTheme)
        val restored = assertNotNull(ViveInkCodec.decode(row))
        assertEquals(HIGHLIGHTER_COLOR, restored.brush.colorIntArgb)
        assertEquals(SelfOverlap.DISCARD, selfOverlapOf(restored.brush))
    }

    // --- stabilization -------------------------------------------------------------------------

    @Test
    fun stabilizationActuallyReachesTheStroke() {
        val off = wobbleOf(strokeAt(0))
        val most = wobbleOf(strokeAt(ViveBrushes.MAX_STABILIZATION))

        assertTrue(most < off - 0.2f, "stabilization did nothing: off=$off max=$most")
    }

    @Test
    fun theScaleIsMonotonicAndNeverReversesItself() {
        val wobbles = (0..ViveBrushes.MAX_STABILIZATION).map { wobbleOf(strokeAt(it)) }
        wobbles.zipWithNext().forEachIndexed { index, (lower, higher) ->
            assertTrue(higher <= lower + 0.01f, "level ${index + 1} is shakier than level $index: $wobbles")
        }
    }

    @Test
    fun levelOneIsTheLibraryDefaultSoOldInkIsUnchanged() {
        assertEquals(BrushFamily.InputModel.DEFAULT_INPUT_MODEL, ViveBrushes.inputModelFor(1))
    }

    @Test
    fun offMeansTheRawSamples() {
        assertEquals(BrushFamily.InputModel.PASSTHROUGH_MODEL, ViveBrushes.inputModelFor(0))
    }

    @Test
    fun aStoredStrokeIsRebuiltWithTheLevelItWasDrawnAt() {
        val level = ViveBrushes.MAX_STABILIZATION
        val drawn = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, level, BLACK, 2f), shakyLine())
        val row = encode(drawn, ViveBrushes.MARKER, stabilization = level)

        assertEquals(level, row.stabilization)
        val restored = assertNotNull(ViveInkCodec.decode(row))
        assertEquals(wobbleOf(drawn), wobbleOf(restored), 0.01f, "a reloaded stroke changed shape")

        // A row that says "off" comes back rough, whatever pen is in hand now.
        val rough = assertNotNull(ViveInkCodec.decode(row.copy(stabilization = 0)))
        assertTrue(wobbleOf(rough) > wobbleOf(restored) + 0.2f, "the stored level was ignored on reload")
    }

    @Test
    fun aHighlighterIgnoresTheStabilizationColumnEntirely() {
        val drawn = Stroke(ViveBrushes.highlighter(HIGHLIGHTER_COLOR, HIGHLIGHTER_SIZE), shakyLine())
        val row = ViveInkCodec.encodeHighlighter(drawn, id = "row", pageId = "page", seq = 0, createdAt = 1L)

        assertEquals(0, row.stabilization)
        val restored = assertNotNull(ViveInkCodec.decode(row))
        assertEquals(wobbleOf(drawn), wobbleOf(restored), 0.01f, "the highlighter was re-rendered through the stabilizer")
    }

    // --- reading what this build cannot use ----------------------------------------------------

    @Test
    fun aRowFromAnEncoderThisBuildDoesNotKnowIsSkipped() {
        val row = encode(Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 1, BLACK, 2f), shakyLine()), ViveBrushes.MARKER, 1)

        assertNull(ViveInkCodec.decode(row.copy(enc = "ink/somethingelse2")))
        assertNull(ViveInkCodec.decode(row.copy(points = byteArrayOf(1, 2, 3))))
        assertNull(ViveInkCodec.decode(row.copy(sizeDp = -1f)), "a brush the engine refuses")
    }

    @Test
    fun anUnknownFamilyDrawsAsThePressurePen() {
        val row = encode(Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 1, BLACK, 2f), shakyLine()), "fountain-v9", 1)

        val restored = assertNotNull(ViveInkCodec.decode(row))

        val pressurePen = StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        assertEquals(pressurePen.coats, restored.brush.family.coats)
    }

    @Test
    fun aCalligraphyIdWithAnUnreadableLevelTakesTheDefaultPressure() {
        val unreadable = ViveBrushes.family("calligraphy-v1-pX", 1)

        assertEquals(ViveBrushes.family(ViveBrushes.calligraphy(ViveBrushes.DEFAULT_CALLIGRAPHY_PRESSURE), 1).coats, unreadable.coats)
    }

    @Test
    fun anEraseRowRoundTripsItsMaskAndMode() {
        val mask = ViveBrushes.eraseMask(line(12f to 34f, 56f to 78f), sizeDp = 23f)

        val row = ViveInkCodec.encodeErase(mask, "erase", "page", InkEraseMode.Object, createdAt = 42L, targetIds = listOf("a", "b"))

        assertEquals("Object", row.mode)
        assertEquals(InkEraseMode.Object, InkEraseMode.of(row.mode))
        assertEquals(listOf("a", "b"), row.targetIds)
        val restored = assertNotNull(ViveInkCodec.decodeErase(row))
        assertEquals(23f, restored.brush.size, 0.001f)
        assertEquals(mask.inputs.size, restored.inputs.size)
        assertNull(InkEraseMode.of("Lasso"), "a mode this build does not know")
    }

    private fun encode(stroke: Stroke, family: String, stabilization: Int): StoredInkStroke = ViveInkCodec.encodeStroke(
        stroke = stroke,
        id = "row",
        pageId = "page",
        seq = 0,
        brushFamily = family,
        stabilization = stabilization,
        colorFollowsTheme = null,
        createdAt = 1L,
    )

    private fun selfOverlapOf(brush: Brush) = brush.family.coats.single().paintPreferences.first().selfOverlap

    private fun tipFor(pressure: Int) = ViveBrushes.family(ViveBrushes.calligraphy(pressure), 1).coats.single().tip

    /** A straight line with a 10 Hz tremor on it, sampled at 120 Hz: the shake a stabilizer is for. */
    private fun shakyLine(): StrokeInputBatch = MutableStrokeInputBatch().apply {
        for (i in 0 until 120) {
            val seconds = i / 120f
            add(InputToolType.STYLUS, 10f + i * 1.5f, 100f + 3f * sin(2f * PI.toFloat() * 10f * seconds), (seconds * 1000f).toLong())
        }
    }.toImmutable()

    /** How far the drawn centreline still strays from the straight line it was meant to be. */
    private fun wobbleOf(stroke: Stroke): Float {
        val box = assertNotNull(stroke.shape.computeBoundingBox())
        return ((box.yMax - box.yMin) - stroke.brush.size) / 2f
    }

    private fun strokeAt(level: Int): Stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, level, BLACK, 2f), shakyLine())

    private fun line(vararg points: Pair<Float, Float>) = MutableStrokeInputBatch().apply {
        points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 10L) }
    }.toImmutable()

    private companion object {
        const val BLACK = 0xFF000000.toInt()
        const val HIGHLIGHTER_COLOR = 0x66FFEB3B
        const val HIGHLIGHTER_SIZE = 18f
    }
}
