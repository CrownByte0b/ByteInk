@file:OptIn(InkInternalOnlyApi::class)

package com.vivenotes.byteink.oracle

import androidx.ink.brush.BrushFamily
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.geometry.getRawTriangleIndexBuffer
import androidx.ink.geometry.getRawVertexBuffer
import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.storage.encode
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.strokes.getRawTriangleIndexBuffer
import androidx.ink.strokes.getRawVertexBuffer
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream

/**
 * Records everything the engine derives from [shape]: bounds, each render group's outlines and
 * meshes (raw vertex and index buffers, attribute unpacking), and coverage of a grid of probes.
 */
fun Dump.shape(case: String, key: String, shape: PartitionedMesh) {
    val bounds = shape.computeBoundingBox()
    this[case, "$key.bounds"] = bounds?.let { Floats.of(it.xMin, it.yMin, it.xMax, it.yMax) } ?: "none"
    val groups = shape.getRenderGroupCount()
    this[case, "$key.groups"] = groups
    val position = MutableVec()
    for (group in 0 until groups) {
        val outlines = shape.getOutlineCount(group)
        val sizes = digest()
        val positions = Floats()
        for (outline in 0 until outlines) {
            val vertices = shape.getOutlineVertexCount(group, outline)
            sizes.add(vertices)
            for (vertex in 0 until vertices) {
                shape.populateOutlinePosition(group, outline, vertex, position)
                positions.add(position.x).add(position.y)
            }
        }
        this[case, "$key.g$group.outlines"] = outlines
        this[case, "$key.g$group.outline-sizes"] = sizes
        this[case, "$key.g$group.outline-positions"] = positions
        val meshes = shape.renderGroupMeshes(group)
        this[case, "$key.g$group.meshes"] = meshes.size
        meshes.forEachIndexed { index, mesh ->
            val at = "$key.g$group.m$index"
            this[case, "$at.vertices"] = mesh.vertexCount
            this[case, "$at.triangles"] = mesh.triangleCount
            // The packed buffer bit for bit; what it decodes to is compared as positions and unpacking.
            this[case, "$at.vertex-buffer"] = digest().add(mesh.getRawVertexBuffer())
            this[case, "$at.index-buffer"] = digest().add(mesh.getRawTriangleIndexBuffer())
            this[case, "$at.positions"] = Floats().apply {
                repeat(mesh.vertexCount) { vertex ->
                    mesh.fillPosition(vertex, position)
                    add(position.x).add(position.y)
                }
            }
            val unpacking = mesh.vertexAttributeUnpackingParams
            this[case, "$at.unpacking-components"] = unpacking.joinToString(" ") { "${it.components.size}" }
            this[case, "$at.unpacking"] = Floats().apply {
                unpacking.forEach { attribute -> attribute.components.forEach { add(it.offset).add(it.scale) } }
            }
        }
    }
    if (bounds != null) coverage(case, key, shape, bounds.xMin, bounds.yMin, bounds.xMax, bounds.yMax)
}

/** Coverage of a 3×3 grid of boxes over the bounds, and of a triangle through their middle. */
private fun Dump.coverage(case: String, key: String, shape: PartitionedMesh, x0: Float, y0: Float, x1: Float, y1: Float) {
    val width = (x1 - x0) / 3f
    val height = (y1 - y0) / 3f
    val boxes = (0 until 9).map { cell ->
        val left = x0 + (cell % 3) * width
        val top = y0 + (cell / 3) * height
        shape.computeCoverage(ImmutableBox.fromTwoPoints(ImmutableVec(left, top), ImmutableVec(left + width, top + height)))
    }
    this[case, "$key.coverage.boxes"] = Floats.of(*boxes.toFloatArray())
    val middle = ImmutableTriangle(
        ImmutableVec(x0, (y0 + y1) / 2f),
        ImmutableVec((x0 + x1) / 2f, y0),
        ImmutableVec(x1, y1),
    )
    this[case, "$key.coverage.triangle"] = Floats.of(shape.computeCoverage(middle))
}

