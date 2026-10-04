package com.vivenotes.byteink.kit

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.StockBrushes
import androidx.ink.brush.behavior.DampingNode
import androidx.ink.brush.behavior.ProgressDomain
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.brush.behavior.ToolTypeFilterNode
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import java.util.concurrent.ConcurrentHashMap

/**
 * The brushes ViveNotes draws with, exactly as the Android app defines them (its `ink/InkCodec.kt`).
 *
 * A stored stroke names its family by id and records its stabilization level, and the mesh is
 * rebuilt from them on every load, so both apps must turn the same id and level into the same
 * `BrushFamily` for a stroke to look the same on both. Every mapping here is therefore frozen: a
 * different nib or smoothing curve is a new id or level, never an edit to an existing one.
 */
public object ViveBrushes {

    public const val MARKER: String = "marker"
    public const val DASHED_LINE: String = "dashed-line"
    public const val HIGHLIGHTER: String = "highlighter"

    /** Written by Android builds before the chisel nib existed, and the family unknown ids draw as. */
    public const val PRESSURE_PEN: String = "pressure-pen"

    private const val CALLIGRAPHY_PREFIX = "calligraphy-v1-p"

    /** Calligraphy pressure levels run from 0, a nib with no flex, to this. */
    public const val MAX_CALLIGRAPHY_PRESSURE: Int = 5

    /** The level a calligraphy id with an unreadable level falls back to: the Android pen's default. */
    public const val DEFAULT_CALLIGRAPHY_PRESSURE: Int = 3

    /** Stabilization levels run from 0, the raw samples, to this. */
    public const val MAX_STABILIZATION: Int = 5

    /** The stock brush version every row records: pinned, never "latest". */
    public const val BRUSH_VERSION: Int = 1

    /**
     * Mesh tolerance in page units (dp). Smaller means more triangles for the same stroke; a quarter
     * of a dp is already finer than a screen shows at any sane zoom.
     */
    public const val EPSILON: Float = 0.25f

    /** Stock, and measured to make no difference to smoothing — see [inputModelFor]. */
    private const val STABILIZATION_UPSAMPLING_HZ = 180

    /** The calligraphy family id for a pressure level, which is clamped to 0..[MAX_CALLIGRAPHY_PRESSURE]. */
    public fun calligraphy(pressure: Int): String = CALLIGRAPHY_PREFIX + pressure.coerceIn(0, MAX_CALLIGRAPHY_PRESSURE)

    /**
     * The family a pen draws with, chosen as the Android app chooses it: any line that is not solid
     * is the dashed line; the fountain pen is the marker, one width however hard it is pressed; any
     * other pen is calligraphy at its pressure level. The level is part of the id because it changes
     * the shape of every point.
     */
    public fun penFamilyId(solidLine: Boolean, fountain: Boolean, pressure: Int): String = when {
        !solidLine -> DASHED_LINE
        fountain -> MARKER
        else -> calligraphy(pressure)
    }

    /**
     * How a stabilization level smooths the input: Ink's own stroke modeller, a sliding window over
     * recent samples.
     *
     * Level 0 is passthrough. Levels 1 to 5 are windows of 20, 40, 60, 90 and 120 ms at the stock
     * 180 Hz; the effect saturates past about 140 ms, so 120 ms is the top of the range. Level 1 is
     * the library's default model, which every stroke drawn before stabilization existed was already
     * getting.
     */
    public fun inputModelFor(stabilization: Int): BrushFamily.InputModel =
        when (stabilization.coerceIn(0, MAX_STABILIZATION)) {
            0 -> BrushFamily.InputModel.PASSTHROUGH_MODEL
            1 -> slidingWindow(20L)
            2 -> slidingWindow(40L)
            3 -> slidingWindow(60L)
            4 -> slidingWindow(90L)
            else -> slidingWindow(120L)
        }

    private fun slidingWindow(windowMillis: Long): BrushFamily.InputModel =
        BrushFamily.InputModel.SlidingWindowModel(
            windowDurationMillis = windowMillis,
            upsamplingFrequencyHz = STABILIZATION_UPSAMPLING_HZ,
        )

    /**
     * The family for a stored id, wearing the input model its stabilization level asks for.
     *
     * The highlighter is exempt: it has no stabilization control, so its rows store 0 meaning "not
     * applicable" rather than "off", and reading that as passthrough would re-render every highlight
     * rougher than it was drawn. Cached because every stroke on a page asks, and each copy is a
     * native allocation; concurrent because pages decode off the main thread.
     */
    public fun family(id: String, stabilization: Int): BrushFamily {
        val base = family(id)
        if (id == HIGHLIGHTER) return base
        val level = stabilization.coerceIn(0, MAX_STABILIZATION)
        return stabilizedFamilies.getOrPut("$id#$level") { base.copy(inputModel = inputModelFor(level)) }
    }

    private val stabilizedFamilies = ConcurrentHashMap<String, BrushFamily>()

