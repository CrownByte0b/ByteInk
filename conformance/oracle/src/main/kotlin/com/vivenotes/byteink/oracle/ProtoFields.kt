package com.vivenotes.byteink.oracle

import java.io.ByteArrayOutputStream

/** Just enough protobuf wire format to drop top-level fields from a serialized message. */
object ProtoFields {

    /** [message] with every top-level occurrence of [field] removed, all other bytes unchanged. */
    fun without(message: ByteArray, field: Int): ByteArray {
        val out = ByteArrayOutputStream(message.size)
        var at = 0
        while (at < message.size) {
            val start = at
            val (key, afterKey) = varint(message, at)
            at = afterKey
            at = when (val wireType = (key and 7L).toInt()) {
                0 -> varint(message, at).second
                1 -> at + 8
                2 -> varint(message, at).let { (length, afterLength) -> afterLength + Math.toIntExact(length) }
                5 -> at + 4
                else -> throw IllegalArgumentException("Unsupported wire type $wireType at byte $start")
            }
            require(at <= message.size) { "Truncated field at byte $start" }
            if ((key ushr 3).toInt() != field) out.write(message, start, at - start)
        }
        return out.toByteArray()
    }

    private fun varint(bytes: ByteArray, from: Int): Pair<Long, Int> {
        var value = 0L
        var shift = 0
        var at = from
        while (true) {
            require(at < bytes.size && shift < 64) { "Truncated varint at byte $from" }
            val byte = bytes[at++].toInt() and 0xff
            value = value or ((byte and 0x7f).toLong() shl shift)
            if (byte and 0x80 == 0) return value to at
            shift += 7
        }
    }
}
