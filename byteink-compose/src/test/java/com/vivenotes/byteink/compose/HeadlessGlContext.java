package com.vivenotes.byteink.compose;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Test-only GLX context on a private Xvfb display; no production renderer dependency. */
public final class HeadlessGlContext implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private final SymbolLookup gl = SymbolLookup.libraryLookup("libGL.so.1", arena);
    private final SymbolLookup x11 = SymbolLookup.libraryLookup("libX11.so.6", arena);
    private final Map<String, MethodHandle> functions = new HashMap<>();
    private MemorySegment display = MemorySegment.NULL;
    private MemorySegment context = MemorySegment.NULL;
    private long window;

    public HeadlessGlContext() {
        try {
            display = pointer("XOpenDisplay", MemorySegment.NULL);
            require(pointerPresent(display), "XOpenDisplay (run under xvfb-run)");
            MemorySegment visual = pointer("glXChooseVisual", display, 0,
                    arena.allocateFrom(ValueLayout.JAVA_INT, 4, 8, 8, 9, 8, 10, 8, 0));
            require(pointerPresent(visual), "glXChooseVisual");
            try {
                long root = (long) call("XDefaultRootWindow", ValueLayout.JAVA_LONG, display);
                window = (long) call("XCreateSimpleWindow", ValueLayout.JAVA_LONG,
                        display, root, 0, 0, 128, 128, 0, 0L, 0L);
                require(window != 0, "XCreateSimpleWindow");
                context = pointer("glXCreateContext", display, visual, MemorySegment.NULL, 1);
                require(pointerPresent(context), "glXCreateContext");
            } finally { integer("XFree", visual); }
            require(integer("glXMakeCurrent", display, window, context) != 0, "glXMakeCurrent");
        } catch (Throwable failure) {
            try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Cannot create test GLX context", failure);
        }
    }

    public String renderer() {
        MemorySegment value = pointer("glGetString", 0x1F01);
        return pointerPresent(value) ? value.reinterpret(1024).getString(0) : "unknown";
    }
    private static boolean pointerPresent(MemorySegment pointer) { return pointer.address() != 0; }
    private static void require(boolean value, String operation) {
        if (!value) throw new IllegalStateException(operation + " failed");
    }
    private Object call(String name, MemoryLayout result, Object... args) {
        try {
            MethodHandle method = functions.computeIfAbsent(name, key -> {
                MemoryLayout[] layouts = Arrays.stream(args).map(arg -> arg instanceof Integer ? ValueLayout.JAVA_INT
                        : arg instanceof Long ? ValueLayout.JAVA_LONG : ValueLayout.ADDRESS).toArray(MemoryLayout[]::new);
                return Linker.nativeLinker().downcallHandle(gl.find(key).or(() -> x11.find(key)).orElseThrow(),
                        result == null ? FunctionDescriptor.ofVoid(layouts) : FunctionDescriptor.of(result, layouts));
            });
            return method.invokeWithArguments(args);
        } catch (Throwable failure) { throw new IllegalStateException(name, failure); }
    }
    private int integer(String name, Object... args) { return (int) call(name, ValueLayout.JAVA_INT, args); }
    private MemorySegment pointer(String name, Object... args) { return (MemorySegment) call(name, ValueLayout.ADDRESS, args); }

    @Override public void close() {
        if (!arena.scope().isAlive()) return;
        try {
            if (pointerPresent(display)) {
                integer("glXMakeCurrent", display, 0L, MemorySegment.NULL);
                if (pointerPresent(context)) call("glXDestroyContext", null, display, context);
                if (window != 0) integer("XDestroyWindow", display, window);
                integer("XCloseDisplay", display);
            }
        } finally { arena.close(); }
    }
}
