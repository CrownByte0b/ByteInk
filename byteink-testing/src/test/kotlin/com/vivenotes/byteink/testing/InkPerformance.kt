@file:OptIn(androidx.ink.nativeloader.InkInternalOnlyApi::class)

package com.vivenotes.byteink.testing

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableAffineTransform
import androidx.ink.storage.encode
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkAuthoringController
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.compose.InkScene
import com.vivenotes.byteink.compose.InkSceneStroke
import com.vivenotes.byteink.compose.InkSceneRasterCache
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.vive.PageStroke
import com.vivenotes.byteink.vive.StoredInkStroke
import com.vivenotes.byteink.vive.ViveBrushes
import com.vivenotes.byteink.vive.ViveInkCodec
import com.vivenotes.byteink.vive.ViveInkPage
import java.io.File
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.util.concurrent.Executors
import kotlin.math.ceil
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Surface

/**
 * Reproducible CPU/raster gross-regression checks, run in a separate JVM by performanceCheck.
 * Synthetic inputs always run. A personal notebook is opt-in, never copied into the reports;
 * reports contain aggregate measurements only. Timings deliberately have loose absolute ceilings:
 * they detect unusable regressions without assuming a runner's CPU or refresh rate.
 */
object InkPerformance {
    private const val PAGE_STROKES = 40_000
    private const val LOAD_CEILING_MS = 180_000.0
    private const val INDEX_CEILING_MS = 30_000.0
    private const val FRAME_P95_CEILING_MS = 2_000.0
    private const val SAMPLE_P95_CEILING_MS = 250.0
    private const val RSS_GROWTH_CEILING_BYTES = 512L * 1024 * 1024
    private val viewport = Rect(0f, 0f, 512f, 512f)
    private val metrics = linkedMapOf<String, Any>()

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size in 1..2) { "InkPerformance <report-directory> [optional-notebook.vive]" }
        metrics.clear()
        val output = File(arguments[0]).apply { mkdirs() }
        metrics["os"] = System.getProperty("os.name")
        metrics["java"] = System.getProperty("java.runtime.version")
        metrics["cpu_count"] = Runtime.getRuntime().availableProcessors()
        metrics["native_sha256"] = InkRuntime.load().sha256
        metrics["measurement_scope"] = "CPU InkAuthoringController append/advance + real Ink shape update + Skia software raster; display/vsync latency is not measured"
        metrics["ceilings"] = linkedMapOf(
            "synthetic_load_ms" to LOAD_CEILING_MS,
            "scene_build_ms" to INDEX_CEILING_MS,
            "pan_zoom_p95_ms" to FRAME_P95_CEILING_MS,
            "full_page_fit_repeat_p95_ms" to FRAME_P95_CEILING_MS,
            "sample_to_raster_p95_ms" to SAMPLE_P95_CEILING_MS,
            "repeated_authoring_rss_growth_bytes" to RSS_GROWTH_CEILING_BYTES,
        )
        try {
            // Resolve cached brush families and Skia before measurements/peer cleanup tracking.
            warmup()
            // Keep lifetime tracking ahead of the large-page allocations so finalization of that
            // workload cannot be mistaken for pointers allocated inside the tracking scope.
            measureNativeLifetime()
            val rows = measured("synthetic_fixture_prepare_ms") { syntheticRows() }
            val page = measured("synthetic_load_ms") { load(rows) }
            check(page.size == PAGE_STROKES) { "Synthetic page must retain all $PAGE_STROKES strokes" }
            metrics["synthetic_stroke_count"] = page.size
            measurePage("synthetic", page, enforceCulling = true)
            measureLiveSamples()
            if (arguments.size == 2) measureNotebook(File(arguments[1]))
            assertCeiling("synthetic_load_ms", LOAD_CEILING_MS)
            assertCeiling("synthetic_scene_build_ms", INDEX_CEILING_MS)
            assertCeiling("synthetic_pan_zoom_p95_ms", FRAME_P95_CEILING_MS)
            assertCeiling("sample_to_raster_p95_ms", SAMPLE_P95_CEILING_MS)
            metrics["status"] = "passed"
        } catch (failure: Throwable) {
            metrics["status"] = "failed"
            metrics["failure"] = "${failure.javaClass.simpleName}: ${failure.message}"
            throw failure
        } finally {
            File(output, "performance.json").writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), jsonObject(metrics)))
            File(output, "performance.md").writeText(markdown())
            println("Ink performance report: ${File(output, "performance.md").absolutePath}")
        }
    }

    private fun warmup() {
        val renderer = InkPathRenderer()
        Surface.makeRasterN32Premul(512, 512).use { surface ->
            val canvas = surface.canvas.asComposeCanvas()
            listOf(ViveBrushes.MARKER, ViveBrushes.HIGHLIGHTER, ViveBrushes.calligraphy(3)).forEach { family ->
                val stroke = Stroke(ViveBrushes.brush(family, 0, 0xff000000.toInt(), 4f), inputs())
                renderer.draw(canvas, stroke)
                val live = InProgressStroke().apply { start(stroke.brush) }
                live.enqueueInputs(inputs(), MutableStrokeInputBatch())
                live.finishInput()
                live.updateShape(1000L)
                renderer.draw(canvas, live)
                live.toImmutable()
            }
            renderer.clearCache()
        }
        collect()
    }

    private fun syntheticRows(): List<StoredInkStroke> {
        val template = ViveInkCodec.encodeStroke(
            Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 3f), inputs()),
            "template", "synthetic", 0, ViveBrushes.MARKER, 0, false, 0L,
        )
        return List(PAGE_STROKES) { i ->
            val x = (i % 200) * 100f
            val y = (i / 200) * 40f
            val batch = inputs(x, y)
            template.copy(id = "synthetic-$i", seq = i, createdAt = i.toLong(),
                minX = template.minX + x, minY = template.minY + y,
                maxX = template.maxX + x, maxY = template.maxY + y,
                points = ByteArrayOutputStream().use { stream -> batch.encode(stream); stream.toByteArray() })
        }
    }

    private fun inputs(x: Float = 0f, y: Float = 0f): MutableStrokeInputBatch = MutableStrokeInputBatch().apply {
        repeat(9) { i -> add(InputToolType.MOUSE, x + 8f + i * 7f, y + 12f + (i % 3) * 2f, i * 8L) }
    }

    private fun load(rows: List<StoredInkStroke>, erases: List<com.vivenotes.byteink.vive.StoredInkErase> = emptyList(),
        moves: List<com.vivenotes.byteink.vive.StoredInkMove> = emptyList()): List<PageStroke> {
        val pool = Executors.newFixedThreadPool(minOf(8, Runtime.getRuntime().availableProcessors()))
        return try {
            ViveInkPage.load(rows, erases, moves, executor = pool).also {
                check(it.unreadable.isEmpty()) { "Performance fixture contains unreadable strokes" }
            }.strokes
        } finally { pool.shutdownNow() }
    }

    private fun measurePage(prefix: String, page: List<PageStroke>, enforceCulling: Boolean) {
        val scene = measured("${prefix}_scene_build_ms") { InkScene(page.map { InkSceneStroke(it.stroke, it.strokeToPageTransform()) }) }
        val renderer = InkPathRenderer(cacheCapacity = 2048)
        val pageBounds = page.mapNotNull { it.pageBounds }
        val left = pageBounds.minOf { it.left }
        val top = pageBounds.minOf { it.top }
        val frames = mutableListOf<Double>()
        val queries = mutableListOf<Double>()
        val candidates = mutableListOf<Int>()
        Surface.makeRasterN32Premul(512, 512).use { surface ->
            val canvas = surface.canvas.asComposeCanvas()
            val initial = ImmutableAffineTransform(1f, 0f, -left + 2f, 0f, 1f, -top + 2f)
            scene.draw(canvas, renderer, initial, viewport)
            val builds = renderer.pathBuildCount
            repeat(10) { scene.draw(canvas, renderer, initial, viewport) }
            check(renderer.pathBuildCount == builds) { "Repeated drawing rebuilt unchanged geometry" }
            // All zooms at this location use a subset of the original view's shapes.
            repeat(10) { i ->
                val zoom = 1f + i * 0.1f
                scene.draw(canvas, renderer, ImmutableAffineTransform(zoom, 0f, zoom * (-left + 2f), 0f, zoom, zoom * (-top + 2f)), viewport)
            }
            check(renderer.pathBuildCount == builds) { "Zooming rebuilt unchanged geometry" }
            metrics["${prefix}_repeat_and_zoom_path_builds"] = renderer.pathBuildCount - builds
            repeat(120) { i ->
                val zoom = 0.8f + (i % 12) * 0.1f
                val x = left + (i % 20) * 35f
                val y = top + (i / 20) * 40f
                val transform = ImmutableAffineTransform(zoom, 0f, -x * zoom, 0f, zoom, -y * zoom)
                var visible: List<InkSceneStroke>
                val queryStart = System.nanoTime()
                visible = scene.visibleStrokes(viewport, transform)
                queries += elapsed(queryStart)
                candidates += visible.size
                val started = System.nanoTime()
                surface.canvas.clear(0xffffffff.toInt())
                scene.draw(canvas, renderer, transform, viewport)
                frames += elapsed(started)
            }
        }
        metrics["${prefix}_query_p50_ms"] = percentile(queries, 0.50)
        metrics["${prefix}_query_p95_ms"] = percentile(queries, 0.95)
        metrics["${prefix}_pan_zoom_p50_ms"] = percentile(frames, 0.50)
        metrics["${prefix}_pan_zoom_p95_ms"] = percentile(frames, 0.95)
        metrics["${prefix}_pan_zoom_max_ms"] = frames.max()
        metrics["${prefix}_visible_candidates_max"] = candidates.max()
        metrics["${prefix}_path_builds"] = renderer.pathBuildCount
        metrics["${prefix}_cached_shapes"] = renderer.cachedShapeCount
        check(renderer.cachedShapeCount <= renderer.cacheCapacity) { "Finished-path cache exceeds capacity" }
        if (enforceCulling) {
            check(candidates.max() < page.size / 20) { "Small viewport visited too much of the 40k-stroke page" }
            check(renderer.pathBuildCount < page.size / 10) { "Culling built paths for faraway strokes" }
        }
        renderer.clearCache()
        check(renderer.cachedShapeCount == 0) { "Changing page failed to release cached paths" }
        metrics["${prefix}_cleared_cached_shapes"] = renderer.cachedShapeCount
        measureFullPageFit(prefix, page, scene)
        measureCachedFullPageFit(prefix, page, scene)
    }

    /** Full-page Fit intentionally exposes path churn when every shape exceeds the LRU capacity. */
    private fun measureFullPageFit(prefix: String, page: List<PageStroke>, scene: InkScene) {
        val bounds = page.mapNotNull { it.pageBounds }
        val left = bounds.minOf { it.left }
        val top = bounds.minOf { it.top }
        val width = maxOf(1f, bounds.maxOf { it.right } - left)
        val height = maxOf(1f, bounds.maxOf { it.bottom } - top)
        val fitScale = minOf((viewport.width - 32f) / width, (viewport.height - 32f) / height)
        fun transform(zoom: Float): ImmutableAffineTransform {
            val scale = fitScale * zoom
            return ImmutableAffineTransform(scale, 0f, (viewport.width - width * scale) / 2f - left * scale,
                0f, scale, (viewport.height - height * scale) / 2f - top * scale)
        }
        val renderer = InkPathRenderer(cacheCapacity = 2048)
        val repeatFrames = mutableListOf<Double>()
        val repeatBuilds = mutableListOf<Long>()
        val repeatCandidates = mutableListOf<Int>()
        var coldDrawn = 0
        Surface.makeRasterN32Premul(512, 512).use { surface ->
            val canvas = surface.canvas.asComposeCanvas()
            val fit = transform(1f)
            metrics["${prefix}_fit_scale"] = fitScale
            metrics["${prefix}_fit_cold_candidates"] = scene.visibleStrokes(viewport, fit).size
            measured("${prefix}_fit_cold_render_ms") {
                surface.canvas.clear(0xffffffff.toInt())
                coldDrawn = scene.draw(canvas, renderer, fit, viewport)
            }
            metrics["${prefix}_fit_cold_drawn"] = coldDrawn
            metrics["${prefix}_fit_cold_path_builds"] = renderer.pathBuildCount
            metrics["${prefix}_fit_cold_cached_shapes"] = renderer.cachedShapeCount
            check(coldDrawn == bounds.size) { "Full-page Fit omitted visible strokes" }
            // Repeated Fit and progressively zoomed-out views all include the entire page.
            listOf(1f, 1f, 0.9f, 0.75f, 1f, 0.8f, 0.95f).forEach { zoom ->
                val view = transform(zoom)
                repeatCandidates += scene.visibleStrokes(viewport, view).size
                val previousBuilds = renderer.pathBuildCount
                val started = System.nanoTime()
                surface.canvas.clear(0xffffffff.toInt())
                val drawn = scene.draw(canvas, renderer, view, viewport)
                repeatFrames += elapsed(started)
                repeatBuilds += renderer.pathBuildCount - previousBuilds
                check(drawn == coldDrawn) { "Zooming out from Fit omitted visible strokes" }
            }
        }
        metrics["${prefix}_fit_repeat_frame_count"] = repeatFrames.size
        metrics["${prefix}_fit_repeat_frames_ms"] = JsonArray(repeatFrames.map { JsonPrimitive(it) })
        metrics["${prefix}_fit_repeat_p50_ms"] = percentile(repeatFrames, 0.50)
        metrics["${prefix}_fit_repeat_p95_ms"] = percentile(repeatFrames, 0.95)
        metrics["${prefix}_fit_repeat_max_ms"] = repeatFrames.max()
        metrics["${prefix}_fit_repeat_candidates_max"] = repeatCandidates.max()
        metrics["${prefix}_fit_repeat_path_builds_per_frame"] = JsonArray(repeatBuilds.map { JsonPrimitive(it) })
        metrics["${prefix}_fit_repeat_path_builds"] = repeatBuilds.sum()
        metrics["${prefix}_fit_total_path_builds"] = renderer.pathBuildCount
        metrics["${prefix}_fit_cached_shapes"] = renderer.cachedShapeCount
        metrics["${prefix}_fit_cache_capacity"] = renderer.cacheCapacity
        metrics["${prefix}_fit_cache_behavior"] = if (repeatBuilds.any { it > 0 })
            "Bounded LRU rebuilds evicted paths when the full visible page exceeds cache capacity" else
            "All full-page paths reused across repeated Fit and zoom-out frames"
        check(renderer.cachedShapeCount <= renderer.cacheCapacity) { "Full-page cache exceeds capacity" }
        renderer.clearCache()
        check(renderer.cachedShapeCount == 0)
        metrics["${prefix}_fit_cleared_cached_shapes"] = renderer.cachedShapeCount
        assertCeiling("${prefix}_fit_repeat_p95_ms", FRAME_P95_CEILING_MS)
    }

    /** The same all-visible page behind an unchanged raster cache and real live authoring. */
    private fun measureCachedFullPageFit(prefix: String, page: List<PageStroke>, scene: InkScene) {
        val bounds = page.mapNotNull { it.pageBounds }
        val left = bounds.minOf { it.left }
        val top = bounds.minOf { it.top }
        val width = maxOf(1f, bounds.maxOf { it.right } - left)
        val height = maxOf(1f, bounds.maxOf { it.bottom } - top)
        val fitScale = minOf((viewport.width - 32f) / width, (viewport.height - 32f) / height)
        fun transform(zoom: Float, panX: Float = 0f, panY: Float = 0f): ImmutableAffineTransform {
            val scale = fitScale * zoom
            return ImmutableAffineTransform(scale, 0f, (viewport.width - width * scale) / 2f - left * scale + panX,
                0f, scale, (viewport.height - height * scale) / 2f - top * scale + panY)
        }
        val renderer = InkPathRenderer(cacheCapacity = 2048)
        val compositeFrames = mutableListOf<Double>()
        val liveFrames = mutableListOf<Double>()
        val changedFrames = mutableListOf<Double>()
        var backgroundLivePathBuilds = 0L
        var maximumRetainedPixelBytes = 0L
        Surface.makeRasterN32Premul(512, 512).use { surface ->
            val canvas = surface.canvas.asComposeCanvas()
            val fit = transform(1f)
            InkSceneRasterCache().use { cache ->
                val coldDrawn = measured("${prefix}_cached_fit_cold_render_ms") {
                    surface.canvas.clear(0xffffffff.toInt())
                    cache.draw(canvas, scene, renderer, 512, 512, fit)
                }
                check(coldDrawn == bounds.size) { "Cached Fit omitted visible strokes" }
                val coldPaths = renderer.pathBuildCount
                val coldRasters = cache.rasterBuildCount
                check(coldRasters == 1L) { "Cold Fit must build exactly one raster" }
                check(cache.retainedPixelBytes == 512L * 512L * 4L) { "Cold Fit must retain one viewport image" }
                metrics["${prefix}_cached_fit_cold_drawn"] = coldDrawn
                metrics["${prefix}_cached_fit_cold_path_builds"] = coldPaths
                metrics["${prefix}_cached_fit_cold_raster_builds"] = coldRasters
                maximumRetainedPixelBytes = cache.retainedPixelBytes
                repeat(40) {
                    val started = System.nanoTime()
                    surface.canvas.clear(0xffffffff.toInt())
                    check(cache.draw(canvas, scene, renderer, 512, 512, fit) == coldDrawn)
                    compositeFrames += elapsed(started)
                }
                check(renderer.pathBuildCount == coldPaths) { "Held Fit rebuilt finished paths" }
                check(cache.rasterBuildCount == coldRasters) { "Held Fit rebuilt the cached viewport" }
                metrics["${prefix}_cached_fit_held_additional_path_builds"] = renderer.pathBuildCount - coldPaths
                metrics["${prefix}_cached_fit_held_additional_raster_builds"] = cache.rasterBuildCount - coldRasters

                InkAuthoringController().use { controller ->
                    controller.begin(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 4f),
                        InkPointerSample(170f, 170f, 1000L), fit)
                    repeat(60) { i ->
                        val observation = InkPointerSample(171f + i * 1.5f, 170f + (i % 9) * 1.5f, 1008L + i * 8L)
                        val started = System.nanoTime()
                        check(controller.append(observation))
                        controller.advance(observation.uptimeMillis)
                        surface.canvas.clear(0xffffffff.toInt())
                        val beforeBackground = renderer.pathBuildCount
                        check(cache.draw(canvas, scene, renderer, 512, 512, fit) == coldDrawn)
                        backgroundLivePathBuilds += renderer.pathBuildCount - beforeBackground
                        check(renderer.draw(canvas, requireNotNull(controller.liveStroke), controller.strokeToView, viewport)) {
                            "Full-Fit live authoring produced no visible wet geometry"
                        }
                        liveFrames += elapsed(started)
                    }
                    check(requireNotNull(controller.finish()).inputs.size == 61)
                }
                check(backgroundLivePathBuilds == 0L) { "Live updates rebuilt cached finished paths" }
                check(cache.rasterBuildCount == coldRasters) { "Live updates rebuilt the finished-page raster" }
                metrics["${prefix}_cached_fit_live_background_path_builds"] = backgroundLivePathBuilds
                metrics["${prefix}_cached_fit_live_additional_raster_builds"] = cache.rasterBuildCount - coldRasters

                // A view change pays the explicit rebuild cost once; its subsequent composites reuse it.
                listOf(transform(0.95f), transform(1f, 3f, 4f), transform(0.85f, -4f, 5f)).forEach { view ->
                    val previousRasters = cache.rasterBuildCount
                    val started = System.nanoTime()
                    surface.canvas.clear(0xffffffff.toInt())
                    check(cache.draw(canvas, scene, renderer, 512, 512, view) == coldDrawn)
                    changedFrames += elapsed(started)
                    check(cache.rasterBuildCount == previousRasters + 1) { "Changed view must build exactly one raster" }
                    val changedPaths = renderer.pathBuildCount
                    repeat(3) { cache.draw(canvas, scene, renderer, 512, 512, view) }
                    check(cache.rasterBuildCount == previousRasters + 1) { "Repeating changed view rebuilt its raster" }
                    check(renderer.pathBuildCount == changedPaths) { "Repeating changed view rebuilt finished paths" }
                    maximumRetainedPixelBytes = maxOf(maximumRetainedPixelBytes, cache.retainedPixelBytes)
                }
                metrics["${prefix}_cached_fit_total_raster_builds"] = cache.rasterBuildCount
                metrics["${prefix}_cached_fit_retained_pixel_bytes_max"] = maximumRetainedPixelBytes
                check(maximumRetainedPixelBytes <= 512L * 512L * 4L) { "Raster cache retains more than one viewport" }
                cache.clearCache()
                check(cache.retainedPixelBytes == 0L) { "Raster cache clear failed to release its image" }
                metrics["${prefix}_cached_fit_cleared_pixel_bytes"] = cache.retainedPixelBytes
            }
        }
        metrics["${prefix}_cached_fit_composite_frame_count"] = compositeFrames.size
        metrics["${prefix}_cached_fit_composite_p50_ms"] = percentile(compositeFrames, 0.50)
        metrics["${prefix}_cached_fit_composite_p95_ms"] = percentile(compositeFrames, 0.95)
        metrics["${prefix}_cached_fit_composite_max_ms"] = compositeFrames.max()
        metrics["${prefix}_cached_fit_live_frame_count"] = liveFrames.size
        metrics["${prefix}_cached_fit_live_sample_to_raster_p50_ms"] = percentile(liveFrames, 0.50)
        metrics["${prefix}_cached_fit_live_sample_to_raster_p95_ms"] = percentile(liveFrames, 0.95)
        metrics["${prefix}_cached_fit_live_sample_to_raster_max_ms"] = liveFrames.max()
        metrics["${prefix}_cached_fit_changed_view_frames_ms"] = JsonArray(changedFrames.map { JsonPrimitive(it) })
        metrics["${prefix}_cached_fit_changed_view_p95_ms"] = percentile(changedFrames, 0.95)
        metrics["${prefix}_cached_fit_changed_view_max_ms"] = changedFrames.max()
        metrics["${prefix}_cached_fit_live_scope"] = "Controller append/advance, cached all-visible finished-page composite and wet-stroke raster at the same Fit transform"
        renderer.clearCache()
        assertCeiling("${prefix}_cached_fit_composite_p95_ms", FRAME_P95_CEILING_MS)
        assertCeiling("${prefix}_cached_fit_live_sample_to_raster_p95_ms", SAMPLE_P95_CEILING_MS)
        assertCeiling("${prefix}_cached_fit_changed_view_p95_ms", FRAME_P95_CEILING_MS)
    }

    private fun measureLiveSamples() {
        val times = mutableListOf<Double>()
        val renderer = InkPathRenderer()
        Surface.makeRasterN32Premul(512, 512).use { surface ->
            val canvas = surface.canvas.asComposeCanvas()
            InkAuthoringController().use { controller ->
                repeat(12) { strokeIndex ->
                    val family = if (strokeIndex % 2 == 0) ViveBrushes.MARKER else ViveBrushes.HIGHLIGHTER
                    val startUptime = strokeIndex * 1000L
                    controller.begin(ViveBrushes.brush(family, 0,
                        if (family == ViveBrushes.HIGHLIGHTER) 0x80ffff00.toInt() else 0xff000000.toInt(), 8f),
                        InkPointerSample(10f, 30f, startUptime))
                    for (sample in 1 until 60) {
                        val observation = InkPointerSample(10f + sample * 7f, 30f + (sample % 7) * 2f, startUptime + sample * 8L)
                        val started = System.nanoTime()
                        check(controller.append(observation))
                        controller.advance(observation.uptimeMillis)
                        renderer.draw(canvas, requireNotNull(controller.liveStroke), controller.strokeToView)
                        if (strokeIndex >= 2 && sample >= 5) times += elapsed(started)
                    }
                    check(requireNotNull(controller.finish()).inputs.size == 60)
                }
            }
        }
        metrics["sample_to_raster_count"] = times.size
        metrics["sample_to_raster_p50_ms"] = percentile(times, 0.50)
        metrics["sample_to_raster_p95_ms"] = percentile(times, 0.95)
        metrics["sample_to_raster_max_ms"] = times.max()
        renderer.clearCache()
    }

    private fun measureNativeLifetime() {
        collect()
        val before = residentBytes()
        val heapBefore = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        var freed = 0
        var allocated = 0
        repeat(2) {
            val result = trackPeerCleanup(::authoringLifetimeBatch)
            allocated += result.first
            freed += result.second
            collect()
        }
        val after = residentBytes()
        metrics["native_peer_cleanups"] = freed
        metrics["native_peer_allocations"] = allocated
        metrics["native_peer_cleanup_batches"] = 2
        metrics["native_peer_cleanup_strokes"] = 600
        metrics["native_peer_cleanup_scope"] = "Two controllers reused across 300 gestures each, then closed; final strokes encoded and rebuilt"
        metrics["heap_before_bytes"] = heapBefore
        metrics["heap_after_bytes"] = ManagementFactory.getMemoryMXBean().heapMemoryUsage.used
        if (before != null && after != null) {
            metrics["rss_measurement_scope"] = "Linux process resident set, inclusive of JVM/Skia/native Ink; native allocations are separately checked through pointer cleanup"
            val growth = after - before
            metrics["rss_before_bytes"] = before
            metrics["rss_after_bytes"] = after
            metrics["repeated_authoring_rss_growth_bytes"] = growth
            check(growth <= RSS_GROWTH_CEILING_BYTES) { "Repeated authoring grew process RSS by $growth bytes" }
        } else {
            metrics["rss_measurement_scope"] = "Unavailable on this OS; native peer cleanup still checked"
            metrics["rss_status"] = "Unavailable on this OS; native peer cleanup still checked"
        }
        check(freed >= 600) { "Authoring did not release the expected native peers" }
    }

    /**
     * Test-only access to the pinned upstream observer, kept out of the published library API.
     * Upstream's helper forbids address reuse even after cleanup; repeated authoring must permit
     * allocator reuse. Counts also allow an allocation callback to arrive between another peer's
     * actual free and its cleanup callback. Every observed allocation still needs exactly one free.
     */
    private fun trackPeerCleanup(block: () -> Unit): Pair<Int, Int> {
        val type = Class.forName("androidx.ink.nativeloader.NativePointerObserver")
        val observer = type.getField("INSTANCE").get(null)
        val setAlloc = type.methods.single { it.name == "setOnAlloc" }
        val setCleanup = type.methods.single { it.name == "setOnCleanup" }
        check(type.methods.single { it.name == "getOnAlloc" }.invoke(observer) == null)
        check(type.methods.single { it.name == "getOnCleanup" }.invoke(observer) == null)
        val lock = Any()
        val active = HashMap<Long, Int>()
        var allocated = 0
        var cleaned = 0
        var failure: String? = null
        val onAlloc: (Long) -> Unit = { pointer -> synchronized(lock) {
            allocated++
            active[pointer] = (active[pointer] ?: 0) + 1
        } }
        val onCleanup: (Long) -> Unit = { pointer -> synchronized(lock) {
            val count = active[pointer]
            if (count == null) failure = "Native cleanup had no corresponding allocation: $pointer"
            else {
                if (count == 1) active.remove(pointer) else active[pointer] = count - 1
                cleaned++
            }
        } }
        try {
            setAlloc.invoke(observer, onAlloc)
            setCleanup.invoke(observer, onCleanup)
            block()
            val deadline = System.nanoTime() + 30_000_000_000L
            while (true) {
                collect()
                val done = synchronized(lock) {
                    check(failure == null) { requireNotNull(failure) }
                    check(allocated > 0) { "No native peers were observed" }
                    active.isEmpty()
                }
                if (done) break
                check(System.nanoTime() < deadline) {
                    synchronized(lock) { "Native peer cleanup timed out: $allocated allocations, $cleaned cleanups, ${active.size} addresses retained" }
                }
                Thread.sleep(25L)
            }
            return synchronized(lock) {
                check(allocated == cleaned) { "Native peer allocation/cleanup counts differ" }
                allocated to cleaned
            }
        } finally {
            setAlloc.invoke(observer, null)
            setCleanup.invoke(observer, null)
        }
    }

    private fun authoringLifetimeBatch() {
        val brush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff000000.toInt(), 4f)
        val renderer = InkPathRenderer(cacheCapacity = 32)
        Surface.makeRasterN32Premul(128, 128).use { surface ->
            val canvas = surface.canvas.asComposeCanvas()
            InkAuthoringController().use { controller ->
                repeat(300) { i ->
                    val startUptime = i * 1000L
                    controller.begin(brush, InkPointerSample(8f, 12f, startUptime))
                    for (sample in 1 until 9) {
                        val observation = InkPointerSample(8f + sample * 7f, 12f + (sample % 3) * 2f, startUptime + sample * 8L)
                        check(controller.append(observation))
                        controller.advance(observation.uptimeMillis)
                        renderer.draw(canvas, requireNotNull(controller.liveStroke), controller.strokeToView)
                    }
                    val dry = requireNotNull(controller.finish())
                    renderer.draw(canvas, dry)
                    val row = ViveInkCodec.encodeStroke(dry, "lifetime-$i", "lifetime", i, ViveBrushes.MARKER, 0, false, 0L)
                    check(ViveInkCodec.decode(row) != null)
                }
            }
            renderer.clearCache()
        }
    }

    private fun measureNotebook(file: File) {
        require(file.isFile) { "Performance notebook does not exist: $file" }
        val pages = measured("real_archive_and_rows_ms") {
            ViveNotebook.open(file).use { notebook ->
                notebook.pageIds.map(notebook::page)
            }
        }
        val loaded = measured("real_load_all_pages_ms") {
            pages.map { rows -> load(rows.strokes, rows.erases, rows.moves) }
        }
        val largest = loaded.indices.maxBy { loaded[it].size }
        val page = loaded[largest]
        metrics["real_page_count"] = pages.size
        metrics["real_largest_page_stroke_count"] = page.size
        metrics["real_notebook_stored_stroke_count"] = pages.sumOf { it.strokes.size }
        measurePage("real", page, enforceCulling = false)
        // The 40k real fixture is spread across pages. Position every page in one virtual vertical
        // scene to exercise all of that ink together without pretending it is a single stored page.
        var cursor = 0f
        val allPages = loaded.flatMap { strokes ->
            val bounds = strokes.mapNotNull { it.pageBounds }
            val top = bounds.minOfOrNull { it.top } ?: 0f
            val bottom = bounds.maxOfOrNull { it.bottom } ?: top
            val shift = cursor - top
            cursor += bottom - top + 128f
            strokes.map { it.copy(offsetY = it.offsetY + shift) }
        }
        metrics["real_virtual_scene_stroke_count"] = allPages.size
        metrics["real_virtual_scene_scope"] = "All actual notebook pages positioned vertically in one virtual scene"
        measurePage("real_virtual", allPages, enforceCulling = false)
    }

    private fun residentBytes(): Long? {
        // Wine exposes a synthetic /proc tree to Windows Java; it is not the JVM's resident set.
        if (!System.getProperty("os.name").startsWith("Linux")) return null
        val status = File("/proc/self/status")
        if (!status.isFile) return null
        return status.useLines { lines -> lines.firstOrNull { it.startsWith("VmRSS:") }
            ?.substringAfter(":")?.trim()?.substringBefore(" ")?.toLongOrNull()?.times(1024) }
    }

    @Suppress("DEPRECATION")
    private fun collect() { System.gc(); System.runFinalization(); System.gc(); System.runFinalization() }

    private fun assertCeiling(key: String, ceiling: Double) {
        val value = (metrics.getValue(key) as Number).toDouble()
        check(value <= ceiling) { "$key=$value exceeds gross-regression ceiling $ceiling" }
    }

    private fun percentile(values: List<Double>, fraction: Double): Double = values.sorted()[ceil(values.size * fraction).toInt() - 1]
    private fun elapsed(started: Long): Double = (System.nanoTime() - started) / 1e6
    private inline fun <T> measured(key: String, block: () -> T): T {
        val started = System.nanoTime()
        return block().also { metrics[key] = elapsed(started) }
    }

    private fun jsonObject(values: Map<String, Any>): JsonObject = JsonObject(values.mapValues { (_, value) -> when (value) {
        is String -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is JsonElement -> value
        is Map<*, *> -> jsonObject(value.entries.associate { it.key.toString() to requireNotNull(it.value) })
        else -> JsonPrimitive(value.toString())
    } })

    private fun markdown(): String = buildString {
        append("# Ink CPU and raster performance\n\n")
        append("Synthetic workload: 40,000 independently decoded real Ink strokes with nine mouse samples per stroke.\n\n")
        append("Live sample timings exercise InkAuthoringController append/advance and Skia software rasterization; display/vsync latency is not measured. ")
        append("RSS includes the JVM and Skia. All native peers created by the repeated authoring workload must be freed.\n\n")
        append("| Measurement | Value |\n|---|---:|\n")
        metrics.filterValues { it !is Map<*, *> }.forEach { (key, value) ->
            append("| $key | ${if (value is Double) java.lang.String.format(java.util.Locale.ROOT, "%.3f", value) else value} |\n")
        }
        append("\nAbsolute ceilings deliberately detect gross regressions across varied CI hardware. ")
        append("Culling, unchanged-path reuse, bounded caches, explicit cache release and native-peer cleanup are structural checks.\n\n")
        append("| Guard | Ceiling |\n|---|---:|\n")
        (metrics["ceilings"] as Map<*, *>).forEach { (key, value) -> append("| $key | $value |\n") }
    }
}
