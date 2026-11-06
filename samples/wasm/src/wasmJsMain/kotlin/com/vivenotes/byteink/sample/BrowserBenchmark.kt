@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package com.vivenotes.byteink.sample

import androidx.compose.ui.graphics.*
import com.vivenotes.byteink.compose.InkMeshRenderer
import com.vivenotes.byteink.wasm.*
import kotlinx.serialization.json.*
import kotlin.time.TimeSource

private fun benchmarkReport(value: String): Unit = js("{ globalThis.byteinkBenchmark = JSON.parse(value); }")

/** Opt-in deterministic workload; software frame timing includes readPixels, not presentation. */
internal fun runBrowserBenchmark(engine: InkWasm,engineLoadMillis: Double) {
    val brush=engine.catalogBrush("pressure-pen",2,0xff508090.toInt(),6f,.25f)
    val samples=List(120) { i -> InkSample(i.toFloat(),30f+(i%17),i*4.0,InkToolType.STYLUS,.1f+(i%9)*.1f) }
    val encoded=engine.createStroke(brush,samples).use { it.encodeAndroidInputs() }
    fun measure(batch: Int,block: ()->Unit): JsonObject {
        repeat(10) { repeat(batch) { block() } }
        val timings=List(40) {
            val start=TimeSource.Monotonic.markNow();repeat(batch) { block() }
            start.elapsedNow().inWholeNanoseconds/1e6/batch
        }.sorted()
        return buildJsonObject { put("batch",batch);put("p50Millis",timings[19]);put("p95Millis",timings[37])
            put("samplesMillis",JsonArray(timings.map(::JsonPrimitive))) }
    }
    val report=buildJsonObject {
        put("engineLoadMillis",engineLoadMillis);put("warmupBatches",10);put("measuredBatches",40);put("inputSamples",120)
        put("createAndRenderingSnapshot",measure(10) { engine.createStroke(brush,samples).use { it.rendering() } })
        put("decodeAndEncode",measure(10) { engine.decodeAndroidStroke(brush,encoded).use {
            check(it.encodeAndroidInputs().contentEquals(encoded)) } })
        put("liveGesture",measure(1) { engine.startStroke(brush).use { live ->
            samples.chunked(4).forEach { batch -> live.enqueue(batch);live.update(batch.last().elapsedTimeMillis);live.rendering() }
            live.finish(samples.last().elapsedTimeMillis).close()
        } })
        val bitmap=ImageBitmap(128,128);val canvas=Canvas(bitmap);val pixels=IntArray(128*128)
        val clear=Paint().apply { blendMode=BlendMode.Clear }
        try {
            InkMeshRenderer().use { renderer -> engine.createStroke(brush,samples).use { stroke ->
                put("cachedSoftwareFrameWithReadback",measure(10) {
                    canvas.drawRect(0f,0f,128f,128f,clear);renderer.draw(canvas,stroke);bitmap.readPixels(pixels)
                    check(pixels.any { it ushr 24 != 0 })
                })
            } }
        } finally { canvas.skiaCanvas.close();bitmap.asSkiaBitmap().close() }
    }
    benchmarkReport(report.toString())
}
