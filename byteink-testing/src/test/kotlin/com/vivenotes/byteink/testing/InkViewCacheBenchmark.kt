package com.vivenotes.byteink.testing

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneRasterCache
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.vive.ViveBrushes
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Surface

/** Controlled one-view versus byte-budgeted return-to-view cache; excludes first-visit timings. */
internal object InkViewCacheBenchmark {
    @JvmStatic fun main(args: Array<String>) {
        val inputs = MutableStrokeInputBatch().apply {
            repeat(9) { i -> add(InputToolType.MOUSE, i * .8f, (i % 3) * .6f, i * 8L) }
        }.toImmutable()
        val brush = ViveBrushes.highlighter(0x80ff0020.toInt(), 2f)
        val scene = InkScene(List(40_000) { i -> InkSceneStroke(Stroke(brush, inputs),
            ImmutableAffineTransform(1f, 0f, i % 200 * 12f, 0f, 1f, i / 200 * 12f)) })
        val shapes = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        scene.strokes.forEach { shapes.add(it.stroke.shape) }
        check(shapes.size == 40_000) { "The scene must have 40k independently modeled meshes" }
        val views = listOf(
            ImmutableAffineTransform(.20f, 0f, 8f, 0f, .20f, 8f),
            ImmutableAffineTransform(.19f, .005f, 14f, -.005f, .19f, 18f),
            ImmutableAffineTransform(.18f, 0f, 30f, 0f, .18f, 24f),
        )
        val sequence = List(4) { listOf(2, 1, 0) }.flatten()
        val cases = ArrayList<JsonObject>()
        var referenceHashes: List<String>? = null
        for (capacity in listOf(1, 3)) {
            val renderer = InkPathRenderer(2048)
            InkSceneRasterCache(capacity, capacity * VIEW_BYTES).use { cache ->
                Surface.makeRasterN32Premul(512, 512).use { surface ->
                    val canvas = surface.canvas.asComposeCanvas()
                    fun draw(view: Int): Int {
                        surface.canvas.clear(0)
                        return cache.draw(canvas, scene, renderer, 512, 512, views[view])
                    }
                    val counts = views.indices.map { draw(it) }
                    check(counts.all { it == 40_000 })
                    val initialRasterBuilds = cache.rasterBuildCount
                    val initialPathBuilds = renderer.pathBuildCount
                    val timings = ArrayList<Double>()
                    val hashes = ArrayList<String>()
                    sequence.forEach { view ->
                        val start = System.nanoTime()
                        check(draw(view) == 40_000)
                        timings += (System.nanoTime() - start) / 1_000_000.0
                        // Readback is outside the timer and validates every revisited view.
                        val bytes = surface.makeImageSnapshot().use { image -> Bitmap.makeFromImage(image).use {
                            requireNotNull(it.readPixels())
                        } }
                        hashes += MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                    }
                    if (referenceHashes == null) referenceHashes = hashes else check(referenceHashes == hashes)
                    val rasterBuilds = cache.rasterBuildCount - initialRasterBuilds
                    val pathBuilds = renderer.pathBuildCount - initialPathBuilds
                    if (capacity == 3) check(rasterBuilds == 0L && pathBuilds == 0L)
                    val retainedBytes = cache.retainedPixelBytes
                    check(retainedBytes <= capacity * VIEW_BYTES)
                    val retainedViews = cache.cachedViewCount
                    val evictions = cache.rasterEvictionCount
                    cache.clearCache()
                    check(cache.retainedPixelBytes == 0L && cache.cachedViewCount == 0)
                    cases += buildJsonObject {
                        put("capacity", capacity); put("pixel_budget_bytes", capacity * VIEW_BYTES)
                        put("additional_raster_builds", rasterBuilds); put("additional_path_builds", pathBuilds)
                        put("raster_evictions", evictions); put("retained_pixel_bytes", retainedBytes)
                        put("retained_views", retainedViews); put("pixel_bytes_after_clear", cache.retainedPixelBytes)
                        put("cached_path_bytes", renderer.cachedPathBytes); put("path_byte_budget", renderer.cacheByteBudget)
                        put("view_order", JsonArray(sequence.map(::JsonPrimitive)))
                        put("raw_ms", JsonArray(timings.map(::JsonPrimitive)))
                        put("pixel_sha256", JsonArray(hashes.map(::JsonPrimitive)))
                    }
                }
            }
            renderer.clearCache()
            check(renderer.cachedPathBytes == 0L)
        }
        val report = buildJsonObject {
            put("status", "passed"); put("schema", 1); put("strokes", 40_000); put("distinct_meshes", shapes.size)
            put("inputs_per_stroke", 9); put("java", System.getProperty("java.runtime.version"))
            put("java_vendor", System.getProperty("java.vendor")); put("os", System.getProperty("os.name"))
            put("native_sha256", InkRuntime.load().sha256); put("pixel_equality", true)
            put("scope", "512x512 CPU software draw/clear; three primed exact views revisited in fixed order. Readback and initial view renders excluded. Retained-byte accounting excludes scene/native peers and wrapper metadata. New-pan/zoom misses still fully rasterize; no compositor or physical latency claim.")
            put("cases", JsonArray(cases))
        }
        val output = File(args.single()).absoluteFile
        output.parentFile.mkdirs()
        output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), report))
        println("View cache benchmark: ${output.path}")
    }

    private const val VIEW_BYTES = 512L * 512 * 4
}
