package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import static java.lang.foreign.ValueLayout.*;

/** Test-only OS input injection; no fabricated pen samples pass through production APIs. */
final class DesktopInputInjection implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final NativeCalls api;
    private final boolean windows = System.getProperty("os.name").startsWith("Windows");
    private MemorySegment display = MemorySegment.NULL, pen = MemorySegment.NULL;
    private boolean foregroundLocked;
    DesktopInputInjection() {
        api = new NativeCalls(arena, windows ? new String[]{"user32.dll", "kernel32.dll"}
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
    void pen(int phase, int screenX, int screenY, int pressure, int tiltX, int tiltY) {
        if (!windows) throw new UnsupportedOperationException("Synthetic pen injection is Win32 only");
        if (pen.address() == 0) {
            pen = api.pointer("CreateSyntheticPointerDevice", 3, 1, 3); // POINTER_FEEDBACK_NONE
            if (pen.address() == 0) throw new IllegalStateException("CreateSyntheticPointerDevice: " + api.integer("GetLastError"));
        }
        MemorySegment info = arena.allocate(152, 8);
        info.set(JAVA_INT, 0, 3); // POINTER_TYPE_INFO.type
        MemorySegment data = info.asSlice(8, 120);
        data.set(JAVA_INT, 0, 3); data.set(JAVA_INT, 4, 1);
        data.set(JAVA_INT, 12, phase == 0 ? 0x10000 | 2 | 4 : phase == 2 ? 0x40000 : 0x20000 | 2 | 4);
        data.set(JAVA_INT, 32, screenX); data.set(JAVA_INT, 36, screenY);
        data.set(JAVA_INT, 100, 1 | 4 | 8);
        data.set(JAVA_INT, 104, pressure); data.set(JAVA_INT, 112, tiltX); data.set(JAVA_INT, 116, tiltY);
        if (api.integer("InjectSyntheticPointerInput", pen, info, 1) == 0)
            throw new IllegalStateException("InjectSyntheticPointerInput: " + api.integer("GetLastError"));
    }
    void penAt(long hwnd, int phase, int clientX, int clientY, int pressure, int tiltX, int tiltY) {
        if (phase == 0 && !foregroundLocked) lockForeground(hwnd);
        MemorySegment point = arena.allocate(8, 4);
        point.set(JAVA_INT, 0, clientX); point.set(JAVA_INT, 4, clientY);
        if (api.integer("ClientToScreen", MemorySegment.ofAddress(hwnd), point) == 0)
            throw new IllegalStateException("ClientToScreen failed");
        pen(phase, point.get(JAVA_INT, 0), point.get(JAVA_INT, 4), pressure, tiltX, tiltY);
    }
    private void lockForeground(long hwnd) {
        MemorySegment root = api.pointer("GetAncestor", MemorySegment.ofAddress(hwnd), 2); // GA_ROOT
        long deadline = System.nanoTime() + 10_000_000_000L;
        do {
            // A console can activate after AWT reports focus. Acquire the OS lock only while our
            // window really is foreground, and hold it across the complete synthetic pen gesture.
            if (api.pointer("GetForegroundWindow").address() == root.address() &&
                    api.integer("LockSetForegroundWindow", 1) != 0) { // LSFW_LOCK
                foregroundLocked = true;
                return;
            }
            api.integer("SetForegroundWindow", root);
            try { Thread.sleep(10); }
            catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while acquiring the pen test foreground", failure);
            }
        } while (System.nanoTime() < deadline);
        throw new IllegalStateException("Cannot acquire the pen test foreground: " + api.pointer("GetForegroundWindow").address());
    }
    void cancelInput(long hwnd) {
        // The system owns pointer-capture notifications; WM_CANCELMODE is a sendable cancellation.
        api.longValue("SendMessageW", MemorySegment.ofAddress(hwnd), 0x1f, 0L, 0L);
    }
    @Override public void close() {
        try {
            if (foregroundLocked) api.integer("LockSetForegroundWindow", 2); // LSFW_UNLOCK
        } finally {
            try {
                if (pen.address() != 0) api.procedure("DestroySyntheticPointerDevice", pen);
                if (display.address() != 0) api.integer("XCloseDisplay", display);
            } finally { arena.close(); }
        }
    }
}
