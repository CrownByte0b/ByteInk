package com.vivenotes.byteink.compose;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.jetbrains.skia.DirectContext;
import org.jetbrains.skia.DirectContext_jvmKt;
import org.jetbrains.skia.GLAssembledInterface;

/**
 * Investigation-only desktop OpenGL context on Mesa's explicit surfaceless EGL platform.
 * It neither opens X11 nor obtains, commits, resizes or destroys JBR's Wayland surface/display.
 * Skia uses its public assembled-interface API; native-window presentation remains the caller's.
 * All instance operations belong to the constructing thread. Close Skia resources before this.
 */
public final class HeadlessEglContext implements AutoCloseable {
    private static final int EGL_NONE = 0x3038;
    private static final int EGL_OPENGL_API = 0x30a2;
    private static final int EGL_OPENGL_ES_API = 0x30a0;
    private static final int EGL_PLATFORM_SURFACELESS_MESA = 0x31dd;
    private static final int EGL_VENDOR = 0x3053;
    private static final int EGL_VERSION = 0x3054;
    private static final int EGL_EXTENSIONS = 0x3055;
    private static final int EGL_DRAW = 0x3059;
    private static final int EGL_READ = 0x305a;
    private static final Linker LINKER = Linker.nativeLinker();
    // eglInitialize/eglTerminate are not reference counted. Independent test contexts can share
    // the platform display, so terminate it only after the final helper has released its lease.
    private static final Map<Long, Integer> DISPLAY_LEASES = new HashMap<>();

    private final Thread owner = Thread.currentThread();
    private final Arena arena = Arena.ofConfined();
    private final Map<String, MethodHandle> functions = new HashMap<>();
    private SymbolLookup egl;
    private MemorySegment display = MemorySegment.NULL;
    private MemorySegment context = MemorySegment.NULL;
    private MemorySegment pbuffer = MemorySegment.NULL;
    private MemorySegment getProcCallback = MemorySegment.NULL;
    private Binding previous;
    private boolean displayLeased;
    private Throwable callbackFailure;

    private record Binding(MemorySegment display, MemorySegment draw, MemorySegment read,
                           MemorySegment context, int api) {}

    public HeadlessEglContext() { this(0, 0); }

    /** A requested desktop GL version lets controls exercise real unsupported-context cleanup. */
    public HeadlessEglContext(int requiredGlMajor, int requiredGlMinor) {
        try {
            if (requiredGlMajor < 0 || requiredGlMinor < 0 || requiredGlMajor == 0 && requiredGlMinor != 0)
                throw new IllegalArgumentException("Invalid required desktop OpenGL version");
            egl = SymbolLookup.libraryLookup("libEGL.so.1", arena);
            String extensions = queryString(MemorySegment.NULL, EGL_EXTENSIONS);
            require(token(extensions, "EGL_MESA_platform_surfaceless"), "EGL_MESA_platform_surfaceless");
            // EGL 1.5 uses pointer-sized EGLAttrib, while EXT_platform_base uses EGLint. The
            // attribute list is NULL in both cases, so only their common parameter ABI is used.
            MemorySegment platformDisplay = egl.find("eglGetPlatformDisplay")
                    .orElseGet(() -> getProcAddress("eglGetPlatformDisplayEXT"));
            require(present(platformDisplay), "eglGetPlatformDisplay[/EXT]");
            MethodHandle getDisplay = LINKER.downcallHandle(platformDisplay,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            display = (MemorySegment) getDisplay.invokeExact(EGL_PLATFORM_SURFACELESS_MESA,
                    MemorySegment.NULL, MemorySegment.NULL);
            require(present(display), "eglGetPlatformDisplay (surfaceless)");
            acquireDisplay();

            MemorySegment configs = arena.allocate(ValueLayout.ADDRESS);
            MemorySegment count = arena.allocate(ValueLayout.JAVA_INT);
            MemorySegment attributes = ints(0x3033, 1, // EGL_SURFACE_TYPE, EGL_PBUFFER_BIT
                    0x3040, 8, // EGL_RENDERABLE_TYPE, EGL_OPENGL_BIT
                    0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, EGL_NONE);
            require(integer("eglChooseConfig", display, attributes, configs, 1, count) != 0
                    && count.get(ValueLayout.JAVA_INT, 0) > 0, "eglChooseConfig (RGBA8 desktop OpenGL)");
            MemorySegment config = configs.get(ValueLayout.ADDRESS, 0);
            pbuffer = pointer("eglCreatePbufferSurface", display, config,
                    ints(0x3057, 1, 0x3056, 1, EGL_NONE));
            require(present(pbuffer), "eglCreatePbufferSurface");
            int oldApi = integer("eglQueryAPI");
            try {
                require(integer("eglBindAPI", EGL_OPENGL_API) != 0, "eglBindAPI (desktop OpenGL)");
                context = pointer("eglCreateContext", display, config, MemorySegment.NULL,
                        requiredGlMajor == 0 ? ints(EGL_NONE)
                                : ints(0x3098, requiredGlMajor, 0x30fb, requiredGlMinor, EGL_NONE));
                require(present(context), "eglCreateContext (desktop OpenGL)");
            } finally {
                require(integer("eglBindAPI", oldApi) != 0, "eglBindAPI (restore creation API)");
            }
            MethodHandle callback = MethodHandles.lookup().findVirtual(HeadlessEglContext.class,
                    "resolveGlFunction", MethodType.methodType(MemorySegment.class,
                            MemorySegment.class, MemorySegment.class)).bindTo(this);
            getProcCallback = LINKER.upcallStub(callback,
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS), arena);
            makeCurrent();
            require(!version().equals("unknown"), "current OpenGL version");
        } catch (Throwable failure) {
            try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
            throw new IllegalStateException("Cannot create isolated surfaceless EGL context", failure);
        }
    }

