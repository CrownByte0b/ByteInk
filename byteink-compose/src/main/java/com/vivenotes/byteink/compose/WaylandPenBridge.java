package com.vivenotes.byteink.compose;

import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Tablet-v2 and touch on an owned queue of the toolkit's borrowed Wayland connection. */
final class WaylandPenBridge implements NativePenBridge {
    private record Proxy(MemorySegment pointer, String type, long parent) {}
    private static final AtomicLong IDS = new AtomicLong(1);
    // A terminal display cannot dispatch pending new-id closures. Retain their native metadata
    // until process shutdown rather than let the toolkit inspect dangling pointers on its display.
    private static final List<WaylandWire> FAILED_DISPLAYS = new ArrayList<>();
    // Surfaces a live bridge serves, with how many bridges serve each. Binding tablet-v2 takes the
    // pen from the toolkit for every surface of the connection, and a compositor shows no cursor for
    // a tool over a client surface until the client sets one: a surface nobody serves gets the arrow.
    private static final ConcurrentHashMap<Long, Integer> TARGETS = new ConcurrentHashMap<>();
    // Interfaces without events take no listener.
    private static final Set<String> SILENT = Set.of("wl_compositor", "wp_cursor_shape_manager_v1", "wp_cursor_shape_device_v1");
    private static final class Tool {
        final long id = IDS.getAndIncrement();
        long surface, tablet;
        double x, y;
        float pressure, tiltX, tiltY;
        int capabilities, axes, kind = PEN;
        // touching: the tool is down, including a contact ignored for beginning before readiness.
        boolean active, changed, out, hovering, touching;
        final List<Integer> transitions = new ArrayList<>();
        // The latest proximity_in serial, which every cursor request for this focus must carry.
        int serial;
        boolean proximity;
        // The cursor shown since that proximity_in: -1 none yet, else a PenCursors shape at cursorScale.
        int cursorShape = -1, cursorScale;
        MemorySegment shapeDevice, cursorSurface;
        long cursorBuffer;
        int cursorBufferScale;
    }
    private static final class Contact {
        final long id = IDS.getAndIncrement();
        double x, y;
        Contact(double x, double y) { this.x = x; this.y = y; }
    }
    private static final class Touch {
        final Map<Integer, Contact> contacts = new HashMap<>();
        final List<Frame> pending = new ArrayList<>();
    }
    private final Object lifecycle = new Object();
    private WaylandWire wire;
    private final MemorySegment display;
    private MemorySegment queue = MemorySegment.NULL, displayWrapper = MemorySegment.NULL;
    private final long target;
    private final Consumer<Frame> frames;
    private final Runnable ready;
    private final Consumer<Throwable> failure;
    private final Map<Long, Proxy> proxies = new LinkedHashMap<>();
    private final Map<Integer, MemorySegment> seats = new LinkedHashMap<>();
    private final Map<Long, MemorySegment> tabletSeats = new HashMap<>(), seatTouches = new HashMap<>();
    private final Map<Long, Tool> tools = new HashMap<>();
    private final Map<Long, Touch> touches = new HashMap<>();
    private MemorySegment registry, manager;
    private int managerGlobal;
    // Pen cursors: cursor-shape-v1 where the compositor has it, else themed cursor surfaces. The
    // compositor and shm globals are bound only once a surface cursor is needed.
    private final boolean cursorShapeAllowed = !"false".equalsIgnoreCase(System.getProperty("byteink.wayland.cursorShape"));
    private MemorySegment shapeManager, compositor, shm;
    private int shapeManagerGlobal, compositorGlobal, compositorVersion, shmGlobal;
    private final Map<Integer, MemorySegment> cursorThemes = new HashMap<>();
    private boolean cursorThemeMissing;
    // Published by the EDT for tools over the target; applied on the reader.
    private volatile int requestedShape = PenCursors.DEFAULT, requestedScale = 1;
    private volatile boolean running = true;
    private boolean initialized;
    private int discoveryBarriers;
    private Throwable failureCause;
    private final Thread reader;
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();

