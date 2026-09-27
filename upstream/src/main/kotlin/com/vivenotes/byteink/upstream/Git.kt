package com.vivenotes.byteink.upstream

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture

/**
 * Runs read-only git commands in [directory], with options that keep their output parseable
 * whatever the user's configuration says (signature display, colour).
 */
internal class Git(private val directory: File) {

    fun run(vararg arguments: String): String {
        val process = ProcessBuilder(command(*arguments)).start()
        process.outputStream.close()
        val errors = CompletableFuture.supplyAsync { process.errorStream.readBytes().decodeToString() }
        val output = process.inputStream.readBytes().decodeToString()
        val exitCode = process.waitFor()
        check(exitCode == 0) {
            "git ${arguments.joinToString(" ")} failed with exit code $exitCode: ${errors.get().trim()}"
        }
        return output
    }

    /** Reads many blobs through one `git cat-file --batch` process. */
    fun blobReader(): BlobReader = BlobReader(ProcessBuilder(command("cat-file", "--batch"))
        .redirectError(ProcessBuilder.Redirect.INHERIT)
        .start())

    private fun command(vararg arguments: String): List<String> = listOf(
        "git", "-C", directory.path,
        "-c", "log.showSignature=false",
        "-c", "color.ui=false",
    ) + arguments

    class BlobReader(private val process: Process) : AutoCloseable {
        private val responses = BufferedInputStream(process.inputStream)
        private val requests = process.outputStream

        fun read(objectId: String): ByteArray {
            requests.write("$objectId\n".toByteArray())
            requests.flush()
            // "<oid> <type> <size>\n<contents>\n", or "<oid> missing\n".
            val header = responses.readLine().split(' ')
            check(header.size == 3) { "git cat-file could not read $objectId: ${header.joinToString(" ")}" }
            val contents = responses.readNBytes(header[2].toInt())
            responses.read()
            return contents
        }

        override fun close() {
            requests.close()
            process.waitFor()
        }

        private fun InputStream.readLine(): String {
            val line = ByteArrayOutputStream()
            while (true) {
                val byte = read()
                check(byte >= 0) { "git cat-file ended unexpectedly" }
                if (byte == '\n'.code) return line.toString(Charsets.UTF_8)
                line.write(byte)
            }
        }
    }
}
