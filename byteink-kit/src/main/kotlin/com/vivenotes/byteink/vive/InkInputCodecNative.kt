package com.vivenotes.byteink.vive

import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import com.vivenotes.byteink.nativeloader.InkNativeLibrary

/** Raw protobuf access to the pinned engine, with its normal native-peer ownership. */
@OptIn(InkInternalOnlyApi::class)
internal object InkInputCodec {
    init { InkNativeLibrary.load() }

    fun encode(inputs: StrokeInputBatch): ByteArray = InkInputCodecNative.encode(inputs)

    fun decode(proto: ByteArray, size: Int): ImmutableStrokeInputBatch =
        ImmutableStrokeInputBatch.wrapNative { InkInputCodecNative.createFromProto(proto, size) }
}

internal object InkInputCodecNative {
    // A typed JNI local reference keeps the owner alive through serialization.
    @JvmStatic external fun encode(inputs: StrokeInputBatch): ByteArray

    // Called only inside wrapNative's allocator, so failures cannot orphan a decoded native peer.
    @JvmStatic external fun createFromProto(proto: ByteArray, size: Int): Long
}
