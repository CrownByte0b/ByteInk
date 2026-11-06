package com.vivenotes.byteink.portable

import java.io.File

class JvmRoundTripTest : RoundTripContract() {
    override val phase: String = System.getProperty("byteink.test.roundTripPhase","local")
    override val runtimeDescription: String = listOf("java.runtime.version","java.vm.name","os.name","os.arch")
        .joinToString { "$it=${System.getProperty(it)}" }
    override suspend fun engine(): InkEngine = JvmInkEngine()
    override suspend fun read(name: String): String = file(name).readText()
    override suspend fun write(name: String,text: String) { file(name).apply { parentFile.mkdirs(); writeText(text) } }
    private fun file(name: String): File = File(System.getProperty("byteink.test.roundTripDirectory"),name)
}
