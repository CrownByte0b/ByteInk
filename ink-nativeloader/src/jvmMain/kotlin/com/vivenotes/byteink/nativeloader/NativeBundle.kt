package com.vivenotes.byteink.nativeloader

import java.io.InputStream
import java.util.Properties

/**
 * The Ink native libraries a jar carries: `<platform>/<file name>` resources under [BASE], listed
 * with their SHA-256 in [MANIFEST], which the build writes beside them.
 */
internal class NativeBundle(private val resources: (String) -> InputStream?) {

    /** One platform's library as the manifest records it. */
    class Library(val platform: Platform, val resource: String, val sha256: String)

    private val manifest: Properties? by lazy {
        resources("$BASE/$MANIFEST")?.use { stream -> Properties().apply { load(stream) } }
    }

    /** The library bundled for [platform], or null if this jar carries none for it. */
    fun library(platform: Platform): Library? {
        val sha256 = manifest?.getProperty("${platform.id}.sha256") ?: return null
        return Library(platform, "$BASE/${platform.id}/${platform.fileName}", sha256)
    }

    /** The library's bytes, as bundled. */
    fun open(library: Library): InputStream =
        resources(library.resource) ?: throw IllegalStateException("The manifest lists ${library.resource}, but the jar lacks it")

    companion object {
        const val BASE = "com/vivenotes/byteink/nativeloader"
        const val MANIFEST = "natives.properties"

        /** The libraries of the jar (or directory) this class came from, and whatever precedes it on its class path. */
        fun ofClassPath(): NativeBundle {
            val loader = NativeBundle::class.java.classLoader ?: ClassLoader.getSystemClassLoader()
            return NativeBundle(loader::getResourceAsStream)
        }
    }
}
