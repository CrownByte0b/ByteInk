/*
    Copyright 2014 © Stephen "Lyude" Chandler Paul
    Copyright 2015-2024 © Red Hat, Inc.

    Permission is hereby granted, free of charge, to any person
    obtaining a copy of this software and associated documentation files
    (the "Software"), to deal in the Software without restriction,
    including without limitation the rights to use, copy, modify, merge,
    publish, distribute, sublicense, and/or sell copies of the Software,
    and to permit persons to whom the Software is furnished to do so,
    subject to the following conditions:

    The above copyright notice and this permission notice (including the
    next paragraph) shall be included in all copies or substantial
    portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
    EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
    MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
    NONINFRINGEMENT.  IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS
    BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN
    ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN
    CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.
  */
package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import java.util.*;

/** ABI metadata from Wayland tablet-v2; pinned XML SHA256 ac1128b26c779cf90b9ed71182ba5e34a7262826789adae206d825a4d45908b4. */
final class WaylandProtocol {
    record Message(String name, String signature, String... types) {}
    record Interface(String name, int version, Message[] requests, Message[] events) {}
    static final Interface[] TABLET = {
        new Interface("zwp_tablet_manager_v2", 2, new Message[]{
            new Message("get_tablet_seat", "no", "zwp_tablet_seat_v2", "wl_seat"),
            new Message("destroy", ""),
        }, new Message[]{
        }),
        new Interface("zwp_tablet_seat_v2", 2, new Message[]{
            new Message("destroy", ""),
        }, new Message[]{
            new Message("tablet_added", "n", "zwp_tablet_v2"),
            new Message("tool_added", "n", "zwp_tablet_tool_v2"),
            new Message("pad_added", "n", "zwp_tablet_pad_v2"),
        }),
        new Interface("zwp_tablet_tool_v2", 2, new Message[]{
            new Message("set_cursor", "u?oii", null, "wl_surface", null, null),
            new Message("destroy", ""),
        }, new Message[]{
            new Message("type", "u", (String) null),
            new Message("hardware_serial", "uu", null, null),
            new Message("hardware_id_wacom", "uu", null, null),
            new Message("capability", "u", (String) null),
            new Message("done", ""),
            new Message("removed", ""),
            new Message("proximity_in", "uoo", null, "zwp_tablet_v2", "wl_surface"),
            new Message("proximity_out", ""),
            new Message("down", "u", (String) null),
            new Message("up", ""),
            new Message("motion", "ff", null, null),
            new Message("pressure", "u", (String) null),
            new Message("distance", "u", (String) null),
            new Message("tilt", "ff", null, null),
            new Message("rotation", "f", (String) null),
            new Message("slider", "i", (String) null),
            new Message("wheel", "fi", null, null),
            new Message("button", "uuu", null, null, null),
            new Message("frame", "u", (String) null),
        }),
        new Interface("zwp_tablet_v2", 2, new Message[]{
            new Message("destroy", ""),
        }, new Message[]{
            new Message("name", "s", (String) null),
            new Message("id", "uu", null, null),
            new Message("path", "s", (String) null),
            new Message("done", ""),
            new Message("removed", ""),
            new Message("bustype", "2u", (String) null),
        }),
        new Interface("zwp_tablet_pad_ring_v2", 2, new Message[]{
            new Message("set_feedback", "su", null, null),
            new Message("destroy", ""),
        }, new Message[]{
            new Message("source", "u", (String) null),
            new Message("angle", "f", (String) null),
            new Message("stop", ""),
            new Message("frame", "u", (String) null),
        }),
        new Interface("zwp_tablet_pad_strip_v2", 2, new Message[]{
            new Message("set_feedback", "su", null, null),
            new Message("destroy", ""),
        }, new Message[]{
            new Message("source", "u", (String) null),
            new Message("position", "u", (String) null),
            new Message("stop", ""),
            new Message("frame", "u", (String) null),
        }),
        new Interface("zwp_tablet_pad_group_v2", 2, new Message[]{
            new Message("destroy", ""),
        }, new Message[]{
            new Message("buttons", "a", (String) null),
            new Message("ring", "n", "zwp_tablet_pad_ring_v2"),
            new Message("strip", "n", "zwp_tablet_pad_strip_v2"),
            new Message("modes", "u", (String) null),
            new Message("done", ""),
            new Message("mode_switch", "uuu", null, null, null),
            new Message("dial", "2n", "zwp_tablet_pad_dial_v2"),
        }),
        new Interface("zwp_tablet_pad_v2", 2, new Message[]{
            new Message("set_feedback", "usu", null, null, null),
            new Message("destroy", ""),
        }, new Message[]{
            new Message("group", "n", "zwp_tablet_pad_group_v2"),
            new Message("path", "s", (String) null),
            new Message("buttons", "u", (String) null),
            new Message("done", ""),
            new Message("button", "uuu", null, null, null),
            new Message("enter", "uoo", null, "zwp_tablet_v2", "wl_surface"),
            new Message("leave", "uo", null, "wl_surface"),
            new Message("removed", ""),
        }),
        new Interface("zwp_tablet_pad_dial_v2", 2, new Message[]{
            new Message("set_feedback", "su", null, null),
            new Message("destroy", ""),
        }, new Message[]{
            new Message("delta", "i", (String) null),
            new Message("frame", "u", (String) null),
        }),
    };

