package com.vivenotes.byteink.compose;

import java.io.EOFException;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** A private core-protocol server that withholds individual sync replies on a real libwayland connection. */
final class WaylandDiscoveryConnection implements AutoCloseable {
    private final Path directory = Files.createTempDirectory(Path.of("/tmp"), "byteink-discovery-");
    private final Path socket = directory.resolve("display");
    private final ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    private final LinkedBlockingQueue<Integer> callbacks = new LinkedBlockingQueue<>();
    private final AtomicReference<Throwable> serverFailure = new AtomicReference<>();
    private final Thread receiver;
    private final WaylandWire owner = new WaylandWire(error -> { throw new AssertionError(error); });
    private final MemorySegment display;
    private final boolean devices;
    private final Map<Integer, String> objects = new HashMap<>();
    private final CountDownLatch touchBound = new CountDownLatch(1);
    private MemorySegment registry, compositor, surface;
    private int surfaceId;
    private volatile int touchId;
    private static final int TABLET = 0xff000000, TOOL = 0xff000001;
    private volatile SocketChannel client;
    private volatile boolean disconnected, closing;

    WaylandDiscoveryConnection() throws IOException { this(false); }

    WaylandDiscoveryConnection(boolean devices) throws IOException {
        this.devices = devices;
        server.bind(UnixDomainSocketAddress.of(socket));
        receiver = new Thread(this::receive, "byteink-wayland-discovery-server");
        receiver.setDaemon(true);
        receiver.start();
        try (Arena local = Arena.ofConfined()) {
            display = owner.pointer("wl_display_connect", local.allocateFrom(socket.toString()));
        }
        if (display.address() == 0) {
            close();
            throw new AssertionError("Cannot connect to the private discovery server");
        }
        if (devices) {
            // A valid client-owned wl_surface lets tablet/touch object arguments go through
            // libwayland's real object/type resolution rather than using invented native pointers.
            registry = owner.child(display, 1, "wl_registry", MemorySegment.NULL);
            compositor = owner.bind(registry, 3, "wl_compositor", 1);
            surface = owner.child(compositor, 0, "wl_surface", MemorySegment.NULL);
            surfaceId = owner.integer("wl_proxy_get_id", surface);
            owner.integer("wl_display_flush", display);
        }
    }

    long display() { return display.address(); }
    long surface() { return surface.address(); }

    /** The former two blocking roundtrips, on the same controlled native connection. */
    void blockingDiscoveryReference() {
        MemorySegment queue = owner.pointer("wl_display_create_queue", display);
        MemorySegment wrapper = owner.pointer("wl_proxy_create_wrapper", display);
        MemorySegment registry = MemorySegment.NULL;
        try {
            owner.procedure("wl_proxy_set_queue", wrapper, queue);
            registry = owner.child(wrapper, 1, "wl_registry", MemorySegment.NULL);
            owner.listen(registry, "wl_registry", args -> { });
            for (int i = 0; i < 2; i++)
                if (owner.integer("wl_display_roundtrip_queue", display, queue) < 0)
                    throw new AssertionError("Blocking discovery reference failed");
        } finally {
            if (registry.address() != 0) owner.destroy(registry, "wl_registry");
            owner.procedure("wl_proxy_wrapper_destroy", wrapper);
            owner.procedure("wl_event_queue_destroy", queue);
        }
    }

    int nextSync() throws InterruptedException {
        Integer callback = callbacks.poll(3, TimeUnit.SECONDS);
        assertHealthy();
        if (callback == null) throw new AssertionError("Discovery did not issue its next sync request");
        return callback;
    }

    void completeSync(int callback) throws IOException {
        // wl_callback.done followed by wl_display.delete_id, as the core server sends them.
        ByteBuffer events = ByteBuffer.allocate(24).order(ByteOrder.nativeOrder());
        events.putInt(callback).putInt(12 << 16).putInt(1);
        events.putInt(1).putInt((12 << 16) | 1).putInt(callback).flip();
        write(events);
    }

    private void write(ByteBuffer events) throws IOException {
        SocketChannel connected = client;
        synchronized (connected) { while (events.hasRemaining()) connected.write(events); }
    }

