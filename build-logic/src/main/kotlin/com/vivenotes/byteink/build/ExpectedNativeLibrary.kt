package com.vivenotes.byteink.build

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import org.gradle.api.file.FileCollection
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.process.CommandLineArgumentProvider

/** The system property through which a test JVM learns which native library it should be running. */
const val EXPECTED_LIBRARY_PROPERTY = "byteink.expectedLibinkSha256"

/** Expects exactly [library], which must exist. */
class ExpectedLibraryFile(
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    val library: Provider<RegularFile>,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val file = library.get().asFile
        check(file.isFile) { "$file does not exist; build it with native/build-linux.sh" }
        return listOf("-D$EXPECTED_LIBRARY_PROPERTY=${file.inputStream().use(::sha256)}")
    }
}

/** Expects the first [resource] on [classpath], the one a class loader over it would find. */
class ExpectedLibraryResource(
    @get:Classpath
    val classpath: FileCollection,
    @get:Input
    val resource: String,
) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> {
        val digest = classpath.files.firstNotNullOfOrNull { entry -> entry.resource()?.let { sha256(it) } }
            ?: error("No $resource on the classpath")
        return listOf("-D$EXPECTED_LIBRARY_PROPERTY=$digest")
    }

    private fun File.resource(): InputStream? = when {
        isDirectory -> resolve(resource).takeIf { it.isFile }?.inputStream()
        isFile && name.endsWith(".jar") -> ZipFile(this).let { zip ->
            val entry = zip.getEntry(resource)
            if (entry == null) {
                zip.close()
                null
            } else {
                // Read fully so the jar can be closed straight away.
                zip.use { it.getInputStream(entry).readBytes() }.inputStream()
            }
        }
        else -> null
    }
}

private fun sha256(input: InputStream): String = input.use { stream ->
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(1 shl 16)
    while (true) {
        val read = stream.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
    }
    digest.digest().joinToString("") { "%02x".format(it) }
}
