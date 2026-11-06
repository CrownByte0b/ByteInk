@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package com.vivenotes.byteink.sample

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import com.vivenotes.byteink.compose.*
import com.vivenotes.byteink.portable.InkTransform
import com.vivenotes.byteink.wasm.*
import kotlinx.coroutines.await
import kotlinx.serialization.json.*
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import org.jetbrains.skia.impl.use
import kotlin.io.encoding.Base64
import kotlin.js.Promise
import kotlin.js.JsString
import kotlin.math.abs

private fun fixture(): Promise<JsString> = js("fetch(new URL('rendering.json', document.baseURI)).then(response => { if (!response.ok) throw Error('Missing renderer fixture'); return response.text(); })")
private fun report(cases: Int, gap: Int): Unit = js("{ globalThis.byteinkRenderingChecks = {cases, maxPremultipliedChannelGap: gap}; }")
private fun diagnostic(name: String, gap: Int, count: Int, png: String): Unit = js("{ (globalThis.byteinkRenderDiagnostics ??= []).push({name, gap, count, png}); }")

/** The actual library renderer checks desktop controls in a browser. Not part of published libraries. */
internal suspend fun checkRendering(engine: InkWasm) {
    val data = Json.parseToJsonElement(fixture().await().toString()).jsonObject
    check(data.getValue("engine").jsonPrimitive.content == engine.engineCommit)
    val images = data.getValue("textures").jsonObject.mapValues { Image.makeFromEncoded(Base64.decode(it.value.jsonPrimitive.content)) }
    val bitmap = ImageBitmap(128, 128)
    val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
    val clear = Paint().apply { blendMode = BlendMode.Clear }
    val pixels = IntArray(128 * 128)
    var maxGap = 0
    var count = 0
    val failures = mutableListOf<String>()
    var atlas: InkTextureAnimation? = null
    fun clear() = canvas.drawRect(0f, 0f, 128f, 128f, clear)
    try {
        InkMeshRenderer(InkTextureStore { images[it] }, cacheCapacity = 2, textureCacheCapacity = 1,
            textureAnimations = InkTextureAnimations { atlas }).use { renderer ->
            for (element in data.getValue("cases").jsonArray) {
                val item = element.jsonObject
                val name = item.getValue("name").jsonPrimitive.content
                atlas = item["atlas"]?.takeIf { it != JsonNull }?.jsonObject?.let { a ->
                    InkTextureAnimation(a.getValue("frames").jsonPrimitive.int, a.getValue("rows").jsonPrimitive.int,
                        a.getValue("columns").jsonPrimitive.int, a.getValue("duration").jsonPrimitive.long, a.getValue("reverse").jsonPrimitive.boolean)
                }
                val brush = InkBrush.fromFamilyProto(Base64.decode(item.getValue("family").jsonPrimitive.content),
                    item.getValue("color").jsonPrimitive.int, item.getValue("size").jsonPrimitive.float, allowDevelopmentVersion = name.startsWith("atlas"))
                fun samples(values: JsonArray): List<InkSample> = values.map { entry ->
                    val p = entry.jsonArray.map { it.jsonPrimitive.float }
                    InkSample(p[0], p[1], p[2].toDouble(), InkToolType.STYLUS, p[3])
                }
                val inputs = samples(item.getValue("inputs").jsonArray)
                renderer.animationTimeMillis = item.getValue("animation").jsonPrimitive.long
                clear()
                val predicted = item.getValue("predicted")
                if (predicted == JsonNull) engine.createStroke(brush, inputs).use { stroke ->
                    val offsets = item["offsets"]
                    if (offsets != null && offsets != JsonNull) {
                        val snapshot = stroke.rendering()
                        snapshot.groups.single().meshes.forEachIndexed { meshIndex, mesh ->
                            val values = offsets.jsonArray[meshIndex].jsonArray
                            check(values.size == mesh.vertexCount)
                            for (i in values.indices) mesh.vertices[i * 15 + 14] = values[i].jsonPrimitive.float
                        }
                        check(renderer.draw(canvas, snapshot)) { name }
                        renderer.clearCache()
                    } else {
                        check(renderer.draw(canvas, stroke)) { name }
                        val before = renderer.snapshotCount
                        clear(); renderer.draw(canvas, stroke)
                        check(before == renderer.snapshotCount) { "Snapshot cache missed: $name" }
                        renderer.release(stroke)
                    }
                } else engine.startStroke(brush).use { live ->
                    live.enqueue(inputs, samples(predicted.jsonArray)); live.update(item.getValue("update").jsonPrimitive.double)
                    check(renderer.draw(canvas, live)) { name }
                    renderer.release(live)
                }
                bitmap.readPixels(pixels)
                var caseGap = 0
                var caseFailures = 0
                Image.makeFromEncoded(Base64.decode(item.getValue("png").jsonPrimitive.content)).use { reference ->
                    Bitmap.makeFromImage(reference).use { expected ->
                        for (i in pixels.indices) {
                            val actual = pixels[i]; val control = expected.getColor(i % 128, i / 128)
                            for (shift in listOf(24, 16, 8, 0)) {
                                val a = if (shift == 24) actual ushr 24 else (actual ushr shift and 255) * (actual ushr 24) / 255
                                val e = if (shift == 24) control ushr 24 else (control ushr shift and 255) * (control ushr 24) / 255
                                val gap = abs(a - e); maxGap = maxOf(maxGap, gap)
                                caseGap = maxOf(caseGap, gap)
                                if (gap > 2) caseFailures++
                            }
                        }
                    }
                }
                if (caseFailures > 0) {
                    failures += "$name max=$caseGap failedChannels=$caseFailures"
                    Image.makeFromBitmap(bitmap.asSkiaBitmap()).use { image -> image.encodeToData()!!.use { png ->
                        diagnostic(name, caseGap, caseFailures, Base64.encode(png.bytes))
                    } }
                }
                check(renderer.cachedShapeCount == 0)
                check(renderer.cachedGeometryBytes == 0L)
                count++
            }
            // Cache bounds, closed stroke rejection, clipping/alpha and path-only rendering.
            val brush = InkBrush(InkStockBrush.HIGHLIGHTER, 0x805070c0.toInt(), 12f)
            val inputs = listOf(InkSample(20f, 60f, 0.0), InkSample(100f, 60f, 100.0))
            engine.createStroke(brush, inputs).use { stroke ->
                clear(); renderer.draw(canvas, stroke, viewport = Rect(0f, 0f, 2f, 2f)); bitmap.readPixels(pixels)
                check(pixels.all { it == 0 })
                InkPathRenderer().use { paths ->
                    clear(); paths.draw(canvas, stroke); bitmap.readPixels(pixels)
                    val pathPixels = pixels.copyOf()
                    clear(); renderer.draw(canvas, stroke); bitmap.readPixels(pixels)
                    check(pathPixels.contentEquals(pixels)) { "DISCARD path/mesh pixels differ" }
                }
                val before = renderer.snapshotCount
                clear(); canvas.saveLayer(Rect(0f, 0f, 128f, 128f), Paint().apply { alpha = .5f })
                canvas.clipRect(Rect(0f, 0f, 64f, 128f))
                try { renderer.draw(canvas, stroke, InkTransform(offsetX = 2f)) } finally { canvas.restore() }
                bitmap.readPixels(pixels)
                check(pixels[60 * 128 + 40] ushr 24 in 63..65)
                check(pixels[60 * 128 + 80] == 0)
                check(before == renderer.snapshotCount)
                renderer.release(stroke)
                InkMeshRenderer(cacheByteBudget = 1).use { tiny ->
                    clear(); tiny.draw(canvas, stroke); check(tiny.cachedShapeCount == 0); check(tiny.cachedGeometryBytes == 0L)
                }
                val copy = stroke.copy(); copy.close()
                check(runCatching { renderer.draw(canvas, copy) }.isFailure)
            }
            renderer.clearCache()
            val opaque = InkBrush(size = 12f)
            val retained = List(3) { engine.createStroke(opaque, inputs) }
            try {
                retained.forEach { clear(); renderer.draw(canvas, it) }
                check(renderer.cachedShapeCount == 2)
                check(renderer.cachedGeometryBytes in 1..renderer.cacheByteBudget)
                val snapshot = retained.first().rendering()
                retained.first().close()
                clear(); renderer.draw(canvas, snapshot); bitmap.readPixels(pixels)
                check(pixels[60 * 128 + 40] ushr 24 == 255)
                renderer.release(snapshot)
            } finally { retained.forEach { it.close() }; renderer.clearCache() }
            // A replacement prediction must retire the old wet shape/shader data immediately.
            engine.startStroke(opaque).use { live ->
                live.enqueue(inputs.take(1) + InkSample(40f, 60f, 100.0), listOf(InkSample(112f, 60f, 200.0)))
                live.update(100.0); clear(); renderer.draw(canvas, live); bitmap.readPixels(pixels)
                check(pixels[60 * 128 + 100] ushr 24 > 0)
                live.enqueue(emptyList(), listOf(InkSample(40f, 110f, 200.0)))
                live.update(100.0); clear(); renderer.draw(canvas, live); bitmap.readPixels(pixels)
                check(pixels[60 * 128 + 100] == 0)
                renderer.release(live); check(renderer.cachedGeometryBytes == 0L)
            }
            // Opaque subtraction/split drops outlines; uniform paths must fill the triangle union.
            engine.createStroke(opaque, inputs).use { source ->
                engine.createStroke(InkBrush(size = 20f), listOf(InkSample(60f,20f,0.0), InkSample(60f,100f,100.0))).use { mask ->
                    val identity = floatArrayOf(1f,1f,0f,0f)
                    source.subtract(mask, identity).use { cut ->
                        val pieces = cut.split(identity)
                        try {
                            check(pieces.size == 2)
                            InkPathRenderer().use { paths ->
                                clear(); pieces.forEach { paths.draw(canvas, it) }; bitmap.readPixels(pixels)
                                check(pixels[60 * 128 + 60] == 0)
                                check(pixels[60 * 128 + 30] ushr 24 == 255 && pixels[60 * 128 + 90] ushr 24 == 255)
                            }
                        } finally { pieces.forEach { it.close() } }
                    }
                }
            }
            renderer.clearCache(); check(images.values.none { it.isClosed })
        }
        report(count, maxGap)
        check(failures.isEmpty()) { failures.joinToString() }
    } finally { images.values.forEach { it.close() }; bitmap.asSkiaBitmap().close() }
}
