package com.vivenotes.byteink.nativeloader

import androidx.ink.nativeloader.InkInternalOnlyApi
import androidx.ink.nativeloader.NativePointer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A finalized [NativePointer] is freed only after a grace period, so a native call still using its
 * pointer, from an object the collector found unreachable during the call, would have to stall that
 * long to read freed memory.
 */
@OptIn(InkInternalOnlyApi::class)
class DeferredFreeTest {

    @Test
    fun finalizedPointersAreFreedOnlyAfterTheGracePeriod() {
        val freed = CompletableFuture<Pair<Long, String>>()
        // Nothing can finalize the pointer before this, so nothing can free it sooner than 250 ms on.
        val start = System.nanoTime()
        unreachablePointer { freed.complete(System.nanoTime() to Thread.currentThread().name) }
        val deadline = start + TimeUnit.SECONDS.toNanos(10)
        while (!freed.isDone && System.nanoTime() < deadline) {
            collect()
            Thread.sleep(10)
        }
        val (freedAt, thread) = freed.get(1, TimeUnit.SECONDS)

        val millis = TimeUnit.NANOSECONDS.toMillis(freedAt - start)
        assertTrue(millis >= 250, "Freed $millis ms after the pointer became unreachable")
        assertEquals("byteink native frees", thread)
    }

    private fun unreachablePointer(free: (Long) -> Unit) {
        NativePointer({ 42L }, free)
    }

    @Suppress("DEPRECATION")
    private fun collect() {
        System.gc()
        System.runFinalization()
    }
}