    WaylandPenBridge(long borrowedDisplay, long surface, Consumer<Frame> frames, Runnable ready, Consumer<Throwable> failure) {
        this.display = MemorySegment.ofAddress(borrowedDisplay); this.target = surface;
        this.frames = frames; this.ready = ready; this.failure = failure;
        TARGETS.merge(surface, 1, Integer::sum);
        reader = new Thread(this::read, "byteink-wayland-pen"); reader.setDaemon(true);
        reader.start();
    }
    private void discover() {
        queue = wire.pointer("wl_display_create_queue", display);
        if (queue.address() == 0) throw new IllegalStateException("Cannot create Wayland ink queue");
        // Wrappers assign the queue atomically when each new proxy is created, even while JBR
        // reads the shared display. The toolkit's display proxy and default queue stay untouched.
        displayWrapper = wire.pointer("wl_proxy_create_wrapper", display);
        if (displayWrapper.address() == 0) throw new IllegalStateException("Cannot wrap Wayland display");
        wire.procedure("wl_proxy_set_queue", displayWrapper, queue);
        registry = own(wire.child(displayWrapper, 1, "wl_registry", MemorySegment.NULL), "wl_registry", 0);
        discoveryBarrier();
    }
    private void discoveryBarrier() {
        own(wire.child(displayWrapper, 0, "wl_callback", MemorySegment.NULL), "wl_callback", 0);
    }
    private void failed(Throwable error) {
        boolean report = running;
        running = false;
        if (failureCause == null) failureCause = error;
        else if (failureCause != error) failureCause.addSuppressed(error);
        if (report) try { failure.accept(error); }
        catch (Throwable reporting) { error.addSuppressed(reporting); }
    }
    private void read() {
        try {
            if (!running) return;
            WaylandWire acquired = new WaylandWire(this::failed);
            synchronized (lifecycle) { wire = acquired; }
            if (!running) return;
            discover();
            wire.readLoop(display, queue, () -> running, this::cursors);
        }
        catch (Throwable error) { failed(error); }
        finally {
            try { releaseResources(); }
            catch (Throwable error) { failed(error); }
            TARGETS.computeIfPresent(target, (surface, count) -> count > 1 ? count - 1 : null);
            if (failureCause == null) stopped.complete(null);
            else stopped.completeExceptionally(failureCause);
        }
    }
    // Completion is for lifecycle verification/owners outside EDT; close itself never waits.
    CompletableFuture<Void> termination() { return stopped; }
    private MemorySegment own(MemorySegment pointer, String type, long parent) {
        if (pointer.address() == 0) throw new IllegalStateException("Cannot create " + type);
        proxies.put(pointer.address(), new Proxy(pointer, type, parent));
        if (type.equals("zwp_tablet_tool_v2")) tools.put(pointer.address(), new Tool());
        if (type.equals("wl_touch")) touches.put(pointer.address(), new Touch());
        if (!SILENT.contains(type)) wire.listen(pointer, type, this::event);
        if (type.equals("zwp_tablet_tool_v2") && running) shapeDevice(pointer.address());
        return pointer;
    }
    private void tabletSeat(MemorySegment seat) {
        if (manager != null && !tabletSeats.containsKey(seat.address())) {
            MemorySegment tabletSeat = wire.child(manager, 0, "zwp_tablet_seat_v2", MemorySegment.NULL, seat);
            tabletSeats.put(seat.address(), own(tabletSeat, "zwp_tablet_seat_v2", seat.address()));
        }
    }
    private void shapeDevice(long toolKey) {
        Tool tool = tools.get(toolKey);
        if (shapeManager == null || tool == null || tool.shapeDevice != null) return;
        tool.shapeDevice = own(wire.child(shapeManager, 2, "wp_cursor_shape_device_v1", MemorySegment.NULL,
            proxies.get(toolKey).pointer()), "wp_cursor_shape_device_v1", toolKey);
    }
    private static int number(Object[] args, int i) { return (Integer) args[4 + i]; }
    private static MemorySegment pointer(Object[] args, int i) { return (MemorySegment) args[4 + i]; }
    private static double fixed(Object[] args, int i) { return number(args, i) / 256.0; }
    private static String createdType(String event) {
        return switch (event) {
            case "tablet_added" -> "zwp_tablet_v2";
            case "tool_added" -> "zwp_tablet_tool_v2";
            case "pad_added" -> "zwp_tablet_pad_v2";
            case "group" -> "zwp_tablet_pad_group_v2";
            case "ring" -> "zwp_tablet_pad_ring_v2";
            case "strip" -> "zwp_tablet_pad_strip_v2";
            case "dial" -> "zwp_tablet_pad_dial_v2";
            default -> null;
        };
    }
    private void event(Object[] args) {
        String event = (String) args[0];
        MemorySegment self = (MemorySegment) args[3];
        Proxy proxy = proxies.get(self.address());
        if (proxy == null) return;
        if (!running) {
            // libwayland creates new-id proxies during reading, before listener dispatch. Still
            // take ownership of these children when stopping; ignoring the callback leaks them.
            String type = createdType(event);
            if (type != null) own(pointer(args, 0), type, self.address());
            return;
        }
        switch (proxy.type()) {
            case "wl_callback" -> {
                release(self.address());
                if (++discoveryBarriers == 1) discoveryBarrier();
                else { initialized = true; ready.run(); }
            }
            case "wl_registry" -> {
                if (event.equals("global")) {
                    int name = number(args, 0), version = number(args, 2);
                    String type = WaylandProtocol.string(pointer(args, 1));
                    if (type.equals("zwp_tablet_manager_v2") && manager == null) {
                        managerGlobal = name;
                        manager = own(wire.bind(self, name, type, 1), type, 0);
                        seats.values().forEach(this::tabletSeat);
                    } else if (type.equals("wl_seat")) {
                        MemorySegment seat = own(wire.bind(self, name, type, Math.min(5, version)), type, 0);
                        seats.put(name, seat); tabletSeat(seat);
                    } else if (type.equals("wp_cursor_shape_manager_v1") && shapeManager == null && cursorShapeAllowed) {
                        // Version 1 holds every shape an AWT cursor maps to.
                        shapeManagerGlobal = name;
                        shapeManager = own(wire.bind(self, name, type, 1), type, 0);
                        List.copyOf(tools.keySet()).forEach(this::shapeDevice);
                    } else if (type.equals("wl_compositor") && compositorGlobal == 0) {
                        compositorGlobal = name; compositorVersion = version;
                    } else if (type.equals("wl_shm") && shmGlobal == 0) {
                        shmGlobal = name;
                    }
                } else if (event.equals("global_remove")) {
                    int name = number(args, 0);
                    MemorySegment seat = seats.remove(name);
                    if (seat != null) release(seat.address());
                    if (manager != null && name == managerGlobal) {
                        for (MemorySegment child : List.copyOf(tabletSeats.values())) release(child.address());
                        release(manager.address()); manager = null;
                    }
                    if (shapeManager != null && name == shapeManagerGlobal) {
                        for (Tool tool : tools.values()) if (tool.shapeDevice != null) {
                            release(tool.shapeDevice.address()); tool.shapeDevice = null; tool.cursorShape = -1;
                        }
                        release(shapeManager.address()); shapeManager = null;
                    }
                    if (name == compositorGlobal) compositorGlobal = 0;
                    if (name == shmGlobal) shmGlobal = 0;
                }
            }
            case "wl_seat" -> {
                if (event.equals("capabilities")) {
                    if ((number(args, 0) & 4) != 0 && !seatTouches.containsKey(self.address())) {
                        MemorySegment touch = own(wire.child(self, 2, "wl_touch", MemorySegment.NULL), "wl_touch", self.address());
                        seatTouches.put(self.address(), touch);
                    } else if ((number(args, 0) & 4) == 0) {
                        MemorySegment touch = seatTouches.get(self.address());
                        if (touch != null) release(touch.address());
                    }
                }
            }
            case "zwp_tablet_seat_v2" -> {
                String type = Objects.requireNonNull(createdType(event));
                own(pointer(args, 0), type, self.address());
            }
            case "zwp_tablet_tool_v2" -> tool(self.address(), event, args);
            case "wl_touch" -> touch(self.address(), event, args);
            default -> {
                // Pads do not author strokes, but their children need listeners and owned lifetimes.
                String child = createdType(event);
                if (child != null) own(pointer(args, 0), child, self.address());
                if (event.equals("removed")) {
                    if (proxy.type().equals("zwp_tablet_v2")) {
                        tools.values().stream().filter(t -> t.tablet == self.address()).forEach(t -> {
                            cancel(t); endHover(t); t.surface = 0; t.tablet = 0; t.proximity = false;
                        });
                    }
                    release(self.address());
                }
            }
        }
    }
    private void tool(long key, String event, Object[] args) {
        Tool tool = tools.get(key);
        switch (event) {
            case "type" -> tool.kind = switch (number(args, 0)) { case 0x146, 0x147 -> MOUSE; case 0x145 -> TOUCH; default -> PEN; };
            case "capability" -> { if (number(args, 0) == 1) tool.capabilities |= TILT; if (number(args, 0) == 2) tool.capabilities |= PRESSURE; }
            case "proximity_in" -> {
                cancel(tool); endHover(tool);
                tool.tablet = pointer(args, 1).address(); tool.surface = pointer(args, 2).address();
                tool.axes = 0; tool.out = false; tool.touching = false;
                // The compositor forgets a tool's cursor when focus changes; answer each proximity_in.
                tool.serial = number(args, 0); tool.proximity = true; tool.cursorShape = -1;
                cursor(key, tool);
            }
            case "proximity_out" -> tool.out = true;
            // A sync reply can arrive between down and frame; gate the actual begin event,
            // not only the frame that later publishes its accumulated observations.
            case "down" -> { tool.touching = true; if (initialized) tool.transitions.add(BEGIN); }
            case "up" -> { tool.touching = false; tool.transitions.add(FINISH); }
            case "motion" -> { tool.x = fixed(args, 0); tool.y = fixed(args, 1); tool.changed = true; }
            case "pressure" -> { tool.pressure = Math.min(65535L, Integer.toUnsignedLong(number(args, 0))) / 65535f; tool.axes |= tool.capabilities & PRESSURE; tool.changed = true; }
            case "tilt" -> { tool.tiltX = (float) fixed(args, 0); tool.tiltY = (float) fixed(args, 1); tool.axes |= tool.capabilities & TILT; tool.changed = true; }
            case "frame" -> {
                boolean mine = initialized && tool.surface == target;
                if (mine && (tool.active || !tool.transitions.isEmpty())) {
                    Point point = new Point(tool.x, tool.y, Integer.toUnsignedLong(number(args, 0)), tool.pressure, tool.tiltX, tool.tiltY, tool.kind == PEN ? tool.axes : 0);
                    boolean transitioned = !tool.transitions.isEmpty();
                    for (int phase : List.copyOf(tool.transitions)) {
                        if (phase == BEGIN) { if (tool.active) cancel(tool); tool.active = true; }
                        if (tool.active) frames.accept(new Frame(tool.id, phase, tool.kind, List.of(point)));
                        if (phase == FINISH) tool.active = false;
                    }
                    if (!transitioned && tool.active && tool.changed) frames.accept(new Frame(tool.id, MOVE, tool.kind, List.of(point)));
                    if (tool.out) cancel(tool);
                    // Contact ends hover without HOVER_EXIT; motion after the lift hovers again.
                    tool.hovering = false;
                } else if (mine && tool.proximity && !tool.out && !tool.touching && tool.changed && tool.kind != TOUCH) {
                    // Hover reports where the tool is, not a contact: no pressure, tilt when reported.
                    frames.accept(new Frame(tool.id, HOVER, tool.kind, List.of(new Point(tool.x, tool.y,
                        Integer.toUnsignedLong(number(args, 0)), 0f, tool.tiltX, tool.tiltY, tool.kind == PEN ? tool.axes & TILT : 0))));
                    tool.hovering = true;
                }
                if (tool.out) endHover(tool);
                tool.transitions.clear(); tool.changed = false;
                if (tool.out) { tool.surface = 0; tool.axes = 0; tool.proximity = false; tool.touching = false; tool.cursorShape = -1; }
            }
            case "removed" -> release(key);
            default -> { /* Descriptive axes, buttons and barrel rotation are not shaft orientation. */ }
        }
    }
    private void touch(long key, String event, Object[] args) {
        Touch touch = touches.get(key);
        switch (event) {
            case "down" -> {
                if (initialized && pointer(args, 2).address() == target) {
                    Contact contact = new Contact(fixed(args, 4), fixed(args, 5));
                    Contact old = touch.contacts.put(number(args, 3), contact);
                    if (old != null) touch.pending.add(new Frame(old.id, CANCEL, TOUCH, List.of()));
                    touch.pending.add(contactFrame(contact, BEGIN, number(args, 1)));
                }
            }
            case "motion" -> {
                Contact contact = touch.contacts.get(number(args, 1));
                if (contact != null) { contact.x = fixed(args, 2); contact.y = fixed(args, 3); touch.pending.add(contactFrame(contact, MOVE, number(args, 0))); }
            }
            case "up" -> {
                Contact contact = touch.contacts.remove(number(args, 2));
                if (contact != null) touch.pending.add(contactFrame(contact, FINISH, number(args, 1)));
            }
            case "frame" -> { touch.pending.forEach(frames); touch.pending.clear(); }
            case "cancel" -> cancel(touch);
            default -> { /* Shape/orientation do not describe a stylus shaft. */ }
        }
    }
    private Frame contactFrame(Contact contact, int phase, int time) {
        return new Frame(contact.id, phase, TOUCH, List.of(new Point(contact.x, contact.y, Integer.toUnsignedLong(time), 0, 0, 0, 0)));
    }
    private void cancel(Tool tool) {
        if (tool.active) frames.accept(new Frame(tool.id, CANCEL, tool.kind, List.of()));
        tool.active = false; tool.transitions.clear(); tool.changed = false;
    }
    private void endHover(Tool tool) {
        if (!tool.hovering) return;
        tool.hovering = false;
        frames.accept(new Frame(tool.id, HOVER_EXIT, tool.kind, List.of()));
    }
    private void cancel(Touch touch) {
        Set<Long> ids = new HashSet<>();
        touch.contacts.values().forEach(c -> ids.add(c.id));
        touch.pending.forEach(f -> ids.add(f.pointerId()));
        ids.forEach(id -> frames.accept(new Frame(id, CANCEL, TOUCH, List.of())));
        touch.contacts.clear(); touch.pending.clear();
    }