    private final Map<String, MemorySegment> interfaces = new HashMap<>();
    private final Arena arena;
    private final SymbolLookup library;
    WaylandProtocol(Arena arena, SymbolLookup library) {
        this.arena = arena; this.library = library;
        for (Interface type : TABLET) interfaces.put(type.name(), arena.allocate(40, 8));
        for (Interface type : TABLET) define(type);
    }
    void define(Interface type) {
        interfaces.computeIfAbsent(type.name(), key -> arena.allocate(40, 8));
        MemorySegment info = interfaces.get(type.name());
        info.set(ValueLayout.ADDRESS, 0, arena.allocateFrom(type.name()));
        info.set(ValueLayout.JAVA_INT, 8, type.version());
        info.set(ValueLayout.JAVA_INT, 12, type.requests().length);
        info.set(ValueLayout.ADDRESS, 16, messages(type.requests()));
        info.set(ValueLayout.JAVA_INT, 24, type.events().length);
        info.set(ValueLayout.ADDRESS, 32, messages(type.events()));
    }
    MemorySegment type(String name) {
        MemorySegment value = interfaces.get(name);
        return value != null ? value : library.find(name + "_interface").orElseThrow();
    }
    Message[] events(String name) {
        for (Interface type : TABLET) if (type.name().equals(name)) return type.events();
        MemorySegment info = type(name).reinterpret(40);
        int count = info.get(ValueLayout.JAVA_INT, 24);
        MemorySegment data = info.get(ValueLayout.ADDRESS, 32).reinterpret(count * 24L);
        Message[] result = new Message[count];
        for (int i = 0; i < count; i++) result[i] = new Message(string(data.get(ValueLayout.ADDRESS, i * 24L)), string(data.get(ValueLayout.ADDRESS, i * 24L + 8)));
        return result;
    }
    static String string(MemorySegment address) { return address.reinterpret(65536).getString(0); }
    private MemorySegment messages(Message[] messages) {
        if (messages.length == 0) return MemorySegment.NULL;
        MemorySegment data = arena.allocate(messages.length * 24L, 8);
        for (int i = 0; i < messages.length; i++) {
            Message message = messages[i];
            MemorySegment types = message.types().length == 0 ? MemorySegment.NULL : arena.allocate(message.types().length * 8L, 8);
            for (int t = 0; t < message.types().length; t++) types.setAtIndex(ValueLayout.ADDRESS, t, message.types()[t] == null ? MemorySegment.NULL : type(message.types()[t]));
            data.set(ValueLayout.ADDRESS, i * 24L, arena.allocateFrom(message.name()));
            data.set(ValueLayout.ADDRESS, i * 24L + 8, arena.allocateFrom(message.signature()));
            data.set(ValueLayout.ADDRESS, i * 24L + 16, types);
        }
        return data;
    }
}