    /**
     * The family for an id, with its stock input model. Legacy ids stay here for good: changing what
     * they mean would restyle ink already saved.
     */
    private fun family(id: String): BrushFamily = when (id) {
        DASHED_LINE -> StockBrushes.dashedLine(StockBrushes.DashedLineVersion.V1)
        MARKER -> StockBrushes.marker(StockBrushes.MarkerVersion.V1)
        // A highlighter that doubles back over itself must not darken where it crosses, which is what
        // ACCUMULATE does to a translucent colour. DISCARD draws one flat band per stroke.
        HIGHLIGHTER -> StockBrushes.highlighter(
            selfOverlap = SelfOverlap.DISCARD,
            version = StockBrushes.HighlighterVersion.V1,
        )
        PRESSURE_PEN -> StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        else -> if (id.startsWith(CALLIGRAPHY_PREFIX)) {
            val pressure = id.removePrefix(CALLIGRAPHY_PREFIX).toIntOrNull()?.coerceIn(0, MAX_CALLIGRAPHY_PRESSURE)
                ?: DEFAULT_CALLIGRAPHY_PRESSURE
            calligraphyFamilies[pressure]
        } else {
            // Unknown ids have always taken this fallback on Android: drawn, not dropped.
            StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1)
        }
    }

    /**
     * A broad-edge nib held at a fixed page angle. The flattened tip gives thick downstrokes and thin
     * cross-strokes even with pressure off; pressure, or speed where there is no pressure, then scales
     * that shape without changing its aspect ratio.
     */
    private val calligraphyFamilies: List<BrushFamily> by lazy {
        (0..MAX_CALLIGRAPHY_PRESSURE).map(::createCalligraphyFamily)
    }

    private fun createCalligraphyFamily(pressureLevel: Int): BrushFamily {
        val response = pressureLevel.toFloat() / MAX_CALLIGRAPHY_PRESSURE
        val minSize = lerp(1f, 0.45f, response)
        val maxSize = lerp(1f, 1.6f, response)
        val behaviors = if (pressureLevel == 0) {
            emptyList()
        } else {
            listOf(
                sizeBehavior(
                    source = SourceNode.Source.NORMALIZED_PRESSURE,
                    sourceRangeEnd = 1f,
                    targetRangeStart = minSize,
                    targetRangeEnd = maxSize,
                    enabledTools = setOf(InputToolType.STYLUS),
                    comment = "Stylus pressure flex for calligraphy level $pressureLevel.",
                ),
                sizeBehavior(
                    source = SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND,
                    sourceRangeEnd = 20f,
                    // Touch and mouse have no useful pressure signal: slow is thick, fast is thin.
                    targetRangeStart = maxSize,
                    targetRangeEnd = minSize,
                    enabledTools = setOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH),
                    comment = "Speed fallback for calligraphy level $pressureLevel.",
                ),
            )
        }
        return BrushFamily(
            tip = BrushTip(
                scaleX = 1f,
                scaleY = 0.22f,
                cornerRounding = 0.2f,
                rotationDegrees = 45f,
                behaviors = behaviors,
            ),
            developerComment = "ViveNotes calligraphy v1: fixed 45-degree broad nib, pressure level $pressureLevel.",
        )
    }

    private fun sizeBehavior(
        source: SourceNode.Source,
        sourceRangeEnd: Float,
        targetRangeStart: Float,
        targetRangeEnd: Float,
        enabledTools: Set<InputToolType>,
        comment: String,
    ): BrushBehavior = BrushBehavior(
        terminalNode = TargetNode(
            target = TargetNode.Target.SIZE_MULTIPLIER,
            targetModifierRangeStart = targetRangeStart,
            targetModifierRangeEnd = targetRangeEnd,
            input = ToolTypeFilterNode(
                enabledToolTypes = enabledTools,
                input = DampingNode(
                    dampingSource = ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE,
                    dampingGap = 0.75f,
                    input = SourceNode(
                        source = source,
                        sourceValueRangeStart = 0f,
                        sourceValueRangeEnd = sourceRangeEnd,
                    ),
                ),
            ),
        ),
        developerComment = comment,
    )

    private fun lerp(start: Float, end: Float, amount: Float): Float = start + (end - start) * amount

    /**
     * A brush for drawing or rebuilding a stroke: the family by id at a stabilization level, a colour
     * with its alpha, and a size in page units, so a stroke is the same width on the page at any zoom.
     */
    public fun brush(familyId: String, stabilization: Int, colorArgb: Int, size: Float): Brush =
        Brush.createWithColorIntArgb(
            family = family(familyId, stabilization),
            colorIntArgb = colorArgb,
            size = size,
            epsilon = EPSILON,
        )

    /**
     * The highlighter's brush. The colour keeps its alpha: the stock highlighter is a chisel nib that
     * is only a highlighter when its colour is translucent.
     */
    public fun highlighter(colorArgb: Int, size: Float): Brush = brush(HIGHLIGHTER, 0, colorArgb, size)

    /** A round, opaque stroke used only as the geometric mask of an eraser gesture. */
    public fun eraseMask(inputs: StrokeInputBatch, sizeDp: Float): Stroke = Stroke(
        brush = Brush.createWithColorIntArgb(
            family = StockBrushes.marker(StockBrushes.MarkerVersion.V1),
            colorIntArgb = 0xFF000000.toInt(),
            size = sizeDp,
            epsilon = EPSILON,
        ),
        inputs = inputs,
    )
}
