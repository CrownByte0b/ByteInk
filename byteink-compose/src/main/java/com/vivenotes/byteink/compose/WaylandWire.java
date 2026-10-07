package com.vivenotes.byteink.compose;

import java.lang.foreign.*;
import java.lang.invoke.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private final MethodHandle getFd, prepareRead, dispatchPending, flush, poll, readEvents, cancelRead;
    private final MethodHandle signalFd, closeFd;
    private final int wakeDescriptor;
    private final MemorySegment wakeSignal;
    private final AtomicBoolean wakeClosed = new AtomicBoolean();
    private static final StructLayout CALL_STATE = Linker.Option.captureStateLayout();
    private static final long ERRNO_OFFSET = CALL_STATE.byteOffset(MemoryLayout.PathElement.groupElement("errno"));
    private static final int EAGAIN = 11, EINTR = 4;
    // Internal diagnostic used by the native idle-reader regression; one writer on the reader.
    volatile long readPollCount;
    private static final MethodHandle CALLBACK;
    static {
        try { CALLBACK = MethodHandles.lookup().findStatic(WaylandWire.class, "invokeListener",
                MethodType.methodType(void.class, Consumer.class, Consumer.class, Object[].class)); }
        catch (ReflectiveOperationException e) { throw new ExceptionInInitializerError(e); }
    }
    WaylandWire(Consumer<Throwable> failure) {
        this.failure = failure;
        try {
            getFd = link("wl_display_get_fd", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            prepareRead = link("wl_display_prepare_read_queue", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            dispatchPending = link("wl_display_dispatch_queue_pending", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
            flush = link("wl_display_flush", FunctionDescriptor.of(JAVA_INT, ADDRESS), Linker.Option.captureCallState("errno"));
            poll = link("poll", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, JAVA_INT), Linker.Option.captureCallState("errno"));
            readEvents = link("wl_display_read_events", FunctionDescriptor.of(JAVA_INT, ADDRESS));
            cancelRead = link("wl_display_cancel_read", FunctionDescriptor.ofVoid(ADDRESS));
            signalFd = link("write", FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG), Linker.Option.captureCallState("errno"));
            closeFd = link("close", FunctionDescriptor.of(JAVA_INT, JAVA_INT));
            wakeSignal = arena.allocate(JAVA_LONG);
            wakeSignal.set(JAVA_LONG, 0, 1L);
            MethodHandle eventFd = link("eventfd", FunctionDescriptor.of(JAVA_INT, JAVA_INT, JAVA_INT));
            // Linux x86_64 EFD_CLOEXEC | EFD_NONBLOCK. This descriptor belongs only to this reader.
            wakeDescriptor = (int) eventFd.invokeExact(0, 0x80000 | 0x800);
            if (wakeDescriptor < 0) throw new IllegalStateException("Cannot create Wayland reader wakeup");
        } catch (Throwable error) {
            arena.close();
            throw new IllegalStateException("Cannot initialize Wayland native calls", error);
        }
    }

    private MethodHandle link(String name, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment symbol = library.find(name).or(() -> libc.find(name)).orElseThrow();
        return Linker.nativeLinker().downcallHandle(symbol, descriptor, options);
    }

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
            MemorySegment pollfd = local.allocate(16, 4);
            MemorySegment state = local.allocate(CALL_STATE);
            pollfd.set(JAVA_INT, 0, (int) getFd.invokeExact(display));
            pollfd.set(JAVA_INT, 8, wakeDescriptor);
            pollfd.set(JAVA_SHORT, 12, (short) 1);
            while (running.getAsBoolean()) {
                while ((int) prepareRead.invokeExact(display, queue) != 0) {
                    if ((int) dispatchPending.invokeExact(display, queue) < 0) throw new IllegalStateException("Wayland queue failed");
                    if (!running.getAsBoolean()) return;
                }
                boolean consumed = false;
                try {
                    if (!running.getAsBoolean()) return;
                    int flushed = (int) flush.invokeExact(state, display);
                    int error = state.get(JAVA_INT, ERRNO_OFFSET);
                    if (flushed < 0 && error != EAGAIN && error != EINTR) throw new IllegalStateException("Wayland flush failed: errno " + error);
                    pollfd.set(JAVA_SHORT, 4, (short) (flushed < 0 ? 5 : 1)); // POLLIN; POLLOUT for backpressure.
                    pollfd.set(JAVA_SHORT, 6, (short) 0);
                    pollfd.set(JAVA_SHORT, 14, (short) 0);
                    readPollCount++;
                    int result = (int) poll.invokeExact(state, pollfd, 2L, -1);
                    error = state.get(JAVA_INT, ERRNO_OFFSET);
                    if (result < 0 && error != EINTR) throw new IllegalStateException("Wayland poll failed: errno " + error);
                    // Shutdown signals the owned eventfd after clearing running. Cancel the read
                    // intention in finally rather than reading the toolkit socket just to stop.
                    if (!running.getAsBoolean()) return;
                    short wakeReady = pollfd.get(JAVA_SHORT, 14);
                    if ((wakeReady & (8 | 16 | 32)) != 0)
                        throw new IllegalStateException("Wayland reader wakeup descriptor failed");
                    if ((wakeReady & 1) != 0)
                        throw new IllegalStateException("Wayland reader was signalled before shutdown");
                    short ready = pollfd.get(JAVA_SHORT, 6);
                    if (result > 0 && (ready & (1 | 8 | 16 | 32)) != 0) {
                        consumed = true;
                        if ((int) readEvents.invokeExact(display) < 0) throw new IllegalStateException("Wayland connection failed");
                    }
                } finally {
                    if (!consumed) cancelRead.invokeExact(display);
                }
                if ((int) dispatchPending.invokeExact(display, queue) < 0) throw new IllegalStateException("Wayland queue failed");
            }
        } catch (Throwable error) {
            throw new IllegalStateException("Wayland input reader failed", error);
        }
    }

    /** Called only after the owning bridge clears its running flag; never takes the call lock. */
    void wakeReader() {
        if (wakeClosed.get()) return;
        try (Arena local = Arena.ofConfined()) {
            MemorySegment state = local.allocate(CALL_STATE);
            long written;
            int error;
            do {
                written = (long) signalFd.invokeExact(state, wakeDescriptor, wakeSignal, 8L);
                error = state.get(JAVA_INT, ERRNO_OFFSET);
            } while (written < 0 && error == EINTR);
            // A saturated nonblocking counter already has a pending readable wakeup.
            if (written < 0 && error == EAGAIN) return;
            if (written != 8L) throw new IllegalStateException("Cannot wake Wayland reader: errno " + error);
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot signal Wayland reader shutdown", error);
        }
    }

    /** Release only after the reader stops; terminal-display metadata can outlive this descriptor. */
    void closeWakeup() {
        if (!wakeClosed.compareAndSet(false, true)) return;
        try {
            if ((int) closeFd.invokeExact(wakeDescriptor) < 0) throw new IllegalStateException("Cannot close Wayland reader wakeup");
        } catch (Throwable error) {
            throw new IllegalStateException("Cannot release Wayland reader wakeup", error);
        }
    }
    @Override public void close() {
        try { closeWakeup(); } finally { arena.close(); }
    }
}
