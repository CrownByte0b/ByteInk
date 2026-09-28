package com.vivenotes.byteink.build

import java.io.InputStream
import java.security.MessageDigest

/** The SHA-256 of everything [input] holds, in lowercase hex; closes [input]. */
internal fun sha256(input: InputStream): String = input.use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
