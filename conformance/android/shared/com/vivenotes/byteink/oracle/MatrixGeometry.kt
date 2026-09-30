package com.vivenotes.byteink.oracle

import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.getRawTriangleIndexBuffer
import androidx.ink.strokes.Stroke

/** A projection without application-specific metadata or non-deterministic native identity. */
internal data class OracleProjection(
    val id: String,
    val stroke: Stroke,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val colorFollowsTheme: Boolean? = false,
)

/** Integers describe topology and are exact; all Float-valued coordinates use oracle tolerance. */
internal fun geometryJson(raw: List<OracleProjection>, replayed: List<OracleProjection>): String =
    "{\"schemaVersion\":1,\"raw\":${raw.joinToString(prefix = "[", postfix = "]") { projectionJson(it) }}," +
        "\"replayed\":${replayed.joinToString(prefix = "[", postfix = "]") { projectionJson(it) }}}\n"

private fun projectionJson(projection: OracleProjection): String {
    val stroke = projection.stroke
    val mesh = stroke.shape
    val position = MutableVec()
    fun point(x: Float, y: Float) = "[$x,$y]"
    fun box(x1: Float, y1: Float, x2: Float, y2: Float) = "[$x1,$y1,$x2,$y2]"
    return buildString {
        append("{\"id\":\"").append(projection.id).append("\",\"transform\":[")
        append(listOf(projection.offsetX, projection.offsetY, projection.scaleX, projection.scaleY).joinToString())
        append("],\"colorFollowsTheme\":").append(projection.colorFollowsTheme)
        append(",\"colorArgb\":").append(stroke.brush.colorIntArgb)
        append(",\"size\":").append(stroke.brush.size)
        val bounds = mesh.computeBoundingBox()
        append(",\"bounds\":").append(bounds?.let { box(it.xMin, it.yMin, it.xMax, it.yMax) } ?: "null")
        append(",\"pageBounds\":").append(bounds?.let { box(
            it.xMin * projection.scaleX + projection.offsetX, it.yMin * projection.scaleY + projection.offsetY,
            it.xMax * projection.scaleX + projection.offsetX, it.yMax * projection.scaleY + projection.offsetY) } ?: "null")
        append(",\"inputs\":[")
        repeat(stroke.inputs.size) { index ->
            if (index > 0) append(',')
            val input = stroke.inputs[index]
            append("{\"tool\":\"").append(input.toolType).append("\",\"time\":").append(input.elapsedTimeMillis)
            append(",\"values\":[").append(listOf(input.x, input.y, input.strokeUnitLengthCm,
                input.pressure, input.tiltRadians, input.orientationRadians).joinToString()).append("]}")
        }
        append("],\"renderGroupCount\":").append(mesh.getRenderGroupCount()).append(",\"groups\":[")
        repeat(mesh.getRenderGroupCount()) { group ->
            if (group > 0) append(',')
            append("{\"outlineCount\":").append(mesh.getOutlineCount(group)).append(",\"outlines\":[")
            repeat(mesh.getOutlineCount(group)) { outline ->
                if (outline > 0) append(',')
                append('[')
                repeat(mesh.getOutlineVertexCount(group, outline)) { vertex ->
                    if (vertex > 0) append(',')
                    mesh.populateOutlinePosition(group, outline, vertex, position)
                    append(point(position.x, position.y))
                }
                append(']')
            }
            append("],\"meshes\":[")
            mesh.renderGroupMeshes(group).forEachIndexed { meshIndex, partition ->
                if (meshIndex > 0) append(',')
                append("{\"vertexCount\":").append(partition.vertexCount)
                append(",\"triangleCount\":").append(partition.triangleCount).append(",\"positions\":[")
                repeat(partition.vertexCount) { vertex ->
                    if (vertex > 0) append(',')
                    partition.fillPosition(vertex, position)
                    append(point(position.x, position.y))
                }
                append("],\"triangleIndices\":[")
                val indices = partition.getRawTriangleIndexBuffer().duplicate()
                var first = true
                while (indices.hasRemaining()) {
                    if (!first) append(',')
                    first = false
                    append(indices.get().toInt() and 0xffff)
                }
                append("]}")
            }
            append("]}")
        }
        append("],\"coverage\":[")
        if (bounds != null) {
            val cx = (bounds.xMin + bounds.xMax) / 2f
            val cy = (bounds.yMin + bounds.yMax) / 2f
            val probes = listOf(
                ImmutableBox.fromTwoPoints(ImmutableVec(bounds.xMin - 1f, bounds.yMin - 1f), ImmutableVec(bounds.xMax + 1f, bounds.yMax + 1f)),
                ImmutableBox.fromTwoPoints(ImmutableVec(bounds.xMin, bounds.yMin), ImmutableVec(cx, cy)),
                ImmutableBox.fromTwoPoints(ImmutableVec(cx - 8f, cy - 8f), ImmutableVec(cx + 8f, cy + 8f)),
                ImmutableBox.fromTwoPoints(ImmutableVec(bounds.xMax + 10f, bounds.yMax + 10f), ImmutableVec(bounds.xMax + 20f, bounds.yMax + 20f)),
            )
            probes.forEachIndexed { index, probe ->
                if (index > 0) append(',')
                append("{\"box\":").append(box(probe.xMin, probe.yMin, probe.xMax, probe.yMax))
                append(",\"value\":").append(mesh.computeCoverage(probe))
                append(",\"above01\":").append(mesh.computeCoverageIsGreaterThan(probe, 0.1f))
                append(",\"above50\":").append(mesh.computeCoverageIsGreaterThan(probe, 0.5f)).append('}')
            }
        }
        append("]}")
    }
}
