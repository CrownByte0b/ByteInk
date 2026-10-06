package com.vivenotes.byteink.compose;

import java.lang.foreign.MemorySegment;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Tablet-v2 and touch on an owned queue of the toolkit's borrowed Wayland connection. */
final class WaylandPenBridge implements NativePenBridge {
    private record Proxy(MemorySegment pointer, String type, long parent) {}
    private static final AtomicLong IDS = new AtomicLong(1);
    // A terminal display cannot dispatch pending new-id closures. Retain their native metadata
    // until process shutdown rather than let the toolkit inspect dangling pointers on its display.
    private static final List<WaylandWire> FAILED_DISPLAYS = new ArrayList<>();
    private static final class Tool {
        final long id = IDS.getAndIncrement();
        long surface, tablet;
        double x, y;
        float pressure, tiltX, tiltY;
        int capabilities, axes, kind = PEN;
        boolean active, changed, out;
        final List<Integer> transitions = new ArrayList<>();
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
    private final WaylandWire wire;
    private final MemorySegment display, queue;
    private final long target;
    private final Consumer<Frame> frames;
    private final Consumer<Throwable> failure;
    private final Map<Long, Proxy> proxies = new LinkedHashMap<>();
    private final Map<Integer, MemorySegment> seats = new LinkedHashMap<>();
    private final Map<Long, MemorySegment> tabletSeats = new HashMap<>(), seatTouches = new HashMap<>();
    private final Map<Long, Tool> tools = new HashMap<>();
    private final Map<Long, Touch> touches = new HashMap<>();
    private MemorySegment manager;
    private int managerGlobal;
    private volatile boolean running = true;
    private boolean closed;
    private final Thread reader;