    private void event(int object, int opcode, Object... arguments) throws IOException {
        ByteBuffer message = ByteBuffer.allocate(1024).order(ByteOrder.nativeOrder());
        message.putInt(object).putInt(0);
        for (Object argument : arguments) {
            if (argument instanceof Integer value) message.putInt(value);
            else if (argument instanceof String value) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                message.putInt(bytes.length + 1).put(bytes).put((byte) 0);
                while (message.position() % 4 != 0) message.put((byte) 0);
            } else throw new AssertionError("Unsupported protocol argument");
        }
        message.putInt(4, message.position() << 16 | opcode).flip();
        write(message);
    }

    void awaitTouchBinding() throws InterruptedException {
        if (!touchBound.await(3, TimeUnit.SECONDS)) throw new AssertionError("Touch capability was not bound during discovery");
        assertHealthy();
    }

    void penDownWithoutFrame() throws IOException {
        event(TOOL, 6, 1, TABLET, surfaceId);
        event(TOOL, 10, 20 * 256, 30 * 256);
        event(TOOL, 11, 32768);
        event(TOOL, 8, 1);
    }

    void finishPenContact() throws IOException {
        event(TOOL, 18, 1000);
        event(TOOL, 10, 40 * 256, 30 * 256);
        event(TOOL, 18, 1010);
        event(TOOL, 9);
        event(TOOL, 18, 1020);
    }

    void touchDownWithoutFrame() throws IOException { event(touchId, 0, 1, 1000, surfaceId, 7, 20 * 256, 30 * 256); }

    void finishTouchContact() throws IOException {
        event(touchId, 3);
        event(touchId, 2, 1010, 7, 40 * 256, 30 * 256);
        event(touchId, 3);
        event(touchId, 1, 1, 1020, 7);
        event(touchId, 3);
    }

    void disconnectServer() throws IOException {
        disconnected = true;
        if (client != null) client.close();
    }

    private void receive() {
        try (SocketChannel connected = server.accept()) {
            client = connected;
            while (!closing && !disconnected) {
                ByteBuffer header = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder());
                read(connected, header);
                int object = header.getInt(), word = header.getInt();
                int size = word >>> 16, opcode = word & 0xffff;
                if (size < 8 || size > 4096 || size % 4 != 0) throw new AssertionError("Invalid core request size");
                ByteBuffer arguments = ByteBuffer.allocate(size - 8).order(ByteOrder.nativeOrder());
                read(connected, arguments);
                request(object, opcode, arguments);
            }
        } catch (Throwable error) {
            if (!closing && !disconnected) serverFailure.set(error);
        }
    }

    private void request(int object, int opcode, ByteBuffer arguments) throws IOException {
        if (object == 1) {
            int newId = arguments.getInt();
            if (opcode == 0) callbacks.add(newId);
            else if (opcode == 1) {
                objects.put(newId, "wl_registry");
                if (devices) {
                    event(newId, 0, 1, "zwp_tablet_manager_v2", 1);
                    event(newId, 0, 2, "wl_seat", 5);
                    event(newId, 0, 3, "wl_compositor", 1);
                }
            } else throw new AssertionError("Unexpected display request");
            return;
        }
        switch (objects.getOrDefault(object, "unknown")) {
            case "wl_registry" -> {
                int name = arguments.getInt(), length = arguments.getInt();
                byte[] bytes = new byte[length - 1];
                arguments.get(bytes);
                arguments.position((arguments.position() + 4) & ~3);
                arguments.getInt(); // version
                int newId = arguments.getInt();
                String type = new String(bytes, StandardCharsets.UTF_8);
                objects.put(newId, type);
                if (name == 2) event(newId, 0, 4); // seat touch capability
            }
            case "wl_compositor" -> objects.put(arguments.getInt(), "wl_surface");
            case "zwp_tablet_manager_v2" -> {
                if (opcode == 0) {
                    int seat = arguments.getInt();
                    objects.put(seat, "zwp_tablet_seat_v2");
                    objects.put(TABLET, "zwp_tablet_v2");
                    objects.put(TOOL, "zwp_tablet_tool_v2");
                    event(seat, 0, TABLET);
                    event(TABLET, 0, "Controlled discovery tablet");
                    event(TABLET, 3);
                    event(seat, 1, TOOL);
                    event(TOOL, 0, 0x140);
                    event(TOOL, 3, 2);
                    event(TOOL, 4);
                }
            }
            case "wl_seat" -> {
                if (opcode == 2) {
                    touchId = arguments.getInt();
                    objects.put(touchId, "wl_touch");
                    touchBound.countDown();
                }
            }
            case "wl_surface", "wl_touch", "zwp_tablet_seat_v2", "zwp_tablet_v2", "zwp_tablet_tool_v2" -> {
                // Destructors need no reply for these one-shot fixtures.
            }
            default -> throw new AssertionError("Unexpected discovery request object " + object);
        }
    }

    private static void read(SocketChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) if (channel.read(buffer) < 0) throw new EOFException("Client disconnected");
        buffer.flip();
    }

    private void assertHealthy() {
        Throwable error = serverFailure.get();
        if (error != null) throw new AssertionError("Private discovery server failed", error);
    }

    @Override public void close() {
        closing = true;
        try {
            if (surface != null) {
                owner.destroy(surface, "wl_surface");
                owner.procedure("wl_proxy_destroy", compositor);
                owner.destroy(registry, "wl_registry");
            }
            if (client != null) client.close();
            server.close();
            receiver.join(3000);
            if (receiver.isAlive()) throw new AssertionError("Discovery server did not stop");
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new AssertionError("Cannot stop discovery server", error);
        } finally {
            if (display.address() != 0) owner.procedure("wl_display_disconnect", display);
            owner.close();
            try { Files.deleteIfExists(socket); Files.deleteIfExists(directory); }
            catch (IOException error) { throw new AssertionError("Cannot remove private discovery socket", error); }
        }
        assertHealthy();
    }
}
