package com.vivenotes.byteink.nativeloader

import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.nativeloader.NativeLoader
import androidx.ink.nativeloader.NativePointer
import androidx.ink.nativeloader.StatusNative
import androidx.ink.nativeloader.testing.awaitNativePointerCleanupAfter
import com.vivenotes.byteink.nativeloader.LoadedInkLibrary.Origin
import java.nio.file.Paths
import java.util.Properties
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.BeforeClass

/**
 * The real library, loaded into this JVM: the only test class that loads it, so the first load
 * happens here, from many threads at once.
 */
@OptIn(InkInternalOnlyApi::class)
class InkNativeLibraryTest {

    companion object {
        private lateinit var loads: List<LoadedInkLibrary>

        @BeforeClass
        @JvmStatic
        fun loadFromManyThreadsAtOnce() {
            val threads = 16
            val start = CyclicBarrier(threads)
            val pool = Executors.newFixedThreadPool(threads)
            try {
                // Half through upstream's entry point, as Ink's own classes load it.
                loads = (0 until threads).map { thread ->
                    pool.submit(
                        Callable {
                            start.await()
                            if (thread % 2 == 0) NativeLoader.load()
                            InkNativeLibrary.load()
                        },
                    )
                }.map { it.get() }
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test
    fun everyCallerGetsTheOneLibraryThisJarBundles() {
        val library = loads.first()
        loads.forEach { assertSame(library, it) }
        assertSame(library, InkNativeLibrary.loaded)
        assertSame(library, InkNativeLibrary.load())

        assertEquals(Origin.BUNDLED, library.origin)
        val platform = Platform.of(System.getProperty("os.name"), System.getProperty("os.arch"))!!
        assertEquals(manifest().getProperty("${platform.id}.sha256"), library.sha256)
        assertEquals(platform.fileName, library.path.fileName.toString())
        // Build scripts point tests at their own cache rather than the user's.
        assertTrue(library.path.startsWith(Paths.get(System.getProperty(InkNativeLibrary.CACHE_PROPERTY))), "${library.path}")
    }

    @Test
    fun javaCallsIntoTheLibraryAndItCallsBack() {
        assertEquals(0, StatusNative.statusCodeOk())
        val failure = assertFailsWith<IllegalArgumentException> {
            StatusNative.throwExceptionFromInvalidArgumentForTesting("thrown from native code")
        }
        // The native side names the absl status; upstream's own test expects the same.
        assertEquals("INVALID_ARGUMENT: thrown from native code", failure.message)
    }

    @Test
    fun finalizersStillFreeNativePointers() {
        // Ink frees native memory from NativePointer.finalize(), which newer JDKs deprecate: this
        // proves this JDK still runs it.
        val next = AtomicLong(1)
        val freed = AtomicLong()
        awaitNativePointerCleanupAfter(timeoutMillis = 10_000) {
            repeat(100) { NativePointer(next::getAndIncrement) { freed.incrementAndGet() } }
        }
        assertEquals(100, freed.get())
    }

    private fun manifest(): Properties {
        val stream = InkNativeLibraryTest::class.java.classLoader
            .getResourceAsStream("${NativeBundle.BASE}/${NativeBundle.MANIFEST}")!!
        return Properties().apply { stream.use { load(it) } }
    }
}
