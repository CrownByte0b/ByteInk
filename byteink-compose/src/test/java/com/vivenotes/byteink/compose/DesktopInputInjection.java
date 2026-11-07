package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;
import static java.lang.foreign.ValueLayout.*;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/** Test-only OS input injection; no fabricated pen samples pass through production APIs. */
final class DesktopInputInjection implements AutoCloseable {
    // Windows releases an injected contact that gets no frames for about half a second with an
    // ordinary pointer up: the stroke finishes and the next frame starts a new contact. Like a
    // digitizer reporting a resting pen, the current frame repeats, from a thread a busy EDT can't stall.
    private static final long REPEAT_NANOS = 50_000_000L;
    private final Arena arena = Arena.ofShared();
    private final NativeCalls api;
    private final boolean windows = System.getProperty("os.name").startsWith("Windows");
    private final MemorySegment frame = arena.allocate(152, 8); // POINTER_TYPE_INFO
    private MemorySegment display = MemorySegment.NULL, pen = MemorySegment.NULL;
    private long target;
    private boolean down;
    private long lastFrame;
    private Thread repeater;
    private RuntimeException repeatFailure;
    DesktopInputInjection() {
        api = new NativeCalls(arena, windows ? new String[]{"user32.dll", "kernel32.dll", "dwmapi.dll"}
                : new String[]{"libX11.so.6", "libXtst.so.6"});
        if (!windows) {
            display = api.pointer("XOpenDisplay", MemorySegment.NULL);
            if (display.address() == 0) throw new IllegalStateException("No X11 test display");
        }
    }
    void mouse(long hwnd, int phase, int x, int y) {
        if (windows) {
            int message = phase == 0 ? 0x201 : phase == 2 ? 0x202 : 0x200;
            long packed = (x & 0xffffL) | (y & 0xffffL) << 16;
            if (api.integer("PostMessageW", MemorySegment.ofAddress(hwnd), message, phase == 2 ? 0L : 1L, packed) == 0)
                throw new IllegalStateException("PostMessageW failed");
        } else {
            api.integer("XTestFakeMotionEvent", display, 0, x, y, 0L);
            if (phase != 1) api.integer("XTestFakeButtonEvent", display, 1, phase == 0 ? 1 : 0, 0L);
            api.integer("XFlush", display);
        }
    }
    synchronized void pen(int phase, int screenX, int screenY, int pressure, int tiltX, int tiltY) {
        if (!windows) throw new UnsupportedOperationException("Synthetic pen injection is Win32 only");
        if (repeatFailure != null) throw new IllegalStateException("Repeating the held pen frame failed", repeatFailure);
        if (pen.address() == 0) {
            pen = api.pointer("CreateSyntheticPointerDevice", 3, 1, 3); // POINTER_FEEDBACK_NONE
            if (pen.address() == 0) throw new IllegalStateException("CreateSyntheticPointerDevice: " + api.integer("GetLastError"));
        }
        frame.fill((byte) 0);
        frame.set(JAVA_INT, 0, 3); // POINTER_TYPE_INFO.type
        MemorySegment data = frame.asSlice(8, 120);
        data.set(JAVA_INT, 0, 3); data.set(JAVA_INT, 4, 1);
        data.set(JAVA_INT, 12, phase == 0 ? 0x10000 | 2 | 4 : phase == 2 ? 0x40000 : 0x20000 | 2 | 4);
        data.set(JAVA_INT, 32, screenX); data.set(JAVA_INT, 36, screenY);
        data.set(JAVA_INT, 100, 1 | 4 | 8);
        data.set(JAVA_INT, 104, pressure); data.set(JAVA_INT, 112, tiltX); data.set(JAVA_INT, 116, tiltY);
        inject();
        down = phase != 2;
        if (down) {
            data.set(JAVA_INT, 12, 0x20000 | 2 | 4); // A repeat is an in-contact update at the same place.
            if (repeater == null) repeater = Thread.ofPlatform().daemon().name("byteink-test-pen-repeat").start(this::repeatWhileDown);
        }
        notifyAll();
    }
    private void inject() {
        if (api.integer("InjectSyntheticPointerInput", pen, frame, 1) == 0)
            throw new IllegalStateException("InjectSyntheticPointerInput: " + api.integer("GetLastError"));
        lastFrame = System.nanoTime();
    }
    private synchronized void repeatWhileDown() {
        try {
            while (down) {
                long wait = REPEAT_NANOS - (System.nanoTime() - lastFrame);
                if (wait > 0) NANOSECONDS.timedWait(this, wait); else inject();
            }
        } catch (InterruptedException ignored) {
        } catch (RuntimeException failure) {
            repeatFailure = failure; down = false;
        } finally { repeater = null; }
    }
    void penAt(long hwnd, int phase, int clientX, int clientY, int pressure, int tiltX, int tiltY) {
        MemorySegment point = arena.allocate(8, 4);
        point.set(JAVA_INT, 0, clientX); point.set(JAVA_INT, 4, clientY);
        if (api.integer("ClientToScreen", MemorySegment.ofAddress(hwnd), point) == 0)
            throw new IllegalStateException("ClientToScreen failed");
        int x = point.get(JAVA_INT, 0), y = point.get(JAVA_INT, 4);
        if (phase == 0) {
            target = composedWindowAt(x, y);
            int dismissed = 0;
            long deadline = 0;
            while (root(target) != root(hwnd)) {
                if (dismissed < 5 && dismissRunnerAccountPrompt(target)) {
                    dismissed++;
                    deadline = System.nanoTime() + 10_000_000_000L; // Its frame outlives it for a moment.
                } else if (System.nanoTime() >= deadline) {
                    // Pointer input is routed by screen position. Fail clearly when another window would receive it.
                    throw new IllegalStateException("The pen target is covered by " + describe(target));
                }
                LockSupport.parkNanos(50_000_000L);
                target = composedWindowAt(x, y);
            }
        }
        pen(phase, x, y, pressure, tiltX, tiltY);
    }
    private long composedWindowAt(int x, int y) {
        // Pen input is hit-tested against the composed desktop, so a contact can miss a window that
        // appeared after the last composition. Wait until DWM presents the current window layout.
        int flushed = api.integer("DwmFlush");
        if (flushed != 0) throw new IllegalStateException("DwmFlush: 0x" + Integer.toHexString(flushed));
        return api.pointer("WindowFromPoint", (y & 0xffffffffL) << 32 | (x & 0xffffffffL)).address();
    }
    /**
     * Hosted windows-11-arm images can show this sign-in prompt above even a topmost window, before or
     * during a job. Closes it on a hosted runner, and returns whether it did; anywhere else, or for any
     * other window, the covered target stays a failure. The caption belongs to the prompt's own window
     * (WWAHost's CoreWindow) and to its ApplicationFrameHost frame, not to WWAHost's main window, which it
     * doesn't have: Process.MainWindowTitle can't find it. Ending WWAHost closes the frame too.
     */
    private boolean dismissRunnerAccountPrompt(long hwnd) {
        if (!"true".equals(System.getenv("GITHUB_ACTIONS")) || hwnd == 0) return false;
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment pid = scratch.allocate(JAVA_INT), className = scratch.allocate(512), title = scratch.allocate(512);
            api.integer("GetWindowThreadProcessId", MemorySegment.ofAddress(hwnd), pid);
            api.integer("GetClassNameW", MemorySegment.ofAddress(hwnd), className, 256);
            api.integer("GetWindowTextW", MemorySegment.ofAddress(hwnd), title, 256);
            ProcessHandle owner = ProcessHandle.of(Integer.toUnsignedLong(pid.get(JAVA_INT, 0))).orElse(null);
            if (owner == null || !"Windows.UI.Core.CoreWindow".equals(className.getString(0, UTF_16LE))
                    || !"Microsoft account".equals(title.getString(0, UTF_16LE))
                    || !owner.info().command().orElse("").toLowerCase(Locale.ROOT).endsWith("\\wwahost.exe")) return false;
            System.out.println("Closing the hosted runner's account prompt, which covers the pen target: " + describe(hwnd));
            if (!owner.destroyForcibly()) return false;
            owner.onExit().get(5, SECONDS);
            return true;
        } catch (ExecutionException | TimeoutException failure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
    /** The desktop state relevant to pointer routing, for failure messages. */
    String describeDesktop() {
        if (!windows) return "";
        return "foreground " + describe(api.pointer("GetForegroundWindow").address()) + ", pen target " + describe(target);
    }
    private long root(long hwnd) { return api.pointer("GetAncestor", MemorySegment.ofAddress(hwnd), 2).address(); } // GA_ROOT
    private String describe(long hwnd) {
        if (hwnd == 0) return "none";
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment pid = scratch.allocate(JAVA_INT), className = scratch.allocate(512), title = scratch.allocate(512);
            api.integer("GetWindowThreadProcessId", MemorySegment.ofAddress(hwnd), pid);
            long owner = Integer.toUnsignedLong(pid.get(JAVA_INT, 0));
            api.integer("GetClassNameW", MemorySegment.ofAddress(hwnd), className, 256);
            if (owner == ProcessHandle.current().pid()) return String.format("0x%x this test JVM [%s]", hwnd, className.getString(0, UTF_16LE));
            // Another process's caption is read without sending it a message.
            api.integer("GetWindowTextW", MemorySegment.ofAddress(hwnd), title, 256);
            String process = ProcessHandle.of(owner).flatMap(handle -> handle.info().command()).orElse("pid " + owner);
            return String.format("0x%x %s [%s] \"%s\"", hwnd, process, className.getString(0, UTF_16LE), title.getString(0, UTF_16LE));
        }
    }
    void cancelInput(long hwnd) {
        // The system owns pointer-capture notifications; WM_CANCELMODE is a sendable cancellation.
        api.longValue("SendMessageW", MemorySegment.ofAddress(hwnd), 0x1f, 0L, 0L);
    }
    @Override public void close() {
        Thread thread;
        synchronized (this) { down = false; notifyAll(); thread = repeater; }
        try {
            if (thread != null) thread.join();
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        try {
            if (pen.address() != 0) api.procedure("DestroySyntheticPointerDevice", pen);
            if (display.address() != 0) api.integer("XCloseDisplay", display);
        } finally { arena.close(); }
        if (repeatFailure != null) throw new IllegalStateException("Repeating the held pen frame failed", repeatFailure);
    }
}