    @Override public void cursor(int shape, int scale) {
        if (shape == requestedShape && scale == requestedScale) return;
        requestedScale = scale; requestedShape = shape;
        synchronized (lifecycle) {
            // Before discovery there is no reader to wake; proximity_in applies the request.
            // A wakeup that fails leaves the cursor as it is; the reader reports its own failures.
            if (running && wire != null) try { wire.wakeReader(); } catch (IllegalStateException ignored) { }
        }
    }
    // Reader thread, woken by cursor(): every tool over the target shows the requested cursor.
    private void cursors() {
        for (Map.Entry<Long, Tool> entry : List.copyOf(tools.entrySet())) {
            if (entry.getValue().surface == target) cursor(entry.getKey(), entry.getValue());
        }
    }
    // Reader thread: shows the cursor a tool in proximity should have, unless it already does.
    private void cursor(long key, Tool tool) {
        if (!running || !tool.proximity || tool.surface == 0) return;
        int shape;
        if (tool.surface == target) shape = requestedShape;
        else if (!TARGETS.containsKey(tool.surface)) shape = PenCursors.DEFAULT;
        else return; // That surface's own bridge chooses its cursor.
        int scale = requestedScale;
        if (tool.cursorShape == shape && (tool.shapeDevice != null || shape == PenCursors.HIDDEN || tool.cursorScale == scale)) return;
        MemorySegment proxy = proxies.get(key).pointer();
        int version = wire.integer("wl_proxy_get_version", proxy);
        if (shape == PenCursors.HIDDEN) {
            wire.marshal(proxy, 0, MemorySegment.NULL, version, 0, tool.serial, MemorySegment.NULL, 0, 0);
        } else if (tool.shapeDevice != null) {
            wire.marshal(tool.shapeDevice, 1, MemorySegment.NULL, wire.integer("wl_proxy_get_version", tool.shapeDevice), 0, tool.serial, shape);
        } else if (!surfaceCursor(key, proxy, version, tool, shape, scale)) {
            return;
        }
        tool.cursorShape = shape; tool.cursorScale = scale;
    }
    // Without cursor-shape-v1: the theme's image on a surface of the tool's own, as for wl_pointer.
    private boolean surfaceCursor(long key, MemorySegment proxy, int version, Tool tool, int shape, int scale) {
        if (compositor == null) {
            if (compositorGlobal == 0) return false;
            compositor = own(wire.bind(registry, compositorGlobal, "wl_compositor", Math.min(4, compositorVersion)), "wl_compositor", 0);
        }
        int surfaceVersion = wire.integer("wl_proxy_get_version", compositor);
        int bufferScale = surfaceVersion >= 3 ? scale : 1;
        WaylandWire.CursorImage image = cursorImage(shape, bufferScale);
        if (image != null && (image.width() % bufferScale != 0 || image.height() % bufferScale != 0)) {
            // A buffer must be a whole multiple of its scale: use the theme's unscaled image instead.
            bufferScale = 1;
            image = cursorImage(shape, 1);
        }
        if (image == null) return false;
        // A surface may only ever be the cursor of one tool.
        if (tool.cursorSurface == null) tool.cursorSurface = own(wire.child(compositor, 0, "wl_surface", MemorySegment.NULL), "wl_surface", key);
        MemorySegment surface = tool.cursorSurface;
        wire.marshal(proxy, 0, MemorySegment.NULL, version, 0, tool.serial, surface,
            image.hotspotX() / bufferScale, image.hotspotY() / bufferScale);
        if (tool.cursorBuffer != image.buffer().address() || tool.cursorBufferScale != bufferScale) {
            wire.marshal(surface, 1, MemorySegment.NULL, surfaceVersion, 0, image.buffer(), 0, 0);
            if (surfaceVersion >= 3) wire.marshal(surface, 8, MemorySegment.NULL, surfaceVersion, 0, bufferScale);
            if (surfaceVersion >= 4) wire.marshal(surface, 9, MemorySegment.NULL, surfaceVersion, 0, 0, 0, image.width(), image.height());
            else wire.marshal(surface, 2, MemorySegment.NULL, surfaceVersion, 0, 0, 0, image.width() / bufferScale, image.height() / bufferScale);
            wire.marshal(surface, 6, MemorySegment.NULL, surfaceVersion, 0);
            tool.cursorBuffer = image.buffer().address(); tool.cursorBufferScale = bufferScale;
        }
        return true;
    }
    private WaylandWire.CursorImage cursorImage(int shape, int scale) {
        MemorySegment theme = cursorTheme(scale);
        return theme == null ? null : wire.cursorImage(theme, PenCursors.themeNames(shape));
    }
    // The user's cursor theme as Xcursor clients find it, loaded once per scale.
    private MemorySegment cursorTheme(int scale) {
        MemorySegment theme = cursorThemes.get(scale);
        if (theme != null || cursorThemeMissing) return theme;
        if (shm == null) {
            if (shmGlobal == 0) { cursorThemeMissing = true; return null; }
            shm = own(wire.bind(registry, shmGlobal, "wl_shm", 1), "wl_shm", 0);
        }
        String name = System.getenv("XCURSOR_THEME");
        theme = wire.cursorTheme(name == null || name.isBlank() ? null : name, cursorSize() * scale, shm);
        if (theme == null) { cursorThemeMissing = true; return null; }
        cursorThemes.put(scale, theme);
        return theme;
    }
    private static int cursorSize() {
        try {
            int size = Integer.parseInt(Objects.requireNonNullElse(System.getenv("XCURSOR_SIZE"), "").trim());
            return size > 0 && size <= 256 ? size : 24;
        } catch (NumberFormatException unset) { return 24; }
    }

