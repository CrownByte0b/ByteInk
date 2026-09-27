@file:OptIn(ExperimentalInkEraserApi::class)

package com.vivenotes.byteink.oracle

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
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.ExperimentalInkEraserApi
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.strokes.createClosedShape
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The brush families ViveNotes stores strokes with, by the ids its rows carry. */
object Families {
    val all: List<Pair<String, BrushFamily>> by lazy {
        listOf(
            "marker" to StockBrushes.marker(StockBrushes.MarkerVersion.V1),
            "pressure-pen" to StockBrushes.pressurePen(StockBrushes.PressurePenVersion.V1),
            "dashed-line" to StockBrushes.dashedLine(StockBrushes.DashedLineVersion.V1),
            "highlighter" to StockBrushes.highlighter(SelfOverlap.DISCARD, StockBrushes.HighlighterVersion.V1),
            "highlighter-any" to StockBrushes.highlighter(SelfOverlap.ANY, StockBrushes.HighlighterVersion.V1),
        ) + (0..5).map { "calligraphy-v1-p$it" to calligraphy(it) }
    }

    fun named(id: String): BrushFamily = all.first { it.first == id }.second

    /** The stabilization levels ViveNotes stores, as input models; 0 is the raw input. */
    val inputModels: List<Pair<String, BrushFamily.InputModel>> = listOf(
        "passthrough" to BrushFamily.InputModel.PASSTHROUGH_MODEL,
        "window20" to BrushFamily.InputModel.SlidingWindowModel(20L, 180),
        "window40" to BrushFamily.InputModel.SlidingWindowModel(40L, 180),
        "window60" to BrushFamily.InputModel.SlidingWindowModel(60L, 180),
        "window90" to BrushFamily.InputModel.SlidingWindowModel(90L, 180),
        "window120" to BrushFamily.InputModel.SlidingWindowModel(120L, 180),
    )

    /** The Android app's calligraphy nib (ink/InkCodec.kt, createCalligraphyFamily), unchanged. */
    private fun calligraphy(pressureLevel: Int): BrushFamily {
        val response = pressureLevel / 5f
        val minSize = 1f + (0.45f - 1f) * response
        val maxSize = 1f + (1.6f - 1f) * response
        val behaviors = if (pressureLevel == 0) {
            emptyList()
        } else {
            listOf(
                size(SourceNode.Source.NORMALIZED_PRESSURE, 1f, minSize, maxSize, setOf(InputToolType.STYLUS)),
                size(
                    SourceNode.Source.SPEED_IN_MULTIPLES_OF_BRUSH_SIZE_PER_SECOND,
                    20f,
                    maxSize,
                    minSize,
                    setOf(InputToolType.UNKNOWN, InputToolType.MOUSE, InputToolType.TOUCH),
                ),
            )
        }
        return BrushFamily(
            tip = BrushTip(scaleX = 1f, scaleY = 0.22f, cornerRounding = 0.2f, rotationDegrees = 45f, behaviors = behaviors),
            developerComment = "ViveNotes calligraphy v1: fixed 45-degree broad nib, pressure level $pressureLevel.",
        )
    }

    private fun size(
        source: SourceNode.Source,
        sourceRangeEnd: Float,
        targetRangeStart: Float,
        targetRangeEnd: Float,
        tools: Set<InputToolType>,
    ): BrushBehavior = BrushBehavior(
        terminalNode = TargetNode(
            target = TargetNode.Target.SIZE_MULTIPLIER,
            targetModifierRangeStart = targetRangeStart,
            targetModifierRangeEnd = targetRangeEnd,
            input = ToolTypeFilterNode(
                enabledToolTypes = tools,
                input = DampingNode(
                    dampingSource = ProgressDomain.DISTANCE_IN_MULTIPLES_OF_BRUSH_SIZE,
                    dampingGap = 0.75f,
                    input = SourceNode(source = source, sourceValueRangeStart = 0f, sourceValueRangeEnd = sourceRangeEnd),
                ),
            ),
        ),
    )
}

/** Deterministic input paths, sampled at 120 Hz; stylus paths carry pressure, tilt and orientation. */
object Paths {
    val kinds = listOf("line", "arc", "zigzag", "loop", "spiral", "dot", "pair")
    val tools = listOf(InputToolType.MOUSE, InputToolType.TOUCH, InputToolType.STYLUS)

