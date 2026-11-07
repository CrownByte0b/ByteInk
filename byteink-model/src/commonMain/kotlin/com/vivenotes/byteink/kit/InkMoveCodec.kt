package com.vivenotes.byteink.kit

/** The shared Android lasso wire format: a little-endian Int count and Float coordinate pairs. */
public object InkMoveCodec {
    public const val ENCODING: String = "ink/lasso-f32le1"

    public fun encodePath(path: List<InkPoint>): ByteArray {
        require(path.size >= 3 && path.size <= (Int.MAX_VALUE - 4) / 8) { "Invalid lasso point count" }
        require(path.all { it.x.isFinite() && it.y.isFinite() }) { "Lasso points must be finite" }
        val bytes = ByteArray(4 + path.size * 8)
        fun put(at: Int, bits: Int) { for (i in 0..3) bytes[at + i] = (bits ushr (8 * i)).toByte() }
        put(0, path.size)
        path.forEachIndexed { i, point -> put(4 + i * 8, point.x.toRawBits()); put(8 + i * 8, point.y.toRawBits()) }
        return bytes
    }

    public fun decodePath(bytes: ByteArray): List<InkPoint>? {
        if (bytes.size < 28 || (bytes.size - 4) % 8 != 0) return null
        fun int(at: Int): Int = (0..3).fold(0) { value, i -> value or ((bytes[at + i].toInt() and 255) shl (8 * i)) }
        val count = int(0)
        if (count != (bytes.size - 4) / 8) return null
        val points = List(count) { i -> InkPoint(Float.fromBits(int(4 + i * 8)), Float.fromBits(int(8 + i * 8))) }
        return points.takeIf { it.all { point -> point.x.isFinite() && point.y.isFinite() } }
    }
}
