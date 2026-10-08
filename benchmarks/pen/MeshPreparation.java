import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.core.StrokeMesh;
import java.lang.management.ManagementFactory;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Isolated experiments. No engine, chunks, caches, native resources or canvas on workers. */
public final class MeshPreparation {
    public enum Mode { ORIGINAL, SCALAR, VECTOR, WORKERS }
    public static Mode mode = Mode.ORIGINAL; // EDT confined, including complete-paint mode switches.
    static final int THRESHOLD = 2048;
    static final long BYTE_LIMIT = 16L * 1024 * 1024;
    static final com.sun.management.ThreadMXBean BEAN =
        (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static long generation = 1, version;
    static WorkerPool pool;
    static long vectorCalls, workerCalls, scalarCalls, fallbackCalls;

    public static boolean tryPrepare(StrokeMesh mesh, MeshLinearTransform transform, MeshColor color,
            StampAnimation stamp, float[] positions, float[] varying, boolean[] changed) {
        if (mode == Mode.ORIGINAL) return false;
        int active = mesh.getVertexCount();
        if (changed != null) { active = 0; for (int i = 0; i < mesh.getVertexCount(); i++) if (changed[i]) active++; }
        if (mode != Mode.SCALAR && active < THRESHOLD) { fallbackCalls++; return false; }
        Context context = new Context(transform, color, stamp, mesh.getAttributeMask());
        switch (mode) {
            case SCALAR -> { scalarCalls++; scalar(mesh.getVertices(), 0, mesh.getVertexCount(), context, positions, varying, changed); }
            case VECTOR -> { vectorCalls++; VectorPreparation.prepare(mesh.getVertices(), mesh.getVertexCount(), context, positions, varying, changed); }
            case WORKERS -> {
                workerCalls++;
                Key key = new Key(generation, version, transform, color, stamp);
                Job job = pool.submit(mesh, context, changed, key);
                if (job == null) { fallbackCalls++; return false; }
                if (!pool.apply(job, key, positions, varying)) throw new AssertionError("Synchronous paint unexpectedly stale");
            }
            default -> throw new AssertionError(mode);
        }
        return true;
    }

    static final class Context {
        final MeshLinearTransform transform;
        final MeshColor color;
        final StampAnimation stamp;
        final float a, b, d, e, det, baseY, baseHue, baseChroma;
        final boolean hsl;
        Context(MeshLinearTransform transform, MeshColor color, StampAnimation stamp, int mask) {
            this.transform = transform; this.color = color; this.stamp = stamp;
            a = transform.getA(); b = transform.getB(); d = transform.getD(); e = transform.getE();
            det = Math.abs(a * e - b * d); hsl = (mask & (1 << 2)) != 0;
            baseY = hsl ? color.getR() * .299f + color.getG() * .587f + color.getB() * .114f : 0f;
            float baseI = hsl ? color.getR() * .596f - color.getG() * .275f - color.getB() * .321f : 0f;
            float baseQ = hsl ? color.getR() * .212f - color.getG() * .523f + color.getB() * .311f : 0f;
            baseHue = !hsl || color.getR() == 0f && color.getG() == 0f && color.getB() == 0f
                ? 0f : (float)Math.atan2(baseQ, baseI);
            baseChroma = hsl ? (float)Math.sqrt(baseI * baseI + baseQ * baseQ) : 0f;
        }
        float distance(float dx, float dy) {
            float rx = -a * dy + b * dx, ry = -d * dy + e * dx;
            return Math.max(.000001f, det * (dx * dx + dy * dy)
                / Math.max(.000001f, (float)Math.sqrt(rx * rx + ry * ry)));
        }
    }
    // Kotlin coerceIn preserves NaN and negative zero; Math.min/max are not equivalent here.
    static float clamp(float x) { return x < 0f ? 0f : x > 1f ? 1f : x; }

    /** Arithmetic order deliberately follows the pinned Kotlin body, including float trig casts. */
    static void scalar(float[] v, int from, int to, Context c, float[] positions, float[] varying, boolean[] changed) {
        for (int vertex = from; vertex < to; vertex++) {
            if (changed != null && !changed[vertex]) continue;
            int i = vertex * 15, o = vertex * 16;
            float sx = v[i+6], sy = v[i+7], sl = v[i+8], fx = v[i+9], fy = v[i+10], fl = v[i+11];
            float sidePixels = c.distance(sx, sy), forwardPixels = c.distance(fx, fy);
            float target = .5f + (.707107f - .5f) * clamp(2f * (sidePixels - .5f));
            float sideTarget = target / sidePixels, forwardTarget = target / forwardPixels;
            float sideMargin = (4f / 126f) * Math.max(Math.abs(sl) - 1f, 0f);
            float forwardMargin = (4f / 126f) * Math.max(Math.abs(fl) - 1f, 0f);
            float sideCapped = Math.min(sideTarget, sideMargin);
            float sideOutset = sideTarget + (sideCapped - sideTarget) * clamp(4f * sidePixels - 1f);
            float forwardOutset = Math.min(forwardTarget, forwardMargin);
            float sox = Math.signum(sl) * sideOutset * sx, soy = Math.signum(sl) * sideOutset * sy;
            float fox = Math.signum(fl) * forwardOutset * fx, foy = Math.signum(fl) * forwardOutset * fy;
            float common = clamp((sox * fox + soy * foy) / Math.max(.000001f, fox * fox + foy * foy));
            float px = v[i] + sox + (1f - common) * fox, py = v[i+1] + soy + (1f - common) * foy;
            positions[vertex*2] = px; positions[vertex*2+1] = py;
            float alpha = clamp((v[i+2] + 1f) * c.color.getA());
            if (c.hsl) {
                float hue = c.baseHue - v[i+3] * (2f * (float)Math.PI);
                float chroma = c.baseChroma * (v[i+4] + 1f), y = c.baseY + v[i+5];
                float shiftedI = chroma * (float)Math.cos(hue), shiftedQ = chroma * (float)Math.sin(hue);
                varying[o] = (y + .956f * shiftedI + .621f * shiftedQ) * alpha;
                varying[o+1] = (y - .272f * shiftedI - .647f * shiftedQ) * alpha;
                varying[o+2] = (y - 1.107f * shiftedI + 1.704f * shiftedQ) * alpha;
            } else {
                varying[o] = c.color.getR() * alpha; varying[o+1] = c.color.getG() * alpha;
                varying[o+2] = c.color.getB() * alpha;
            }
            varying[o+3] = alpha; varying[o+4] = sidePixels; varying[o+5] = forwardPixels;
            for (int edge = 0; edge < 4; edge++) {
                float flag = switch (edge) {
                    case 0 -> sl > -.005f ? 1f : 0f;
                    case 1 -> sl < .005f ? 1f : 0f;
                    case 2 -> fl > -.005f ? 1f : 0f;
                    default -> fl < .005f ? 1f : 0f;
                };
                varying[o+6+edge] = flag;
                varying[o+10+edge] = target * (1f - flag)
                    * (edge < 2 ? sideOutset / sideTarget : forwardOutset / forwardTarget);
            }
            if (c.stamp == null) { varying[o+14] = px; varying[o+15] = py; }
            else {
                float progress = c.stamp.getProgress() + v[i+14];
                int frame = (int)(float)Math.floor((progress - (float)Math.floor(progress)) * c.stamp.getFrames());
                varying[o+14] = (v[i+12] + frame % c.stamp.getColumns()) / c.stamp.getColumns();
                varying[o+15] = (v[i+13] + frame / c.stamp.getColumns()) / c.stamp.getRows();
            }
        }
    }

    record Key(long generation, long version, MeshLinearTransform transform, MeshColor color, StampAnimation stamp) {}
    static final class Job {
        final Key key;
        final float[] input, positions, varying;
        final boolean[] changed;
        final Context context;
        final long bytes;
        final CountDownLatch done = new CountDownLatch(2);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean cancelled;
        Job(StrokeMesh mesh, Context context, boolean[] changed, Key key) {
            this.key = key; this.context = context;
            input = mesh.getVertices().clone(); int n = mesh.getVertexCount();
            this.changed = changed == null ? null : java.util.Arrays.copyOf(changed, n);
            positions = new float[n*2]; varying = new float[n*16];
            bytes = arrayBytes(n, changed != null);
        }
    }
    static long arrayBytes(int n, boolean changed) { return (long)n * (15*4 + 18*4 + (changed ? 1 : 0)); }

    /** One in-flight immutable job, two fixed workers, two bounded queue slots, 16 MiB arrays. */
    static final class WorkerPool implements AutoCloseable {
        final Thread owner = Thread.currentThread();
        final ThreadPoolExecutor executor;
        final long[] threadIds = new long[2];
        Job pending;
        long peakBytes;
        boolean closed;
        WorkerPool() {
            AtomicInteger sequence = new AtomicInteger();
            executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(2), task -> {
                int number = sequence.incrementAndGet();
                Thread thread = new Thread(task, "byteink-preparation-experiment-" + number);
                threadIds[number-1] = thread.threadId();
                thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
            executor.prestartAllCoreThreads();
        }
        void owner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Owner thread required"); }
        long cpu() { long sum=0; for (long id : threadIds) sum += BEAN.getThreadCpuTime(id); return sum; }
        long allocation() { long sum=0; for (long id : threadIds) sum += BEAN.getThreadAllocatedBytes(id); return sum; }
        Job submit(StrokeMesh mesh, Context context, boolean[] changed, Key key) {
            owner();
            if (closed || pending != null || arrayBytes(mesh.getVertexCount(), changed != null) > BYTE_LIMIT) return null;
            Job job = new Job(mesh, context, changed, key); pending = job; peakBytes = Math.max(peakBytes, job.bytes);
            for (int half = 0; half < 2; half++) {
                int from = mesh.getVertexCount() * half / 2, to = mesh.getVertexCount() * (half+1) / 2;
                executor.execute(() -> {
                    try { if (!job.cancelled) scalar(job.input, from, to, job.context, job.positions, job.varying, job.changed); }
                    catch (Throwable failure) { job.failure.compareAndSet(null, failure); }
                    finally {
                        job.done.countDown();
                    }
                });
            }
            return job;
        }
        void retire() { owner(); if (pending != null) pending.cancelled = true; }
        boolean apply(Job job, Key current, float[] positions, float[] varying) {
            owner(); if (pending != job) throw new IllegalStateException("Not this pool's pending job");
            try {
                job.done.await();
                if (job.failure.get() != null) throw new IllegalStateException("Worker failed", job.failure.get());
                if (closed || job.cancelled || !job.key.equals(current)) return false;
                if (job.changed == null) {
                    System.arraycopy(job.positions, 0, positions, 0, job.positions.length);
                    System.arraycopy(job.varying, 0, varying, 0, job.varying.length);
                } else for (int i = 0; i < job.changed.length; i++) if (job.changed[i]) {
                    System.arraycopy(job.positions, i*2, positions, i*2, 2);
                    System.arraycopy(job.varying, i*16, varying, i*16, 16);
                }
                return true;
            } catch (InterruptedException failure) {
                // Do not release ownership/budget while a worker still owns these arrays.
                job.cancelled = true; boolean interrupted = true;
                while (job.done.getCount() > 0) try { job.done.await(); } catch (InterruptedException again) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted preparation", failure);
            } finally { pending = null; }
        }
        public void close() {
            owner(); closed = true; retire();
            try { if (pending != null) apply(pending, pending.key, new float[0], new float[0]); }
            finally {
                executor.shutdown();
                try { if (!executor.awaitTermination(5, TimeUnit.SECONDS)) throw new IllegalStateException("Worker leak"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
            }
        }
    }
}

/*
 * Shader math adapted from google/ink at 96e50239e1c8955f7e222301661b595847d7de83,
 * ink/rendering/skia/common_internal/sksl_{vertex,fragment,common}_shader_helper_functions.h.
 * Copyright 2024 Google LLC
 * Licensed under the Apache License, Version 2.0.
 * https://www.apache.org/licenses/LICENSE-2.0
 */
