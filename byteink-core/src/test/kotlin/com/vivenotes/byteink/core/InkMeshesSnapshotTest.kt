@file:OptIn(androidx.ink.nativeloader.InkInternalOnlyApi::class)

package com.vivenotes.byteink.core

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.geometry.MutableVec
import androidx.ink.geometry.PartitionedMesh
import androidx.ink.geometry.getRawTriangleIndexBuffer
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.getRawTriangleIndexBuffer
import androidx.ink.strokes.getRawVertexBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InkMeshesSnapshotTest {
    private val marker = brush(StockBrushes.marker(StockBrushes.MarkerVersion.V1))
    private fun brush(family: BrushFamily) = Brush.createWithColorIntArgb(family, 0x804020e0.toInt(), 6f, .25f)
    private fun inputs(count: Int, from: Int = 0) = MutableStrokeInputBatch().apply {
        repeat(count) { offset ->
            val i = from + offset
            add(InputToolType.STYLUS, 16f + i * .04f, 60f + sin(i * .08).toFloat() * 40f, i * 8L,
                pressure = .2f + (i % 7) * .1f)
        }
    }

    @Test
    fun finishedAndLiveCopiesExactlyMatchPinnedGettersAcrossSizesAndCoats() {
        val families = listOf(marker.family, StockBrushes.highlighter(version = StockBrushes.HighlighterVersion.V1),
            BrushFamily(listOf(BrushCoat(BrushTip()), BrushCoat(BrushTip(scaleX = .5f, scaleY = 2f)))))
        for (family in families) for (count in listOf(1, 9, 1024, 8192)) {
            val brush = brush(family)
            val inputs = inputs(count)
            val dry = Stroke(brush, inputs)
            val wet = InProgressStroke()
            try {
                wet.start(brush)
                wet.enqueueInputs(inputs, MutableStrokeInputBatch())
                wet.updateShape(count * 8L)
                repeat(family.coats.size) { coat ->
                    equalOutlines(scalarOutlines(dry.shape, coat), InkMeshes.outlines(dry.shape, coat))
                    equalOutlines(scalarOutlines(wet, coat), InkMeshes.outlines(wet, coat))
                    equalMeshes(scalarTriangles(dry.shape, coat), InkMeshes.triangles(dry.shape, coat))
                    equalMeshes(scalarTriangles(wet, coat), InkMeshes.triangles(wet, coat))
                }
            } finally { wet.clear() }
        }
    }

    @Test
    fun predictionReplacementFinishClearAndRestartNeverMutatePriorCopies() {
        val wet = InProgressStroke()
        val empty = MutableStrokeInputBatch()
        try {
            wet.start(marker)
            wet.enqueueInputs(inputs(9), inputs(5, 9))
            wet.updateShape(104L)
            val old = InkMeshes.outlines(wet, 0)
            val saved = old.map(FloatArray::clone)
            val triangles = InkMeshes.triangles(wet, 0)
            val savedTriangles = triangles.map { TriangleMesh(it.positions.clone(), it.triangles.clone()) }
            wet.enqueueInputs(inputs(4, 9), inputs(2, 13))
            wet.updateShape(112L)
            equalOutlines(scalarOutlines(wet, 0), InkMeshes.outlines(wet, 0))
            wet.enqueueInputs(empty, empty)
            wet.finishInput()
            wet.updateShape()
            equalOutlines(scalarOutlines(wet, 0), InkMeshes.outlines(wet, 0))
            wet.clear()
            wet.start(marker)
            wet.enqueueInputs(inputs(1), empty)
            wet.updateShape(0L)
            equalOutlines(saved, old)
            equalMeshes(savedTriangles, triangles)
            old.first().fill(-999f)
            assertTrue(InkMeshes.outlines(wet, 0).flattenFloats().none { it == -999f })
        } finally { wet.clear() }
    }

    @Test
    fun emptyGroupsAndInvalidIndicesFailBeforeNativeMeshAccess() {
        val dry = Stroke(marker, MutableStrokeInputBatch()).shape
        assertTrue(InkMeshes.outlines(dry, 0).isEmpty())
        assertTrue(InkMeshes.triangles(dry, 0).isEmpty())
        val wet = InProgressStroke()
        for (bad in listOf(Int.MIN_VALUE, -1, 0, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { InkMeshes.outlines(wet, bad) }
            assertFailsWith<IllegalArgumentException> { InkMeshes.triangles(wet, bad) }
        }
        wet.start(marker)
        assertTrue(InkMeshes.outlines(wet, 0).all { it.isEmpty() })
        assertTrue(InkMeshes.triangles(wet, 0).all { it.vertexCount == 0 && it.triangleCount == 0 })
        wet.clear()
        assertFailsWith<IllegalArgumentException> { InkMeshes.triangles(wet, 0) }
        for (bad in listOf(-1, 1, Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { InkMeshes.outlines(dry, bad) }
            assertFailsWith<IllegalArgumentException> { InkMeshes.triangles(dry, bad) }
        }
    }

    @Test
    fun snapshotsNeverRegisterNativeBufferOwners() {
        val wet = InProgressStroke()
        val dry = Stroke(marker, inputs(9)).shape
        try {
            wet.start(marker)
            wet.enqueueInputs(inputs(9), MutableStrokeInputBatch())
            wet.updateShape(72L)
            repeat(3) {
                InkMeshes.outlines(wet, 0); InkMeshes.triangles(wet, 0)
                InkMeshes.outlines(dry, 0); InkMeshes.triangles(dry, 0)
                InkMeshes.rendering(wet, 0); InkMeshes.rendering(dry, 0)
            }
            val field = Class.forName("androidx.ink.strokes.InProgressStrokeExtensions")
                .getDeclaredField("inProgressStrokesReferencedByBuffers").apply { isAccessible = true }
            val owners = field.get(null) as Map<*, *>
            synchronized(owners) { assertFalse(owners.values.any { it === wet }) }
        } finally { wet.clear() }
    }

    @Test
    fun immutableOwnersSupportConcurrentIndependentCopies() {
        val shape = Stroke(marker, inputs(1024)).shape
        val expected = InkMeshes.outlines(shape, 0)
        Executors.newFixedThreadPool(4).use { executor ->
            val jobs = (0 until 16).map {
                executor.submit {
                    equalOutlines(expected, InkMeshes.outlines(shape, 0))
                    equalMeshes(scalarTriangles(shape, 0), InkMeshes.triangles(shape, 0))
                }
            }
            jobs.forEach { it.get() }
        }
    }

    @Test
    fun livePartitionsPastUnsigned16BitLimitCopyTheirOwnVertexSlice() {
        val wet = InProgressStroke()
        val batch = MutableStrokeInputBatch()
        val empty = MutableStrokeInputBatch()
        try {
            wet.start(marker)
            var count = 0
            while (wet.getMeshPartitionCount(0) <= 1 && count < 16_384) {
                batch.clear()
                repeat(256) {
                    val i = count++
                    val radius = 100f * sqrt(i.toFloat())
                    batch.add(InputToolType.MOUSE, radius * cos(i.toFloat()), radius * sin(i.toFloat()), i.toLong())
                }
                wet.enqueueInputs(batch, empty)
                wet.updateShape(count.toLong())
            }
            assertTrue(wet.getMeshPartitionCount(0) > 1, "bounded spiral must cross the partition boundary")
            val meshes = InkMeshes.triangles(wet, 0)
            assertTrue(meshes.first().vertexCount >= 65_535)
            for ((partition, mesh) in meshes.withIndex()) {
                val rendering = InkMeshes.rendering(wet, 0)[partition]
                assertEquals(mesh.vertexCount, rendering.vertexCount)
                assertContentEquals(mesh.triangles, rendering.triangles)
                val raw = wet.getRawVertexBuffer(0, partition).order(ByteOrder.nativeOrder())
                // Pinned StrokeVertex::FullMeshFormat begins with two float positions.
                // Derive its stride from this partition and verify it against the scalar
                // getter in partition zero before checking the offset-sensitive copies.
                val stride = raw.remaining() / mesh.vertexCount
                assertTrue(stride >= 8 && stride % 4 == 0)
                assertEquals(StrokeMesh.VERTEX_STRIDE * 4, stride)
                assertEquals(511, rendering.attributeMask)
                repeat(mesh.vertexCount) { vertex ->
                    repeat(StrokeMesh.VERTEX_STRIDE) { component ->
                        assertEquals(raw.getFloat(vertex * stride + component * 4),
                            rendering.vertices[vertex * StrokeMesh.VERTEX_STRIDE + component])
                    }
                    assertEquals(raw.getFloat(vertex * stride), mesh.positions[vertex * 2])
                    assertEquals(raw.getFloat(vertex * stride + 4), mesh.positions[vertex * 2 + 1])
                    if (partition == 0) {
                        val p = wet.populatePosition(0, 0, vertex, MutableVec())
                        assertEquals(p.x, mesh.positions[vertex * 2])
                        assertEquals(p.y, mesh.positions[vertex * 2 + 1])
                    }
                }
                val indices = wet.getRawTriangleIndexBuffer(0, partition)
                assertContentEquals(IntArray(indices.remaining()) { indices.get().toInt() and 0xffff }, mesh.triangles)
                assertTrue(mesh.triangles.all { it in 0 until mesh.vertexCount })
            }
            // The pinned public live position getter ignores the partition offset. Check the
            // actual partition buffer above, and prove later partitions aren't copied from zero.
            assertFalse(meshes[0].positions.take(2) == meshes[1].positions.take(2))
            val dry = wet.toImmutable().shape
            repeat(dry.getRenderGroupCount()) { group ->
                equalOutlines(scalarOutlines(dry, group), InkMeshes.outlines(dry, group))
                equalMeshes(scalarTriangles(dry, group), InkMeshes.triangles(dry, group))
            }
        } finally { wet.clear() }
    }

    @Test
    fun renderingSnapshotsOwnDecodedAttributesAndSurviveUpdatesAndClear() {
        val wet = InProgressStroke()
        wet.start(marker)
        wet.enqueueInputs(inputs(9), inputs(5, 9))
        wet.updateShape(104L)
        val snapshot = InkMeshes.rendering(wet, 0)
        val copies = snapshot.map { it.vertices.clone() }
        assertTrue(snapshot.all { it.attributeMask == 511 })
        assertTrue(snapshot.all { it.hasSurfaceUv && it.hasAnimationOffset })
        wet.finishInput(); wet.updateShape(1000L)
        val dry = wet.toImmutable()
        val positions = InkMeshes.triangles(dry.shape, 0)
        val decoded = InkMeshes.rendering(dry.shape, 0)
        assertEquals(positions.size, decoded.size)
        positions.zip(decoded).forEach { (geometry, mesh) ->
            assertContentEquals(geometry.triangles, mesh.triangles)
            repeat(mesh.vertexCount) { vertex ->
                assertEquals(geometry.positions[vertex * 2], mesh.vertices[vertex * StrokeMesh.VERTEX_STRIDE])
                assertEquals(geometry.positions[vertex * 2 + 1], mesh.vertices[vertex * StrokeMesh.VERTEX_STRIDE + 1])
            }
            assertTrue(mesh.vertices.all(Float::isFinite))
            assertTrue(mesh.attributeMask and 1 != 0)
        }
        wet.clear()
        snapshot.zip(copies).forEach { (mesh, saved) -> assertContentEquals(saved, mesh.vertices) }
        snapshot.first().vertices.fill(-999f)
        assertTrue(InkMeshes.rendering(dry.shape, 0).all { it.vertices.none { f -> f == -999f } })
        assertFailsWith<IllegalArgumentException> { InkMeshes.rendering(wet, 0) }
        assertFailsWith<IllegalArgumentException> { InkMeshes.rendering(dry.shape, -1) }
        assertTrue(InkMeshes.rendering(Stroke(marker, MutableStrokeInputBatch()).shape, 0).isEmpty())
    }

    private fun scalarOutlines(shape: PartitionedMesh, group: Int): List<FloatArray> {
        val p = MutableVec()
        return List(shape.getOutlineCount(group)) { outline ->
            FloatArray(shape.getOutlineVertexCount(group, outline) * 2).also { values ->
                for (vertex in 0 until values.size / 2) {
                    shape.populateOutlinePosition(group, outline, vertex, p)
                    values[vertex * 2] = p.x; values[vertex * 2 + 1] = p.y
                }
            }
        }
    }

    private fun scalarOutlines(wet: InProgressStroke, coat: Int): List<FloatArray> {
        val p = MutableVec()
        return List(wet.getOutlineCount(coat)) { outline ->
            FloatArray(wet.getOutlineVertexCount(coat, outline) * 2).also { values ->
                for (vertex in 0 until values.size / 2) {
                    wet.populateOutlinePosition(coat, outline, vertex, p)
                    values[vertex * 2] = p.x; values[vertex * 2 + 1] = p.y
                }
            }
        }
    }

    private fun scalarTriangles(shape: PartitionedMesh, group: Int): List<TriangleMesh> =
        shape.renderGroupMeshes(group).map { mesh ->
            val p = MutableVec()
            val positions = FloatArray(mesh.vertexCount * 2).also { values ->
                repeat(mesh.vertexCount) { vertex ->
                    mesh.fillPosition(vertex, p); values[vertex * 2] = p.x; values[vertex * 2 + 1] = p.y
                }
            }
            val raw = mesh.getRawTriangleIndexBuffer()
            TriangleMesh(positions, IntArray(raw.remaining()) { raw.get().toInt() and 0xffff })
        }

    private fun scalarTriangles(wet: InProgressStroke, coat: Int): List<TriangleMesh> =
        List(wet.getMeshPartitionCount(coat)) { partition ->
            val p = MutableVec()
            val positions = FloatArray(wet.getVertexCount(coat, partition) * 2).also { values ->
                repeat(values.size / 2) { vertex ->
                    wet.populatePosition(coat, partition, vertex, p)
                    values[vertex * 2] = p.x; values[vertex * 2 + 1] = p.y
                }
            }
            val raw = wet.getRawTriangleIndexBuffer(coat, partition)
            TriangleMesh(positions, IntArray(raw.remaining()) { raw.get().toInt() and 0xffff })
        }

    private fun List<FloatArray>.flattenFloats() = flatMap { it.asList() }
    private fun equalOutlines(expected: List<FloatArray>, actual: List<FloatArray>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) -> assertContentEquals(a, b) }
    }
    private fun equalMeshes(expected: List<TriangleMesh>, actual: List<TriangleMesh>) {
        assertEquals(expected.size, actual.size)
        expected.zip(actual).forEach { (a, b) ->
            assertContentEquals(a.positions, b.positions); assertContentEquals(a.triangles, b.triangles)
        }
    }
}