    /** Creates a Skia context using EGL's symbol resolver and the pinned public Skiko API. */
    public DirectContext makeSkiaContext() {
        checkCurrent();
        try (GLAssembledInterface assembled = GLAssembledInterface.Companion
                .createFromNativePointers(0L, getProcCallback.address())) {
            if (callbackFailure != null) throw new IllegalStateException("EGL GL symbol resolution", callbackFailure);
            return DirectContext_jvmKt.makeGLWithInterface(DirectContext.Companion, assembled);
        } catch (Throwable failure) {
            if (callbackFailure != null && failure.getCause() != callbackFailure) failure.addSuppressed(callbackFailure);
            throw failure;
        }
    }

    /** Saves the caller's EGL binding/API and makes this context current; repeated binds are safe. */
    public void makeCurrent() {
        checkOpen();
        if (previous != null) {
            checkCurrent();
            return;
        }
        Binding saved = new Binding(pointer("eglGetCurrentDisplay"), pointer("eglGetCurrentSurface", EGL_DRAW),
                pointer("eglGetCurrentSurface", EGL_READ), pointer("eglGetCurrentContext"), integer("eglQueryAPI"));
        if (saved.api != EGL_OPENGL_API && saved.api != EGL_OPENGL_ES_API)
            throw new IllegalStateException("The test helper supports restoring OpenGL/OpenGL ES bindings only");
        require(integer("eglBindAPI", EGL_OPENGL_API) != 0, "eglBindAPI (desktop OpenGL)");
        if (integer("eglMakeCurrent", display, pbuffer, pbuffer, context) == 0) {
            integer("eglBindAPI", saved.api);
            throw error("eglMakeCurrent");
        }
        previous = saved;
    }

    /** Restores the binding/API that preceded makeCurrent; never touches a foreign Wayland surface. */
    public void releaseCurrent() {
        checkOpen();
        if (previous == null) return;
        checkCurrent();
        Binding saved = previous;
        // Explicitly unbind the owned desktop GL context before restoring the caller's
        // rendering API and binding. The test helper restores OpenGL/OpenGL ES callers.
        require(integer("eglBindAPI", EGL_OPENGL_API) != 0, "eglBindAPI (unbind desktop OpenGL)");
        require(integer("eglMakeCurrent", display, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL) != 0,
                "eglMakeCurrent (unbind desktop OpenGL)");
        require(integer("eglBindAPI", saved.api) != 0, "eglBindAPI (restore)");
        if (present(saved.context))
            require(integer("eglMakeCurrent", saved.display, saved.draw, saved.read, saved.context) != 0,
                    "eglMakeCurrent (restore)");
        previous = null;
    }

    public String renderer() { return glString(0x1f01); }
    public String vendor() { return glString(0x1f00); }
    public String version() { return glString(0x1f02); }
    public String eglVendor() { checkOpen(); return queryString(display, EGL_VENDOR); }
    public String eglVersion() { checkOpen(); return queryString(display, EGL_VERSION); }
    public boolean isSoftwareRenderer() {
        String name = renderer().toLowerCase(Locale.ROOT);
        return name.contains("llvmpipe") || name.contains("softpipe") || name.contains("swrast")
                || name.contains("software rasterizer") || name.contains("swiftshader")
                || name.contains("lavapipe") || name.equals("swr") || name.contains("swr rasterizer");
    }

