package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.function.Consumer;
import static java.lang.foreign.ValueLayout.*;

/** Fixed-signature libwayland calls. The toolkit owns the borrowed display and its default queue. */
final class WaylandWire implements AutoCloseable {
    final Arena arena = Arena.ofShared();
    final SymbolLookup library = SymbolLookup.libraryLookup("libwayland-client.so.0", arena);
    final SymbolLookup libc = SymbolLookup.libraryLookup("libc.so.6", arena);
    final WaylandProtocol protocol = new WaylandProtocol(arena, library);
    private final Map<String, MethodHandle> functions = new HashMap<>();
    private final Map<String, MemorySegment> listeners = new HashMap<>();
    private final Consumer<Throwable> failure;
    private static final MethodHandle CALLBACK;
    static {
        try { CALLBACK = MethodHandles.lookup().findStatic(WaylandWire.class, "invokeListener",
                MethodType.methodType(void.class, Consumer.class, Consumer.class, Object[].class)); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    WaylandWire(Consumer<Throwable> failure) { this.failure = failure; }

    private synchronized Object call(String name, MemoryLayout result, Object... arguments) {
        MethodHandle handle = functions.computeIfAbsent(name, key -> {
            MemoryLayout[] types = Arrays.stream(arguments).map(a -> a instanceof Integer ? JAVA_INT
                    : a instanceof Long ? JAVA_LONG : ADDRESS).toArray(MemoryLayout[]::new);
            MemorySegment symbol = library.find(key).or(() -> libc.find(key)).orElseThrow();
            return Linker.nativeLinker().downcallHandle(symbol,
                    result == null ? FunctionDescriptor.ofVoid(types) : FunctionDescriptor.of(result, types));
        });
        try { return handle.invokeWithArguments(arguments); }
        catch (Throwable e) { throw new IllegalStateException(name, e); }
    }
    int integer(String name, Object... args) { return (int) call(name, JAVA_INT, args); }
    MemorySegment pointer(String name, Object... args) { return (MemorySegment) call(name, ADDRESS, args); }
    void procedure(String name, Object... args) { call(name, null, args); }
    private static void invokeListener(Consumer<Object[]> listener, Consumer<Throwable> failure, Object[] args) {
        try { listener.accept(args); } catch (Throwable e) {
            // No Java exception may unwind through a native Wayland dispatch/upcall.
            try { failure.accept(e); } catch (Throwable reporting) { e.addSuppressed(reporting); }
        }
    }
    void listen(MemorySegment proxy, String type, Consumer<Object[]> callback) {
        // One callback table per interface, shared by all instances; no upcall allocation per device.
        MemorySegment table = listeners.computeIfAbsent(type, key -> {
            WaylandProtocol.Message[] events = protocol.events(type);
            MemorySegment functions = arena.allocate(events.length * 8L, 8);
            for (int event = 0; event < events.length; event++) {
                final String eventName = events[event].name();
                final int eventIndex = event;
                Consumer<Object[]> dispatch = args -> {
                    Object[] named = new Object[args.length + 2];
                    named[0] = eventName; named[1] = eventIndex;
                    System.arraycopy(args, 0, named, 2, args.length);
                    callback.accept(named);
                };
                List<MemoryLayout> layouts = new ArrayList<>(List.of(ADDRESS, ADDRESS));
                for (char c : events[event].signature().toCharArray()) {
                    if (Character.isDigit(c) || c == '?') continue;
                    layouts.add("iufh".indexOf(c) >= 0 ? JAVA_INT : ADDRESS);
                }
                Class<?>[] classes = layouts.stream().map(l -> l == JAVA_INT ? int.class : MemorySegment.class).toArray(Class<?>[]::new);
                MethodHandle target = MethodHandles.insertArguments(CALLBACK, 0, dispatch, failure)
                        .asCollector(Object[].class, classes.length).asType(MethodType.methodType(void.class, classes));
                functions.setAtIndex(ADDRESS, event, Linker.nativeLinker().upcallStub(target,
                        FunctionDescriptor.ofVoid(layouts.toArray(MemoryLayout[]::new)), arena));
            }
            return functions;
        });
        if (integer("wl_proxy_add_listener", proxy, table, MemorySegment.NULL) != 0)
            throw new IllegalStateException("Wayland listener already installed: " + type);
    }
    MemorySegment marshal(MemorySegment proxy, int opcode, MemorySegment resultType, int version, int flags, Object... arguments) {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment args = arguments.length == 0 ? MemorySegment.NULL : local.allocate(arguments.length * 8L, 8);
            for (int i = 0; i < arguments.length; i++) {
                Object value = arguments[i];
                if (value instanceof Integer integer) args.set(JAVA_INT, i * 8L, integer);
                else args.set(ADDRESS, i * 8L, value instanceof String text ? local.allocateFrom(text) : (MemorySegment) value);
            }
            return pointer("wl_proxy_marshal_array_flags", proxy, opcode, resultType, version, flags, args);
        }
    }
    MemorySegment bind(MemorySegment registry, int name, String type, int version) {
        return marshal(registry, 0, protocol.type(type), version, 0, name, type, version, MemorySegment.NULL);
    }
    MemorySegment child(MemorySegment parent, int opcode, String type, Object... args) {
        return marshal(parent, opcode, protocol.type(type), integer("wl_proxy_get_version", parent), 0, args);
    }
    void destroy(MemorySegment proxy, String type) {
        int version = integer("wl_proxy_get_version", proxy);
        int opcode = switch (type) {
            case "zwp_tablet_manager_v2", "zwp_tablet_tool_v2", "zwp_tablet_pad_v2",
                 "zwp_tablet_pad_ring_v2", "zwp_tablet_pad_strip_v2", "zwp_tablet_pad_dial_v2" -> 1;
            case "wl_seat" -> 3;
            default -> 0;
        };
        if (type.equals("wl_registry") || type.equals("wl_seat") && version < 5 || type.equals("wl_touch") && version < 3)
            procedure("wl_proxy_destroy", proxy);
        else marshal(proxy, opcode, MemorySegment.NULL, version, 1);
    }
    void readLoop(MemorySegment display, MemorySegment queue, java.util.function.BooleanSupplier running) {
        try (Arena local = Arena.ofConfined()) {
            MemorySegment pollfd = local.allocate(8, 4);
            pollfd.set(JAVA_INT, 0, integer("wl_display_get_fd", display));
            while (running.getAsBoolean()) {
                while (integer("wl_display_prepare_read_queue", display, queue) != 0) {
                    if (integer("wl_display_dispatch_queue_pending", display, queue) < 0) throw new IllegalStateException("Wayland queue failed");
                    if (!running.getAsBoolean()) return;
                }
                boolean consumed = false;
                try {
                    int flushed = integer("wl_display_flush", display);
                    if (flushed < 0 && errno() != 11 && errno() != 4) throw new IllegalStateException("Wayland flush failed");
                    pollfd.set(JAVA_SHORT, 4, (short) (flushed < 0 ? 5 : 1)); // POLLIN; POLLOUT for backpressure.
                    pollfd.set(JAVA_SHORT, 6, (short) 0);
                    int result = integer("poll", pollfd, 1L, 25);
                    if (result < 0 && errno() != 4) throw new IllegalStateException("Wayland poll failed");
                    short ready = pollfd.get(JAVA_SHORT, 6);
                    if (result > 0 && (ready & (1 | 8 | 16 | 32)) != 0) {
                        consumed = true;
                        if (integer("wl_display_read_events", display) < 0) throw new IllegalStateException("Wayland connection failed");
                    }
                } finally {
                    if (!consumed) procedure("wl_display_cancel_read", display);
                }
                if (integer("wl_display_dispatch_queue_pending", display, queue) < 0) throw new IllegalStateException("Wayland queue failed");
            }
        }
    }
    private int errno() { return pointer("__errno_location").reinterpret(4).get(JAVA_INT, 0); }
    @Override public void close() { arena.close(); }
}
