package com.vivenotes.byteink.nativeloader

import androidx.ink.nativeloader.NativePointerObserver
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Frees the native objects of finalized [androidx.ink.nativeloader.NativePointer]s a grace period
 * after finalization, on a thread of its own.
 *
 * AndroidX Ink's classes read an object's native pointer and pass only that number to a native
 * method, so a collection during the call can find the object unreachable. Its finalizer used to
 * free the native object while the call still read it: `MutableStrokeInputBatch.toImmutable()`
 * copying a batch its finalizer had just freed. Now the call would have to stall for the whole
 * grace period first.
 */
internal object DeferredFree {
    /** Far longer than a native call stalls in practice, and short of upstream tests' one-second cleanup wait. */
    private const val GRACE_MILLIS = 250L

    private class Pending(val pointer: Long, val free: (Long) -> Unit, val cleanup: ((Long) -> Unit)?, val due: Long)

    // Each entry is due a fixed time after it was queued, so the head is always the next one due.
    private val pending = LinkedBlockingQueue<Pending>()

    init {
        Thread(::freeWhenDue, "byteink native frees").apply { isDaemon = true }.start()
    }

    /**
     * Frees [pointer] with [free] after the grace period, then tells [cleanup], the observer when it
     * was finalized, if [NativePointerObserver] still has it: upstream's finalize() reports a free
     * to the observer of that moment.
     */
    fun free(pointer: Long, free: (Long) -> Unit, cleanup: ((Long) -> Unit)?) {
        pending.put(Pending(pointer, free, cleanup, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GRACE_MILLIS)))
    }

    private fun freeWhenDue() {
        while (true) {
            try {
                val next = pending.take()
                val wait = next.due - System.nanoTime()
                if (wait > 0) TimeUnit.NANOSECONDS.sleep(wait)
                next.free(next.pointer)
                next.cleanup?.takeIf { it === NativePointerObserver.onCleanup }?.invoke(next.pointer)
            } catch (_: Throwable) {
                // Like a finalizer's, a failure is ignored, and its pointer is not reported as freed.
            }
        }
    }
}