/** The family's own serialized form, as ink-storage writes it. */
fun Dump.family(case: String, family: BrushFamily) {
    this[case, "family.encoded"] = digest().add(ByteArrayOutputStream().also { family.encode(it) }.toByteArray())
}

/**
 * The batch's serialized form, as ink-storage writes it: gzip (done in Java) over a protobuf (done
 * natively). The protobuf is recorded separately, in full for batches small enough to read, and
 * once more without field 10: Google's internal source writes the stroke's base animation phase
 * there, which the public source reserves.
 */
fun Dump.inputs(case: String, inputs: StrokeInputBatch) {
    val encoded = inputs.encode()
    val proto = GZIPInputStream(encoded.inputStream()).use { it.readBytes() }
    this[case, "inputs.count"] = inputs.size
    this[case, "inputs.encoded"] = digest().add(encoded)
    this[case, "inputs.proto"] = if (inputs.size <= 2) proto.joinToString("") { "%02x".format(it) } else digest().add(proto)
    this[case, "inputs.proto.public"] = digest().add(ProtoFields.without(proto, field = 10))
}

/**
 * Feeds [inputs] to an in-progress stroke [chunk] inputs at a time — the way live drawing does —
 * recording its geometry after every update, then after finishing, then as the finished stroke.
 * With [predicted] set, the last few inputs of each chunk also go in as predictions.
 */
fun Dump.inProgress(
    case: String,
    brush: androidx.ink.brush.Brush,
    inputs: StrokeInputBatch,
    chunk: Int,
    predicted: Int = 0,
) {
    val stroke = InProgressStroke()
    stroke.start(brush)
    val input = StrokeInput()
    var start = 0
    var step = 0
    while (start < inputs.size) {
        val end = minOf(start + chunk, inputs.size)
        val real = MutableStrokeInputBatch()
        for (index in start until end) real.add(inputs.populate(index, input))
        val guesses = MutableStrokeInputBatch()
        for (index in end until minOf(end + predicted, inputs.size)) guesses.add(inputs.populate(index, input))
        stroke.enqueueInputs(real, guesses)
        stroke.updateShape(inputs[end - 1].elapsedTimeMillis)
        inProgressShape(case, "live.step$step", stroke)
        start = end
        step++
    }
    stroke.finishInput()
    stroke.updateShape()
    inProgressShape(case, "live.finished", stroke)
    shape(case, "live.dry", stroke.toImmutable().shape)
}

private fun Dump.inProgressShape(case: String, key: String, stroke: InProgressStroke) {
    val position = MutableVec()
    for (coat in 0 until stroke.getBrushCoatCount()) {
        val partitions = stroke.getMeshPartitionCount(coat)
        this[case, "$key.c$coat.partitions"] = partitions
        for (partition in 0 until partitions) {
            val at = "$key.c$coat.p$partition"
            this[case, "$at.vertices"] = stroke.getVertexCount(coat, partition)
            // Unpacked while in progress: every attribute of every vertex is a float.
            this[case, "$at.vertex-buffer"] = Floats().add(stroke.getRawVertexBuffer(coat, partition))
            this[case, "$at.index-buffer"] = digest().add(stroke.getRawTriangleIndexBuffer(coat, partition))
        }
        val outlines = stroke.getOutlineCount(coat)
        val sizes = digest()
        val positions = Floats()
        for (outline in 0 until outlines) {
            val vertices = stroke.getOutlineVertexCount(coat, outline)
            sizes.add(vertices)
            for (vertex in 0 until vertices) {
                stroke.populateOutlinePosition(coat, outline, vertex, position)
                positions.add(position.x).add(position.y)
            }
        }
        this[case, "$key.c$coat.outlines"] = outlines
        this[case, "$key.c$coat.outline-sizes"] = sizes
        this[case, "$key.c$coat.outline-positions"] = positions
    }
}
