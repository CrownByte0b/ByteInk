package com.vivenotes.byteink.kit

import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.strokes.ImmutableStrokeInputBatch
import androidx.ink.strokes.StrokeInputBatch
import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import com.vivenotes.byteink.vive.InkInputCodecNative

/** Raw protobuf access to the pinned engine, with its normal native-peer ownership. */
@OptIn(InkInternalOnlyApi::class)
internal object InkInputCodec {
    init { InkNativeLibrary.load() }

    fun encode(inputs: StrokeInputBatch): ByteArray = InkInputCodecNative.encode(inputs)

    fun decode(proto: ByteArray, size: Int): ImmutableStrokeInputBatch =
        ImmutableStrokeInputBatch.wrapNative { InkInputCodecNative.createFromProto(proto, size) }
}
