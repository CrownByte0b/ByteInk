package com.vivenotes.byteink.nativeloader

import com.vivenotes.byteink.nativeloader.LoadedInkLibrary.Origin
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.security.MessageDigest

/**
 * Finds and loads the Ink native library: the one [libraryProperty] names, or else the one
 * [bundle] carries for [platform], extracted into the first of [locations] that works.
 *
 * Extraction is keyed by the library's SHA-256, so versions sit side by side and a verified file is
 * reused rather than rewritten. A missing or damaged file is written beside its final name and then
 * moved over it in one step, so concurrent processes and interrupted runs never see half a library.
 */
internal class NativeLibraryLoader(
    private val platform: Platform?,
    /** `os.name os.arch`, for messages. */
    private val platformName: String,
    private val libraryProperty: String?,
    private val locations: List<Location>,
    private val bundle: NativeBundle,
    private val systemLoad: (String) -> Unit,
) {

    /** A place to extract the bundled library into. */
    interface Location {
        /** The directory the library with this SHA-256 goes in; it need not exist yet. */
        fun directoryFor(sha256: String): Path

        /** Whether what is extracted here should be deleted when the JVM exits. */
        val temporary: Boolean get() = false
    }

    /** A cache that outlives the process: `<root>/<sha256>/`. */
    class CacheLocation(private val root: Path) : Location {
        override fun directoryFor(sha256: String): Path = root.resolve(sha256)
        override fun toString(): String = root.toString()
    }

    /** A new private temporary directory, for when no cache can be written. */
    object TemporaryLocation : Location {
        override val temporary: Boolean get() = true
        override fun directoryFor(sha256: String): Path =
            Files.createTempDirectory("byteink-ink-").also { it.toFile().deleteOnExit() }
        override fun toString(): String = "a new temporary directory"
    }

    fun load(): LoadedInkLibrary {
        libraryProperty?.let { return loadNamed(it) }
        val platform = platform ?: throw UnsatisfiedLinkError(
            "byteink bundles the Ink native library for Linux x86_64 and Windows x86_64, not for " +
                "$platformName. Build google/ink's //ink/jni:libink.so for this platform and name it " +
                "with -D${InkNativeLibrary.LIBRARY_PROPERTY}=<absolute path>.",
        )
        val library = bundle.library(platform) ?: throw UnsatisfiedLinkError(
            "This byteink ink-nativeloader jar carries no Ink native library for ${platform.id}. " +
                "Name one with -D${InkNativeLibrary.LIBRARY_PROPERTY}=<absolute path>.",
        )
        val attempts = mutableListOf<String>()
        val failures = mutableListOf<Throwable>()
        for (location in locations) {
            val file = try {
                extract(library, location)
            } catch (failure: IOException) {
                attempts += "$location: $failure"
                failures += failure
                continue
            }
            try {
                systemLoad(file.toString())
                return LoadedInkLibrary(file, library.sha256, Origin.BUNDLED)
            } catch (failure: UnsatisfiedLinkError) {
                // A file system mounted noexec, for example; another location may still work.
                attempts += "$file: ${failure.message}"
                failures += failure
            }
        }
        throw UnsatisfiedLinkError(
            "Could not load the Ink native library for ${platform.id}:\n" +
                attempts.joinToString("\n") { "- $it" } +
                "\nSet -D${InkNativeLibrary.CACHE_PROPERTY}=<directory> to extract it elsewhere, or " +
                "-D${InkNativeLibrary.LIBRARY_PROPERTY}=<absolute path> to load one installed with the application.",
        ).apply { failures.forEach(::addSuppressed) }
    }

    private fun loadNamed(name: String): LoadedInkLibrary {
        val file = try {
            Paths.get(name)
        } catch (invalid: InvalidPathException) {
            null
        }
        if (file == null || !file.isAbsolute || !Files.isRegularFile(file)) {
            throw UnsatisfiedLinkError(
                "-D${InkNativeLibrary.LIBRARY_PROPERTY} must name an existing file by absolute path, not \"$name\"",
            )
        }
        try {
            systemLoad(file.toString())
        } catch (failure: UnsatisfiedLinkError) {
            throw UnsatisfiedLinkError(
                "Could not load $file, named by -D${InkNativeLibrary.LIBRARY_PROPERTY}: ${failure.message}",
            ).apply { initCause(failure) }
        }
        return LoadedInkLibrary(file, Files.newInputStream(file).use { sha256(it, null) }, Origin.PROPERTY)
    }

    /** The bundled [library], verified, in [location]: reused if already there, written if not. */
    private fun extract(library: NativeBundle.Library, location: Location): Path {
        val directory = location.directoryFor(library.sha256)
        val target = directory.resolve(library.platform.fileName)
        if (verified(target, library.sha256)) return target
        Files.createDirectories(directory)
        if (location.temporary) target.toFile().deleteOnExit()
        val partial = Files.createTempFile(directory, library.platform.fileName, ".part")
        try {
            val written = open(library).use { input ->
                Files.newOutputStream(partial).use { output -> sha256(input, output) }
            }
            if (written != library.sha256) {
                throw UnsatisfiedLinkError(
                    "${library.resource} has SHA-256 $written, not ${library.sha256} as the jar's manifest " +
                        "says: the byteink ink-nativeloader jar is damaged",
                )
            }
            try {
                Files.move(partial, target, ATOMIC_MOVE)
            } catch (failure: IOException) {
                // Another process may have put the same library there first, and Windows keeps a
                // loaded library locked.
                if (!verified(target, library.sha256)) throw failure
            }
        } finally {
            Files.deleteIfExists(partial)
        }
        return target
    }

    private fun open(library: NativeBundle.Library): InputStream = try {
        bundle.open(library)
    } catch (missing: IllegalStateException) {
        throw UnsatisfiedLinkError(missing.message)
    }

    private fun verified(file: Path, sha256: String): Boolean =
        Files.isRegularFile(file) && Files.newInputStream(file).use { sha256(it, null) } == sha256

    companion object {
        /** The SHA-256 of what [input] holds, in lowercase hex, copying it to [output] on the way. */
        fun sha256(input: InputStream, output: OutputStream?): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                output?.write(buffer, 0, read)
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
