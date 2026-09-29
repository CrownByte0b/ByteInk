package com.vivenotes.byteink.core

import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.BoxAccumulator
import androidx.ink.geometry.ImmutableTriangle
import androidx.ink.geometry.ImmutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What byteink relies on in Ink's internal meshes, pinned: if an Ink upgrade changes any of it, these
 * fail rather than a renderer drawing something wrong.
 */
class InkMeshesTest {

    private val brush = Brush.createWithColorIntArgb(StockBrushes.marker(StockBrushes.MarkerVersion.V1), 0xFF000000.toInt(), 6f, 0.25f)

    private val inputs: StrokeInputBatch = MutableStrokeInputBatch().apply {
        for (i in 0 until 40) add(InputToolType.STYLUS, 10f + i * 4f, 50f + (i % 7) * 3f, i * 8L)
    }.toImmutable()

    @Test
    fun aFinishedStrokesTrianglesLieOnItsInk() {
        val shape = Stroke(brush, inputs).shape

        val meshes = (0 until shape.getRenderGroupCount()).flatMap { InkMeshes.triangles(shape, it) }

        assertTrue(meshes.isNotEmpty() && meshes.all { it.triangleCount > 0 })
        meshes.forEach { mesh -> assertTrianglesOnInk(mesh, shape) }
    }

    @Test
    fun aFinishedStrokesOutlinesStayWithinItsBounds() {
        val shape = Stroke(brush, inputs).shape
        val bounds = shape.computeBoundingBox()!!

        val outlines = (0 until shape.getRenderGroupCount()).flatMap { InkMeshes.outlines(shape, it) }

        assertTrue(outlines.isNotEmpty() && outlines.all { it.size >= 6 }, "a closed outline has at least three vertices")
        outlines.forEach { outline ->
            for (i in outline.indices step 2) {
                assertTrue(outline[i] in bounds.xMin - 0.01f..bounds.xMax + 0.01f && outline[i + 1] in bounds.yMin - 0.01f..bounds.yMax + 0.01f)
            }
        }
    }

    /** Compares with Ink's own partition count, internal as it is: pinning such facts is this test's job. */
    @OptIn(InkInternalOnlyApi::class)
    @Test
    fun aStrokeBeingDrawnHasTrianglesAndOutlinesToo() {
        val stroke = InProgressStroke()
        stroke.start(brush)
        stroke.enqueueInputs(inputs, MutableStrokeInputBatch())
        stroke.updateShape(inputs[inputs.size - 1].elapsedTimeMillis)
        val bounds = BoxAccumulator().also { stroke.populateMeshBounds(0, it) }.box!!

        val meshes = InkMeshes.triangles(stroke, 0)
        val outlines = InkMeshes.outlines(stroke, 0)

        assertTrue(meshes.isNotEmpty() && meshes.all { it.triangleCount > 0 })
        assertTrue(outlines.isNotEmpty())
        meshes.forEach { mesh ->
            mesh.triangles.forEach { assertTrue(it in 0 until mesh.vertexCount) }
            for (i in mesh.positions.indices step 2) {
                assertTrue(mesh.positions[i] in bounds.xMin - 0.01f..bounds.xMax + 0.01f && mesh.positions[i + 1] in bounds.yMin - 0.01f..bounds.yMax + 0.01f)
            }
        }
        assertEquals(stroke.getMeshPartitionCount(0), meshes.size)
    }

    /** Every index names a vertex of its own mesh, and every triangle's middle is covered by the shape. */
    private fun assertTrianglesOnInk(mesh: TriangleMesh, shape: PartitionedMesh) {
        assertEquals(0, mesh.triangles.size % 3)
        mesh.triangles.forEach { assertTrue(it in 0 until mesh.vertexCount, "index $it of ${mesh.vertexCount} vertices") }
        for (t in 0 until mesh.triangleCount) {
            val (a, b, c) = (0 until 3).map { mesh.triangles[t * 3 + it] }
            val x = (mesh.positions[a * 2] + mesh.positions[b * 2] + mesh.positions[c * 2]) / 3f
            val y = (mesh.positions[a * 2 + 1] + mesh.positions[b * 2 + 1] + mesh.positions[c * 2 + 1]) / 3f
            val probe = ImmutableTriangle(ImmutableVec(x - 0.01f, y - 0.01f), ImmutableVec(x + 0.01f, y - 0.01f), ImmutableVec(x, y + 0.01f))
            assertTrue(shape.computeCoverageIsGreaterThan(probe, 0f), "triangle $t's middle ($x, $y) is not on the ink")
        }
    }
}
