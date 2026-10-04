@file:OptIn(androidx.ink.nativeloader.InkInternalOnlyApi::class)

package com.vivenotes.byteink.testing

import androidx.compose.ui.graphics.asComposeCanvas
import androidx.ink.brush.InputToolType
import androidx.ink.geometry.ImmutableBox
import androidx.ink.geometry.ImmutableVec
import androidx.ink.storage.decode
import androidx.ink.storage.encode
import androidx.ink.strokes.InProgressStroke
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInputBatch
import androidx.ink.strokes.getRawTriangleIndexBuffer
import com.vivenotes.byteink.compose.InkAuthoringController
import com.vivenotes.byteink.compose.InkPathRenderer
import com.vivenotes.byteink.compose.InkPointerSample
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.core.SpatialIndex
import com.vivenotes.byteink.kit.InkEraseMode
import com.vivenotes.byteink.kit.InkPoint
import com.vivenotes.byteink.kit.LassoShape
import com.vivenotes.byteink.kit.PageStroke
import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.kit.ViveBrushes
import com.vivenotes.byteink.kit.ViveInkCodec
import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.byteink.kit.closesIntoALoop
import com.vivenotes.byteink.kit.planProjectionDelete
import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.management.ManagementFactory
import java.time.Instant
import jdk.jfr.Event
import jdk.jfr.Label
import jdk.jfr.Name
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.skia.Surface
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Optional diagnostic workload benchmark. Run serial fresh JVM forks with benchmarks/run.py.
 * Measures real APIs, retains returned results, and reports measured-thread JVM allocation.
 * It is not JMH: percentiles describe these finite samples, not a confidence interval or display
 * latency. Raw AndroidX codec cases are diagnostic controls and bypass ByteInk's validation policy.
 */
object InkHotPathBenchmark {
    @Volatile private var sink: Long = 0
    @Volatile private var referenceSink: Any? = null
    private val cases = linkedMapOf<String, Any>()
    private val allocation = (ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean)?.apply {
        if (isThreadAllocatedMemorySupported) isThreadAllocatedMemoryEnabled = true
    }

