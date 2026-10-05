package com.vivenotes.byteink.kit

import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import com.vivenotes.byteink.vive.AndroidInkCompressionNative

/** Pinned classic deflate parameters matching Android's ink-storage gzip writer. */
internal object AndroidInkCompression {
    init {
        InkNativeLibrary.load()
    }

    // JVMs can use either classic zlib or zlib-ng, whose output differs even at the same level.
    // The bundled native library already links pinned classic zlib; its narrow gzip extension
    // uses Android's compression/header parameters and is tested against large Android blobs.
    fun gzip(proto: ByteArray): ByteArray = AndroidInkCompressionNative.gzip(proto)
}
