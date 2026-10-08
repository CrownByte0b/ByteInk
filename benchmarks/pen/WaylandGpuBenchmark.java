import androidx.compose.ui.graphics.Canvas;
import androidx.compose.ui.graphics.SkiaBackedCanvas_skikoKt;
import androidx.ink.brush.Brush;
import androidx.ink.geometry.AffineTransform;
import androidx.ink.strokes.InProgressStroke;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.core.InkMeshes;
import com.vivenotes.byteink.core.StrokeMesh;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import kotlin.Unit;
import org.jetbrains.skia.*;
import org.jetbrains.skia.Image;
import org.jetbrains.skiko.GpuPriority;
import org.jetbrains.skiko.SkiaLayerAnalytics;
import org.jetbrains.skiko.swing.SoftwareSwingPainter;
import org.jetbrains.skiko.swing.SoftwareSwingRedrawer;
import org.jetbrains.skiko.swing.SwingLayerProperties;

/**
 * Isolated Step 3 investigation: actual JBR WLToolkit paintImmediately plus Toolkit.sync.
 * EGL renders into an owned offscreen texture and uses exactly Skiko's software Swing painter.
 * No code in this experiment attaches EGL buffers to, or commits, JBR's borrowed wl_surface.
 * sync means completion of the client toolkit operation, not compositor display/scanout.
 */
public final class WaylandGpuBenchmark {
    static final int INPUTS = Integer.getInteger("byteink.gpu.liveInputs", 8192);
    static final int BATCH = Integer.getInteger("byteink.gpu.inputsPerFrame", 128);
    static final int CHANGED = Integer.getInteger("byteink.gpu.changedFrames", 8);
    static final int WARMUP = Integer.getInteger("byteink.gpu.warmupCycles", 4);
    static final int MEASURED = Integer.getInteger("byteink.gpu.measuredCycles", 3);
    static final String DESTINATION = System.getProperty("byteink.gpu.destination", "software");
    static final String DRIVER = System.getProperty("byteink.gpu.driver", "software");
    static final boolean WINDOW_COUNTERS = Boolean.getBoolean("byteink.gpu.windowCounters");
    static final String[] MODES = System.getProperty("byteink.gpu.modes", "software-full,software-retained,egl-readback").split(",");
    static final com.sun.management.ThreadMXBean THREAD = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final com.sun.management.OperatingSystemMXBean PROCESS = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
    static final Map<String, Object> CASES = new TreeMap<>(), CONTROLS = new TreeMap<>();
    static final Map<String, Object> RUNTIME = new TreeMap<>();
    static final int CLEAR = PenRetainedAuthoringBenchmark.CLEAR;