    private String glString(int name) {
        checkCurrent();
        try {
            MethodHandle function = functions.get("glGetString");
            if (function == null) {
                // Resolving a GL address can initialize eglGetProcAddress in the same cache.
                MemorySegment address = getProcAddress("glGetString");
                require(present(address), "glGetString");
                function = LINKER.downcallHandle(address,
                        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
                functions.put("glGetString", function);
            }
            return string((MemorySegment) function.invokeExact(name));
        } catch (Throwable failure) { throw new IllegalStateException("glGetString", failure); }
    }

    private MemorySegment resolveGlFunction(MemorySegment ignoredContext, MemorySegment name) {
        try { return getProcAddress(name); }
        catch (Throwable failure) {
            callbackFailure = failure; // Exceptions cannot cross an FFM upcall boundary.
            return MemorySegment.NULL;
        }
    }

    private MemorySegment getProcAddress(String name) { return getProcAddress(arena.allocateFrom(name)); }
    private MemorySegment getProcAddress(MemorySegment name) { return pointer("eglGetProcAddress", name); }
    private String queryString(MemorySegment dpy, int name) { return string(pointer("eglQueryString", dpy, name)); }
    private static String string(MemorySegment value) {
        return present(value) ? value.reinterpret(1 << 20).getString(0) : "unknown";
    }
    private static boolean token(String list, String name) {
        for (String value : list.split("\\s+")) if (value.equals(name)) return true;
        return false;
    }
    private MemorySegment ints(int... values) { return arena.allocateFrom(ValueLayout.JAVA_INT, values); }
    private static boolean present(MemorySegment pointer) { return pointer.address() != 0L; }
    private void require(boolean value, String operation) { if (!value) throw error(operation); }
    private IllegalStateException error(String operation) {
        int code = egl == null ? 0 : integer("eglGetError");
        return new IllegalStateException(operation + " failed (EGL error 0x" + Integer.toHexString(code) + ")");
    }

    private Object call(String name, MemoryLayout result, Object... args) {
        try {
            MethodHandle method = functions.computeIfAbsent(name, key -> {
                MemoryLayout[] parameters = new MemoryLayout[args.length];
                for (int i = 0; i < args.length; i++)
                    parameters[i] = args[i] instanceof Integer ? ValueLayout.JAVA_INT : ValueLayout.ADDRESS;
                return LINKER.downcallHandle(egl.find(key).orElseThrow(),
                        result == null ? FunctionDescriptor.ofVoid(parameters) : FunctionDescriptor.of(result, parameters));
            });
            return method.invokeWithArguments(args);
        } catch (Throwable failure) { throw new IllegalStateException(name, failure); }
    }
    private int integer(String name, Object... args) { return (int) call(name, ValueLayout.JAVA_INT, args); }
    private MemorySegment pointer(String name, Object... args) { return (MemorySegment) call(name, ValueLayout.ADDRESS, args); }

    private void acquireDisplay() {
        synchronized (DISPLAY_LEASES) {
            long address = display.address();
            int leases = DISPLAY_LEASES.getOrDefault(address, 0);
            if (leases == 0) {
                require(integer("eglInitialize", display, arena.allocate(ValueLayout.JAVA_INT),
                        arena.allocate(ValueLayout.JAVA_INT)) != 0, "eglInitialize");
            }
            DISPLAY_LEASES.put(address, leases + 1);
            displayLeased = true;
        }
    }

    private void releaseDisplay() {
        synchronized (DISPLAY_LEASES) {
            int leases = DISPLAY_LEASES.get(display.address());
            if (leases == 1) {
                DISPLAY_LEASES.remove(display.address());
                displayLeased = false;
                require(integer("eglTerminate", display) != 0, "eglTerminate");
            } else {
                DISPLAY_LEASES.put(display.address(), leases - 1);
                displayLeased = false;
            }
        }
    }

    static int activeDisplayLeaseCount() {
        synchronized (DISPLAY_LEASES) {
            return DISPLAY_LEASES.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    private void checkOpen() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("EGL context belongs to " + owner.getName());
        if (!arena.scope().isAlive()) throw new IllegalStateException("EGL context is closed");
    }
    private void checkCurrent() {
        checkOpen();
        if (!present(context) || pointer("eglGetCurrentContext").address() != context.address())
            throw new IllegalStateException("Make this EGL context current before using Skia/GL resources");
    }

    @Override public void close() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Close EGL context on its owning thread");
        if (!arena.scope().isAlive()) return;
        // Nested bindings must close in LIFO order. Reject before mutating any resource;
        // destroying an outer context would otherwise invalidate the inner restore target.
        if (previous != null) checkCurrent();
        Throwable failure = null;
        if (previous != null) {
            try { releaseCurrent(); } catch (Throwable cleanup) { failure = cleanup; }
        }
        if (present(context)) {
            try { require(integer("eglDestroyContext", display, context) != 0, "eglDestroyContext"); }
            catch (Throwable cleanup) { failure = append(failure, cleanup); }
        }
        if (present(pbuffer)) {
            try { require(integer("eglDestroySurface", display, pbuffer) != 0, "eglDestroySurface"); }
            catch (Throwable cleanup) { failure = append(failure, cleanup); }
        }
        if (displayLeased) {
            try { releaseDisplay(); } catch (Throwable cleanup) { failure = append(failure, cleanup); }
        }
        try { arena.close(); } catch (Throwable cleanup) { failure = append(failure, cleanup); }
        if (failure != null) throw new IllegalStateException("Surfaceless EGL cleanup failed", failure);
    }
    private static Throwable append(Throwable failure, Throwable next) {
        if (failure == null) return next;
        failure.addSuppressed(next);
        return failure;
    }
}
