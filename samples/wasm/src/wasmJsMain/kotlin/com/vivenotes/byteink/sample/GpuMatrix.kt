@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package com.vivenotes.byteink.sample

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeViewport
import com.vivenotes.byteink.compose.*
import com.vivenotes.byteink.wasm.*
import kotlinx.coroutines.await
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import kotlin.io.encoding.Base64
import kotlin.js.JsString
import kotlin.js.Promise

private fun gpuFixtures(): Promise<JsString> = js("fetch(new URL('rendering.json', document.baseURI)).then(r => { if (!r.ok) throw Error('Missing GPU controls'); return r.text(); })")
private fun gpuHost(): Unit = js("""{
    document.body.innerHTML = '<div id="gpu-surface" style="width:1792px;height:1152px"></div><p id="status"></p>';
}""")
private fun gpuReady(): Unit = js("{ globalThis.byteinkGpuReady = true; }")
private fun desktopReference(): Boolean = js("new URLSearchParams(location.search).get('reference') === '1'")
private fun gpuDispose(close: () -> Unit): Unit = js("{ window.addEventListener('pagehide', close, {once:true}); }")
private data class GpuControl(val name: String, val snapshot: InkRendering, val time: Long, val atlas: InkTextureAnimation?)

/** Actual Compose window/WebGL draws, separately from the ImageBitmap software reference checks. */
internal suspend fun showGpuMatrix(engine: InkWasm) {
    val data = Json.parseToJsonElement(gpuFixtures().await().toString()).jsonObject
    check(data.getValue("engine").jsonPrimitive.content == engine.engineCommit)
    val images = data.getValue("textures").jsonObject.mapValues { Image.makeFromEncoded(Base64.decode(it.value.jsonPrimitive.content)) }
    val controls = try { data.getValue("cases").jsonArray.map { element ->
        val item = element.jsonObject
        val name = item.getValue("name").jsonPrimitive.content
        val brush = InkBrush.fromFamilyProto(Base64.decode(item.getValue("family").jsonPrimitive.content),
            item.getValue("color").jsonPrimitive.int, item.getValue("size").jsonPrimitive.float,
            allowDevelopmentVersion = name.startsWith("atlas"))
        fun samples(values: JsonArray): List<InkSample> = values.map { value ->
            val p = value.jsonArray.map { it.jsonPrimitive.float }
            InkSample(p[0],p[1],p[2].toDouble(),InkToolType.STYLUS,p[3])
        }
        val inputs = samples(item.getValue("inputs").jsonArray)
        val predicted = item.getValue("predicted")
        val snapshot = if (predicted == JsonNull) engine.createStroke(brush,inputs).use { it.rendering() }
            else engine.startStroke(brush).use { live ->
                live.enqueue(inputs,samples(predicted.jsonArray)); live.update(item.getValue("update").jsonPrimitive.double); live.rendering()
            }
        item["offsets"]?.takeIf { it != JsonNull }?.jsonArray?.let { offsets ->
            snapshot.groups.single().meshes.forEachIndexed { m, mesh ->
                val values = offsets[m].jsonArray; check(values.size == mesh.vertexCount)
                for (i in values.indices) mesh.vertices[i*15+14] = values[i].jsonPrimitive.float
            }
        }
        val geometry = item.getValue("geometry").jsonArray
        check(geometry.size == snapshot.groups.size) { "$name coat count" }
        fun compare(actual: FloatArray, expected: JsonArray) {
            check(actual.size == expected.size) { "$name attribute count" }
            expected.forEachIndexed { i, element ->
                val value = element.jsonPrimitive.float
                check(kotlin.math.abs(actual[i]-value) <= .0001f + kotlin.math.abs(value)*.000001f) {
                    "$name canonical attribute $i: ${actual[i]} != $value"
                }
                if (desktopReference()) actual[i] = value
            }
        }
        snapshot.groups.forEachIndexed { g, group ->
            val reference = geometry[g].jsonObject
            val meshes = reference.getValue("meshes").jsonArray
            check(meshes.size == group.meshes.size) { "$name mesh count" }
            group.meshes.forEachIndexed { m, mesh ->
                val expected = meshes[m].jsonObject
                check(mesh.triangles.contentEquals(expected.getValue("triangles").jsonArray.map { it.jsonPrimitive.int }.toIntArray())) {
                    "$name triangle ordering"
                }
                compare(mesh.vertices,expected.getValue("vertices").jsonArray)
            }
            val outlines = reference.getValue("outlines").jsonArray
            check(outlines.size == group.outlines.size) { "$name outline count" }
            group.outlines.forEachIndexed { i, points -> compare(points,outlines[i].jsonArray) }
        }
        val atlas = item["atlas"]?.takeIf { it != JsonNull }?.jsonObject?.let { a ->
            InkTextureAnimation(a.getValue("frames").jsonPrimitive.int,a.getValue("rows").jsonPrimitive.int,
                a.getValue("columns").jsonPrimitive.int,a.getValue("duration").jsonPrimitive.long,a.getValue("reverse").jsonPrimitive.boolean)
        }
        GpuControl(name,snapshot,item.getValue("animation").jsonPrimitive.long,atlas)
    } } catch (error: Throwable) { images.values.forEach { it.close() }; throw error }
    check(controls.size == 63)
    var atlas: InkTextureAnimation? = null
    val renderer = InkMeshRenderer(InkTextureStore { images[it] }, cacheCapacity = 63, textureCacheCapacity = 1,
        textureAnimations = InkTextureAnimations { atlas })
    var disposed = false
    gpuHost()
    gpuDispose { disposed = true; renderer.close(); images.values.forEach { it.close() } }
    ComposeViewport(viewportContainerId = "gpu-surface") {
        Canvas(Modifier.fillMaxSize()) {
            if (disposed) return@Canvas
            drawRect(Color.Black)
            controls.forEachIndexed { index, control ->
                val x = (index % 7) * 256f; val y = (index / 7) * 128f
                drawRect(Color.White,Offset(x+128f,y),Size(128f,128f))
                atlas = control.atlas; renderer.animationTimeMillis = control.time
                for (background in 0..1) {
                    val canvas = drawContext.canvas
                    canvas.save()
                    try {
                        canvas.translate(x+background*128f,y)
                        canvas.clipRect(0f, 0f, 128f, 128f)
                        renderer.draw(canvas,control.snapshot)
                    }
                    finally { canvas.restore() }
                }
            }
            gpuReady()
        }
    }
}
