@file:OptIn(androidx.ink.nativeloader.InkInternalOnlyApi::class)

package com.vivenotes.byteink.testing

import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.core.InkMeshes
import com.vivenotes.byteink.core.InkRuntime
import com.vivenotes.byteink.vive.ViveBrushes
import java.io.File
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.*
import org.jetbrains.skia.*
import kotlin.math.sin

/** Engineering control: prepared Ink outlines; finite batch means, not JMH confidence intervals.
 * Direct Skiko construction excludes Compose snapshot/swap/builder reconstruction. Timings are
 * elapsed wall time (including GC/scheduling), not CPU time or display latency. Run serial forks. */
object InkPathConstructionBenchmark {
    @Volatile private var pathSink: Path? = null
    @Volatile private var checksum: Long = 0

    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 1) { "InkPathConstructionBenchmark <output.json>" }
        val cases = linkedMapOf<String, JsonElement>()
        val report = buildJsonObject {
            put("schema", 1); put("started_utc", Instant.now().toString())
            put("java", System.getProperty("java.runtime.version"))
            put("java_vendor", System.getProperty("java.vendor"))
            put("java_vm", System.getProperty("java.vm.name"))
            put("os", System.getProperty("os.name")); put("os_version", System.getProperty("os.version"))
            put("architecture", System.getProperty("os.arch")); put("cpu_count", Runtime.getRuntime().availableProcessors())
            put("native_sha256", InkRuntime.load().sha256); put("skiko", "0.150.1")
            put("max_heap_bytes", Runtime.getRuntime().maxMemory())
            put("java_arguments", JsonArray(ManagementFactory.getRuntimeMXBean().inputArguments.map(::JsonPrimitive)))
            put("gc", JsonArray(ManagementFactory.getGarbageCollectorMXBeans().map { JsonPrimitive(it.name) }))
            put("scope", "Prepared Ink outlines; fresh WINDING builder, detach, consumed point count and deterministic path/builder close. Raster: clear + draw already-built path on 512x512 CPU surface; readback outside timing.")
            put("limitations", "Fixed case order and finite batch means; no confidence intervals, native allocation accounting, Compose materialization, compositor/vsync or physical latency. No geometry/extraction inside construction timing.")
        }.toMutableMap()
        try {
            val workloads = listOf(Triple("marker_1024", ViveBrushes.MARKER, 1024),
                Triple("marker_8192", ViveBrushes.MARKER, 8192), Triple("calligraphy3_8192", ViveBrushes.calligraphy(3), 8192))
            for ((name, family, count) in workloads) {
                val inputs = MutableStrokeInputBatch().apply {
                    repeat(count) { i -> add(InputToolType.MOUSE, 16f + i * .04f,
                        220f + sin(i * .08).toFloat() * 60f, i * 8L) }
                }
                val stroke = Stroke(ViveBrushes.brush(family, 0, 0xff202020.toInt(), 3f), inputs)
                val outlines = InkMeshes.outlines(stroke.shape, 0)
                check(outlines.any { it.isNotEmpty() })
                build(outlines, false).use { scalar -> build(outlines, true).use { bulk ->
                    check(scalar.fillMode == PathFillMode.WINDING && bulk.fillMode == PathFillMode.WINDING)
                    check(scalar.verbs.contentEquals(bulk.verbs) && scalar.points.contentEquals(bulk.points)) {
                        "Scalar/bulk point or verb mismatch: $name"
                    }
                    Paint().use { paint -> Surface.makeRasterN32Premul(512, 512).use { surface ->
                        paint.color = 0xff202020.toInt(); paint.isAntiAlias = true; paint.mode = PaintMode.FILL
                        fun raster(path: Path): ByteArray {
                            surface.canvas.clear(0); surface.canvas.drawPath(path, paint)
                            return surface.makeImageSnapshot().use { image -> Bitmap.makeFromImage(image).use { bitmap ->
                                requireNotNull(bitmap.readPixels())
                            } }
                        }
                        val pixels = raster(scalar)
                        check(pixels.any { it != 0.toByte() } && pixels.contentEquals(raster(bulk))) { "Pixel mismatch: $name" }
                        cases["$name.geometry"] = buildJsonObject {
                            put("input_count", count); put("outline_count", outlines.size)
                            put("outline_vertices", outlines.sumOf { it.size / 2 }); put("points", scalar.pointsCount)
                            put("verbs", scalar.verbsCount); put("geometric_equality", true); put("pixel_equality", true)
                            put("pixel_sha256", MessageDigest.getInstance("SHA-256").digest(pixels).joinToString("") { "%02x".format(it) })
                        }
                        for (bulkMode in listOf(false, true)) {
                            val method = if (bulkMode) "add_poly" else "scalar_commands"
                            cases["$name.build.$method"] = measure(8) {
                                build(outlines, bulkMode).use { path -> pathSink = path; path.pointsCount.toLong() }
                            }
                        }
                        cases["$name.raster_prebuilt"] = measure(16) {
                            surface.canvas.clear(0); surface.canvas.drawPath(bulk, paint)
                            pathSink = bulk; bulk.pointsCount.toLong()
                        }
                        check(pixels.contentEquals(raster(bulk)))
                    } }
                } }
            }
            report["status"] = JsonPrimitive("passed")
        } catch (failure: Throwable) {
            report["status"] = JsonPrimitive("failed")
            report["failure"] = JsonPrimitive("${failure.javaClass.name}: ${failure.message}")
            throw failure
        } finally {
            pathSink = null; report["finished_utc"] = JsonPrimitive(Instant.now().toString())
            report["checksum"] = JsonPrimitive(checksum); report["cases"] = JsonObject(cases)
            val output = File(args.single()).absoluteFile; output.parentFile.mkdirs()
            output.writeText(Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), JsonObject(report)))
            println("Path construction benchmark: ${output.path}")
        }
    }

    private fun build(outlines: List<FloatArray>, bulk: Boolean): Path = PathBuilder(PathFillMode.WINDING).use { b ->
        for (p in outlines) if (p.isNotEmpty()) {
            if (bulk) b.addPoly(p, true) else {
                b.moveTo(p[0], p[1]); for (i in 2 until p.size step 2) b.lineTo(p[i], p[i + 1]); b.closePath()
            }
        }
        b.detach()
    }

    private fun measure(batch: Int, operation: () -> Long): JsonObject {
        repeat(4) { repeat(batch) { checksum = checksum xor operation() } }
        val raw = DoubleArray(11)
        val gcBefore = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) }
        for (sample in raw.indices) {
            var value = 0L; val started = System.nanoTime()
            repeat(batch) { value += operation() }
            raw[sample] = (System.nanoTime() - started).toDouble() / batch / 1e6; checksum = checksum xor value
        }
        return buildJsonObject {
            put("warmup_batches", 4); put("measurement_batches", raw.size); put("operations_per_batch", batch)
            put("batch_mean_ms_per_operation", JsonArray(raw.map(::JsonPrimitive)))
            put("median_batch_mean_ms", raw.sorted()[raw.size / 2]); put("min_batch_mean_ms", raw.min()); put("max_batch_mean_ms", raw.max())
            put("gc_collections", ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionCount.coerceAtLeast(0) } - gcBefore)
        }
    }
}
