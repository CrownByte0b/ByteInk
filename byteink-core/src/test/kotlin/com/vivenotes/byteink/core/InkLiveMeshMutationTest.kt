@file:OptIn(androidx.ink.nativeloader.InkInternalOnlyApi::class, androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.vivenotes.byteink.core

import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.getRawTriangleIndexBuffer
import androidx.ink.strokes.getRawVertexBuffer
import java.nio.ByteOrder
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exercises engine mutations that invalidate a cache based only on growth or triangle indices. */
class InkLiveMeshMutationTest {
    private val empty = MutableStrokeInputBatch()
    private val brush = brush(BrushFamily())

    private fun brush(family: BrushFamily) =
        Brush.createWithColorIntArgb(family, 0x804020e0.toInt(), 12f, .25f)

    private fun line(count: Int, from: Int = 0) = MutableStrokeInputBatch().apply {
        repeat(count) { offset ->
            val i = from + offset
            add(InputToolType.STYLUS, 20f + i * 10f, 60f, i * 8L)
        }
    }

    @Test
    fun timedStrokeEndRebuildMutatesPrefixWithoutChangingCountsOrTriangleIndices() {
        val timed = brush(BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(
            TargetNode.Target.OPACITY_MULTIPLIER, 1f, .2f,
            SourceNode(SourceNode.Source.TIME_SINCE_STROKE_END_IN_SECONDS, 0f, 1f),
        ))))))
        val live = InProgressStroke()
        try {
            live.start(timed)
            live.enqueueInputs(line(48), empty)
            live.updateShape(376L)
            live.finishInput()
            live.updateShape(400L)
            assertTrue(live.changesWithTime())
            val first = InkMeshes.rendering(live, 0).single()
            val preserved = first.vertices.clone()
            val indices = first.triangles.clone()

            // ShapeBuilder restarts the tip modeler/extruder for this post-finish animation.
            live.updateShape(650L)
            val changed = InkMeshes.rendering(live, 0).single()
            assertEquals(first.vertexCount, changed.vertexCount)
            assertEquals(first.triangleCount, changed.triangleCount)
            assertContentEquals(indices, changed.triangles)
            assertTrue(changed.vertices[2] < first.vertices[2], "The first vertex opacity must change")
            repeat(first.vertexCount) { vertex ->
                val position = vertex * StrokeMesh.VERTEX_STRIDE
                assertEquals(first.vertices[position].toRawBits(), changed.vertices[position].toRawBits())
                assertEquals(first.vertices[position + 1].toRawBits(), changed.vertices[position + 1].toRawBits())
            }
            assertMatchesRaw(live)

            // Multiple time updates may precede another renderer's snapshot.
            live.updateShape(800L)
            live.updateShape(1100L)
            val delayed = InkMeshes.rendering(live, 0).single()
            assertTrue(delayed.vertices[2] < changed.vertices[2])
            assertMatchesRaw(live)
            assertContentEquals(preserved, first.vertices)
            assertContentEquals(indices, first.triangles)
        } finally { live.clear() }
    }

    @Test
    fun mirroredPredictionReplacementAndEmptyRollbackCopyEveryCurrentAttribute() {
        val live = InProgressStroke()
        try {
            live.start(brush)
            val real = line(12)
            fun prediction(y: Float) = MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, 160f, y, 120L)
                add(InputToolType.STYLUS, 200f, y, 144L)
            }
            live.enqueueInputs(real, prediction(84f))
            live.updateShape(88L)
            val upper = InkMeshes.rendering(live, 0).single()
            val saved = upper.vertices.clone()

            live.enqueueInputs(empty, prediction(36f))
            live.updateShape(88L)
            val lower = InkMeshes.rendering(live, 0).single()
            assertEquals(upper.vertexCount, lower.vertexCount, "Mirrored predictions keep the same vertex count")
            assertEquals(upper.triangleCount, lower.triangleCount)
            assertFalse(upper.vertices.contentEquals(lower.vertices), "Same-count replacement changes existing vertices")
            assertMatchesRaw(live)

            live.enqueueInputs(empty, MutableStrokeInputBatch().apply {
                add(InputToolType.STYLUS, 140f, 60f, 112L)
            })
            live.updateShape(88L)
            assertEquals(1, live.getPredictedInputCount())
            assertMatchesRaw(live)
            live.enqueueInputs(empty, empty)
            live.updateShape(88L)
            assertEquals(0, live.getPredictedInputCount())
            val rolledBack = InkMeshes.rendering(live, 0).single()
            assertTrue(rolledBack.vertices.asList().chunked(StrokeMesh.VERTEX_STRIDE).all { it[0] < 140f })
            assertMatchesRaw(live)
            assertContentEquals(saved, upper.vertices)

            val version = live.getVersion()
            live.updateShape(100L)
            assertTrue(live.getVersion() > version, "A no-op update still advances the Kotlin version")
            val noOp = InkMeshes.rendering(live, 0).single()
            assertContentEquals(rolledBack.vertices, noOp.vertices)
            assertContentEquals(rolledBack.triangles, noOp.triangles)
        } finally { live.clear() }
    }

    @Test
    fun snapshotsAfterSkippedUpdatesRemainExactAndIndependentAcrossClearAndReuse() {
        val live = InProgressStroke()
        fun trace(from: Int, count: Int) = MutableStrokeInputBatch().apply {
            repeat(count) { offset ->
                val i = from + offset
                add(InputToolType.STYLUS, 20f + i * 2f, 60f + sin(i * .5).toFloat() * 16f, i * 8L)
            }
        }
        try {
            live.start(brush)
            live.enqueueInputs(trace(0, 64), empty)
            live.updateShape(504L)
            val first = InkMeshes.rendering(live, 0)
            val preserved = first.map { it.vertices.clone() to it.triangles.clone() }
            repeat(12) { update ->
                val from = 64 + update * 32
                live.enqueueInputs(trace(from, 32), empty)
                live.updateShape((from + 31) * 8L)
            }
            val delayed = InkMeshes.rendering(live, 0)
            assertTrue(delayed.sumOf { it.vertexCount } > first.sumOf { it.vertexCount })
            assertMatchesRaw(live)

            live.clear()
            live.start(brush(BrushFamily(BrushTip(scaleX = .5f, scaleY = 2f))))
            live.enqueueInputs(line(1), empty)
            live.updateShape(0L)
            assertMatchesRaw(live)
            first.zip(preserved).forEach { (mesh, copy) ->
                assertContentEquals(copy.first, mesh.vertices)
                assertContentEquals(copy.second, mesh.triangles)
            }
        } finally { live.clear() }
    }

    private fun assertMatchesRaw(live: InProgressStroke) {
        val snapshots = InkMeshes.rendering(live, 0)
        assertEquals(live.getMeshPartitionCount(0), snapshots.size)
        snapshots.forEachIndexed { partition, mesh ->
            val raw = live.getRawVertexBuffer(0, partition).order(ByteOrder.nativeOrder())
            assertEquals(mesh.vertices.size * Float.SIZE_BYTES, raw.remaining())
            repeat(mesh.vertices.size) { component ->
                assertEquals(raw.getFloat(component * Float.SIZE_BYTES).toRawBits(), mesh.vertices[component].toRawBits())
            }
            val indices = live.getRawTriangleIndexBuffer(0, partition)
            assertContentEquals(IntArray(indices.remaining()) { indices.get().toInt() and 0xffff }, mesh.triangles)
        }
    }
}
