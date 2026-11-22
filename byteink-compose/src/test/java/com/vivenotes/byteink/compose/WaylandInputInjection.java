package com.vivenotes.byteink.compose;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

/** Sends protocol observations to the private test compositor on the actual toolkit connection. */
final class WaylandInputInjection implements AutoCloseable {
    /** What one tablet tool's cursor requests have been, as the private compositor recorded them. */
    record Cursor(int seat, int tool, int shapes, int shape, boolean shapeSerial, int surfaces, boolean surfaceSerial,
                  int hidden, int hotspotX, int hotspotY, int width, int height, int scale) {}
    private final WaylandWire wire = new WaylandWire(error -> { throw new AssertionError(error); });
    private final MemorySegment display, queue, registry;
    private final List<Cursor> reported = new ArrayList<>();
    private MemorySegment control;
    WaylandInputInjection(long displayPointer) {
        display = MemorySegment.ofAddress(displayPointer);
        wire.protocol.define(new WaylandProtocol.Interface("byteink_test", 2, new WaylandProtocol.Message[]{
            new WaylandProtocol.Message("send", "ouiffiffu", "wl_surface", null, null, null, null, null, null, null, null),
            new WaylandProtocol.Message("destroy", ""),
            new WaylandProtocol.Message("cursors", "2"),
        }, new WaylandProtocol.Message[]{
            new WaylandProtocol.Message("cursor", "2uuuuuuuuiiiii", null, null, null, null, null, null, null, null, null, null, null, null, null),
        }));
        queue = wire.pointer("wl_display_create_queue", display);
        MemorySegment wrapper = wire.pointer("wl_proxy_create_wrapper", display);
        try {
            wire.procedure("wl_proxy_set_queue", wrapper, queue);
            registry = wire.child(wrapper, 1, "wl_registry", MemorySegment.NULL);
        } finally { wire.procedure("wl_proxy_wrapper_destroy", wrapper); }
        wire.listen(registry, "wl_registry", args -> {
            if (args[0].equals("global") && WaylandProtocol.string((MemorySegment) args[5]).equals("byteink_test")) {
                control = wire.bind(registry, (Integer) args[4], "byteink_test", 2);
                wire.listen(control, "byteink_test", event -> {
                    int[] v = new int[13];
                    for (int i = 0; i < v.length; i++) v[i] = (Integer) event[4 + i];
                    reported.add(new Cursor(v[0], v[1], v[2], v[3], v[4] != 0, v[5], v[6] != 0, v[7], v[8], v[9], v[10], v[11], v[12]));
                });
            }
        });
        if (wire.integer("wl_display_roundtrip_queue", display, queue) < 0 || control == null)
            throw new AssertionError("Run on the isolated tablet test compositor");
    }
    void send(long surface, int tool, int phase, double x, double y, int pressure, double tiltX, double tiltY, int time) {
        wire.marshal(control, 0, MemorySegment.NULL, 2, 0, MemorySegment.ofAddress(surface), tool, phase,
            (int) Math.round(x * 256), (int) Math.round(y * 256), pressure,
            (int) Math.round(tiltX * 256), (int) Math.round(tiltY * 256), time);
        if (wire.integer("wl_display_roundtrip_queue", display, queue) < 0) throw new AssertionError("Input injection failed");
    }
    /** The cursor requests of every tool of this client's tablet seats so far. */
    List<Cursor> cursors() {
        reported.clear();
        wire.marshal(control, 2, MemorySegment.NULL, 2, 0);
        if (wire.integer("wl_display_roundtrip_queue", display, queue) < 0) throw new AssertionError("Cursor query failed");
        return List.copyOf(reported);
    }
    @Override public void close() {
        wire.marshal(control, 1, MemorySegment.NULL, 2, 1);
        wire.destroy(registry, "wl_registry"); wire.integer("wl_display_flush", display);
        wire.procedure("wl_event_queue_destroy", queue); wire.close();
    }
}
