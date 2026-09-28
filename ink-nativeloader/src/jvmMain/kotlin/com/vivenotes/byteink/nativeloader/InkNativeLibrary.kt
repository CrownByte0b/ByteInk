package com.vivenotes.byteink.nativeloader

import java.nio.file.Path
import java.nio.file.Paths

/**
 * The Ink native library of this process. AndroidX Ink loads it through `NativeLoader.load()` the
 * first time any of its classes needs native code; [load] does the same explicitly.
 *
 * By default it is the library this jar bundles for the platform — Linux x86_64 or Windows x86_64 —
 * extracted once into a per-user cache and loaded from there:
 * - Linux: `$XDG_CACHE_HOME/byteink/natives/<sha256>/libink.so`, or under `~/.cache`.
 * - Windows: `%LOCALAPPDATA%\byteink\natives\<sha256>\ink.dll`.
 *
 * If that cache cannot be written, a new private temporary directory is used instead.
 * [CACHE_PROPERTY] names another cache, and [LIBRARY_PROPERTY] a library to load in place of the
 * bundled one.
 */
public object InkNativeLibrary {

    /**
     * System property naming, by absolute path, the Ink native library to load instead of the
     * bundled one: a copy installed with a packaged application, a build for another platform, or
     * another build under test.
     */
    public const val LIBRARY_PROPERTY: String = "byteink.ink.library"

    /** System property naming the directory to extract the bundled library into. */
    public const val CACHE_PROPERTY: String = "byteink.ink.cache"

    @Volatile
    private var library: LoadedInkLibrary? = null

    /** The library this class loader has loaded, or null before [load]. */
    public val loaded: LoadedInkLibrary?
        get() = library

    /**
     * Loads the library, unless it is loaded already, and returns it. Safe to call from any thread;
     * the JVM loads it once.
     *
     * @throws UnsatisfiedLinkError if no library can be loaded, saying what was tried.
     */
    public fun load(): LoadedInkLibrary {
        library?.let { return it }
        synchronized(this) {
            library?.let { return it }
            return fromSystem().load().also { library = it }
        }
    }

    /** A loader configured by this JVM's system properties and environment. */
    internal fun fromSystem(): NativeLibraryLoader {
        val osName = System.getProperty("os.name").orEmpty()
        val osArch = System.getProperty("os.arch").orEmpty()
        val platform = Platform.of(osName, osArch)
        val cache = System.getProperty(CACHE_PROPERTY)
        val locations = when {
            cache != null -> listOf(NativeLibraryLoader.CacheLocation(Paths.get(cache)))
            platform != null -> listOf(
                NativeLibraryLoader.CacheLocation(userCache(platform, System::getenv, System.getProperty("user.home"))),
                NativeLibraryLoader.TemporaryLocation,
            )
            else -> emptyList()
        }
        return NativeLibraryLoader(
            platform = platform,
            platformName = "$osName $osArch",
            libraryProperty = System.getProperty(LIBRARY_PROPERTY),
            locations = locations,
            bundle = NativeBundle.ofClassPath(),
            systemLoad = System::load,
        )
    }

    /** Where this user's byteink native libraries are cached on [platform]. */
    internal fun userCache(platform: Platform, environment: (String) -> String?, userHome: String): Path {
        fun absolute(variable: String): Path? =
            environment(variable)?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }?.takeIf { it.isAbsolute }
        val base = when (platform) {
            // The XDG base directory spec: ignore a relative or empty value.
            Platform.LINUX_X86_64 -> absolute("XDG_CACHE_HOME") ?: Paths.get(userHome, ".cache")
            Platform.WINDOWS_X86_64 -> absolute("LOCALAPPDATA") ?: Paths.get(userHome, "AppData", "Local")
        }
        return base.resolve("byteink").resolve("natives")
    }
}

/** An Ink native library loaded into this JVM. */
public class LoadedInkLibrary internal constructor(
    /** The file the JVM loaded. */
    public val path: Path,
    /** That file's SHA-256, in lowercase hex. */
    public val sha256: String,
    /** Where the library came from. */
    public val origin: Origin,
) {
    /** Where a loaded library came from. */
    public enum class Origin {
        /** The library this jar bundles for the platform. */
        BUNDLED,

        /** The file [InkNativeLibrary.LIBRARY_PROPERTY] named. */
        PROPERTY,
    }

    override fun toString(): String = "$path (${origin.name.lowercase()}, SHA-256 $sha256)"
}
