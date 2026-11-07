@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
package com.vivenotes.byteink.portable

import kotlinx.coroutines.await
import kotlin.js.Promise

private fun engineUrl(): String = js("process.env.BYTEINK_WASM_ENGINE")
private fun readFixture(): Promise<kotlin.js.JsString> = js("import('node:fs/promises').then(fs => fs.readFile(process.env.BYTEINK_KIT_FIXTURE, 'utf8'))")

class WasmInkKitTest : InkKitContract() {
    override suspend fun engine(): InkEngine = WasmInkEngine.load(engineUrl())
    override suspend fun fixture(): String = readFixture().await().toString()
}
