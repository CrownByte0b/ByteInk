package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import java.util.*;
import java.util.function.Consumer;
import static java.lang.foreign.ValueLayout.*;

/** XInput2 on an owned connection to the host's X11/XWayland window; no device-file access. */
final class X11PenBridge implements NativePenBridge {
    private final Arena arena = Arena.ofShared();
    private final NativeCalls api;
    private final Consumer<Frame> listener;
    private final Consumer<Throwable> failureListener;
    private MemorySegment display = MemorySegment.NULL;
    private final Map<Integer, Device> devices = new HashMap<>();
    private final Thread worker;
    private volatile boolean stopped;
    private int opcode;
    private final long window;
    private MemorySegment eventMasks;
    private final Map<Long, int[]> windowOffsets = new HashMap<>();
    private static final class Axis {
        final int number; final double min, max; double value;
        Axis(int number, double min, double max, double value) {
            this.number = number; this.min = min; this.max = max; this.value = value;
        }
    }
    private static final class Device {
        final int tool; final Axis pressure, tiltX, tiltY;
        Device(int tool, Axis pressure, Axis tiltX, Axis tiltY) {
            this.tool = tool; this.pressure = pressure; this.tiltX = tiltX; this.tiltY = tiltY;
        }
    }
    X11PenBridge(long window, Consumer<Frame> listener, Consumer<Throwable> failureListener) {
        this.listener = listener;
        this.failureListener = failureListener;
        this.window = window;
        try {
            api = new NativeCalls(arena, "libX11.so.6", "libXi.so.6", "libc.so.6");
            api.integer("XInitThreads");
            display = api.pointer("XOpenDisplay", MemorySegment.NULL);
            if (display.address() == 0) throw new UnsupportedOperationException("Cannot open X11 display");
            MemorySegment op = arena.allocate(JAVA_INT), first = arena.allocate(JAVA_INT), error = arena.allocate(JAVA_INT);
            if (api.integer("XQueryExtension", display, arena.allocateFrom("XInputExtension"), op, first, error) == 0)
                throw new UnsupportedOperationException("XInput2 is unavailable");
            opcode = op.get(JAVA_INT, 0);
            MemorySegment major = arena.allocate(JAVA_INT), minor = arena.allocate(JAVA_INT);
            major.set(JAVA_INT, 0, 2); minor.set(JAVA_INT, 0, 2);
            if (api.integer("XIQueryVersion", display, major, minor) != 0 || major.get(JAVA_INT, 0) < 2)
                throw new UnsupportedOperationException("XInput2 2.0 or newer is required");
            MemorySegment bits = arena.allocate(4);
            for (int event : new int[]{1, 4, 5, 6, 18, 19, 20}) {
                if (event >= 18 && minor.get(JAVA_INT, 0) < 2) continue;
                int offset = event / 8;
                bits.set(JAVA_BYTE, offset, (byte) (bits.get(JAVA_BYTE, offset) | (1 << (event % 8))));
            }
            MemorySegment mask = arena.allocate(32, 8);
            mask.set(JAVA_INT, 0, 1); // XIAllMasterDevices; sourceid identifies the real device.
            mask.set(JAVA_INT, 4, 4); mask.set(ADDRESS, 8, bits);
            MemorySegment hierarchy = arena.allocate(2);
            hierarchy.set(JAVA_BYTE, 1, (byte) 8);
            mask.set(JAVA_INT, 16, 0); // XIAllDevices is required for hierarchy events.
            mask.set(JAVA_INT, 20, 2); mask.set(ADDRESS, 24, hierarchy);
            eventMasks = mask;
            refreshWindows();
            api.integer("XFlush", display);
            worker = new Thread(this::read, "byteink-xinput2");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable failure) {
            if (display.address() != 0) {
                try { new NativeCalls(arena, "libX11.so.6").integer("XCloseDisplay", display); }
                catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            }
            arena.close();
            throw failure;
        }
    }
    private void read() {
        try {
            MemorySegment event = arena.allocate(192, 8);
            MemorySegment pollfd = arena.allocate(8, 4);
            pollfd.set(JAVA_INT, 0, api.integer("XConnectionNumber", display));
            pollfd.set(JAVA_SHORT, 4, (short) 1);
            while (!stopped) {
                if (api.integer("XPending", display) == 0) {
                    api.integer("poll", pollfd, 1L, 50);
                    continue;
                }
                api.integer("XNextEvent", display, event);
                int coreType = event.get(JAVA_INT, 0);
                if (coreType == 16) { // Renderers can create their input window after addNotify.
                    refreshWindows();
                    continue;
                }
                if (coreType == 17) {
                    if (windowOffsets.remove(event.get(JAVA_LONG, 40)) != null)
                        listener.accept(new Frame(0, CANCEL_ALL, MOUSE, List.of()));
                    continue;
                }
                if (coreType == 22) { refreshWindows(); continue; }
                if (event.get(JAVA_INT, 0) != 35 || event.get(JAVA_INT, 32) != opcode) continue;
                int type = event.get(JAVA_INT, 36);
                if (api.integer("XGetEventData", display, event) == 0) continue;
                try {
                    if (type == 1 || type == 11) {
                        devices.clear();
                        listener.accept(new Frame(0, CANCEL_ALL, MOUSE, List.of()));
                    } else if (type == 4 || type == 5 || type == 6 || type >= 18 && type <= 20) {
                        consume(event.get(ADDRESS, 48).reinterpret(200), type);
                    }
                } finally { api.procedure("XFreeEventData", display, event); }
            }
        } catch (Throwable failure) {
            if (!stopped) failureListener.accept(failure);
        } finally {
            api.integer("XCloseDisplay", display);
            arena.close();
        }
    }
    private void consume(MemorySegment event, int type) {
        int source = event.get(JAVA_INT, 52), detail = event.get(JAVA_INT, 56);
        // Pointer-emulated touch packets duplicate XI_Touch events.
        if (type < 18 && (event.get(JAVA_INT, 120) & 0x10000) != 0) return;
        if ((type == 4 || type == 5) && detail != 1) return; // tip/primary button only
        Device device = devices.computeIfAbsent(source, this::queryDevice);
        MemorySegment mask = event.get(ADDRESS, 152).reinterpret(event.get(JAVA_INT, 144));
        MemorySegment values = event.get(ADDRESS, 160);
        int index = 0;
        for (int bit = 0; bit < mask.byteSize() * 8; bit++) {
            if ((mask.get(JAVA_BYTE, bit / 8) & (1 << (bit % 8))) == 0) continue;
            double value = values.reinterpret((index + 1L) * 8).get(JAVA_DOUBLE, index++ * 8L);
            for (Axis axis : new Axis[]{device.pressure, device.tiltX, device.tiltY})
                if (axis != null && axis.number == bit) axis.value = value;
        }
        int axes = 0;
        float pressure = 0, tx = 0, ty = 0;
        if (device.pressure != null && device.pressure.max > device.pressure.min) {
            pressure = (float) Math.clamp((device.pressure.value - device.pressure.min) /
                    (device.pressure.max - device.pressure.min), 0, 1);
            axes |= PRESSURE;
        }
        if (device.tiltX != null && device.tiltY != null) {
            tx = (float) Math.clamp(device.tiltX.value, -90, 90);
            ty = (float) Math.clamp(device.tiltY.value, -90, 90);
            axes |= TILT;
        }
        long id = type >= 18 ? ((long) source << 32) | Integer.toUnsignedLong(detail) : source;
        int phase = type == 4 || type == 18 ? BEGIN : type == 5 || type == 20 ? FINISH : MOVE;
        int[] offset = windowOffsets.getOrDefault(event.get(JAVA_LONG, 72), new int[]{0, 0});
        Point point = new Point(event.get(JAVA_DOUBLE, 104) + offset[0], event.get(JAVA_DOUBLE, 112) + offset[1],
                event.get(JAVA_LONG, 40) & 0xffffffffL, pressure, tx, ty, axes);
        listener.accept(new Frame(id, phase, type >= 18 ? TOUCH : device.tool, List.of(point)));
    }
    private void refreshWindows() {
        // Keep the descendant snapshot stable while selecting events. Without this brief server
        // transaction, a renderer can destroy a child between XQueryTree and XISelectEvents.
        api.integer("XGrabServer", display);
        try { selectTree(window, 0); }
        finally { api.integer("XUngrabServer", display); api.integer("XFlush", display); }
    }
    private void selectTree(long target, int depth) {
        if (depth > 32 || windowOffsets.size() > 1024) throw new IllegalStateException("Drawing window hierarchy is too large");
        try (Arena scratch = Arena.ofConfined()) {
            if (api.integer("XISelectEvents", display, target, eventMasks, 2) != 0)
                throw new IllegalStateException("XISelectEvents failed");
            api.integer("XSelectInput", display, target, 1L << 19); // SubstructureNotifyMask
            MemorySegment x = scratch.allocate(JAVA_INT), y = scratch.allocate(JAVA_INT), child = scratch.allocate(JAVA_LONG);
            api.integer("XTranslateCoordinates", display, target, window, 0, 0, x, y, child);
            windowOffsets.put(target, new int[]{x.get(JAVA_INT, 0), y.get(JAVA_INT, 0)});
            MemorySegment root = scratch.allocate(JAVA_LONG), parent = scratch.allocate(JAVA_LONG),
                    children = scratch.allocate(ADDRESS), count = scratch.allocate(JAVA_INT);
            if (api.integer("XQueryTree", display, target, root, parent, children, count) != 0) {
                MemorySegment nodes = children.get(ADDRESS, 0);
                try {
                    int n = count.get(JAVA_INT, 0);
                    for (int i = 0; i < n; i++) selectTree(nodes.reinterpret(n * 8L).get(JAVA_LONG, i * 8L), depth + 1);
                } finally { if (nodes.address() != 0) api.integer("XFree", nodes); }
            }
        }
    }
    private Device queryDevice(int id) {
        try (Arena scratch = Arena.ofConfined()) {
            MemorySegment count = scratch.allocate(JAVA_INT);
            MemorySegment raw = api.pointer("XIQueryDevice", display, id, count);
            if (raw.address() == 0 || count.get(JAVA_INT, 0) == 0) return new Device(MOUSE, null, null, null);
            try {
                MemorySegment info = raw.reinterpret(40);
                String name = info.get(ADDRESS, 8).reinterpret(4096).getString(0).toLowerCase(Locale.ROOT);
                int n = info.get(JAVA_INT, 28);
                MemorySegment classes = info.get(ADDRESS, 32).reinterpret(n * 8L);
                Axis pressure = null, tx = null, ty = null;
                boolean touch = false, absolute = false;
                for (int i = 0; i < n; i++) {
                    MemorySegment cls = classes.get(ADDRESS, i * 8L).reinterpret(56);
                    int type = cls.get(JAVA_INT, 0);
                    if (type == 8) touch = true;
                    if (type != 2) continue;
                    if (cls.get(JAVA_INT, 52) == 1) absolute = true;
                    MemorySegment atomName = api.pointer("XGetAtomName", display, cls.get(JAVA_LONG, 16));
                    if (atomName.address() == 0) continue;
                    String label;
                    try { label = atomName.reinterpret(4096).getString(0); }
                    finally { api.integer("XFree", atomName); }
                    Axis axis = new Axis(cls.get(JAVA_INT, 8), cls.get(JAVA_DOUBLE, 24),
                            cls.get(JAVA_DOUBLE, 32), cls.get(JAVA_DOUBLE, 40));
                    switch (label) {
                        case "Abs Pressure", "Abs MT Pressure" -> pressure = axis;
                        case "Abs Tilt X" -> tx = axis;
                        case "Abs Tilt Y" -> ty = axis;
                        default -> { }
                    }
                }
                int tool = touch || name.contains("touch") ? TOUCH : absolute &&
                        (pressure != null || tx != null || name.contains("stylus") || name.contains("pen") || name.contains("eraser")) ? PEN : MOUSE;
                return new Device(tool, tool == MOUSE ? null : pressure, tx, ty);
            } finally { api.procedure("XIFreeDeviceInfo", raw); }
        }
    }
    @Override public void close() {
        stopped = true;
        if (Thread.currentThread() == worker) return;
        try { worker.join(1000); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IllegalStateException(failure); }
        if (worker.isAlive()) throw new IllegalStateException("XInput2 capture did not stop");
    }
}