    fun inputs(kind: String, tool: InputToolType): StrokeInputBatch {
        val points = points(kind)
        val batch = MutableStrokeInputBatch()
        points.forEachIndexed { index, (x, y) ->
            val time = index * 8L
            if (tool == InputToolType.STYLUS) {
                val phase = index / 7f
                batch.add(
                    tool, x, y, time,
                    pressure = 0.5f + 0.4f * sin(phase),
                    tiltRadians = 0.4f + 0.3f * cos(phase),
                    orientationRadians = (phase * 0.9f) % (2f * PI.toFloat()),
                )
            } else {
                batch.add(tool, x, y, time)
            }
        }
        return batch.toImmutable()
    }

    private fun points(kind: String): List<Pair<Float, Float>> = when (kind) {
        "line" -> (0..40).map { it * 5f to 0f }
        "arc" -> (0..60).map { val a = it * PI.toFloat() / 60f; 60f * cos(a) to 60f * sin(a) }
        "zigzag" -> (0..48).map { it * 4f to if ((it / 6) % 2 == 0) (it % 6) * 5f else (6 - it % 6) * 5f }
        "loop" -> (0..90).map { val a = it * 2f * PI.toFloat() / 90f; 70f * sin(a) to 35f * sin(2f * a) }
        // Speeding up and slowing down, for the speed-driven behaviours.
        "spiral" -> (0..120).map { val a = (it * it) / 1200f * 6f * PI.toFloat() / 12f; (4f + a * 6f) * cos(a) to (4f + a * 6f) * sin(a) }
        "dot" -> listOf(10f to 10f)
        "pair" -> listOf(10f to 20f, 30f to 40f)
        else -> error("No path $kind")
    }
}

/** Every synthetic case, in a stable order. */
fun Dump.syntheticCases() {
    for ((id, family) in Families.all) family("family/$id", family)
    for ((name, model) in Families.inputModels) family("family/marker+$name", Families.named("marker").copy(inputModel = model))

    // Every family with every path and tool, at ViveNotes' tolerance.
    for ((id, family) in Families.all) {
        for (kind in Paths.kinds) {
            for (tool in Paths.tools) {
                val case = "stroke/$id/$kind/${tool.label}"
                val inputs = Paths.inputs(kind, tool)
                val brush = brush(family, id)
                inputs(case, inputs)
                shape(case, "dry", Stroke(brush, inputs).shape)
            }
        }
    }
    // Stabilization, which ViveNotes applies to every pen family but the highlighter.
    for ((name, model) in Families.inputModels) {
        for (id in listOf("marker", "calligraphy-v1-p3")) {
            for (kind in listOf("zigzag", "spiral")) {
                val tool = if (id == "marker") InputToolType.MOUSE else InputToolType.STYLUS
                val case = "stabilized/$id+$name/$kind/${tool.label}"
                val family = Families.named(id).copy(inputModel = model)
                shape(case, "dry", Stroke(brush(family, id), Paths.inputs(kind, tool)).shape)
            }
        }
    }
    // Live drawing: the stroke as it grows, with and without predicted inputs.
    for (id in listOf("marker", "highlighter", "calligraphy-v1-p3", "pressure-pen", "dashed-line")) {
        for ((kind, tool) in listOf("loop" to InputToolType.STYLUS, "zigzag" to InputToolType.MOUSE)) {
            val inputs = Paths.inputs(kind, tool)
            inProgress("live/$id/$kind/${tool.label}", brush(Families.named(id), id), inputs, chunk = 7)
            inProgress("live-predicted/$id/$kind/${tool.label}", brush(Families.named(id), id), inputs, chunk = 7, predicted = 3)
        }
    }
    eraseCases()
    lassoCases()
}

fun brush(family: BrushFamily, id: String, size: Float = if (id.startsWith("highlighter")) 18f else 4f): Brush =
    Brush.createWithColorIntArgb(
        family = family,
        colorIntArgb = if (id.startsWith("highlighter")) 0x66FFEB3B else 0xFF202020.toInt(),
        size = size,
        epsilon = 0.25f,
    )

/** A ViveNotes eraser mask: a black marker V1 stroke of the eraser's size. */
fun eraseMask(inputs: StrokeInputBatch, size: Float): Stroke =
    Stroke(Brush.createWithColorIntArgb(StockBrushes.marker(StockBrushes.MarkerVersion.V1), 0xFF000000.toInt(), size, 0.25f), inputs)

/**
 * Partial and object erasing as the Android app replays it: [Stroke.subtract] by a mask in page
 * space, then [Stroke.split] into pieces, with the stroke at page offsets and scales.
 */
