@file:OptIn(androidx.ink.brush.ExperimentalInkAnimationApi::class, androidx.ink.brush.ExperimentalInkCustomBrushApi::class,
    androidx.ink.nativeloader.InkInternalOnlyApi::class)

package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushBehavior
import androidx.ink.brush.BrushCoat
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.BrushPaint
import androidx.ink.brush.BrushTip
import androidx.ink.brush.InputToolType
import androidx.ink.brush.SelfOverlap
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.geometry.AffineTransform
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.core.StrokeMesh
import com.vivenotes.byteink.kit.ViveBrushes
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/** Each reference draw starts with an empty renderer, so it cannot reuse any previous wet data. */
class InkIncrementalMeshRendererTest {
    private fun brush(family: BrushFamily = BrushFamily(), color: Int = 0xb02060c0.toInt(), size: Float = 8f) =
        Brush.createWithColorIntArgb(family, color, size, .05f)

    private fun inputs(start: Int, end: Int, y: Float = 80f) = MutableStrokeInputBatch().apply {
        for (i in start until end) {
            add(InputToolType.STYLUS, 16f + i * 2f, y + 8f * sin(i * .19f), i * 2L,
                pressure = .6f + .3f * sin(i * .07f))
        }
    }

    private fun longInputs(start: Int, end: Int) = MutableStrokeInputBatch().apply {
        for (i in start until end) {
            val row = i / 512
            val column = i % 512
            val x = 24f + (if (row % 2 == 0) column else 511 - column) * 1.9f
            val y = 28f + row * 38f + 16f * sin(i * .5f)
            add(InputToolType.STYLUS, x, y, i * 4L, pressure = .5f, tiltRadians = .4f, orientationRadians = .5f)
        }
    }

    private fun raster(
        renderer: InkMeshRenderer,
        stroke: InProgressStroke,
        transform: AffineTransform = AffineTransform.IDENTITY,
        color: Int? = null,
        width: Int = 256,
        height: Int = 192,
        canvasScale: Float = 1f,
    ): ByteArray = Surface.makeRaster(ImageInfo.makeS32(width, height, ColorAlphaType.PREMUL)).use { surface ->
        surface.canvas.clear(0)
        surface.canvas.scale(canvasScale, canvasScale)
        renderer.draw(surface.canvas.asComposeCanvas(), stroke, transform, colorArgb = color)
        Bitmap().use { bitmap ->
            assertTrue(bitmap.allocN32Pixels(width, height, false))
            assertTrue(surface.readPixels(bitmap, 0, 0))
            requireNotNull(bitmap.readPixels(bitmap.imageInfo, bitmap.rowBytes, 0, 0))
        }
    }

    private fun frame(
        label: String,
        renderer: InkMeshRenderer,
        stroke: InProgressStroke,
        transform: AffineTransform = AffineTransform.IDENTITY,
        color: Int? = null,
        width: Int = 256,
        height: Int = 192,
        canvasScale: Float = 1f,
    ): ByteArray {
        val actual = raster(renderer, stroke, transform, color, width, height, canvasScale)
        InkMeshRenderer(renderer.textureStore, cacheCapacity = 0, cacheByteBudget = 0).use { reference ->
            reference.animationTimeMillis = renderer.animationTimeMillis
            val expected = raster(reference, stroke, transform, color, width, height, canvasScale)
            exactBytes(expected, actual, label, width)
        }
        return actual
    }

    private fun exactBytes(expected: ByteArray, actual: ByteArray, label: String, width: Int) {
        assertEquals(expected.size, actual.size, label)
        val first = expected.indices.firstOrNull { expected[it] != actual[it] } ?: return
        val differences = expected.indices.count { expected[it] != actual[it] }
        fail("$label differs in $differences raw N32 bytes; first at pixel (${first / 4 % width}, ${first / 4 / width}), " +
            "channel ${first % 4}: expected ${expected[first].toUByte()}, actual ${actual[first].toUByte()}")
    }

