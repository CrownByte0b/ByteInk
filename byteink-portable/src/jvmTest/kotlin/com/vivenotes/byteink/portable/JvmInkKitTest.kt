package com.vivenotes.byteink.portable

class JvmInkKitTest : InkKitContract() {
    override suspend fun engine(): InkEngine = JvmInkEngine()
    override suspend fun fixture(): String = java.io.File(System.getProperty("byteink.test.kitFixture")).readText()
}
