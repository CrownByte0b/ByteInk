package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import static java.lang.foreign.ValueLayout.*;

/** Reads WM_POINTER history on the window's native message thread before AWT retrieves another message. */
final class WindowsPenBridge implements NativePenBridge {
    // Two process-lifetime trampolines, rather than upcall allocations per subscription. Keeping them
    // alive also covers callbacks still returning after UnhookWindowsHookEx on another thread.
    private static final class Runtime {
        static final Arena ARENA = Arena.ofAuto();
        static final NativeCalls API = new NativeCalls(ARENA, "user32.dll", "kernel32.dll");
        static final Map<Integer, Hub> HUBS = new ConcurrentHashMap<>();
        static final MemorySegment STUB;
        static final MemorySegment SENT_STUB;
        static {
            try {
                MethodHandle callback = MethodHandles.lookup().findStatic(WindowsPenBridge.class, "dispatch",
                        MethodType.methodType(long.class, int.class, long.class, long.class));
                STUB = Linker.nativeLinker().upcallStub(callback,
                        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), ARENA);
                MethodHandle sent = MethodHandles.lookup().findStatic(WindowsPenBridge.class, "observeSentMessages",
                        MethodType.methodType(long.class, int.class, long.class, long.class));
                SENT_STUB = Linker.nativeLinker().upcallStub(sent,
                        FunctionDescriptor.of(JAVA_LONG, JAVA_INT, JAVA_LONG, JAVA_LONG), ARENA);
            } catch (ReflectiveOperationException failure) { throw new ExceptionInInitializerError(failure); }
        }
    }
    private static final class Hub {
        final Map<Long, WindowsPenBridge> windows = new ConcurrentHashMap<>();
        MemorySegment hook = MemorySegment.NULL;
        MemorySegment sentHook = MemorySegment.NULL;
    }
    private final long window;
    private final Consumer<Frame> listener;
    private final Consumer<Throwable> failureListener;
    private final int thread;
    private volatile boolean closed;

    WindowsPenBridge(long window, Consumer<Frame> listener, Consumer<Throwable> failureListener) {
        this.window = window; this.listener = listener; this.failureListener = failureListener;
        thread = Runtime.API.integer("GetWindowThreadProcessId", MemorySegment.ofAddress(window), MemorySegment.NULL);
        if (thread == 0) throw new IllegalArgumentException("Not a live HWND");
        synchronized (Runtime.HUBS) {
            Hub hub = Runtime.HUBS.computeIfAbsent(thread, ignored -> new Hub());
            if (hub.windows.putIfAbsent(window, this) != null) throw new IllegalStateException("Window already has a native ink subscription");
            if (hub.hook.address() == 0) {
                hub.hook = Runtime.API.pointer("SetWindowsHookExW", 3, Runtime.STUB, MemorySegment.NULL, thread);
                if (hub.hook.address() == 0) {
                    hub.windows.remove(window);
                    Runtime.HUBS.remove(thread);
                    throw new IllegalStateException("Cannot install window-local pen capture");
                }
                hub.sentHook = Runtime.API.pointer("SetWindowsHookExW", 4, Runtime.SENT_STUB, MemorySegment.NULL, thread);
                if (hub.sentHook.address() == 0) {
                    Runtime.API.integer("UnhookWindowsHookEx", hub.hook);
                    hub.windows.remove(window); Runtime.HUBS.remove(thread);
                    throw new IllegalStateException("Cannot observe native capture cancellation");
                }
            }
        }
    }
    private static long observeSentMessages(int code, long wparam, long address) {
        NativeCalls api = Runtime.API;
        try {
            Hub hub = Runtime.HUBS.get(api.integer("GetCurrentThreadId"));
            if (hub != null && code >= 0 && address != 0) {
                MemorySegment data = MemorySegment.ofAddress(address).reinterpret(32); // CWPSTRUCT
                int message = data.get(JAVA_INT, 16);
                if (message == 0x24c || message == 0x215 || message == 0x1f || message == 0x8 || message == 0x82) {
                    long hwnd = data.get(ADDRESS, 24).address();
                    for (WindowsPenBridge bridge : hub.windows.values()) {
                        if (bridge.closed || hwnd != bridge.window && api.integer("IsChild", MemorySegment.ofAddress(bridge.window), MemorySegment.ofAddress(hwnd)) == 0) continue;
                        long id = message == 0x24c ? data.get(JAVA_LONG, 8) & 0xffff : -1;
                        int phase = message == 0x24c || message == 0x215 ? CANCEL : CANCEL_ALL;
                        bridge.listener.accept(new Frame(id, phase, MOUSE, List.of()));
                    }
                }
            }
        } catch (Throwable ignored) { /* Never unwind through the native hook chain. */ }
        try { return api.longValue("CallNextHookEx", MemorySegment.NULL, code, wparam, address); }
        catch (Throwable ignored) { return 0; }
    }
    private static long dispatch(int code, long removed, long address) {
        NativeCalls api = Runtime.API;
        Hub hub = Runtime.HUBS.get(api.integer("GetCurrentThreadId"));
        if (hub != null && code >= 0 && removed == 1 && address != 0) {
            MemorySegment msg = MemorySegment.ofAddress(address).reinterpret(48);
            long hwnd = msg.get(ADDRESS, 0).address();
            for (WindowsPenBridge bridge : hub.windows.values()) {
                if (bridge.closed || hwnd != bridge.window && api.integer("IsChild", MemorySegment.ofAddress(bridge.window), MemorySegment.ofAddress(hwnd)) == 0) continue;
                try { bridge.consume(msg); }
                catch (Throwable failure) {
                    // No Java exception may unwind through a Win32 callback.
                    try { bridge.failureListener.accept(failure); } catch (Throwable ignored) { }
                }
                break;
            }
        }
        try { return api.longValue("CallNextHookEx", MemorySegment.NULL, code, removed, address); }
        catch (Throwable ignored) { return 0; }
    }
    private void consume(MemorySegment msg) {
        int message = msg.get(JAVA_INT, 8);
        long wparam = msg.get(JAVA_LONG, 16);
        if (message == 0x24c) { // WM_POINTERCAPTURECHANGED
            listener.accept(new Frame(wparam & 0xffff, CANCEL, PEN, List.of()));
            return;
        }
        if (message == 0x245 || message == 0x246 || message == 0x247) {
            int id = (int) (wparam & 0xffff);
            try (Arena scratch = Arena.ofConfined()) {
                MemorySegment type = scratch.allocate(JAVA_INT);
                if (Runtime.API.integer("GetPointerType", id, type) == 0) return;
                int tool = type.get(JAVA_INT, 0);
                if (tool != 2 && tool != 3) return;
                String infoFunction = tool == 3 ? "GetPointerPenInfo" : "GetPointerInfo";
                String historyFunction = tool == 3 ? "GetPointerPenInfoHistory" : "GetPointerInfoHistory";
                int size = tool == 3 ? 120 : 96;
                MemorySegment current = scratch.allocate(size, 8);
                if (Runtime.API.integer(infoFunction, id, current) == 0) {
                    listener.accept(new Frame(id, CANCEL, tool == 3 ? PEN : TOUCH, List.of()));
                    return;
                }
                int requested = Math.max(1, current.get(JAVA_INT, 68));
                MemorySegment count = scratch.allocate(JAVA_INT);
                MemorySegment history;
                while (true) {
                    if (requested > 65536) throw new IllegalStateException("Unreasonable pointer history count");
                    history = scratch.allocate((long) size * requested, 8);
                    count.set(JAVA_INT, 0, requested);
                    if (Runtime.API.integer(historyFunction, id, count, history) == 0) {
                        // The current observation is still valid if its history has expired.
                        history = current; count.set(JAVA_INT, 0, 1); break;
                    }
                    if (count.get(JAVA_INT, 0) <= requested) break;
                    requested = count.get(JAVA_INT, 0);
                }
                int phase = message == 0x246 ? BEGIN : message == 0x247 ? FINISH : MOVE;
                if ((current.get(JAVA_INT, 12) & 0x8000) != 0) phase = CANCEL;
                List<Point> points = new ArrayList<>();
                MemorySegment counter = scratch.allocate(JAVA_LONG), frequency = scratch.allocate(JAVA_LONG);
                Runtime.API.integer("QueryPerformanceCounter", counter);
                Runtime.API.integer("QueryPerformanceFrequency", frequency);
                long nowCounter = counter.get(JAVA_LONG, 0), counterFrequency = frequency.get(JAVA_LONG, 0);
                long nowTicks = Runtime.API.longValue("GetTickCount64");
                for (int i = count.get(JAVA_INT, 0) - 1; i >= 0; i--) {
                    MemorySegment entry = history.asSlice((long) i * size, size);
                    if (phase == MOVE && (entry.get(JAVA_INT, 12) & 4) == 0) continue; // hover
                    MemorySegment xy = scratch.allocate(8, 4);
                    xy.set(JAVA_INT, 0, entry.get(JAVA_INT, 48));
                    xy.set(JAVA_INT, 4, entry.get(JAVA_INT, 52));
                    if (Runtime.API.integer("ScreenToClient", MemorySegment.ofAddress(window), xy) == 0)
                        throw new IllegalStateException("ScreenToClient failed");
                    int axes = 0; float pressure = 0, tx = 0, ty = 0;
                    if (tool == 3) {
                        int mask = entry.get(JAVA_INT, 100);
                        if ((mask & 1) != 0) { axes |= PRESSURE; pressure = Math.clamp(entry.get(JAVA_INT, 104) / 1024f, 0, 1); }
                        if ((mask & 12) == 12) { axes |= TILT; tx = entry.get(JAVA_INT, 112); ty = entry.get(JAVA_INT, 116); }
                        // rotation is barrel twist, not the azimuth of the shaft.
                    }
                    long ticks = Integer.toUnsignedLong(entry.get(JAVA_INT, 64));
                    long scanCounter = entry.get(JAVA_LONG, 80);
                    if (ticks == 0 && scanCounter != 0 && counterFrequency > 0)
                        ticks = (nowTicks - Math.round((nowCounter - scanCounter) * 1000.0 / counterFrequency)) & 0xffffffffL;
                    if (ticks == 0) ticks = Integer.toUnsignedLong(Runtime.API.integer("GetMessageTime"));
                    points.add(new Point(xy.get(JAVA_INT, 0), xy.get(JAVA_INT, 4), ticks, pressure, tx, ty, axes));
                }
                if (!points.isEmpty() || phase == CANCEL)
                    listener.accept(new Frame(id, phase, tool == 3 ? PEN : TOUCH, points));
                // Prevent DefWindowProc from promoting handled pen/touch packets to mouse ink.
                msg.set(JAVA_INT, 8, 0); // WM_NULL
            }
        } else if (message == 0x200 || message == 0x201 || message == 0x202) {
            long extra = Runtime.API.longValue("GetMessageExtraInfo");
            if ((extra & 0xffffff00L) == 0xff515700L) return; // promoted pen/touch mouse
            if (message == 0x200 && (wparam & 1) == 0) return;
            long packed = msg.get(JAVA_LONG, 24);
            try (Arena scratch = Arena.ofConfined()) {
                MemorySegment xy = scratch.allocate(8, 4);
                xy.set(JAVA_INT, 0, (short) packed); xy.set(JAVA_INT, 4, (short) (packed >>> 16));
                Runtime.API.integer("MapWindowPoints", msg.get(ADDRESS, 0), MemorySegment.ofAddress(window), xy, 1);
                Point point = new Point(xy.get(JAVA_INT, 0), xy.get(JAVA_INT, 4),
                        Integer.toUnsignedLong(msg.get(JAVA_INT, 32)), 0, 0, 0, 0);
                listener.accept(new Frame(-1, message == 0x201 ? BEGIN : message == 0x202 ? FINISH : MOVE, MOUSE, List.of(point)));
            }
        }
    }
    @Override public void close() {
        synchronized (Runtime.HUBS) {
            if (closed) return;
            closed = true;
            Hub hub = Runtime.HUBS.get(thread);
            if (hub == null) return;
            hub.windows.remove(window);
            if (hub.windows.isEmpty()) {
                if (Runtime.API.integer("UnhookWindowsHookEx", hub.hook) == 0)
                    throw new IllegalStateException("Cannot remove native pen hook");
                if (Runtime.API.integer("UnhookWindowsHookEx", hub.sentHook) == 0)
                    throw new IllegalStateException("Cannot remove native capture observer");
                Runtime.HUBS.remove(thread);
            }
        }
    }
}