    private void release(long key) {
        for (Proxy child : List.copyOf(proxies.values())) if (child.parent() == key) release(child.pointer().address());
        Tool tool = tools.remove(key); if (tool != null) { cancel(tool); endHover(tool); }
        Touch touch = touches.remove(key); if (touch != null) cancel(touch);
        tabletSeats.values().removeIf(p -> p.address() == key);
        seatTouches.values().removeIf(p -> p.address() == key);
        Proxy proxy = proxies.remove(key);
        if (proxy != null) wire.destroy(proxy.pointer(), proxy.type());
    }
    @Override public void close() {
        synchronized (lifecycle) {
            if (!running) return;
            running = false;
            if (wire != null) wire.wakeReader();
        }
    }
    private void releaseResources() {
        if (wire == null) return;
        boolean prepared = false, healthy = false, released = false;
        try {
            // Reserve a read after draining our queue. All other toolkit readers now wait until
            // cancel_read, so they cannot create an unobserved child between draining and destroy.
            while (queue.address() != 0) {
                if (wire.integer("wl_display_prepare_read_queue", display, queue) == 0) { prepared = true; break; }
                if (wire.integer("wl_display_dispatch_queue_pending", display, queue) < 0) break;
            }
            healthy = prepared && wire.integer("wl_display_get_error", display) == 0;
            // Themes own their shm pool and buffers; release them before their wl_shm.
            for (MemorySegment theme : cursorThemes.values()) wire.destroyCursorTheme(theme);
            cursorThemes.clear();
            for (long key : List.copyOf(proxies.keySet())) release(key);
            if (displayWrapper.address() != 0) wire.procedure("wl_proxy_wrapper_destroy", displayWrapper);
            wire.integer("wl_display_flush", display);
            released = true;
        } finally {
            // prepare_read_queue can succeed on a terminal display too; pair every successful call.
            if (prepared) wire.procedure("wl_display_cancel_read", display);
            if (queue.address() != 0) wire.procedure("wl_event_queue_destroy", queue);
            // Exclude a concurrent stop+wake while closing its descriptor/arena. No protocol
            // dispatch or compositor wait runs under this short lock or on the UI thread.
            synchronized (lifecycle) {
                try { wire.closeWakeup(); }
                finally {
                    if ((healthy || queue.address() == 0) && released) wire.close();
                    else synchronized (FAILED_DISPLAYS) { FAILED_DISPLAYS.add(wire); }
                    wire = null;
                }
            }
        }
    }
}