    @JvmStatic
    fun main(arguments: Array<String>) {
        require(arguments.size == 1) { "InkHotPathBenchmark <output.json>" }
        val output = File(arguments.single()).absoluteFile
        output.parentFile.mkdirs()
        val report = linkedMapOf<String, Any?>(
            "schema" to 1,
            "started_utc" to Instant.now().toString(),
            "os" to System.getProperty("os.name"),
            "os_version" to System.getProperty("os.version"),
            "architecture" to System.getProperty("os.arch"),
            "java" to System.getProperty("java.runtime.version"),
            "java_vendor" to System.getProperty("java.vendor"),
            "java_vm" to System.getProperty("java.vm.name"),
            "java_arguments" to ManagementFactory.getRuntimeMXBean().inputArguments,
            "cpu_count" to Runtime.getRuntime().availableProcessors(),
            "cpu_model" to cpuModel(),
            "max_heap_bytes" to Runtime.getRuntime().maxMemory(),
            "gc" to ManagementFactory.getGarbageCollectorMXBeans().map { it.name },
            "native_sha256" to InkRuntime.load().sha256,
            "allocation_scope" to "Measured-thread JVM heap bytes, including harness calls and input sample creation; excludes native/Skia buffers and worker threads",
            "timing_scope" to "Elapsed wall time of processing and Skia 512x512 software raster, including scheduling/GC; no compositor/vsync/device latency",
            "cases" to cases,
        )
        try {
            codec()
            queries()
            deletion()
            replay()
            lassoClosure()
            lassoContainment()
            meshes()
            archive()
            longGestures()
            report["status"] = "passed"
        } catch (failure: Throwable) {
            report["status"] = "failed"
            report["failure"] = "${failure.javaClass.name}: ${failure.message}"
            throw failure
        } finally {
            report["finished_utc"] = Instant.now().toString()
            report["result_sink"] = sink
            output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json(report) as JsonObject))
            println("Hot-path benchmark: ${output.path}")
        }
    }

    private fun inputs(count: Int): MutableStrokeInputBatch = MutableStrokeInputBatch().apply {
        repeat(count) { i ->
            add(InputToolType.MOUSE, 16f + i * 0.04f, 220f + sin(i * 0.08).toFloat() * 60f, i * 8L)
        }
    }

    private fun stored(stroke: Stroke): StoredInkStroke = ViveInkCodec.encodeStroke(
        stroke, "template", "synthetic", 0, ViveBrushes.MARKER, 0, false, 0L,
    )

    private fun codec() {
        for (count in listOf(9, 1024, 8192)) {
            val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff202020.toInt(), 3f), inputs(count))
            val row = stored(stroke)
            check(StrokeInputBatch.decode(row.points).size == count)
            val batch = if (count == 9) 128 else if (count == 1024) 16 else 4
            measure("codec.validated_inputs.$count", batch, details = mapOf("input_count" to count, "gzip_bytes" to row.points.size)) {
                check(ViveInkCodec.hasValidInputData(row.points)); count.toLong()
            }
            measure("codec.upstream_inputs_control.$count", batch, details = mapOf("input_count" to count,
                "policy" to "Known valid synthetic bytes only; no ByteInk decompression cap")) {
                val decoded = StrokeInputBatch.decode(row.points)
                referenceSink = decoded
                check(decoded.size == count); decoded.size.toLong()
            }
            measure("codec.row_decode.$count", batch) {
                val decoded = requireNotNull(ViveInkCodec.decode(row))
                referenceSink = decoded
                check(decoded.inputs.size == count); decoded.inputs.size.toLong()
            }
            measure("codec.android_row_encode.$count", batch) {
                val encoded = stored(stroke)
                referenceSink = encoded
                check(encoded.points.contentEquals(row.points)); encoded.points.size.toLong()
            }
            measure("codec.upstream_encode_control.$count", batch, details = mapOf(
                "policy" to "Different wire bytes: omits private Android phase and uses host gzip")) {
                val bytes = ByteArrayOutputStream().use { out -> stroke.inputs.encode(out); out.toByteArray() }
                referenceSink = bytes
                bytes.size.toLong()
            }
        }
    }

    private fun queries() {
        for (count in listOf(40_000, 200_000)) {
            val items = (0 until count).toList()
            fun bounds(index: Int) = ImmutableBox.fromTwoPoints(
                ImmutableVec((index % 400) * 100f, (index / 400) * 40f),
                ImmutableVec((index % 400) * 100f + 30f, (index / 400) * 40f + 10f),
            )
            val index = SpatialIndex.of(items, bounds = ::bounds)
            measure("spatial.empty.$count", 512) {
                val hits = index.query(-500f, -500f, -450f, -450f)
                referenceSink = hits
                check(hits.isEmpty()); hits.size.toLong()
            }
            measure("spatial.sparse.$count", 512) {
                val hits = index.query(0f, 0f, 512f, 512f)
                referenceSink = hits
                check(hits.size == 78); hits.size.toLong()
            }
            measure("spatial.all_visible.$count", 8) {
                val hits = index.query(-1f, -1f, 50_000f, 50_000f)
                referenceSink = hits
                check(hits.size == count); hits.size.toLong()
            }
            if (count == 40_000) measure("spatial.build.$count", 1, warmups = 3, samples = 7) {
                val rebuilt = SpatialIndex.of(items, bounds = ::bounds)
                referenceSink = rebuilt
                check(rebuilt.size == count); rebuilt.size.toLong()
            }
        }
    }

    private fun deletion() {
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff202020.toInt(), 3f), inputs(9))
        for (count in listOf(500, 2000, 4000)) {
            val page = List(count) { PageStroke("row-$it", stroke) }
            val held = page.take(count / 2).map { it.projectionKey }.toSet()
            measure("projection.delete_half.$count", 1, warmups = 3, samples = 7,
                details = mapOf("projection_count" to count, "whole_rows_deleted" to held.size,
                    "geometry" to "Unique rows share an immutable short mesh; every selected row is wholly deleted")) {
                val plan = page.planProjectionDelete(held)
                referenceSink = plan
                check(plan.erases.isEmpty() && plan.wholeRows.size == count / 2 && plan.after.size == count / 2)
                plan.after.size.toLong()
            }
        }
    }

    private fun replay() {
        val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff202020.toInt(), 3f), inputs(9))
        val template = stored(stroke)
        val rows = List(4096) { template.copy(id = "row-$it", seq = it) }
        val maskInputs = MutableStrokeInputBatch().apply { add(InputToolType.MOUSE, 100_000f, 100_000f, 0L) }
        val mask = ViveBrushes.eraseMask(maskInputs, 4f)
        val erases = List(64) { i ->
            ViveInkCodec.encodeErase(mask, "erase-$i", "synthetic", InkEraseMode.Object, i.toLong(), listOf("row-$i"))
        }
        for (streaming in listOf(false, true)) {
            measure("replay.4096_rows_64_masks.${if (streaming) "streaming" else "batch"}", 1,
                warmups = 3, samples = 7, details = mapOf("rows" to rows.size, "erase_count" to erases.size,
                    "target_policy" to "One actual row per mask, masks far outside all geometry; no deletions",
                    "executor" to "None; sequential decoding to isolate replay/streaming work")) {
                var published = 0L
                val partial: ((List<PageStroke>) -> Unit)? = if (streaming) { chunk -> published += chunk.size } else null
                val loaded = ViveInkPage.load(rows, erases, emptyList(), onPartial = partial)
                referenceSink = loaded
                check(loaded.strokes.size == rows.size && loaded.unreadable.isEmpty() && loaded.erasedAway.isEmpty())
                check(loaded.strokes.map { it.id } == rows.map { it.id })
                sink = sink xor published
                loaded.strokes.size.toLong()
            }
        }
    }

    private fun lassoClosure() {
        fun arc(degrees: Int): List<InkPoint> = (0..degrees step 5).map { angle ->
            val radians = Math.toRadians(angle.toDouble())
            InkPoint(100f + 70f * cos(radians).toFloat(), 100f + 70f * sin(radians).toFloat())
        }
        // Same closure boundaries as LassoClosureTest, measured without mesh construction.
        val controls = listOf(
            Triple("closed_circle", arc(360), true),
            Triple("open_circle_355_degrees", arc(355), false),
            Triple("self_crossing", listOf(InkPoint(60f, 60f), InkPoint(220f, 60f),
                InkPoint(220f, 160f), InkPoint(40f, 160f), InkPoint(40f, 40f), InkPoint(100f, 80f)), true),
        )
        for ((name, path, expected) in controls) {
            check(path.closesIntoALoop(4f) == expected)
            measure("lasso.closure.$name", 32, details = mapOf("path_points" to path.size,
                "touch_dp" to 4, "expected_closed" to expected)) {
                val closed = path.closesIntoALoop(4f)
                check(closed == expected)
                path.size.toLong() + if (closed) 1L else 0L
            }
        }
        for (span in listOf(100, 1000, 10_000)) {
            // One long diagonal followed by two horizontal segments, always travelling away.
            // The largest case stays below 400,000 bucket cells; never use unbounded coordinates.
            val distance = span.toFloat()
            val path = listOf(InkPoint(0f, 0f), InkPoint(distance, distance),
                InkPoint(distance * 2f, distance), InkPoint(distance * 3f, distance))
            measure("lasso.closure.open_diagonal_span_$span", 1, warmups = 1, samples = 5,
                details = mapOf("path_points" to path.size, "diagonal_span_dp" to span,
                    "touch_dp" to 4, "expected_closed" to false,
                    "safety" to "At most 10,000 dp diagonal; fewer than 400,000 rectangle bucket cells per operation")) {
                check(!path.closesIntoALoop(4f))
                path.size.toLong()
            }
        }
    }

    private fun lassoContainment() {
        val convex = listOf(InkPoint(-64f, 96f), InkPoint(416f, 96f),
            InkPoint(416f, 336f), InkPoint(-64f, 336f))
        // An inward notch above all stroke geometry defeats the convex-box shortcut while
        // preserving a positive containment result, so every outline vertex must be visited.
        val concave = listOf(InkPoint(-64f, 96f), InkPoint(128f, 96f), InkPoint(128f, 112f),
            InkPoint(144f, 112f), InkPoint(144f, 96f), InkPoint(416f, 96f),
            InkPoint(416f, 336f), InkPoint(-64f, 336f))
        fun sampledPolygon(corners: List<InkPoint>, vertexCount: Int): List<InkPoint> {
            check(vertexCount % corners.size == 0)
            val perEdge = vertexCount / corners.size
            return List(vertexCount) { index ->
                val edge = index / perEdge
                val from = corners[edge]
                val to = corners[(edge + 1) % corners.size]
                val fraction = (index % perEdge).toFloat() / perEdge
                InkPoint(from.x + (to.x - from.x) * fraction, from.y + (to.y - from.y) * fraction)
            }
        }
        for (count in listOf(9, 1024, 8192)) {
            val stroke = Stroke(ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff202020.toInt(), 3f), inputs(count))
            val projection = PageStroke("lasso-$count", stroke)
            val outside = projection.copy(offsetX = 1000f)
            val shape = stroke.shape
            val outlineVertices = (0 until shape.getRenderGroupCount()).sumOf { group ->
                (0 until shape.getOutlineCount(group)).sumOf { outline -> shape.getOutlineVertexCount(group, outline) }
            }
            check(outlineVertices > 0)
            for (vertexCount in listOf(8, 64, 256)) {
                for ((kind, corners) in listOf("convex" to convex, "concave" to concave)) {
                    val lasso = LassoShape(sampledPolygon(corners, vertexCount), edgeTolerance = 4f)
                    check(lasso.acceptsWholeBox == (kind == "convex"))
                    check(lasso.contains(projection) && !lasso.contains(outside))
                    val batch = if (count == 9) 16 else if (count == 1024) 2 else 1
                    measure("lasso.containment.$kind.inputs_$count.polygon_$vertexCount", batch,
                        warmups = 2, samples = 7,
                        details = mapOf("input_count" to count, "outline_vertices" to outlineVertices,
                            "polygon_vertices" to vertexCount, "expected_contains" to true,
                            "preparation" to "Stroke, page bounds and polygon prepared outside measured operation",
                            "pathway" to if (kind == "convex") "Convex whole-box shortcut" else "Exact walk of every outline vertex")) {
                        check(lasso.contains(projection))
                        outlineVertices.toLong()
                    }
                }
            }
        }
    }

    private fun meshes() {
        for (count in listOf(1024, 8192)) {
            val brush = ViveBrushes.brush(ViveBrushes.MARKER, 0, 0xff202020.toInt(), 3f)
            val batch = inputs(count)
            val stroke = Stroke(brush, batch)
            val live = InProgressStroke().apply {
                start(brush)
                enqueueInputs(batch, MutableStrokeInputBatch())
                finishInput()
                updateShape(count * 8L + 1000L)
            }
            measure("mesh.finished_outlines.$count", 8) {
                val outlines = InkMeshes.outlines(stroke.shape, 0)
                referenceSink = outlines
                outlines.sumOf { it.size / 2 }.toLong()
            }
            measure("mesh.live_outlines.$count", 8) {
                val outlines = InkMeshes.outlines(live, 0)
                referenceSink = outlines
                outlines.sumOf { it.size / 2 }.toLong()
            }
            measure("mesh.finished_triangles.$count", 8) {
                val triangles = InkMeshes.triangles(stroke.shape, 0)
                referenceSink = triangles
                triangles.sumOf { it.triangleCount }.toLong()
            }
            check(!live.isUpdateNeeded())
            measure("mesh.noop_shape_update.$count", 64) {
                live.updateShape(count * 8L + 1000L)
                live.getInputCount().toLong()
            }
            live.clear()
        }
    }

    private fun archive() {
        val fixture = File("conformance/android/fixtures/matrix/synthetic.vive")
        check(fixture.isFile) { "Missing committed synthetic notebook: ${fixture.absolutePath}" }
        measure("archive.synthetic_open_and_read", 1, warmups = 2, samples = 7,
            details = mapOf("cache_policy" to "Warm OS file cache; includes checksum validation, ZIP extraction, SQLite and typed row materialization")) {
            ViveNotebook.open(fixture).use { notebook ->
                val pages = notebook.pageIds.map(notebook::page)
                referenceSink = pages
                val strokes = pages.sumOf { it.strokes.size }
                check(strokes > 0)
                strokes.toLong()
            }
        }
    }

    private fun longGestures() {
        for (family in listOf(ViveBrushes.MARKER, ViveBrushes.HIGHLIGHTER, ViveBrushes.calligraphy(3))) {
            val brush = ViveBrushes.brush(family, 0, 0xff202020.toInt(), 3f)
            // Warm the controller/raster path without prewarming the entire long workload.
            InkAuthoringController().use { controller ->
                controller.begin(brush, sample(0))
                repeat(128) { i -> controller.append(sample(i + 1)); controller.advance((i + 1) * 8L) }
                check(requireNotNull(controller.finish()).inputs.size == 129)
            }
            val event = BenchmarkPhase().apply { caseName = "live.$family"; begin() }
            val boundaries = listOf(64, 256, 1024, 4096, 8192)
            val renderer = InkPathRenderer()
            Surface.makeRasterN32Premul(512, 512).use { surface ->
                val canvas = surface.canvas.asComposeCanvas()
                InkAuthoringController().use { controller ->
                    controller.begin(brush, sample(0))
                    var start = 1
                    for (end in boundaries) {
                        val sampleCount = end - start + 1
                        val append = DoubleArray(sampleCount)
                        val advance = DoubleArray(sampleCount)
                        val draw = DoubleArray(sampleCount)
                        val frame = DoubleArray(sampleCount)
                        val gcBefore = gcCount()
                        val pathsBefore = renderer.pathBuildCount
                        val allocatedBefore = allocated()
                        val started = System.nanoTime()
                        for (i in start..end) {
                            val observation = sample(i)
                            val t0 = System.nanoTime()
                            check(controller.append(observation))
                            val t1 = System.nanoTime()
                            check(controller.advance(observation.uptimeMillis))
                            val t2 = System.nanoTime()
                            surface.canvas.clear(0)
                            check(renderer.draw(canvas, requireNotNull(controller.liveStroke)))
                            val t3 = System.nanoTime()
                            val index = i - start
                            append[index] = (t1 - t0) / 1e6
                            advance[index] = (t2 - t1) / 1e6
                            draw[index] = (t3 - t2) / 1e6
                            frame[index] = (t3 - t0) / 1e6
                        }
                        val total = (System.nanoTime() - started) / 1e6
                        val allocatedAfter = allocated()
                        val wet = requireNotNull(controller.liveStroke)
                        val vertices = (0 until wet.getOutlineCount(0)).sumOf { wet.getOutlineVertexCount(0, it) }
                        val triangles = (0 until wet.getMeshPartitionCount(0)).sumOf { wet.getRawTriangleIndexBuffer(0, it).remaining() / 3 }
                        val key = "live.$family.inputs_${start}_to_$end"
                        cases[key] = linkedMapOf(
                            "kind" to "gesture_window", "sample_count" to frame.size,
                            "gesture_inputs_at_end" to end + 1, "outline_vertices_at_end" to vertices,
                            "triangle_count_at_end" to triangles,
                            "append" to stats(append.toList()), "advance" to stats(advance.toList()), "draw" to stats(draw.toList()),
                            "frame" to stats(frame.toList()), "total_window_ms" to total,
                            "jvm_allocated_bytes_per_sample" to if (allocatedBefore >= 0 && allocatedAfter >= 0)
                                (allocatedAfter - allocatedBefore).toDouble() / frame.size else null,
                            "gc_collections" to gcCount() - gcBefore,
                            "path_builds_so_far" to renderer.pathBuildCount,
                            "path_builds_in_window" to renderer.pathBuildCount - pathsBefore,
                        )
                        println("$key frame P50=${percentile(frame.toList(), .5)}ms P95=${percentile(frame.toList(), .95)}ms vertices=$vertices")
                        start = end + 1
                    }
                    val finalStarted = System.nanoTime()
                    val finished = requireNotNull(controller.finish())
                    val finishMs = (System.nanoTime() - finalStarted) / 1e6
                    check(finished.inputs.size == 8193)
                    sink = sink xor finished.inputs.size.toLong()
                    cases["live.$family.finish_8193"] = mapOf("kind" to "single_finish", "ms" to finishMs,
                        "input_count" to finished.inputs.size, "policy" to "Includes native settle, input copy, full immutable Stroke rebuild and clear")
                }
            }
            renderer.clearCache()
            event.commit()
        }
    }

    private fun sample(i: Int) = InkPointerSample(16f + i * 0.04f, 220f + sin(i * 0.08).toFloat() * 60f, i * 8L)

    private fun measure(name: String, batch: Int, warmups: Int = 3, samples: Int = 9,
        details: Map<String, Any> = emptyMap(), operation: () -> Long) {
        val event = BenchmarkPhase().apply { caseName = name; begin() }
        repeat(warmups) { repeat(batch) { sink = sink xor operation() } }
        val times = ArrayList<Double>(samples)
        val allocations = ArrayList<Double>(samples)
        val gcBefore = gcCount()
        repeat(samples) {
            val allocatedBefore = allocated()
            val started = System.nanoTime()
            var checksum = 0L
            repeat(batch) { checksum += operation() }
            val duration = System.nanoTime() - started
            val allocatedAfter = allocated()
            sink = sink xor checksum
            times += duration.toDouble() / batch / 1e6
            if (allocatedBefore >= 0 && allocatedAfter >= 0) allocations += (allocatedAfter - allocatedBefore).toDouble() / batch
        }
        event.commit()
        cases[name] = linkedMapOf(
            "kind" to "repeated_operation", "warmup_batches" to warmups, "measurement_batches" to samples,
            "operations_per_batch" to batch, "ms_per_operation" to times, "timing" to stats(times),
            "jvm_allocated_bytes_per_operation" to allocations,
            "jvm_allocated_bytes_p50" to allocations.takeIf { it.isNotEmpty() }?.let { percentile(it, .5) },
            "gc_collections" to gcCount() - gcBefore, "details" to details,
        )
        println("$name P50=${percentile(times, .5)}ms allocation=${allocations.takeIf { it.isNotEmpty() }?.let { percentile(it, .5) }}B/op")
    }

    private fun allocated(): Long = allocation?.takeIf { it.isThreadAllocatedMemorySupported }?.getThreadAllocatedBytes(Thread.currentThread().threadId()) ?: -1L
    private fun gcCount(): Long = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) }
    private fun percentile(values: List<Double>, percentile: Double): Double = values.sorted()[
        (ceil(values.size * percentile).toInt() - 1).coerceIn(values.indices)
    ]
    private fun stats(values: List<Double>): Map<String, Any> = mapOf(
        "p50_ms" to percentile(values, .5), "p95_ms" to percentile(values, .95),
        "mean_ms" to values.average(), "min_ms" to values.min(), "max_ms" to values.max(),
    )
    private fun cpuModel(): String? = File("/proc/cpuinfo").takeIf(File::isFile)?.useLines { lines ->
        lines.firstOrNull { it.startsWith("model name") }?.substringAfter(':')?.trim()
    }
    private fun json(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to json(it.value) })
        is Iterable<*> -> JsonArray(value.map(::json))
        else -> error("Unsupported benchmark value ${value.javaClass.name}")
    }
}

@Name("com.vivenotes.byteink.BenchmarkPhase")
@Label("ByteInk benchmark phase")
internal class BenchmarkPhase : Event() {
    @Label("Case") @JvmField var caseName: String = ""
}
