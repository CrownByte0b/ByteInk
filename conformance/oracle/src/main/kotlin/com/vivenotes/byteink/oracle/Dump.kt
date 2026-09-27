package com.vivenotes.byteink.oracle

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.security.MessageDigest
import java.util.TreeMap

/**
 * Engine results as sorted `case<TAB>key<TAB>value` lines under `# name=value` header lines. Every
 * value is canonical text — floats as their raw bits, many values as a count and a digest — so two
 * runs agree bit for bit exactly when their text agrees.
 *
 * A detailed dump keeps only the cases whose names start with [detail], and writes their buffers in
 * full instead of as digests, for measuring how two runs differ.
 */
class Dump(private val detail: String? = null) {
    private val values = TreeMap<String, String>()

    operator fun set(case: String, key: String, value: Any) {
        require('\t' !in case && '\t' !in key) { "Tabs separate fields" }
        if (detail != null && !case.startsWith(detail)) return
        val previous = values.put("$case\t$key", value.toString())
        require(previous == null) { "$case $key is recorded twice" }
    }

    /** A digest, or in a detailed dump the values themselves. */
    fun digest(): Digest = Digest(full = detail != null)

    val size: Int get() = values.size

    fun write(file: File, header: Map<String, String>) {
        file.parentFile?.mkdirs()
        file.bufferedWriter().use { out ->
            header.forEach { (name, value) -> out.write("# $name=$value\n") }
            values.forEach { (key, value) -> out.write("$key\t$value\n") }
        }
    }

    /** A dump read back: its header, and its values keyed by `case<TAB>key`. */
    class Contents(val header: Map<String, String>, val values: Map<String, String>)

    companion object {
        fun read(file: File): Contents {
            val header = linkedMapOf<String, String>()
            val values = linkedMapOf<String, String>()
            file.forEachLine { line ->
                if (line.startsWith("# ")) {
                    header[line.substring(2).substringBefore('=')] = line.substringAfter('=')
                } else if (line.isNotEmpty()) {
                    values[line.substringBeforeLast('\t')] = line.substringAfterLast('\t')
                }
            }
            return Contents(header, values)
        }
    }
}

/**
 * Floats kept whole, as `f32 n=<count> <hex>` of their little-endian bits: equal text is the same
 * floats, and a comparison can also measure how far apart two runs' floats are.
 */
class Floats {
    private val bytes = java.io.ByteArrayOutputStream()
    private val scratch = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
    private var count = 0

    fun add(value: Float): Floats {
        bytes.write(scratch.clear().putFloat(value).array(), 0, 4)
        count++
        return this
    }

    /** Every float in [buffer], read in the machine's (little-endian) order whatever its own order says. */
    fun add(buffer: ByteBuffer): Floats {
        val floats = buffer.duplicate().order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        while (floats.hasRemaining()) add(floats.get())
        return this
    }

    override fun toString(): String = "f32 n=$count " + bytes.toByteArray().joinToString("") { "%02x".format(it) }

    companion object {
        fun of(vararg values: Float): Floats = Floats().apply { values.forEach { add(it) } }

        /** The floats of a `f32` value, or null for any other value. */
        fun parse(value: String): FloatArray? {
            if (!value.startsWith("f32 ")) return null
            val hex = value.substringAfterLast(' ')
            val bytes = ByteBuffer.wrap(ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
                .order(ByteOrder.LITTLE_ENDIAN)
            return FloatArray(bytes.remaining() / 4) { bytes.getFloat() }
        }
    }
}

/**
 * Many values reduced to how many there were and a SHA-256 of their little-endian bytes — or, when
 * [full], those bytes themselves in hex.
 */
class Digest(private val full: Boolean = false) {
    private val sha = MessageDigest.getInstance("SHA-256")
    private val bytes = if (full) java.io.ByteArrayOutputStream() else null
    private val scratch = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
    private var count = 0

    private fun update(data: ByteArray, length: Int = data.size) {
        sha.update(data, 0, length)
        bytes?.write(data, 0, length)
    }

    fun add(value: Float): Digest = add(value.toRawBits())

    fun add(value: Int): Digest {
        update(scratch.clear().putInt(value).array(), 4)
        count++
        return this
    }

    fun add(buffer: ByteBuffer): Digest {
        val copy = buffer.duplicate()
        count += copy.remaining()
        update(ByteArray(copy.remaining()).also { copy.get(it) })
        return this
    }

    fun add(shorts: ShortBuffer): Digest {
        val copy = shorts.duplicate()
        while (copy.hasRemaining()) {
            update(scratch.clear().putShort(copy.get()).array(), 2)
            count++
        }
        return this
    }

    fun add(data: ByteArray): Digest {
        update(data)
        count += data.size
        return this
    }

    private val result by lazy {
        val shown = bytes?.toByteArray()?.joinToString("", prefix = "hex=") { "%02x".format(it) }
            ?: "sha256=${sha.digest().joinToString("") { "%02x".format(it) }}"
        "n=$count $shown"
    }

    /** The count and digest; nothing may be added afterwards. */
    override fun toString(): String = result
}
