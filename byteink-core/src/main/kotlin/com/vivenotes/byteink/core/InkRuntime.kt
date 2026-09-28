package com.vivenotes.byteink.core

import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.nativeloader.NativeLoader
import com.vivenotes.byteink.nativeloader.InkNativeLibrary
import com.vivenotes.byteink.nativeloader.LoadedInkLibrary

/**
 * The Ink engine of this process, as byteink runs it: Google's AndroidX Ink classes on the native
 * library byteink's loader provides.
 */
public object InkRuntime {

    /**
     * Loads the native library the way Ink's own classes do, and checks that byteink's loader did
     * it. Google's `androidx.ink:ink-nativeloader` must not be on the classpath: its `NativeLoader`
     * would load Google's library on Linux instead, and nothing at all on Windows.
     *
     * @throws IllegalStateException if Google's `NativeLoader` shadows byteink's.
     * @throws UnsatisfiedLinkError if no library can be loaded, saying what was tried.
     */
    @OptIn(InkInternalOnlyApi::class)
    public fun load(): LoadedInkLibrary {
        NativeLoader.load()
        return InkNativeLibrary.loaded ?: throw IllegalStateException(
            "androidx.ink.nativeloader.NativeLoader comes from " +
                "${NativeLoader::class.java.protectionDomain?.codeSource?.location ?: "an unknown location"}, " +
                "not from byteink's ink-nativeloader: keep androidx.ink:ink-nativeloader off the classpath " +
                "by substituting byteink's for it (see byteink's README).",
        )
    }
}
