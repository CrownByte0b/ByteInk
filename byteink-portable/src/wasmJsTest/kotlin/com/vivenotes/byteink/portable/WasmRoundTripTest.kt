@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package com.vivenotes.byteink.portable

import com.vivenotes.byteink.kit.StoredInkStroke
import com.vivenotes.byteink.wasm.InkBrush
import com.vivenotes.byteink.wasm.InkSample as WasmSample
import kotlinx.coroutines.await
import kotlin.js.JsString
import kotlin.js.Promise
import kotlin.test.assertContentEquals

private fun roundTripPhase(): String = js("process.env.BYTEINK_ROUND_TRIP_PHASE || 'local'")
private fun runtime(): String = js("JSON.stringify({node:process.version,v8:process.versions.v8,platform:process.platform,arch:process.arch})")
private fun engineUrl(): String = js("process.env.BYTEINK_WASM_ENGINE")
private fun readExchange(name: String): Promise<JsString> = js("import('node:fs/promises').then(fs=>fs.readFile(process.env.BYTEINK_ROUND_TRIP_DIRECTORY+'/'+name,'utf8'))")
private fun writeExchange(name: String,text: String): Promise<JsString> = js("import('node:fs/promises').then(async fs=>{await fs.mkdir(process.env.BYTEINK_ROUND_TRIP_DIRECTORY,{recursive:true});await fs.writeFile(process.env.BYTEINK_ROUND_TRIP_DIRECTORY+'/'+name,text);return 'ok';})")

class WasmRoundTripTest : RoundTripContract() {
    private lateinit var backend: WasmInkEngine
    override val phase: String = roundTripPhase()
    override val runtimeDescription: String = runtime()
    override suspend fun engine(): InkEngine = WasmInkEngine.load(engineUrl()).also { backend=it }
    override suspend fun read(name: String): String = readExchange(name).await().toString()
    override suspend fun write(name: String,text: String) { writeExchange(name,text).await() }
    override fun additionalRows(kit: InkKit): List<StoredInkStroke> = backend.wasm.createStroke(InkBrush(size=4f),
        listOf(WasmSample(10f,450f,0.0),WasmSample(30f,450f,.25),WasmSample(60f,450f,.75))).use { stroke ->
        // The portable convenience input getter has millisecond Longs, but storage must preserve
        // the original native sub-ms observations when loading and re-encoding browser strokes.
        val bytes=stroke.encodeAndroidInputs()
        kit.engine.decodeStroke(InkStyle(),bytes).use { portable ->
            assertContentEquals(bytes,portable.encodeInputs(),"Preserve fractional browser times in native storage")
            listOf(kit.encodeStroke(portable,"wasm-fractional","exchange",30,"marker",0,null,9007199254744995L))
        }
    }
}
