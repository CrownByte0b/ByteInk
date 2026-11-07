@file:OptIn(androidx.ink.brush.ExperimentalInkAnimationApi::class, androidx.ink.brush.ExperimentalInkCustomBrushApi::class)

package com.vivenotes.byteink.compose

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.*
import androidx.ink.brush.behavior.SourceNode
import androidx.ink.brush.behavior.TargetNode
import androidx.ink.strokes.*
import androidx.ink.storage.encode
import com.vivenotes.byteink.kit.ViveBrushes
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.zip.GZIPInputStream
import org.jetbrains.skia.*

/** Synthetic fixtures only; never reads an application notebook. Reproduce with the Gradle task. */
internal object WasmRenderingFixtureExporter {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1)
        val cases = mutableListOf<String>()
        fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
        fun texture(colors: List<Int>, width: Int): Image = Surface.makeRasterN32Premul(width, colors.size / width).use { surface ->
            colors.forEachIndexed { i, color -> Paint().use { paint ->
                paint.color = color; surface.canvas.drawRect(Rect.makeXYWH((i % width).toFloat(), (i / width).toFloat(), 1f, 1f), paint)
            } }; surface.makeImageSnapshot()
        }
        val images = mapOf("stripes" to texture(listOf(0xffff0000.toInt(), 0xff00ff00.toInt()), 2),
            "atlas" to texture(listOf(0xffff0000.toInt(), 0xff00ff00.toInt(), 0xff0000ff.toInt(), -1), 2),
            "color" to texture(listOf(0x9060d040.toInt()), 1), "second" to texture(listOf(0xb040d090.toInt()), 1))
        val real = listOf(floatArrayOf(16f, 60f, 0f, .1f), floatArrayOf(112f, 60f, 100f, .9f))
        fun inputs(values: List<FloatArray>) = MutableStrokeInputBatch().apply {
            values.forEach { add(InputToolType.STYLUS, it[0], it[1], it[2].toLong(), pressure = it[3]) }
        }
        fun numbers(values: List<FloatArray>): String = values.joinToString(prefix = "[", postfix = "]") { it.joinToString(prefix = "[", postfix = "]") }
        fun emit(name: String, family: BrushFamily, color: Int = 0xffffffff.toInt(), size: Float = 12f,
            points: List<FloatArray> = real, predicted: List<FloatArray>? = null, update: Long = 100L, animation: Long = 0L,
            serializedFamily: BrushFamily = family, exportOffsets: Boolean = false) {
            val brush = Brush.createWithColorIntArgb(family, color, size, .25f)
            val encoded = ByteArrayOutputStream().also { serializedFamily.encode(it) }.toByteArray()
            val proto = GZIPInputStream(encoded.inputStream()).use { it.readBytes() }
            val stroke = if (predicted == null) Stroke(brush, inputs(points)) else null
            val live = if (predicted != null) InProgressStroke().apply {
                start(brush); enqueueInputs(inputs(points), inputs(predicted)); updateShape(update)
            } else null
            // Desktop native geometry is also rendered on each browser's WebGL backend. This
            // separates engine parity from the backend's own software-vs-GPU raster variation.
            val geometry = family.coats.indices.joinToString(prefix = "[", postfix = "]") { coat ->
                val meshes = if (stroke != null) com.vivenotes.byteink.core.InkMeshes.rendering(stroke.shape, coat)
                    else com.vivenotes.byteink.core.InkMeshes.rendering(live!!, coat)
                val outlines = if (stroke != null) com.vivenotes.byteink.core.InkMeshes.outlines(stroke.shape, coat)
                    else com.vivenotes.byteink.core.InkMeshes.outlines(live!!, coat)
                val meshJson = meshes.joinToString(prefix = "[", postfix = "]") { mesh ->
                    """{"vertices":${mesh.vertices.joinToString(prefix="[",postfix="]")},"triangles":${mesh.triangles.joinToString(prefix="[",postfix="]")}}"""
                }
                """{"meshes":$meshJson,"outlines":${numbers(outlines)}}"""
            }
            val png = try {
                InkMeshRenderer(InkTextureStore { images[it] }, textureCacheCapacity = 1).use { renderer ->
                    renderer.animationTimeMillis = animation
                    Surface.makeRasterN32Premul(128, 128).use { surface ->
                        surface.canvas.clear(0)
                        if (stroke != null) renderer.draw(surface.canvas.asComposeCanvas(), stroke)
                        else renderer.draw(surface.canvas.asComposeCanvas(), live!!)
                        surface.makeImageSnapshot().use { image -> image.encodeToData(EncodedImageFormat.PNG)!!.use { it.bytes } }
                    }
                }
            } finally { live?.clear() }
            val offsets = if (exportOffsets) com.vivenotes.byteink.core.InkMeshes.rendering(stroke!!.shape, 0).map { mesh ->
                FloatArray(mesh.vertexCount) { mesh.vertices[it * 15 + 14] }
            }.let(::numbers) else "null"
            val atlas = family.coats.first().paintPreferences.first().textureLayers.firstOrNull() as? BrushPaint.StampingTexture
            val atlasJson = atlas?.let { """{"frames":${it.animationFrames},"rows":${it.animationRows},"columns":${it.animationColumns},"duration":${it.animationDurationMillis},"reverse":${it.animationRepeatMode == BrushPaint.TextureLayer.AnimationRepeatMode.REVERSE}}""" } ?: "null"
            cases += """{"name":"$name","family":"${b64(proto)}","color":$color,"size":$size,"inputs":${numbers(points)},"predicted":${predicted?.let(::numbers) ?: "null"},"update":$update,"animation":$animation,"atlas":$atlasJson,"offsets":$offsets,"geometry":$geometry,"png":"${b64(png)}"}"""
        }
        try {
            for (id in listOf(ViveBrushes.MARKER, ViveBrushes.PRESSURE_PEN, ViveBrushes.DASHED_LINE, ViveBrushes.HIGHLIGHTER) + (0..5).map(ViveBrushes::calligraphy))
                emit("catalog-$id", ViveBrushes.family(id, 0), 0x805070c0.toInt())
            for ((target, low, high) in listOf(Triple(TargetNode.Target.OPACITY_MULTIPLIER, .1f, 1f),
                Triple(TargetNode.Target.HUE_OFFSET_IN_RADIANS, 0f, 1.8f), Triple(TargetNode.Target.SATURATION_MULTIPLIER, .2f, 1.8f),
                Triple(TargetNode.Target.LUMINOSITY_OFFSET, -.1f, .3f))) {
                emit("vertex-$target", BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(target, low, high,
                    SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f)))))), 0xff508090.toInt())
            }
            val modes = listOf(BrushPaint.TextureLayer.BlendMode.MODULATE, BrushPaint.TextureLayer.BlendMode.DST_IN,
                BrushPaint.TextureLayer.BlendMode.DST_OUT, BrushPaint.TextureLayer.BlendMode.SRC_ATOP, BrushPaint.TextureLayer.BlendMode.SRC_IN,
                BrushPaint.TextureLayer.BlendMode.SRC_OVER, BrushPaint.TextureLayer.BlendMode.DST_OVER, BrushPaint.TextureLayer.BlendMode.SRC,
                BrushPaint.TextureLayer.BlendMode.DST, BrushPaint.TextureLayer.BlendMode.SRC_OUT, BrushPaint.TextureLayer.BlendMode.DST_ATOP, BrushPaint.TextureLayer.BlendMode.XOR)
            for ((i, mode) in modes.withIndex()) for (overlap in listOf(SelfOverlap.ANY, SelfOverlap.DISCARD))
                emit("blend-$i-$overlap", BrushFamily(paint = BrushPaint(listOf(BrushPaint.TilingTexture("color", 20f, 20f, blendMode = mode)), selfOverlap = overlap)), 0x806040c0.toInt())
            val base = BrushPaint.TilingTexture("stripes", 20f, 20f)
            val layers = listOf(base, base.copy(offsetX = .5f), base.copy(origin = BrushPaint.TilingTexture.Origin.FIRST_STROKE_INPUT),
                base.copy(origin = BrushPaint.TilingTexture.Origin.LAST_STROKE_INPUT), base.copy(wrapX = BrushPaint.TextureLayer.Wrap.CLAMP),
                base.copy(wrapX = BrushPaint.TextureLayer.Wrap.MIRROR), base.copy(rotationDegrees = 45f),
                base.copy(sizeUnit = BrushPaint.TextureLayer.SizeUnit.BRUSH_SIZE, sizeX = 2f, sizeY = 2f))
            layers.forEachIndexed { i, layer -> emit("tiling-$i", BrushFamily(paint = BrushPaint(listOf(layer)))) }
            for (overlap in listOf(SelfOverlap.ANY, SelfOverlap.DISCARD)) emit("layers-$overlap", BrushFamily(paint = BrushPaint(listOf(
                BrushPaint.TilingTexture("color", 20f, 20f, blendMode = BrushPaint.TextureLayer.BlendMode.SRC_ATOP),
                BrushPaint.TilingTexture("second", 20f, 20f, blendMode = BrushPaint.TextureLayer.BlendMode.DST_OVER)), selfOverlap = overlap)), 0x805070c0.toInt())
            val atlas = BrushPaint.StampingTexture("atlas", animationFrames = 4, animationRows = 2, animationColumns = 2,
                animationDurationMillis = 1000L, animationRepeatMode = BrushPaint.TextureLayer.AnimationRepeatMode.REVERSE)
            for (time in listOf(0L, 300L, 600L, 900L, 1500L, 1800L)) emit("atlas-$time", BrushFamily(BrushTip(particleGapDistanceScale = 2f), BrushPaint(listOf(atlas))),
                size = 20f, points = listOf(floatArrayOf(20f,60f,0f,.1f),floatArrayOf(100f,60f,100f,.9f)), animation = time)
            emit("atlas-offset", BrushFamily(BrushTip(particleGapDistanceScale = 2f, behaviors = listOf(BrushBehavior(TargetNode(
                TargetNode.Target.TEXTURE_ANIMATION_PROGRESS_OFFSET, .6f, .7f, SourceNode(SourceNode.Source.NORMALIZED_PRESSURE, 0f, 1f))))),
                BrushPaint(listOf(atlas.copy(animationDurationMillis = 0L)))), size = 20f,
                serializedFamily = BrushFamily(BrushTip(particleGapDistanceScale = 2f), BrushPaint(listOf(atlas.copy(animationDurationMillis = 0L)))), exportOffsets = true)
            val crossed = listOf(floatArrayOf(20f,20f,0f,.1f), floatArrayOf(100f,100f,100f,.4f), floatArrayOf(20f,100f,200f,.7f),floatArrayOf(100f,20f,300f,.9f))
            for (overlap in listOf(SelfOverlap.ANY, SelfOverlap.ACCUMULATE, SelfOverlap.DISCARD)) emit("overlap-$overlap",
                BrushFamily(paint = BrushPaint(selfOverlap = overlap)), 0x80ff0000.toInt(), points = crossed)
            emit("paint-fallback", BrushFamily(listOf(BrushCoat(BrushTip(), listOf(BrushPaint(listOf(BrushPaint.TilingTexture("missing", 20f,20f))),
                BrushPaint(colorFunctions = listOf(BrushPaint.ColorFunction.ReplaceColor.withColorIntArgb(0xffff0000.toInt()))))))))
            emit("multiple-coats", BrushFamily(listOf(BrushCoat(BrushTip()), BrushCoat(BrushTip(scaleX = .5f, scaleY = .5f),
                BrushPaint(colorFunctions = listOf(BrushPaint.ColorFunction.ReplaceColor.withColorIntArgb(0xff00ff00.toInt())))))), 0xffff0000.toInt())
            val prediction = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(TargetNode.Target.OPACITY_MULTIPLIER, 1f, 0f,
                SourceNode(SourceNode.Source.PREDICTED_DISTANCE_TRAVELED_IN_MULTIPLES_OF_BRUSH_SIZE, 0f, 6f))))))
            emit("prediction", prediction, 0xff508090.toInt(), points = listOf(floatArrayOf(20f,60f,0f,.2f),floatArrayOf(40f,60f,100f,.6f)),
                predicted = listOf(floatArrayOf(112f,60f,200f,1f)))
            val timed = BrushFamily(BrushTip(behaviors = listOf(BrushBehavior(TargetNode(TargetNode.Target.OPACITY_MULTIPLIER,1f,0f,
                SourceNode(SourceNode.Source.TIME_SINCE_INPUT_IN_SECONDS,0f,1f))))))
            for (time in listOf(0L,500L)) emit("timed-$time", timed, size = 20f, points = listOf(floatArrayOf(60f,60f,0f,.5f)), predicted = emptyList(), update = time)
            val textures = images.entries.joinToString { (name,image) -> image.encodeToData(EncodedImageFormat.PNG)!!.use { "\"$name\":\"${b64(it.bytes)}\"" } }
            val output = File(args[0]); output.parentFile.mkdirs()
            output.writeText("""{"engine":"96e50239e1c8955f7e222301661b595847d7de83","renderer":"desktop-software","textures":{$textures},"cases":[${cases.joinToString(",\n")}]}""" + "\n")
            println("Exported ${cases.size} synthetic renderer cases to $output")
        } finally { images.values.forEach { it.close() } }
    }
}