private fun Dump.eraseCases() {
    val targets = listOf(
        "marker/line" to Stroke(brush(Families.named("marker"), "marker", 10f), Paths.inputs("line", InputToolType.MOUSE)),
        "calligraphy-v1-p3/zigzag" to Stroke(brush(Families.named("calligraphy-v1-p3"), "calligraphy-v1-p3", 8f), Paths.inputs("zigzag", InputToolType.STYLUS)),
        "pressure-pen/arc" to Stroke(brush(Families.named("pressure-pen"), "pressure-pen", 6f), Paths.inputs("arc", InputToolType.STYLUS)),
        "highlighter/line" to Stroke(brush(Families.named("highlighter"), "highlighter"), Paths.inputs("line", InputToolType.MOUSE)),
        "dashed-line/loop" to Stroke(brush(Families.named("dashed-line"), "dashed-line", 4f), Paths.inputs("loop", InputToolType.MOUSE)),
    )
    val masks = listOf(
        "across" to eraseMask(segment(100f, -40f, 100f, 40f), 8f),
        "dot" to eraseMask(segment(60f, 2f, 60f, 2f), 30f),
        "everything" to eraseMask(segment(100f, 0f, 100f, 0f), 600f),
        "elsewhere" to eraseMask(segment(500f, 500f, 520f, 500f), 8f),
        "end" to eraseMask(segment(200f, -20f, 200f, 20f), 12f),
    )
    val placements = listOf(
        "identity" to AffineTransform.IDENTITY,
        "offset" to ImmutableAffineTransform(1f, 0f, 30f, 0f, 1f, -10f),
        "scaled" to ImmutableAffineTransform(1.5f, 0f, -20f, 0f, 0.75f, 5f),
    )
    for ((targetName, target) in targets) {
        for ((maskName, mask) in masks) {
            for ((placementName, strokeToPage) in placements) {
                val case = "erase/$targetName/$maskName/$placementName"
                val cut = target.subtract(mask.shape, AffineTransform.IDENTITY, strokeToPage)
                shape(case, "cut", cut.shape)
                val pieces = cut.split(strokeToPage, 0f).sortedWith(
                    compareBy<Stroke>(
                        { it.shape.computeBoundingBox()?.xMin ?: Float.NaN },
                        { it.shape.computeBoundingBox()?.yMin ?: Float.NaN },
                        { it.shape.computeBoundingBox()?.xMax ?: Float.NaN },
                        { it.shape.computeBoundingBox()?.yMax ?: Float.NaN },
                    ),
                )
                this[case, "pieces"] = pieces.size
                pieces.forEachIndexed { index, piece -> shape(case, "piece$index", piece.shape) }
            }
        }
    }
}

private fun segment(x0: Float, y0: Float, x1: Float, y1: Float): StrokeInputBatch =
    MutableStrokeInputBatch().apply {
        add(InputToolType.UNKNOWN, x0, y0, 0L)
        if (x1 != x0 || y1 != y0) add(InputToolType.UNKNOWN, x1, y1, 16L)
    }.toImmutable()

/** Closed lasso regions, and which strokes they reach, as lasso selection asks. */
private fun Dump.lassoCases() {
    val polygons = mapOf(
        "square" to listOf(-10f to -10f, 110f to -10f, 110f to 50f, -10f to 50f),
        "star" to (0 until 10).map { val a = it * PI.toFloat() / 5f; val r = if (it % 2 == 0) 80f else 30f; r * cos(a) to r * sin(a) },
        "bowtie" to listOf(0f to 0f, 100f to 60f, 100f to 0f, 0f to 60f),
    )
    val stroke = Stroke(brush(Families.named("marker"), "marker", 6f), Paths.inputs("zigzag", InputToolType.MOUSE))
    for ((name, points) in polygons) {
        val region = MutableStrokeInputBatch().apply {
            points.forEachIndexed { index, (x, y) -> add(InputToolType.UNKNOWN, x, y, index * 16L) }
        }.toImmutable().createClosedShape()
        val case = "lasso/$name"
        shape(case, "region", region)
        this[case, "reaches-zigzag"] = stroke.shape.computeCoverageIsGreaterThan(region, 0f)
        this[case, "zigzag-coverage"] = Floats.of(stroke.shape.computeCoverage(region))
    }
}

/** "mouse", "touch", "stylus": [InputToolType] is not an enum, but prints as `InputToolType.MOUSE`. */
val InputToolType.label: String get() = toString().substringAfter('.').lowercase()
