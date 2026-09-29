@file:OptIn(InkInternalOnlyApi::class)

package com.vivenotes.byteink.core

import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.geometry.getRawTriangleIndexBuffer
import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.getRawTriangleIndexBuffer

/** One triangle mesh in its shape's own coordinates: an x, y pair per vertex, three vertex indices per triangle. */
public class TriangleMesh(public val positions: FloatArray, public val triangles: IntArray) {
    public val vertexCount: Int get() = positions.size / 2
    public val triangleCount: Int get() = triangles.size / 3
}

/**
 * The geometry Ink builds a stroke from, for renderers.
 *
 * Outlines — closed polygons, which a path renderer fills — come through Ink's public API. Triangles
 * do not: AndroidX marks the meshes behind them `@InkInternalOnlyApi` and `@RestrictTo`, so they
 * may change between alphas. This file is the only place byteink reaches for them, and its tests
 * pin down each fact it relies on, so an Ink upgrade that changes one fails here, loudly, rather
 * than drawing something wrong.
 */
public object InkMeshes {

    /** The outlines of [shape]'s render group [group], each as x, y pairs in the shape's coordinates. */
    public fun outlines(shape: PartitionedMesh, group: Int): List<FloatArray> {
        val position = MutableVec()
        return List(shape.getOutlineCount(group)) { outline ->
            val vertices = shape.getOutlineVertexCount(group, outline)
            FloatArray(vertices * 2).also { points ->
                for (vertex in 0 until vertices) {
                    shape.populateOutlinePosition(group, outline, vertex, position)
                    points[vertex * 2] = position.x
                    points[vertex * 2 + 1] = position.y
                }
            }
        }
    }

    /** The outlines of a stroke still being drawn, for brush coat [coat]. */
    public fun outlines(stroke: InProgressStroke, coat: Int): List<FloatArray> {
        val position = MutableVec()
        return List(stroke.getOutlineCount(coat)) { outline ->
            val vertices = stroke.getOutlineVertexCount(coat, outline)
            FloatArray(vertices * 2).also { points ->
                for (vertex in 0 until vertices) {
                    stroke.populateOutlinePosition(coat, outline, vertex, position)
                    points[vertex * 2] = position.x
                    points[vertex * 2 + 1] = position.y
                }
            }
        }
    }

    /** The triangle meshes of [shape]'s render group [group]. */
    public fun triangles(shape: PartitionedMesh, group: Int): List<TriangleMesh> {
        val position = MutableVec()
        return shape.renderGroupMeshes(group).map { mesh ->
            val positions = FloatArray(mesh.vertexCount * 2)
            for (vertex in 0 until mesh.vertexCount) {
                mesh.fillPosition(vertex, position)
                positions[vertex * 2] = position.x
                positions[vertex * 2 + 1] = position.y
            }
            TriangleMesh(positions, indices(mesh.getRawTriangleIndexBuffer(), mesh.triangleCount))
        }
    }

    /** The triangle meshes of a stroke still being drawn, for brush coat [coat]: one per partition. */
    public fun triangles(stroke: InProgressStroke, coat: Int): List<TriangleMesh> {
        val position = MutableVec()
        return List(stroke.getMeshPartitionCount(coat)) { partition ->
            val vertices = stroke.getVertexCount(coat, partition)
            val positions = FloatArray(vertices * 2)
            for (vertex in 0 until vertices) {
                stroke.populatePosition(coat, partition, vertex, position)
                positions[vertex * 2] = position.x
                positions[vertex * 2 + 1] = position.y
            }
            val raw = stroke.getRawTriangleIndexBuffer(coat, partition)
            TriangleMesh(positions, indices(raw, raw.remaining() / 3))
        }
    }

    /** Ink's 16-bit triangle indices, which are unsigned, as ints. */
    private fun indices(raw: java.nio.ShortBuffer, triangleCount: Int): IntArray {
        val buffer = raw.duplicate()
        check(buffer.remaining() == triangleCount * 3) {
            "Ink's index buffer holds ${buffer.remaining()} indices for $triangleCount triangles; its layout has changed"
        }
        return IntArray(buffer.remaining()) { buffer.get().toInt() and 0xFFFF }
    }
}
