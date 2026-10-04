package com.vivenotes.byteink.kit

import androidx.ink.strokes.StrokeInputBatch

// These internal class names bind the shipped byteink.3 native exports. The public storage API
// lives in com.vivenotes.byteink.kit; retaining these JNI names keeps both native bundles compatible.
internal object AndroidInkCompressionNative {
    @JvmStatic
    external fun gzip(proto: ByteArray): ByteArray
}

internal object InkInputCodecNative {
    // A typed JNI local reference keeps the owner alive through serialization.
    @JvmStatic external fun encode(inputs: StrokeInputBatch): ByteArray

    // Called only inside wrapNative's allocator, so failures cannot orphan a decoded native peer.
    @JvmStatic external fun createFromProto(proto: ByteArray, size: Int): Long
}