    record Probe(long wall, long cpu, long processCpu, long bytes) {
        static Probe start() { return new Probe(System.nanoTime(), THREAD.getCurrentThreadCpuTime(), PROCESS.getProcessCpuTime(), THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId())); }
        Probe end() { return new Probe(System.nanoTime() - wall, THREAD.getCurrentThreadCpuTime() - cpu, PROCESS.getProcessCpuTime() - processCpu, THREAD.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes); }
    }
    static final class Samples {
        final List<Long> wall = new ArrayList<>(), cpu = new ArrayList<>(), processCpu = new ArrayList<>(), bytes = new ArrayList<>();
        void add(Probe p) { wall.add(p.wall); cpu.add(p.cpu); processCpu.add(p.processCpu); bytes.add(p.bytes); }
        Map<String, Object> json() { return map("wall_ns", wall, "thread_cpu_ns", cpu, "process_cpu_ns", processCpu, "jvm_allocated_bytes", bytes); }
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static <T> T edt(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        EventQueue.invokeAndWait(task);
        return task.get();
    }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> entries) return "{" + String.join(",", entries.entrySet().stream().map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof Collection<?> entries) return "[" + String.join(",", entries.stream().map(WaylandGpuBenchmark::json).toList()) + "]";
        throw new IllegalArgumentException("Unsupported JSON type " + value.getClass());
    }

    /** Real observations from the Step 2 long trace, fitted into the logical window. */
    static InkPointerSample[] observations(PenRetainedAuthoringBenchmark.Workload work) {
        InkPointerSample[] result = new InkPointerSample[INPUTS];
        int rows = Math.max(1, (INPUTS + 511) / 512);
        for (int i = 0; i < INPUTS; i++) {
            int row = i / 512, col = i % 512;
            float x = 40f + (row % 2 == 0 ? col : 511 - col) * (work.width() - 80f) / 511f;
            float y = 40f + row * (work.height() - 80f) / rows + (float) Math.sin(i * .5) * 16f;
            result[i] = PenRetainedAuthoringBenchmark.sample(x, y, 1000L + i * 4L, .5f);
        }
        return result;
    }
    static void begin(InkAuthoringSession session, InkPointerSample[] samples) {
        Brush brush = PenRetainedAuthoringBenchmark.marker();
        session.handle(new InkInputEvent.Begin(samples[0], 0L), brush, AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
        int start = INPUTS - CHANGED * BATCH;
        if (start > 1) session.handle(new InkInputEvent.Batch(Arrays.asList(samples).subList(1, start), List.of(), 0L), brush, AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
        session.advance(samples[start - 1].getUptimeMillis());
    }
    static void append(InkAuthoringSession session, InkPointerSample[] samples, int frame) {
        int start = INPUTS - CHANGED * BATCH + frame * BATCH, end = start + BATCH;
        InkPointerSample last = samples[end - 1];
        session.handle(new InkInputEvent.Batch(Arrays.asList(samples).subList(start, end), PenRetainedAuthoringBenchmark.prediction(last, end), 0L),
                PenRetainedAuthoringBenchmark.marker(), AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
        session.advance(last.getUptimeMillis());
    }
    static int vertexCount(InProgressStroke stroke) { return InkMeshes.INSTANCE.rendering(stroke, 0).stream().mapToInt(StrokeMesh::getVertexCount).sum(); }
    static String meshHash(InProgressStroke stroke) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (StrokeMesh mesh : InkMeshes.INSTANCE.rendering(stroke, 0)) {
            integerHash(digest, mesh.getAttributeMask());
            integerHash(digest, mesh.getVertexCount()); integerHash(digest, mesh.getTriangleCount());
            for (float value : mesh.getVertices()) integerHash(digest, Float.floatToRawIntBits(value));
            for (int value : mesh.getTriangles()) integerHash(digest, value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static void integerHash(MessageDigest digest, int value) { for (int b = 0; b < 4; b++) digest.update((byte) (value >>> (8 * b))); }
    static byte[] pixels(BufferedImage image) {
        int[] values = ((java.awt.image.DataBufferInt) image.getRaster().getDataBuffer()).getData();
        byte[] bytes = new byte[values.length * 4];
        for (int i = 0; i < values.length; i++) for (int b = 0; b < 4; b++) bytes[i * 4 + b] = (byte) (values[i] >>> (8 * b));
        return bytes;
    }

    static final class Target implements AutoCloseable {
        final PenRetainedAuthoringBenchmark.Workload work;
        final String mode;
        final InkMeshRenderer renderer = new InkMeshRenderer();
        final PenRetainedAuthoringBenchmark.Scene scene;
        PenRetainedAuthoringBenchmark.Painter software;
        SoftwareSwingRedrawer redrawer;
        SoftwareSwingPainter transfer;
        HeadlessEglContext egl;
        DirectContext context;
        Surface gpuSurface, background;
        Image backgroundImage;
        List<InkLiveStroke> live = List.of();
        boolean closed;
        Target(Host host) throws Exception {
            work = host.work; mode = host.mode;
            scene = new PenRetainedAuthoringBenchmark.Scene(work, renderer);
            SwingLayerProperties properties = new SwingLayerProperties() {
                public int getWidth() { return host.panel.getWidth(); }
                public int getHeight() { return host.panel.getHeight(); }
                public GraphicsConfiguration getGraphicsConfiguration() { return host.panel.getGraphicsConfiguration(); }
                public GpuPriority getAdapterPriority() { return GpuPriority.Auto; }
                public long getGpuResourceCacheLimit() { return 64L * 1024 * 1024; }
            };
            try { if (mode.equals("egl-readback")) {
                egl = new HeadlessEglContext();
                context = egl.makeSkiaContext();
                context.setResourceCacheLimit(64L * 1024 * 1024);
                ImageInfo info = ImageInfo.Companion.makeS32(work.physicalWidth(), work.physicalHeight(), ColorAlphaType.PREMUL);
                gpuSurface = Surface.Companion.makeRenderTarget(context, false, info);
                background = Surface.Companion.makeRenderTarget(context, false, info);
                require(gpuSurface != null && background != null, "EGL offscreen surfaces");
                transfer = new SoftwareSwingPainter(properties);
                RUNTIME.put("egl", map("renderer", egl.renderer(), "vendor", egl.vendor(), "version", egl.version(),
                        "egl_vendor", egl.eglVendor(), "egl_version", egl.eglVersion(), "software_renderer", egl.isSoftwareRenderer(),
                        "platform", "EGL_MESA_platform_surfaceless", "skia_interface", "public GLAssembledInterface + makeGLWithInterface"));
                boolean knownHardware = egl.vendor().equals("NVIDIA Corporation") && egl.renderer().startsWith("NVIDIA ");
                require(DRIVER.equals("software") ? egl.isSoftwareRenderer() : !egl.isSoftwareRenderer() && knownHardware,
                        "Requested " + DRIVER + " OpenGL driver; observed " + egl.vendor() + "/" + egl.renderer());
                egl.releaseCurrent();
            } else {
                require(mode.equals("software-full") || mode.equals("software-retained"), "Known rendering mode " + mode);
                software = new PenRetainedAuthoringBenchmark.Painter(mode.equals("software-retained") ? "retained" : "full", work, renderer, scene);
                redrawer = new SoftwareSwingRedrawer(properties, (canvas, width, height, time) -> {
                    require(width == work.physicalWidth() && height == work.physicalHeight(), "Actual physical software dimensions");
                    software.live = live; software.draw(canvas);
                }, SkiaLayerAnalytics.Companion.getEmpty());
            } } catch (Throwable failure) {
                try { close(); } catch (Throwable cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        void draw(Graphics2D graphics) {
            if (egl == null) { redrawer.redraw(graphics); return; }
            egl.makeCurrent();
            try {
                if (backgroundImage == null) {
                    org.jetbrains.skia.Canvas canvas = background.getCanvas(); canvas.clear(CLEAR); canvas.save();
                    try { canvas.scale(work.scale(), work.scale()); scene.draw.invoke(SkiaBackedCanvas_skikoKt.asComposeCanvas(canvas), work.width(), work.height()); }
                    finally { canvas.restore(); }
                    backgroundImage = background.makeImageSnapshot();
                }
                org.jetbrains.skia.Canvas canvas = gpuSurface.getCanvas();
                canvas.clear(CLEAR); canvas.drawImage(backgroundImage, 0f, 0f); canvas.save();
                try {
                    canvas.scale(work.scale(), work.scale()); Canvas compose = SkiaBackedCanvas_skikoKt.asComposeCanvas(canvas);
                    for (InkLiveStroke stroke : live) require(renderer.render(compose, stroke.getStroke(), stroke.getStrokeToView(), scene.viewport, null), "GPU live stroke visible");
                } finally { canvas.restore(); }
                // CPU wait/readback are intentionally inside complete timed rendering/presentation.
                context.flushAndSubmit(gpuSurface, true);
                transfer.paint(graphics, gpuSurface, 0L);
            } finally { egl.releaseCurrent(); }
        }
        void retire(InProgressStroke stroke) throws Exception { if (software != null) software.retire(stroke); renderer.releaseLiveStroke(stroke); }
        long retainedPixels() throws Exception { return software == null ? 0L : software.get("RetainedPixelBytes"); }
        @Override public void close() throws Exception {
            if (closed) return;
            closed = true;
            Throwable failure = null;
            if (egl != null) {
                try { egl.makeCurrent(); }
                catch (Throwable bindFailure) {
                    failure = bindFailure;
                    if (context != null) failure = cleanup(failure, context::abandon);
                }
                if (backgroundImage != null) failure = cleanup(failure, backgroundImage::close);
                failure = cleanup(failure, renderer::close); failure = cleanup(failure, scene::close);
                if (transfer != null) failure = cleanup(failure, transfer::dispose);
                if (gpuSurface != null) failure = cleanup(failure, gpuSurface::close);
                if (background != null) failure = cleanup(failure, background::close);
                if (context != null) failure = cleanup(failure, context::close);
                failure = cleanup(failure, egl::close);
            } else {
                if (redrawer != null) failure = cleanup(failure, redrawer::dispose);
                if (software != null) failure = cleanup(failure, software::close);
                failure = cleanup(failure, renderer::close); failure = cleanup(failure, scene::close);
            }
            if (failure != null) throw new IllegalStateException("Benchmark target cleanup failed", failure);
        }
    }
    @FunctionalInterface interface Cleanup { void run() throws Exception; }
    static Throwable cleanup(Throwable failure, Cleanup action) {
        try { action.run(); } catch (Throwable next) {
            if (failure == null) return next;
            failure.addSuppressed(next);
        }
        return failure;
    }

    static final class Host implements AutoCloseable {
        final PenRetainedAuthoringBenchmark.Workload work;
        final String mode;
        final JFrame frame = new JFrame("ByteInk Wayland Step 3 investigation");
        final JComponent panel;
        Target target;
        long nativePaints, paintRequests;
        String graphicsClass, configClass, surfaceDataClass;
        Object nativePeer;
        Method commitToServer;
        Host(PenRetainedAuthoringBenchmark.Workload work, String mode) {
            this.work = work; this.mode = mode;
            panel = new JComponent() {
                @Override public void paint(java.awt.Graphics graphics) {
                    if (target == null) return;
                    Graphics2D g = (Graphics2D) graphics;
                    graphicsClass = g.getClass().getName(); configClass = g.getDeviceConfiguration().getClass().getName();
                    try {
                        Field data = Class.forName("sun.java2d.SunGraphics2D").getField("surfaceData");
                        data.setAccessible(true); surfaceDataClass = data.get(g).getClass().getName();
                    } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Identify actual JBR destination", failure); }
                    Rectangle clip = g.getClipBounds();
                    require(clip == null || clip.contains(0, 0, getWidth(), getHeight()), "Full native window paint clip");
                    target.draw(g); nativePaints++;
                }
            };
            panel.setDoubleBuffered(false); panel.setOpaque(false);
            RepaintManager.currentManager(panel).setDoubleBufferingEnabled(false);
            frame.setUndecorated(true); frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            frame.setContentPane(panel); frame.setSize(work.width(), work.height()); frame.setVisible(true);
        }
        boolean ready() {
            if (!panel.isShowing() || panel.getWidth() != work.width() || panel.getHeight() != work.height()) return false;
            return WaylandRuntime.INSTANCE.findWindowSurface(panel) != null;
        }
        void initialize() throws Exception {
            double sx = panel.getGraphicsConfiguration().getDefaultTransform().getScaleX(), sy = panel.getGraphicsConfiguration().getDefaultTransform().getScaleY();
            require(sx == work.scale() && sy == work.scale(), "Actual JBR scale " + sx + "/" + sy + " must match workload " + work.scale());
            Field peersField = Class.forName("sun.awt.wl.WLToolkit").getDeclaredField("wlSurfaceToPeerMap");
            peersField.setAccessible(true);
            Map<?, ?> peers = (Map<?, ?>) peersField.get(null);
            synchronized (peers) {
                for (Object peer : peers.values()) if (peer != null) {
                    Method getTarget = peer.getClass().getMethod("getTarget"); getTarget.setAccessible(true);
                    if (getTarget.invoke(peer) == frame) { nativePeer = peer; break; }
                }
            }
            require(nativePeer != null, "JBR owns this configured window peer");
            commitToServer = nativePeer.getClass().getMethod("commitToServer"); commitToServer.setAccessible(true);
            target = new Target(this); paintNative();
            boolean vulkan = configClass.contains("WLVK") && surfaceDataClass.contains("WLVK");
            boolean shm = configClass.contains("WLSM") && surfaceDataClass.contains("WLSM");
            require(DESTINATION.equals("vulkan") ? vulkan : shm, "Requested " + DESTINATION + " destination; actual " + configClass + "/" + surfaceDataClass);
            Class<?> vkEnv = Class.forName("sun.java2d.vulkan.VKEnv");
            Method enabled = vkEnv.getMethod("isPresentationEnabled"); enabled.setAccessible(true);
            require(enabled.invoke(null).equals(vulkan), "JBR Vulkan presentation enablement matches actual destination");
            List<String> devices = new ArrayList<>();
            String chosenGpu = null, chosenGpuType = null;
            if (vulkan) {
                Method getDevices = vkEnv.getMethod("getDevices"); getDevices.setAccessible(true);
                try (var stream = (java.util.stream.Stream<?>) getDevices.invoke(null)) {
                    for (Object device : stream.toList()) {
                        Method name = device.getClass().getMethod("getName"); name.setAccessible(true);
                        devices.add((String) name.invoke(device));
                    }
                }
                Method getGpu = Class.forName("sun.java2d.vulkan.VKGraphicsConfig").getMethod("getGPU"); getGpu.setAccessible(true);
                Object gpu = getGpu.invoke(panel.getGraphicsConfiguration());
                Method name = gpu.getClass().getMethod("getName"); name.setAccessible(true);
                Method type = gpu.getClass().getMethod("getType"); type.setAccessible(true);
                chosenGpu = (String) name.invoke(gpu); chosenGpuType = ((Enum<?>) type.invoke(gpu)).name();
                require(DRIVER.equals("software") ? chosenGpuType.equals("CPU")
                        : chosenGpuType.equals("DISCRETE_GPU") || chosenGpuType.equals("INTEGRATED_GPU"),
                        "Requested " + DRIVER + " Vulkan device; observed " + chosenGpu + "/" + chosenGpuType);
            }
            RUNTIME.put("graphics", map("toolkit", Toolkit.getDefaultToolkit().getClass().getName(), "graphics", graphicsClass,
                    "graphics_configuration", configClass, "graphics_configuration_description", panel.getGraphicsConfiguration().toString(),
                    "surface_data", surfaceDataClass, "destination", DESTINATION, "vulkan_devices", devices,
                    "chosen_vulkan_gpu", chosenGpu, "chosen_vulkan_gpu_type", chosenGpuType,
                    "requested_egl_vendor_file", System.getenv("__EGL_VENDOR_LIBRARY_FILENAMES"), "requested_vulkan_icd_file", System.getenv("VK_ICD_FILENAMES")));
        }
        void paintNative() {
            long before = nativePaints;
            panel.paintImmediately(0, 0, panel.getWidth(), panel.getHeight());
            // JBR normally commits after an AWT paint event. This investigation explicitly
            // invokes that owning-peer operation while a timing cycle occupies the EDT.
            try { commitToServer.invoke(nativePeer); }
            catch (ReflectiveOperationException failure) { throw new IllegalStateException("JBR window commit", failure); }
            Toolkit.getDefaultToolkit().sync();
            require(nativePaints == before + 1, "Each timed request paints the actual native window exactly once");
            paintRequests++;
        }
        byte[] snapshot() {
            BufferedImage image = new BufferedImage(work.physicalWidth(), work.physicalHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D graphics = image.createGraphics();
            try { graphics.scale(work.scale(), work.scale()); target.draw(graphics); }
            finally { graphics.dispose(); }
            return pixels(image);
        }
        byte[] nativeSnapshot() throws Exception {
            Graphics2D graphics = (Graphics2D) frame.getGraphics();
            require(graphics != null, "Actual window graphics for client buffer capture");
            try {
                Field field = Class.forName("sun.java2d.SunGraphics2D").getField("surfaceData"); field.setAccessible(true);
                Object data = field.get(graphics);
                Method getBounds = data.getClass().getMethod("getBounds"); getBounds.setAccessible(true);
                Rectangle bounds = (Rectangle) getBounds.invoke(data);
                require(bounds.width == work.physicalWidth() && bounds.height == work.physicalHeight(), "Actual JBR buffer physical dimensions");
                Class<?> grabber = Class.forName("sun.java2d.wl.WLPixelGrabberExt");
                require(grabber.isInstance(data), "Pinned JBR native buffer capture supported");
                Method getPixels = grabber.getMethod("getRGBPixelsAt", Rectangle.class); getPixels.setAccessible(true);
                int[] values = (int[]) getPixels.invoke(data, bounds);
                byte[] bytes = new byte[values.length * 4];
                for (int p = 0; p < values.length; p++) for (int c = 0; c < 4; c++) bytes[p * 4 + c] = (byte) (values[p] >>> (8 * c));
                return bytes;
            } finally { graphics.dispose(); }
        }
        NativeCounterSnapshot nativeCounterSnapshot() throws Exception {
            if (!WINDOW_COUNTERS || !DESTINATION.equals("software")) return null;
            Class<?> accessors = Class.forName("sun.awt.AWTAccessor");
            Method getAccessor = accessors.getMethod("getWindowAccessor"); getAccessor.setAccessible(true);
            Object accessor = getAccessor.invoke(null);
            Class<?> windowAccessor = Class.forName("sun.awt.AWTAccessor$WindowAccessor");
            Method countersEnabled = windowAccessor.getMethod("countersEnabled", Window.class); countersEnabled.setAccessible(true);
            boolean enabled = (Boolean) countersEnabled.invoke(accessor, frame);
            Method getCounter = windowAccessor.getMethod("getCounter", Window.class, String.class);
            getCounter.setAccessible(true);
            Map<String, Long> raw = new TreeMap<>();
            for (String key : List.of("java2d.native.frames", "java2d.native.framesDropped"))
                raw.put(key, ((Number) getCounter.invoke(accessor, frame, key)).longValue());
            // A distinct manual counter calibrates the accessor outside every timed Probe.
            // It proves the Window accessor works, not that native callbacks occurred.
            String probeKey = "byteink.counter.probe";
            long probeBefore = ((Number) getCounter.invoke(accessor, frame, probeKey)).longValue();
            Method incrementCounter = windowAccessor.getMethod("incrementCounter", Window.class, String.class);
            incrementCounter.setAccessible(true); incrementCounter.invoke(accessor, frame, probeKey);
            long probeAfter = ((Number) getCounter.invoke(accessor, frame, probeKey)).longValue();
            boolean probePassed = enabled && probeAfter == (probeBefore < 0 ? 1L : probeBefore + 1L);
            Graphics2D graphics = (Graphics2D) frame.getGraphics();
            require(graphics != null, "Actual window graphics for counter destination inspection");
            try {
                Field field = Class.forName("sun.java2d.SunGraphics2D").getField("surfaceData"); field.setAccessible(true);
                Object data = field.get(graphics);
                Method getDestination = Class.forName("sun.java2d.SurfaceData").getMethod("getDestination"); getDestination.setAccessible(true);
                Object destination = getDestination.invoke(data);
                return new NativeCounterSnapshot(enabled, destination == frame, data.getClass().getName(),
                        destination == null ? null : destination.getClass().getName(), raw, probeBefore, probeAfter, probePassed);
            } finally { graphics.dispose(); }
        }
        @Override public void close() throws Exception { try { if (target != null) target.close(); } finally { frame.dispose(); Toolkit.getDefaultToolkit().sync(); } }
    }

    static Map<String, Object> diff(byte[] expected, byte[] actual) {
        require(expected.length == actual.length, "Comparable physical pixels");
        long total = 0, differingPixels = 0; int maximum = 0;
        for (int p = 0; p < expected.length; p += 4) {
            boolean differs = false;
            for (int c = 0; c < 4; c++) { int distance = Math.abs((expected[p + c] & 255) - (actual[p + c] & 255)); total += distance; maximum = Math.max(maximum, distance); differs |= distance != 0; }
            if (differs) differingPixels++;
        }
        return map("differing_pixels", differingPixels, "max_channel_difference", maximum, "mean_absolute_channel_difference", (double) total / expected.length);
    }
    static final class Oracle implements AutoCloseable {
        final InkAuthoringSession session = new InkAuthoringSession(null, 12L);
        final InkMeshRenderer renderer = new InkMeshRenderer();
        final PenRetainedAuthoringBenchmark.Scene scene;
        final PenRetainedAuthoringBenchmark.Painter painter;
        final Surface surface;
        Oracle(PenRetainedAuthoringBenchmark.Workload work) throws Exception {
            scene = new PenRetainedAuthoringBenchmark.Scene(work, renderer);
            painter = new PenRetainedAuthoringBenchmark.Painter("full", work, renderer, scene);
            surface = Surface.Companion.makeRaster(ImageInfo.Companion.makeS32(work.physicalWidth(), work.physicalHeight(), ColorAlphaType.PREMUL));
        }
        @Override public void close() throws Exception {
            Throwable failure = cleanup(null, session::close);
            failure = cleanup(failure, painter::close); failure = cleanup(failure, renderer::close);
            failure = cleanup(failure, scene::close); failure = cleanup(failure, surface::close);
            if (failure != null) throw new IllegalStateException("Oracle cleanup", failure);
        }
    }
    /** The driver yields between EDT tasks so server callbacks and deferred JBR commits can run. */
    static void correctness(Host host) throws Exception {
        var work = host.work; Brush brush = PenRetainedAuthoringBenchmark.marker(); InkPointerSample[] samples = observations(work);
        Map<String, Object> frames = new LinkedHashMap<>();
        Oracle oracle = edt(() -> new Oracle(work));
        try {
            edt(() -> { begin(oracle.session, samples); compare(host, oracle.session, oracle.painter, oracle.surface, frames, "long-prefix"); return null; });
            for (int frame = 0; frame < CHANGED; frame++) { final int step = frame; edt(() -> { append(oracle.session, samples, step); return null; }); }
            edt(() -> { compare(host, oracle.session, oracle.painter, oracle.surface, frames, "predicted-final"); return null; });
            edt(() -> {
                oracle.session.handle(new InkInputEvent.Predict(List.of(), 0L), brush, AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
                oracle.session.advance(samples[INPUTS - 1].getUptimeMillis());
                compare(host, oracle.session, oracle.painter, oracle.surface, frames, "retracted-final");
                require(oracle.session.getLiveStrokes().getFirst().getStroke().getRealInputCount() == INPUTS, "Predictions never enter canonical real history");
                return null;
            });
            edt(() -> {
                for (InkLiveStroke live : oracle.session.getLiveStrokes()) { host.target.retire(live.getStroke()); oracle.renderer.releaseLiveStroke(live.getStroke()); }
                oracle.session.cancelAll(); compare(host, oracle.session, oracle.painter, oracle.surface, frames, "cancel"); return null;
            });
        } finally { edt(() -> { oracle.close(); return null; }); }
        if (host.mode.equals("egl-readback")) {
            Map<?, ?> wet = (Map<?, ?>) frames.get("predicted-final"), cancel = (Map<?, ?>) frames.get("cancel");
            require(!wet.get("candidate_sha256").equals(cancel.get("candidate_sha256")), "GPU actually paints the live geometry");
        }
        CONTROLS.put(work.name() + "/" + host.mode, frames);
    }
    static void compare(Host host, InkAuthoringSession session, PenRetainedAuthoringBenchmark.Painter painter, Surface oracle, Map<String, Object> frames, String label) throws Exception {
        painter.live = session.getLiveStrokes(); painter.draw(oracle.getCanvas());
        byte[] expected = PenRetainedAuthoringBenchmark.readPixels(oracle);
        host.target.live = session.getLiveStrokes(); byte[] actual = host.snapshot();
        Map<String, Object> difference = diff(expected, actual);
        if (!host.mode.equals("egl-readback")) require(Arrays.equals(expected, actual), "Exact software Swing/full reference " + label + " " + difference);
        Map<String, Object> frame = map("software_reference_sha256", PenRetainedAuthoringBenchmark.sha(expected),
                "candidate_sha256", PenRetainedAuthoringBenchmark.sha(actual), "difference", difference);
        host.paintNative(); byte[] nativePixels = host.nativeSnapshot();
        Map<String, Object> nativeDifference = diff(actual, nativePixels);
        require(((Number) nativeDifference.get("max_channel_difference")).intValue() <= 2,
                "Actual JBR client buffer matches the candidate Swing pixels within 2/255: " + nativeDifference);
        frame.put("native_client_buffer_sha256", PenRetainedAuthoringBenchmark.sha(nativePixels)); frame.put("native_vs_candidate_difference", nativeDifference);
        frame.put("gpu_fidelity_status", host.mode.equals("egl-readback") ? "diagnostic software/GPU differences; production fidelity gate is separate" : "exact software reference");
        if (!session.getLiveStrokes().isEmpty()) {
            InProgressStroke stroke = session.getLiveStrokes().getFirst().getStroke();
            int vertices = vertexCount(stroke); if (INPUTS >= 6144) require(vertices >= 8192, "Long frame has >=8192 actual engine vertices");
            frame.put("vertices", vertices); frame.put("real_inputs", stroke.getRealInputCount()); frame.put("mesh_sha256", meshHash(stroke));
        }
        frames.put(label, frame);
    }

    record NativeCounterSnapshot(boolean countersEnabled, boolean destinationIsFrame, String surfaceDataClass,
                                 String destinationClass, Map<String, Long> raw, long probeBefore, long probeAfter, boolean probePassed) {
        Map<String, Object> json() {
            return map("counters_enabled_for_frame", countersEnabled, "surface_destination_is_frame", destinationIsFrame,
                    "surface_data", surfaceDataClass, "surface_destination_class", destinationClass, "raw_native_counters", raw,
                    "calibration", map("key", "byteink.counter.probe", "raw_before", probeBefore, "raw_after", probeAfter,
                            "passed", probePassed, "scope", "Manual accessor increment outside timed Probe; does not verify native frame callbacks"));
        }
    }
    static Map<String, Object> nativeCounterDiagnostic(NativeCounterSnapshot before, NativeCounterSnapshot after) {
        Map<String, Object> observations = new TreeMap<>();
        Map<String, Long> delta = new TreeMap<>();
        boolean allAvailable = before != null && after != null;
        if (allAvailable) for (String key : before.raw.keySet()) {
            long rawBefore = before.raw.get(key), rawAfter = after.raw.get(key);
            String reason = !before.countersEnabled || !after.countersEnabled ? "Window counters disabled"
                    : !before.destinationIsFrame || !after.destinationIsFrame ? "Native SurfaceData destination does not match the inspected Window"
                    : !before.probePassed || !after.probePassed ? "Window accessor calibration failed"
                    : rawBefore < 0 || rawAfter < 0 ? "Native counter key absent at a snapshot boundary; callback availability is unestablished"
                    : rawAfter < rawBefore ? "Native counter decreased across snapshot boundaries" : null;
            boolean available = reason == null;
            allAvailable &= available;
            Long observedDelta = available ? rawAfter - rawBefore : null;
            delta.put(key, observedDelta);
            observations.put(key, map("status", available ? "available" : "unavailable", "raw_before", rawBefore,
                    "raw_after", rawAfter, "delta", observedDelta, "reason", reason));
        }
        return map("enabled", WINDOW_COUNTERS, "status", allAvailable ? "available" : "unavailable",
                "reason", !WINDOW_COUNTERS ? "Optional counter instrumentation disabled"
                        : !DESTINATION.equals("software") ? "No equivalent Vulkan native counter seam" : null,
                "before_snapshot", before == null ? null : before.json(), "after_snapshot", after == null ? null : after.json(),
                "observations", observations, "delta", delta,
                "scope", "Optional instrumented SHM callback counters across warmup+measurement, with raw missing-key values preserved; sent callback precedes enclosing wl_surface_commit; excludes display/scanout and may include normal AWT paints; unavailable deltas remain null; no equivalent Vulkan counter");
    }
    record Painted(Probe elapsed, long sceneCalls) {}
    static Painted paint(Host host) {
        long sceneBefore = host.target.scene.calls;
        Probe start = Probe.start(); host.paintNative(); Probe elapsed = start.end();
        return new Painted(elapsed, host.target.scene.calls - sceneBefore);
    }
    record ChangedPaint(Painted painted, int vertices) {}
    static void timing(Host host) throws Exception {
        InkPointerSample[] samples = observations(host.work); Samples measured = new Samples(), changed = new Samples(), cancelled = new Samples(), first = new Samples();
        long sceneCalls = 0, checkedInputs = 0, paintCount = host.paintRequests;
        NativeCounterSnapshot nativeCountersBefore = edt(host::nativeCounterSnapshot);
        int minimumVertices = Integer.MAX_VALUE, maximumVertices = 0;
        InkAuthoringSession session = edt(() -> new InkAuthoringSession(null, 12L));
        try {
            for (int cycle = 0; cycle < WARMUP + MEASURED; cycle++) {
                final boolean measuring = cycle >= WARMUP;
                Painted initial = edt(() -> { begin(session, samples); host.target.live = session.getLiveStrokes(); return paint(host); });
                sceneCalls += initial.sceneCalls;
                if (cycle == 0) first.add(initial.elapsed);
                for (int frame = 0; frame < CHANGED; frame++) {
                    final int step = frame;
                    ChangedPaint result = edt(() -> {
                        append(session, samples, step); host.target.live = session.getLiveStrokes();
                        int vertices = measuring ? vertexCount(host.target.live.getFirst().getStroke()) : 0;
                        if (measuring && INPUTS >= 6144) require(vertices >= 8192, "Every measured long update has >=8192 engine vertices");
                        return new ChangedPaint(paint(host), vertices);
                    });
                    sceneCalls += result.painted.sceneCalls;
                    if (measuring) {
                        minimumVertices = Math.min(minimumVertices, result.vertices); maximumVertices = Math.max(maximumVertices, result.vertices);
                        measured.add(result.painted.elapsed); changed.add(result.painted.elapsed);
                    }
                }
                Painted cancel = edt(() -> {
                    require(host.target.live.getFirst().getStroke().getRealInputCount() == INPUTS, "Timing canonical input history");
                    for (InkLiveStroke live : host.target.live) host.target.retire(live.getStroke());
                    session.cancelAll(); host.target.live = session.getLiveStrokes();
                    Painted result = paint(host);
                    require(host.target.renderer.getCachedLiveShapeCount() == 0 && host.target.renderer.getCachedLiveGeometryBytes() == 0L, "Zero wet caches after retirement");
                    return result;
                });
                sceneCalls += cancel.sceneCalls;
                if (measuring) { checkedInputs += INPUTS; measured.add(cancel.elapsed); cancelled.add(cancel.elapsed); }
            }
        } finally { edt(() -> { session.close(); return null; }); }
        long frameBytes = host.work.pixelCount() * 4L;
        // These are source-derived transfer volumes, not hardware/performance-counter bandwidth.
        Map<String, Object> copyModel = map("surface_to_cpu_bitmap_bytes_per_frame", frameBytes,
                "bitmap_to_jvm_argb_bytes_per_frame", frameBytes,
                "jbr_shm_destination_write_bytes_per_frame", DESTINATION.equals("software") ? frameBytes : null,
                "jbr_draw_to_shm_show_payload_bytes_per_actually_submitted_full_damage_frame", DESTINATION.equals("software") ? frameBytes : null,
                "jbr_vulkan_destination_upload_payload_if_uncached_bytes_per_frame", DESTINATION.equals("vulkan") ? frameBytes : null,
                "jbr_vulkan_surface_to_swapchain_blit_payload_bytes_per_present_if_issued", DESTINATION.equals("vulkan") ? frameBytes : null,
                "gpu_to_cpu_readback_bytes_per_frame", host.mode.equals("egl-readback") ? frameBytes : 0L,
                "known_source_transfer_payload_bytes_per_frame", frameBytes * (DESTINATION.equals("software") ? 3L : 2L),
                "scope", "Source-derived payload volumes: software3 full-frame request transfers plus a conditional4th draw-to-SHM-show transfer per actual full-damage send; GPU readback overlaps surface-to-bitmap, not additional; Vulkan uploads/staging/present blits conditional; excludes retained restore/blit, driver caches, compositor/scanout; no measured memory bandwidth");
        NativeCounterSnapshot nativeCountersAfter = edt(host::nativeCounterSnapshot);
        CASES.put(host.work.name() + "/" + host.mode, map("logical_width", host.work.width(), "logical_height", host.work.height(), "scale", host.work.scale(),
                "physical_width", host.work.physicalWidth(), "physical_height", host.work.physicalHeight(), "mode", host.mode, "destination", DESTINATION,
                "finished_strokes", PenRetainedAuthoringBenchmark.SCENE_STROKES, "finished_inputs_per_stroke", PenRetainedAuthoringBenchmark.SCENE_INPUTS,
                "real_inputs_per_gesture", INPUTS, "inputs_per_changed_frame", BATCH, "changed_frames_per_gesture", CHANGED,
                "warmup_cycles", WARMUP, "measured_cycles", MEASURED, "measured_frames", measured.wall.size(), "checked_real_inputs", checkedInputs,
                "measured_changed_frames", changed.wall.size(), "measured_cancel_frames", cancelled.wall.size(),
                "minimum_measured_vertices", minimumVertices, "maximum_measured_vertices", maximumVertices,
                "native_paint_and_commit_requests_including_warmup", host.paintRequests - paintCount, "draw_content_calls_in_requested_paints_including_warmup", sceneCalls,
                "retained_cpu_pixel_bytes", edt(host.target::retainedPixels), "offscreen_gpu_pixel_payload_bytes", host.mode.equals("egl-readback") ? frameBytes * 2L : 0L,
                "final_pixel_sha256", PenRetainedAuthoringBenchmark.sha(edt(host::snapshot)), "copy_model", copyModel,
                "native_counter_diagnostic", nativeCounterDiagnostic(nativeCountersBefore, nativeCountersAfter),
                "initial_draw_scope", "First live draw in timing after correctness; context/background/JIT warm, fresh engine/prepared resources; excludes cold application and context/window construction",
                "initial_draw_samples", first.json(), "samples", measured.json(), "changed_samples", changed.json(), "cancel_samples", cancelled.json()));
        System.out.println("Measured " + host.work.name() + "/" + host.mode + " measured paint/commit requests=" + measured.wall.size());
    }

    public static void main(String[] args) throws Exception {
        require(INPUTS > CHANGED * BATCH && BATCH > 0 && CHANGED > 0 && WARMUP >= 0 && MEASURED > 0, "Valid workloads");
        require(DESTINATION.equals("software") || DESTINATION.equals("vulkan"), "Known destination");
        require(DRIVER.equals("software") || DRIVER.equals("hardware"), "Known driver selection");
        require(Toolkit.getDefaultToolkit().getClass().getName().equals("sun.awt.wl.WLToolkit"), "Actual JBR native Wayland toolkit required");
        THREAD.setThreadAllocatedMemoryEnabled(true); THREAD.setThreadCpuTimeEnabled(true);
        RUNTIME.put("java", System.getProperty("java.runtime.version")); RUNTIME.put("vendor", System.getProperty("java.vendor"));
        RUNTIME.put("driver_requested", DRIVER);
        RUNTIME.put("window_counter_instrumentation", WINDOW_COUNTERS);
        RUNTIME.put("timing_scope", "Separate EDT tasks: actual JBR WLToolkit paintImmediately + owning JBR peer commitToServer + Toolkit.sync, complete render/synchronous GPU readback/full-frame Swing copies/client submission request; yields server callbacks between tasks; excludes input handling, any deferred commit completion/coalescing, compositor presentation/scanout and physical pen-to-photon");
        RUNTIME.put("cpu_scope", "EDT CPU plus JVM process CPU including driver workers; excludes separate Weston process; process clock can be quantized");
        RUNTIME.put("allocation_scope", "EDT JVM allocated bytes; excludes native/driver/other threads");
        for (var work : PenRetainedAuthoringBenchmark.WORKLOADS) for (String mode : MODES) {
            System.out.println("Starting " + work.name() + "/" + mode + "/" + DESTINATION);
            Host host = edt(() -> new Host(work, mode));
            try {
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (!edt(host::ready)) {
                    require(System.nanoTime() < deadline, "Actual Wayland window configures within 10 seconds");
                    Thread.sleep(20L);
                }
                edt(() -> { host.initialize(); return null; });
                correctness(host); timing(host);
            } finally { edt(() -> { host.close(); return null; }); }
        }
        Files.writeString(Path.of(args[0]), json(map("schema", 1, "status", "passed", "runtime", RUNTIME, "correctness", CONTROLS, "cases", CASES)) + "\n");
    }
}