    @Test
    fun eightThousandRealInputsReuseStableChunksAndMatchFreshFullFrames() {
        val stroke = InProgressStroke()
        InkMeshRenderer().use { renderer ->
            try {
                stroke.start(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff2060c0.toInt(), 4f))
                stroke.enqueueInputs(longInputs(0, 8192), MutableStrokeInputBatch())
                stroke.updateShape(8192 * 4L)
                assertEquals(8192, stroke.getRealInputCount())
                assertTrue(InkMeshes.rendering(stroke, 0).sumOf { it.vertexCount } >= 8192,
                    "The long-input fixture must exercise at least 8192 real mesh vertices")
                val transform = ImmutableAffineTransform(.5f, 0f, 0f, 0f, .25f, 0f)
                val initial = frame("8192 real inputs", renderer, stroke, transform, width = 512)
                assertTrue(initial.any { it != 0.toByte() }, "The long real mesh must produce visible pixels")
                val prepared = renderer.preparedVertexCount
                val built = renderer.preparedChunkCount
                val reused = renderer.reusedChunkCount
                stroke.enqueueInputs(longInputs(8192, 8200), MutableStrokeInputBatch())
                stroke.updateShape(8200 * 4L)
                val totalVertices = InkMeshes.rendering(stroke, 0).sumOf { it.vertexCount }
                frame("eight real inputs appended to a long stroke", renderer, stroke, transform, width = 512)
                assertTrue(renderer.reusedChunkCount > reused, "Appending a tail must retain verified stable chunks")
                assertTrue(renderer.preparedVertexCount - prepared in 1 until totalVertices.toLong(),
                    "Only changed or appended vertices should run the vertex math")
                assertTrue(renderer.preparedChunkCount > built, "The changed tail must be prepared")
                assertEquals(renderer.preparedChunkCount, renderer.shaderBuildCount,
                    "Each newly prepared texture-free chunk gets one shader; stable chunks retain their shader")
                val snapshots = renderer.meshSnapshotCount
                val unchangedPrepared = renderer.preparedVertexCount
                val unchangedChunks = renderer.preparedChunkCount
                val unchangedShaders = renderer.shaderBuildCount
                frame("unchanged long stroke", renderer, stroke, transform, width = 512)
                assertEquals(snapshots, renderer.meshSnapshotCount)
                assertEquals(unchangedPrepared, renderer.preparedVertexCount)
                assertEquals(unchangedChunks, renderer.preparedChunkCount)
                assertEquals(unchangedShaders, renderer.shaderBuildCount)
            } finally { stroke.clear() }
        }
    }

    @Test
    fun predictionReplacementShrinkAndExpiryMatchFreshRendering() {
        InkAuthoringSession(predictorFactory = null).use { session ->
            InkMeshRenderer().use { renderer ->
                val brush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xb02060c0.toInt(), 10f)
                fun sample(x: Float, y: Float, time: Long) = InkPointerSample(x, y, time, InputToolType.STYLUS, .7f)
                fun send(event: InkInputEvent) = session.handle(event, brush) { _, _ -> }
                send(InkInputEvent.Begin(sample(16f, 64f, 1000L)))
                send(InkInputEvent.Batch((1..70).map { sample(16f + it * 2f, 64f + 5f * sin(it * .2f), 1000L + it) },
                    predictedSamples = listOf(sample(190f, 64f, 1090L), sample(224f, 64f, 1100L))))
                session.advance(1070L)
                val stroke = session.liveStrokes.single().stroke
                val horizontal = frame("long horizontal prediction", renderer, stroke)
                assertEquals(70 + 1, stroke.getRealInputCount())
                send(InkInputEvent.Predict(listOf(sample(156f, 104f, 1090L), sample(156f, 148f, 1100L))))
                session.advance(1071L)
                val replacement = frame("prediction changes direction", renderer, stroke)
                assertFalse(horizontal.contentEquals(replacement), "A prediction replacement must change the visible tail")
                send(InkInputEvent.Predict(listOf(sample(156f, 90f, 1080L))))
                session.advance(1072L)
                val shortened = frame("prediction shrinks", renderer, stroke)
                assertFalse(replacement.contentEquals(shortened))
                session.advance(1080L)
                assertEquals(0, stroke.getPredictedInputCount())
                val expired = frame("prediction expiry without a new real input", renderer, stroke)
                assertFalse(shortened.contentEquals(expired))
                assertEquals(71, stroke.getRealInputCount())
                send(InkInputEvent.Cancel)
                renderer.releaseLiveStroke(stroke)
                assertEquals(0, renderer.cachedLiveShapeCount)
                assertEquals(0L, renderer.cachedLiveGeometryBytes)
            }
        }
    }

    @Test
    fun multipleShapeUpdatesBetweenDrawsUseTheLastRenderedSnapshot() {
        val stroke = InProgressStroke()
        InkMeshRenderer().use { renderer ->
            try {
                stroke.start(brush())
                stroke.enqueueInputs(inputs(0, 64), MutableStrokeInputBatch())
                stroke.updateShape(128L)
                frame("last drawn prefix", renderer, stroke)
                for (end in listOf(72, 80, 88)) {
                    stroke.enqueueInputs(inputs(end - 8, end), MutableStrokeInputBatch())
                    stroke.updateShape(end * 2L)
                    // Other consumers can clear engine damage between this renderer's frames.
                    stroke.resetUpdatedRegion()
                }
                val reused = renderer.reusedChunkCount
                frame("three updates skipped before drawing", renderer, stroke)
                assertTrue(renderer.reusedChunkCount > reused)
                assertEquals(88, stroke.getRealInputCount())
            } finally { stroke.clear() }
        }
    }

    @Test
    fun timeChangingPrefixAttributesInvalidateSameSizedGeometry() {
        val timed = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(
            TargetNode.Target.OPACITY_MULTIPLIER, 1f, .1f,
            SourceNode(SourceNode.Source.TIME_SINCE_INPUT_IN_SECONDS, 0f, 1f),
        )))))
        val stroke = InProgressStroke()
        InkMeshRenderer().use { renderer ->
            try {
                stroke.start(brush(timed))
                stroke.enqueueInputs(inputs(0, 64), MutableStrokeInputBatch())
                stroke.updateShape(128L)
                val beforeMesh = InkMeshes.rendering(stroke, 0)
                val before = frame("timed prefix before fade", renderer, stroke)
                val prepared = renderer.preparedVertexCount
                stroke.updateShape(600L)
                val afterMesh = InkMeshes.rendering(stroke, 0)
                assertEquals(beforeMesh.map { it.vertices.size to it.triangles.size },
                    afterMesh.map { it.vertices.size to it.triangles.size }, "The mutation must preserve mesh counts")
                assertTrue(beforeMesh.first().vertices[2].toRawBits() != afterMesh.first().vertices[2].toRawBits(),
                    "A referenced prefix opacity attribute must change")
                val after = frame("same-count prefix opacity mutation", renderer, stroke)
                assertFalse(before.contentEquals(after))
                assertTrue(renderer.preparedVertexCount > prepared)
            } finally { stroke.clear() }
        }
    }

    @Test
    fun recycledEngineBrushAndCoatChangesDoNotRetainPreviousChunks() {
        val families = listOf(
            BrushFamily(),
            BrushFamily(listOf(
                BrushCoat(BrushTip(scaleX = 1.3f, scaleY = .6f), listOf(BrushPaint(selfOverlap = SelfOverlap.ACCUMULATE))),
                BrushCoat(BrushTip(scaleX = .4f, scaleY = 1.6f), listOf(BrushPaint())),
            )),
            BrushFamily(paint = BrushPaint(selfOverlap = SelfOverlap.DISCARD)),
            BrushFamily(),
        )
        val stroke = InProgressStroke()
        InkMeshRenderer().use { renderer ->
            try {
                families.forEachIndexed { index, family ->
                    // Clear and restart before a draw intentionally leaves the old renderer entry in place.
                    stroke.clear()
                    stroke.start(brush(family, 0x804060a0.toInt(), 6f + index * 2f))
                    stroke.enqueueInputs(inputs(0, 50, 30f + index * 40f), MutableStrokeInputBatch())
                    stroke.updateShape(100L)
                    frame("recycled engine brush $index with ${family.coats.size} coats", renderer, stroke)
                    assertEquals(1, renderer.cachedLiveShapeCount)
                }
                stroke.clear()
                val empty = frame("clear without restart retires all live resources", renderer, stroke)
                assertTrue(empty.all { it == 0.toByte() })
                assertEquals(0, renderer.cachedLiveShapeCount)
                assertEquals(0L, renderer.cachedLiveGeometryBytes)
            } finally { stroke.clear() }
        }
    }

    @Test
    fun translationReusesPreparationWhileLinearTransformAndColorRebuildIt() {
        val stroke = InProgressStroke()
        InkMeshRenderer().use { renderer ->
            try {
                stroke.start(brush())
                stroke.enqueueInputs(inputs(0, 64), MutableStrokeInputBatch())
                stroke.updateShape(128L)
                frame("identity", renderer, stroke)
                val firstPrepared = renderer.preparedVertexCount
                val firstChunks = renderer.preparedChunkCount
                val firstShaders = renderer.shaderBuildCount
                frame("fractional translation", renderer, stroke,
                    ImmutableAffineTransform(1f, 0f, 10.25f, 0f, 1f, 8.75f))
                assertEquals(firstPrepared, renderer.preparedVertexCount)
                assertEquals(firstChunks, renderer.preparedChunkCount)
                assertEquals(firstShaders, renderer.shaderBuildCount)
                val shear = ImmutableAffineTransform(1.25f, .17f, 3.25f, -.12f, .8f, 22.5f)
                frame("scale and shear rebuild derivative AA", renderer, stroke, shear)
                assertTrue(renderer.preparedVertexCount > firstPrepared)
                val scaledPrepared = renderer.preparedVertexCount
                frame("existing canvas 2x scale", renderer, stroke, shear, width = 512, height = 384, canvasScale = 2f)
                assertTrue(renderer.preparedVertexCount > scaledPrepared)
                val beforeColor = renderer.preparedVertexCount
                frame("ARGB override changes premultiplied varyings", renderer, stroke, color = 0x7030b090)
                assertTrue(renderer.preparedVertexCount > beforeColor)
                val overridePrepared = renderer.preparedVertexCount
                frame("same ARGB override", renderer, stroke, color = 0x7030b090)
                assertEquals(overridePrepared, renderer.preparedVertexCount)
                frame("restore brush color", renderer, stroke)
                assertTrue(renderer.preparedVertexCount > overridePrepared)
            } finally { stroke.clear() }
        }
    }

    @Test
    fun stampingAtlasClockAndAnimatedTailMatchFreshPreparation() {
        atlas().use { image ->
            val layer = BrushPaint.StampingTexture("atlas", animationFrames = 4, animationRows = 2,
                animationColumns = 2, animationDurationMillis = 1000L,
                animationRepeatMode = BrushPaint.TextureLayer.AnimationRepeatMode.REVERSE)
            val offset = BrushBehavior(TargetNode(TargetNode.Target.TEXTURE_ANIMATION_PROGRESS_OFFSET, .1f, .2f,
                SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)))
            val family = BrushFamily(BrushTip(particleGapDistanceScale = 1.5f, behaviors = listOf(offset)), BrushPaint(listOf(layer)))
            val stroke = InProgressStroke()
            InkMeshRenderer(InkTextureStore { image }).use { renderer ->
                try {
                    stroke.start(brush(family, 0xffffffff.toInt(), 6f))
                    stroke.enqueueInputs(inputs(0, 80), MutableStrokeInputBatch())
                    stroke.updateShape(160L)
                    var last: ByteArray? = null
                    for (time in listOf(0L, 350L, 700L, 1800L, 2000L)) {
                        renderer.animationTimeMillis = time
                        val before = renderer.preparedVertexCount
                        val next = frame("atlas clock $time", renderer, stroke)
                        if (last != null) assertFalse(last.contentEquals(next), "Clock changes must alter atlas UVs")
                        assertTrue(renderer.preparedVertexCount > before)
                        last = next
                    }
                    val reused = renderer.reusedChunkCount
                    stroke.enqueueInputs(inputs(80, 88), MutableStrokeInputBatch())
                    stroke.updateShape(176L)
                    frame("new particles with unchanged atlas clock", renderer, stroke)
                    assertTrue(renderer.reusedChunkCount > reused)
                    assertFalse(image.isClosed, "The renderer only borrows the atlas image")
                } finally { stroke.clear() }
            }
            assertFalse(image.isClosed)
        }
    }

    private fun atlas(): Image = Surface.makeRasterN32Premul(2, 2).use { surface ->
        listOf(0xffff2040.toInt(), 0xff30e060.toInt(), 0xff4070ff.toInt(), 0xfff0c030.toInt()).forEachIndexed { i, color ->
            org.jetbrains.skia.Paint().use { paint ->
                paint.color = color
                surface.canvas.drawRect(org.jetbrains.skia.Rect.makeXYWH((i % 2).toFloat(), (i / 2).toFloat(), 1f, 1f), paint)
            }
        }
        surface.makeImageSnapshot()
    }

    private val linear = MeshLinearTransform(1.3f, .17f, -.11f, .8f)
    private val meshColor = MeshColor(.1f, .2f, .4f, .6f)

    private fun syntheticMesh(vertexCount: Int = 96, triangleCount: Int = 35, highIndex: Boolean = false): StrokeMesh {
        val vertices = FloatArray(vertexCount * StrokeMesh.VERTEX_STRIDE)
        repeat(vertexCount) { vertex ->
            val i = vertex * StrokeMesh.VERTEX_STRIDE
            vertices[i] = vertex % 100 * .7f
            vertices[i + 1] = vertex / 100 * 1.1f
            vertices[i + 2] = vertex % 5 * -.03f
            vertices[i + 3] = vertex % 9 * .01f
            vertices[i + 4] = vertex % 7 * .02f
            vertices[i + 5] = vertex % 3 * .005f
            vertices[i + 6] = .7f; vertices[i + 7] = .3f; vertices[i + 8] = if (vertex % 2 == 0) -1.5f else 1.5f
            vertices[i + 9] = .2f; vertices[i + 10] = .9f; vertices[i + 11] = if (vertex % 3 == 0) -1.8f else 1.8f
            vertices[i + 12] = vertex % 2 * .6f; vertices[i + 13] = vertex % 3 * .3f; vertices[i + 14] = vertex % 4 * .1f
        }
        val offset = if (highIndex) vertexCount - 96 else 0
        val triangles = IntArray(triangleCount * 3) { index -> offset + (index % 96) }
        return StrokeMesh(vertices, triangles, 511)
    }

    private fun assertFullPreparation(mesh: StrokeMesh, actual: PreparedMesh, stamp: StampAnimation? = null,
        transform: MeshLinearTransform = linear, color: MeshColor = meshColor) {
        val expected = prepareMesh(mesh, transform, color, stamp)
        assertEquals(expected.size, actual.chunks.size)
        expected.zip(actual.chunks).forEachIndexed { index, (full, incremental) ->
            exactFloats(full.vertices.positions, incremental.vertices.positions, "chunk $index positions")
            exactFloats(full.vertices.textureCoordinates, incremental.vertices.textureCoordinates, "chunk $index barycentric UVs")
            assertTrue(full.vertices.colors.contentEquals(incremental.vertices.colors), "chunk $index colors")
            assertTrue(full.vertices.indices.contentEquals(incremental.vertices.indices), "chunk $index indices")
            exactFloats(full.varyings, incremental.varyings, "chunk $index varyings")
        }
    }

    private fun exactFloats(expected: FloatArray, actual: FloatArray, label: String) {
        assertEquals(expected.size, actual.size, label)
        expected.indices.forEach { i ->
            assertEquals(expected[i].toRawBits(), actual[i].toRawBits(), "$label float $i")
        }
    }

    @Test
    fun sameCountVertexMutationRebuildsOnlyChunksReferencingTheChangedVertex() {
        val before = syntheticMesh()
        var prepared = prepareMeshIncrementally(before, linear, meshColor, null)
        try {
            assertFullPreparation(before, prepared)
            val oldChunks = prepared.chunks.toList()
            val afterVertices = before.vertices.clone().apply { this[2] = -.7f }
            val after = StrokeMesh(afterVertices, before.triangles.clone(), before.attributeMask)
            prepared = prepareMeshIncrementally(after, linear, meshColor, null, prepared)
            assertFullPreparation(after, prepared)
            assertEquals(1, prepared.preparedVertexCount)
            // Vertex zero occurs in triangles 0 and 32, including the last partial batch.
            assertNotSame(oldChunks[0], prepared.chunks[0])
            assertSame(oldChunks[1], prepared.chunks[1])
            assertNotSame(oldChunks[2], prepared.chunks[2])
            assertEquals(1, prepared.reusedChunkCount)
            assertEquals(2, prepared.preparedChunkCount)
        } finally { prepared.close() }
    }

    @Test
    fun everyCanonicalVertexComponentAndSignedZeroAreCheckedBeforeReuse() {
        val stamp = StampAnimation(.15f, 4, 2, 2)
        for (component in 0 until StrokeMesh.VERTEX_STRIDE) {
            val mesh = syntheticMesh()
            var prepared = prepareMeshIncrementally(mesh, linear, meshColor, stamp)
            try {
                val previous = prepared.chunks.toList()
                val mutated = StrokeMesh(mesh.vertices.clone().apply { this[component] += .17f },
                    mesh.triangles.clone(), mesh.attributeMask)
                prepared = prepareMeshIncrementally(mutated, linear, meshColor, stamp, prepared)
                assertFullPreparation(mutated, prepared, stamp)
                assertEquals(1, prepared.preparedVertexCount, "component $component")
                assertNotSame(previous[0], prepared.chunks[0], "component $component")
                assertSame(previous[1], prepared.chunks[1], "component $component")
                assertNotSame(previous[2], prepared.chunks[2], "component $component")
            } finally { prepared.close() }
        }
        val mesh = syntheticMesh()
        var prepared = prepareMeshIncrementally(mesh, linear, meshColor, stamp)
        try {
            assertEquals(0f.toRawBits(), mesh.vertices[3].toRawBits())
            val signedZero = StrokeMesh(mesh.vertices.clone().apply { this[3] = -0f }, mesh.triangles.clone(), mesh.attributeMask)
            prepared = prepareMeshIncrementally(signedZero, linear, meshColor, stamp, prepared)
            assertEquals(1, prepared.preparedVertexCount, "Raw bit comparison must distinguish signed zero")
            assertFullPreparation(signedZero, prepared, stamp)
        } finally { prepared.close() }
    }

    @Test
    fun directPreparationTransformColorAndStampKeysInvalidateEveryVertexAndChunk() {
        val mesh = syntheticMesh()
        var prepared = prepareMeshIncrementally(mesh, linear, meshColor, null)
        val reflected = MeshLinearTransform(-.7f, .3f, .2f, 1.6f)
        val recolored = MeshColor(.4f, .1f, .15f, .3f)
        data class Parameters(val transform: MeshLinearTransform, val color: MeshColor, val stamp: StampAnimation?)
        val parameters = listOf(
            Parameters(reflected, meshColor, null),
            Parameters(reflected, recolored, null),
            Parameters(reflected, recolored, StampAnimation(.1f, 4, 2, 2)),
            Parameters(reflected, recolored, StampAnimation(.6f, 4, 2, 2)),
            Parameters(reflected, recolored, StampAnimation(.6f, 4, 1, 4)),
            Parameters(reflected, recolored, null),
        )
        try {
            parameters.forEach { key ->
                val oldChunks = prepared.chunks.toList()
                prepared = prepareMeshIncrementally(mesh, key.transform, key.color, key.stamp, prepared)
                assertFullPreparation(mesh, prepared, key.stamp, key.transform, key.color)
                assertEquals(mesh.vertexCount, prepared.preparedVertexCount)
                assertEquals(0, prepared.reusedChunkCount)
                oldChunks.zip(prepared.chunks).forEach { (old, current) -> assertNotSame(old, current) }
            }
        } finally { prepared.close() }
    }

    @Test
    fun changedAndRemovedChunkShadersCloseWhileReusedChunksKeepTheirShader() {
        val mesh = syntheticMesh()
        var prepared = prepareMeshIncrementally(mesh, linear, meshColor, null)
        val shaders = prepared.chunks.map { org.jetbrains.skia.Shader.makeColor(0xffffffff.toInt()) }
        prepared.chunks.zip(shaders).forEach { (chunk, shader) -> chunk.untexturedShader = shader }
        try {
            val changed = StrokeMesh(mesh.vertices.clone().apply { this[2] = -.5f }, mesh.triangles.clone(), mesh.attributeMask)
            prepared = prepareMeshIncrementally(changed, linear, meshColor, null, prepared)
            assertTrue(shaders[0].isClosed)
            assertFalse(shaders[1].isClosed)
            assertTrue(shaders[2].isClosed)
            assertSame(shaders[1], prepared.chunks[1].untexturedShader)
            val shortened = StrokeMesh(changed.vertices.copyOf(48 * StrokeMesh.VERTEX_STRIDE), changed.triangles.copyOf(16 * 3), changed.attributeMask)
            prepared = prepareMeshIncrementally(shortened, linear, meshColor, null, prepared)
            assertFullPreparation(shortened, prepared)
            assertTrue(shaders[1].isClosed, "Removing a batch must retire its native shader")
            prepared = prepareMeshIncrementally(StrokeMesh(FloatArray(0), IntArray(0), mesh.attributeMask),
                linear, meshColor, null, prepared)
            assertTrue(prepared.chunks.isEmpty())
            assertEquals(0L, prepared.scratchBytes)
        } finally {
            prepared.close()
            shaders.forEach { if (!it.isClosed) it.close() }
        }
    }

    @Test
    fun triangleIndexMutationAndPartialChunkShrinkAppendPreserveFullPreparation() {
        var mesh = syntheticMesh()
        var prepared = prepareMeshIncrementally(mesh, linear, meshColor, null)
        try {
            val oldChunks = prepared.chunks.toList()
            mesh = StrokeMesh(mesh.vertices.clone(), mesh.triangles.clone().apply { this[3] = 92 }, mesh.attributeMask)
            prepared = prepareMeshIncrementally(mesh, linear, meshColor, null, prepared)
            assertFullPreparation(mesh, prepared)
            assertEquals(0, prepared.preparedVertexCount)
            assertNotSame(oldChunks[0], prepared.chunks[0])
            assertSame(oldChunks[1], prepared.chunks[1])
            assertSame(oldChunks[2], prepared.chunks[2])
            assertEquals(2, prepared.reusedChunkCount)
            val shrinkChunks = prepared.chunks.toList()
            mesh = StrokeMesh(mesh.vertices.clone(), mesh.triangles.copyOf(17 * 3), mesh.attributeMask)
            prepared = prepareMeshIncrementally(mesh, linear, meshColor, null, prepared)
            assertFullPreparation(mesh, prepared)
            assertSame(shrinkChunks[0], prepared.chunks[0])
            assertNotSame(shrinkChunks[1], prepared.chunks[1], "A shorter batch must discard its old triangles")
            assertEquals(2, prepared.chunks.size)
            val appendChunks = prepared.chunks.toList()
            mesh = StrokeMesh(mesh.vertices.clone(), syntheticMesh(triangleCount = 49).triangles, mesh.attributeMask)
            prepared = prepareMeshIncrementally(mesh, linear, meshColor, null, prepared)
            assertFullPreparation(mesh, prepared)
            assertNotSame(appendChunks[0], prepared.chunks[0], "The append fixture also restores the changed prefix index")
            assertNotSame(appendChunks[1], prepared.chunks[1], "The old partial chunk must be expanded")
            assertEquals(4, prepared.chunks.size)
        } finally { prepared.close() }
    }

    @Test
    fun attributeMaskAndUnsignedHighIndicesMatchTheFullReference() {
        var mesh = syntheticMesh(vertexCount = 65_536, highIndex = true)
        assertTrue(mesh.triangles.max() > 32_767, "Exercise unsigned 16-bit indices whose signed Short would be negative")
        assertTrue(65_535 in mesh.triangles, "The maximum unsigned 16-bit index must remain valid")
        val stamp = StampAnimation(.35f, 4, 2, 2)
        var prepared = prepareMeshIncrementally(mesh, linear, meshColor, stamp)
        try {
            assertFullPreparation(mesh, prepared, stamp)
            val oldChunks = prepared.chunks.toList()
            val changed = mesh.vertices.clone().apply { this[(65_535 * StrokeMesh.VERTEX_STRIDE) + 14] = .4f }
            mesh = StrokeMesh(changed, mesh.triangles.clone(), mesh.attributeMask)
            prepared = prepareMeshIncrementally(mesh, linear, meshColor, stamp, prepared)
            assertFullPreparation(mesh, prepared, stamp)
            assertSame(oldChunks[0], prepared.chunks[0])
            assertNotSame(oldChunks[1], prepared.chunks[1])
            assertSame(oldChunks[2], prepared.chunks[2])
            val maskChunks = prepared.chunks.toList()
            mesh = StrokeMesh(mesh.vertices.clone(), mesh.triangles.clone(), mesh.attributeMask and (1 shl 2).inv())
            prepared = prepareMeshIncrementally(mesh, linear, meshColor, stamp, prepared)
            assertFullPreparation(mesh, prepared, stamp)
            assertEquals(mesh.vertexCount, prepared.preparedVertexCount, "Attribute-mask changes affect all vertex math")
            prepared.chunks.zip(maskChunks).forEach { (current, previous) -> assertNotSame(previous, current) }
        } finally { prepared.close() }
    }

    @Test
    fun realNativePartitionsCrossUnsignedLimitAndRollbackPredictionsExactly() {
        val stroke = InProgressStroke()
        var prepared = emptyList<PreparedMesh>()
        fun spiral(start: Int, count: Int) = MutableStrokeInputBatch().apply {
            for (i in start until start + count) {
                val radius = 100f * sqrt(i.toFloat())
                add(InputToolType.MOUSE, radius * cos(i.toFloat()), radius * sin(i.toFloat()), i.toLong())
            }
        }
        fun prepareCurrent(label: String): List<StrokeMesh> {
            val meshes = InkMeshes.rendering(stroke, 0)
            val previous = prepared
            prepared = meshes.mapIndexed { partition, mesh ->
                prepareMeshIncrementally(mesh, linear, meshColor, null, previous.getOrNull(partition)).also {
                    assertFullPreparation(mesh, it)
                }
            }
            previous.drop(meshes.size).forEach { it.close() }
            assertEquals(stroke.getMeshPartitionCount(0), prepared.size, label)
            return meshes
        }
        try {
            stroke.start(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff2060c0.toInt(), 8f))
            var count = 0
            while ((count == 0 || stroke.getVertexCount(0, 0) < 60_000) && count < 16_384) {
                stroke.enqueueInputs(spiral(count, 128), MutableStrokeInputBatch())
                count += 128
                stroke.updateShape(count.toLong())
            }
            assertEquals(1, stroke.getMeshPartitionCount(0), "The real prefix should stop before the partition boundary")
            assertTrue(stroke.getVertexCount(0, 0) >= 60_000)
            val original = prepareCurrent("real prefix")
            assertTrue(original.single().triangles.any { it > 32_767 }, "The real partition must contain unsigned high indices")
            val stableFirstChunk = prepared.single().chunks.first()
            stroke.enqueueInputs(MutableStrokeInputBatch(), spiral(count, 2048))
            stroke.updateShape(count.toLong())
            assertEquals(2048, stroke.getPredictedInputCount())
            val predicted = prepareCurrent("prediction creates another native partition")
            assertTrue(predicted.size > 1)
            assertTrue(predicted.sumOf { it.vertexCount } > 65_536)
            assertSame(stableFirstChunk, prepared.first().chunks.first(), "Stable first partition chunks survive partition growth")
            assertTrue(prepared.first().reusedChunkCount > 0)
            stroke.enqueueInputs(MutableStrokeInputBatch(), MutableStrokeInputBatch())
            stroke.updateShape(count + 1L)
            assertEquals(0, stroke.getPredictedInputCount())
            prepareCurrent("prediction rollback removes its extra native partition")
            assertEquals(1, prepared.size)
            assertSame(stableFirstChunk, prepared.single().chunks.first(), "Stable prefix survives prediction rollback")
            assertTrue(prepared.single().reusedChunkCount > 0)
        } finally {
            prepared.forEach { it.close() }
            stroke.clear()
        }
    }
}