    WaylandPenBridge(long borrowedDisplay, long surface, Consumer<Frame> frames, Consumer<Throwable> failure) {
        this.display = MemorySegment.ofAddress(borrowedDisplay); this.target = surface;
        this.frames = frames; this.failure = failure;
        wire = new WaylandWire(this::failed);
        queue = wire.pointer("wl_display_create_queue", display);
        if (queue.address() == 0) { wire.close(); throw new IllegalStateException("Cannot create Wayland ink queue"); }
        reader = new Thread(this::read, "byteink-wayland-pen"); reader.setDaemon(true);
        try {
            MemorySegment wrapper = wire.pointer("wl_proxy_create_wrapper", display);
            MemorySegment registry;
            try {
                wire.procedure("wl_proxy_set_queue", wrapper, queue);
                registry = wire.child(wrapper, 1, "wl_registry", MemorySegment.NULL);
            } finally { wire.procedure("wl_proxy_wrapper_destroy", wrapper); }
            own(registry, "wl_registry", 0);
            if (wire.integer("wl_display_roundtrip_queue", display, queue) < 0 || !running)
                throw new IllegalStateException("Cannot discover Wayland input globals");
            // Flush bindings and device descriptions before accepting input.
            if (wire.integer("wl_display_roundtrip_queue", display, queue) < 0 || !running)
                throw new IllegalStateException("Cannot initialize Wayland ink devices");
            reader.start();
        } catch (Throwable error) { close(); throw error; }
    }
    private void failed(Throwable error) {
        if (running) { running = false; failure.accept(error); }
    }
    private void read() {
        try { wire.readLoop(display, queue, () -> running); }
        catch (Throwable error) { failed(error); }
    }
    private MemorySegment own(MemorySegment pointer, String type, long parent) {
        if (pointer.address() == 0) throw new IllegalStateException("Cannot create " + type);
        proxies.put(pointer.address(), new Proxy(pointer, type, parent));
        if (type.equals("zwp_tablet_tool_v2")) tools.put(pointer.address(), new Tool());
        if (type.equals("wl_touch")) touches.put(pointer.address(), new Touch());
        wire.listen(pointer, type, this::event);
        return pointer;
    }
    private void tabletSeat(MemorySegment seat) {
        if (manager != null && !tabletSeats.containsKey(seat.address())) {
            MemorySegment tabletSeat = wire.child(manager, 0, "zwp_tablet_seat_v2", MemorySegment.NULL, seat);
            tabletSeats.put(seat.address(), own(tabletSeat, "zwp_tablet_seat_v2", seat.address()));
        }
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
                    }
                } else if (event.equals("global_remove")) {
                    int name = number(args, 0);
                    MemorySegment seat = seats.remove(name);
                    if (seat != null) release(seat.address());
                    if (manager != null && name == managerGlobal) {
                        for (MemorySegment child : List.copyOf(tabletSeats.values())) release(child.address());
                        release(manager.address()); manager = null;
                    }
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
                            cancel(t); t.surface = 0; t.tablet = 0;
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
                cancel(tool); tool.tablet = pointer(args, 1).address(); tool.surface = pointer(args, 2).address();
                tool.axes = 0; tool.out = false;
            }
            case "proximity_out" -> tool.out = true;
            case "down" -> tool.transitions.add(BEGIN);
            case "up" -> tool.transitions.add(FINISH);
            case "motion" -> { tool.x = fixed(args, 0); tool.y = fixed(args, 1); tool.changed = true; }
            case "pressure" -> { tool.pressure = Math.min(65535L, Integer.toUnsignedLong(number(args, 0))) / 65535f; tool.axes |= tool.capabilities & PRESSURE; tool.changed = true; }
            case "tilt" -> { tool.tiltX = (float) fixed(args, 0); tool.tiltY = (float) fixed(args, 1); tool.axes |= tool.capabilities & TILT; tool.changed = true; }
            case "frame" -> {
                if (tool.surface == target) {
                    Point point = new Point(tool.x, tool.y, Integer.toUnsignedLong(number(args, 0)), tool.pressure, tool.tiltX, tool.tiltY, tool.kind == PEN ? tool.axes : 0);
                    boolean transitioned = !tool.transitions.isEmpty();
                    for (int phase : List.copyOf(tool.transitions)) {
                        if (phase == BEGIN) { if (tool.active) cancel(tool); tool.active = true; }
                        if (tool.active) frames.accept(new Frame(tool.id, phase, tool.kind, List.of(point)));
                        if (phase == FINISH) tool.active = false;
                    }
                    if (!transitioned && tool.active && tool.changed) frames.accept(new Frame(tool.id, MOVE, tool.kind, List.of(point)));
                    if (tool.out) cancel(tool);
                }
                tool.transitions.clear(); tool.changed = false;
                if (tool.out) { tool.surface = 0; tool.axes = 0; }
            }
            case "removed" -> release(key);
            default -> { /* Descriptive axes, buttons and barrel rotation are not shaft orientation. */ }
        }
    }
    private void touch(long key, String event, Object[] args) {
        Touch touch = touches.get(key);
        switch (event) {
            case "down" -> {
                if (pointer(args, 2).address() == target) {
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
    private void cancel(Touch touch) {
        Set<Long> ids = new HashSet<>();
        touch.contacts.values().forEach(c -> ids.add(c.id));
        touch.pending.forEach(f -> ids.add(f.pointerId()));
        ids.forEach(id -> frames.accept(new Frame(id, CANCEL, TOUCH, List.of())));
        touch.contacts.clear(); touch.pending.clear();
    }
    private void release(long key) {
        for (Proxy child : List.copyOf(proxies.values())) if (child.parent() == key) release(child.pointer().address());
        Tool tool = tools.remove(key); if (tool != null) cancel(tool);
        Touch touch = touches.remove(key); if (touch != null) cancel(touch);
        tabletSeats.values().removeIf(p -> p.address() == key);
        seatTouches.values().removeIf(p -> p.address() == key);
        Proxy proxy = proxies.remove(key);
        if (proxy != null) wire.destroy(proxy.pointer(), proxy.type());
    }
    @Override public void close() {
        if (closed) return;
        running = false;
        if (reader.isAlive()) {
            try { reader.join(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Wayland shutdown interrupted", e); }
            if (reader.isAlive()) throw new IllegalStateException("Wayland ink reader did not stop; its callbacks remain allocated");
        }
        closed = true;
        boolean prepared = false, healthy = false, released = false;
        try {
            // Reserve a read after draining our queue. All other toolkit readers now wait until
            // cancel_read, so they cannot create an unobserved child between draining and destroy.
            while (true) {
                if (wire.integer("wl_display_prepare_read_queue", display, queue) == 0) { prepared = true; break; }
                if (wire.integer("wl_display_dispatch_queue_pending", display, queue) < 0) break;
            }
            healthy = prepared && wire.integer("wl_display_get_error", display) == 0;
            for (long key : List.copyOf(proxies.keySet())) release(key);
            wire.integer("wl_display_flush", display);
            released = true;
        } finally {
            // prepare_read_queue can succeed on a terminal display too; pair every successful call.
            if (prepared) wire.procedure("wl_display_cancel_read", display);
            wire.procedure("wl_event_queue_destroy", queue);
            if (healthy && released) wire.close();
            else synchronized (FAILED_DISPLAYS) { FAILED_DISPLAYS.add(wire); }
        }
    }
}
