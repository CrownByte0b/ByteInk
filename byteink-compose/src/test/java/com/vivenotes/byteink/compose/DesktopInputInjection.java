package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import static java.lang.foreign.ValueLayout.*;
import static java.nio.charset.StandardCharsets.UTF_16LE;

/** Test-only OS input injection; no fabricated pen samples pass through production APIs. */
final class DesktopInputInjection implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final NativeCalls api;
    private final boolean windows = System.getProperty("os.name").startsWith("Windows");
    private MemorySegment display = MemorySegment.NULL, pen = MemorySegment.NULL;
    private long target;
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
        MemorySegment point = arena.allocate(8, 4);
        point.set(JAVA_INT, 0, clientX); point.set(JAVA_INT, 4, clientY);
        if (api.integer("ClientToScreen", MemorySegment.ofAddress(hwnd), point) == 0)
            throw new IllegalStateException("ClientToScreen failed");
        int x = point.get(JAVA_INT, 0), y = point.get(JAVA_INT, 4);
        if (phase == 0) {
            // Pen input is hit-tested against the composed desktop, so a contact can miss a window that
            // appeared after the last composition. Wait until DWM presents the current window layout.
            int flushed = api.integer("DwmFlush");
            if (flushed != 0) throw new IllegalStateException("DwmFlush: 0x" + Integer.toHexString(flushed));
            // Pointer input is routed by screen position. Fail clearly when another window would receive it.
            target = api.pointer("WindowFromPoint", (y & 0xffffffffL) << 32 | (x & 0xffffffffL)).address();
            if (root(target) != root(hwnd)) throw new IllegalStateException("The pen target is covered by " + describe(target));
        }
        pen(phase, x, y, pressure, tiltX, tiltY);
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
        try {
            if (pen.address() != 0) api.procedure("DestroySyntheticPointerDevice", pen);
            if (display.address() != 0) api.integer("XCloseDisplay", display);
        } finally { arena.close(); }
    }
}
