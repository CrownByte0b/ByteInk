package com.vivenotes.byteink.compose;

import java.lang.foreign.MemorySegment;

/** Sends protocol observations to the private test compositor on the actual toolkit connection. */
final class WaylandInputInjection implements AutoCloseable {
    private final WaylandWire wire = new WaylandWire(error -> { throw new AssertionError(error); });
    private final MemorySegment display, queue, registry;
    private MemorySegment control;
    WaylandInputInjection(long displayPointer) {
        display = MemorySegment.ofAddress(displayPointer);
        wire.protocol.define(new WaylandProtocol.Interface("byteink_test", 1, new WaylandProtocol.Message[]{
            new WaylandProtocol.Message("send", "ouiffiffu", "wl_surface", null, null, null, null, null, null, null, null),
            new WaylandProtocol.Message("destroy", "")
        }, new WaylandProtocol.Message[0]));
        queue = wire.pointer("wl_display_create_queue", display);
        MemorySegment wrapper = wire.pointer("wl_proxy_create_wrapper", display);
        try {
            wire.procedure("wl_proxy_set_queue", wrapper, queue);
            registry = wire.child(wrapper, 1, "wl_registry", MemorySegment.NULL);
        } finally { wire.procedure("wl_proxy_wrapper_destroy", wrapper); }
        wire.listen(registry, "wl_registry", args -> {
            if (args[0].equals("global") && WaylandProtocol.string((MemorySegment) args[5]).equals("byteink_test"))
                control = wire.bind(registry, (Integer) args[4], "byteink_test", 1);
        });
        if (wire.integer("wl_display_roundtrip_queue", display, queue) < 0 || control == null)
            throw new AssertionError("Run on the isolated tablet test compositor");
    }
    void send(long surface, int tool, int phase, double x, double y, int pressure, double tiltX, double tiltY, int time) {
        wire.marshal(control, 0, MemorySegment.NULL, 1, 0, MemorySegment.ofAddress(surface), tool, phase,
            (int) Math.round(x * 256), (int) Math.round(y * 256), pressure,
            (int) Math.round(tiltX * 256), (int) Math.round(tiltY * 256), time);
        if (wire.integer("wl_display_roundtrip_queue", display, queue) < 0) throw new AssertionError("Input injection failed");
    }
    @Override public void close() {
        wire.marshal(control, 1, MemorySegment.NULL, 1, 1);
        wire.destroy(registry, "wl_registry"); wire.integer("wl_display_flush", display);
        wire.procedure("wl_event_queue_destroy", queue); wire.close();
    }
}
