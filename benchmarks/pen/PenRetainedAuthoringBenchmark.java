import androidx.compose.ui.geometry.Rect;
import androidx.compose.ui.graphics.Canvas;
import androidx.compose.ui.graphics.SkiaBackedCanvas_skikoKt;
import androidx.ink.brush.Brush;
import androidx.ink.brush.InputToolType;
import androidx.ink.geometry.AffineTransform;
import androidx.ink.strokes.InProgressStroke;
import androidx.ink.strokes.Stroke;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.kit.ViveBrushes;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.image.BufferedImage;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.FutureTask;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.ImageInfo;
import org.jetbrains.skia.Paint;
import org.jetbrains.skia.Surface;
import org.jetbrains.skiko.GpuPriority;
import org.jetbrains.skiko.SkiaLayerAnalytics;
import org.jetbrains.skiko.swing.SoftwareSwingRedrawer;
import org.jetbrains.skiko.swing.SwingLayerProperties;

/**
 * Real Ink authoring over a finished ink page, using the same software painter as Wayland.
 * Input preparation and exact full-frame pixel checks are outside all timed frame phases.
 * Reflection lets this source compile unchanged against a frozen pre-retention classpath.
 */
public final class PenRetainedAuthoringBenchmark {
    static final int INPUTS = Integer.getInteger("byteink.retained.liveInputs", 8192);
    static final int INPUTS_PER_FRAME = Integer.getInteger("byteink.retained.inputsPerFrame", 128);
    static final int SCENE_STROKES = Integer.getInteger("byteink.retained.sceneStrokes", 48);
    static final int SCENE_INPUTS = Integer.getInteger("byteink.retained.sceneInputs", 128);
    static final boolean PAPER_GRID = !Boolean.getBoolean("byteink.retained.noPaperGrid");
    static final int CLEAR = 0xfffbfbf8;
    static final int WARMUP = Integer.getInteger("byteink.retained.warmupCycles", 2);
    static final int MEASURED = Integer.getInteger("byteink.retained.measuredCycles", 3);
    static final String[] MODES = System.getProperty("byteink.retained.modes", "full,retained").split(",");
    static final String[] SUITES = System.getProperty("byteink.retained.suites", "raster,swing").split(",");
    static final Map<String, String> CASES = new TreeMap<>();
    static final Map<String, String> CORRECTNESS = new TreeMap<>();
    static final com.sun.management.ThreadMXBean BEAN = (com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static volatile Object sink;

    record Workload(int width, int height, int scale) {
        int physicalWidth() { return width * scale; }
        int physicalHeight() { return height * scale; }
        long pixelCount() { return (long)physicalWidth() * physicalHeight(); }
        String name() { return width + "x" + height + "_scale" + scale; }
    }
    static final Workload[] WORKLOADS = selectedWorkloads();
    static Workload[] selectedWorkloads() {
        Workload hd = new Workload(1920, 1080, 1), uhd = new Workload(3840, 2160, 1), doubleHd = new Workload(1920, 1080, 2);
        return switch (System.getProperty("byteink.retained.workloads", "all")) {
            case "all" -> new Workload[] { hd, uhd, doubleHd };
            case "1080p" -> new Workload[] { hd };
            case "4k" -> new Workload[] { uhd };
            case "2x" -> new Workload[] { doubleHd };
            case "1080p,4k" -> new Workload[] { hd, uhd };
            default -> throw new IllegalArgumentException("Unknown workload selection");
        };
    }
    record Probe(long wall, long cpu, long bytes) {
        static Probe start() {
            return new Probe(System.nanoTime(), BEAN.getCurrentThreadCpuTime(), BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()));
        }
        Probe end() {
            return new Probe(System.nanoTime() - wall, BEAN.getCurrentThreadCpuTime() - cpu,
                BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()) - bytes);
        }
    }
    static final class Samples {
        final List<Long> wall = new ArrayList<>(), cpu = new ArrayList<>(), bytes = new ArrayList<>();
        void add(Probe p) { wall.add(p.wall); cpu.add(p.cpu); bytes.add(p.bytes); }
        String json() { return "{\"wall_ns\":" + wall + ",\"thread_cpu_ns\":" + cpu + ",\"jvm_allocated_bytes\":" + bytes + "}"; }
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Brush marker() { return ViveBrushes.INSTANCE.brush(ViveBrushes.MARKER, 0, 0xff2060c0, 8f); }
    static Brush highlighter() { return ViveBrushes.INSTANCE.brush(ViveBrushes.HIGHLIGHTER, 0, 0x80ffff00, 24f); }
    static InkPointerSample sample(float x, float y, long time, float pressure) {
        return new InkPointerSample(x, y, time, InputToolType.STYLUS, pressure, .4f, .5f, null);
    }
    static InkPointerSample[] wetSamples(Workload work) {
        InkPointerSample[] result = new InkPointerSample[INPUTS];
        for (int i = 0; i < INPUTS; i++) {
            float x = 40f + (work.width - 80f) * i / (Math.max(8192, INPUTS) - 1);
            float y = work.height * .52f + (float)Math.sin(i * .014) * 32f;
            result[i] = sample(x, y, 1000L + i, .55f + (float)Math.sin(i * .007) * .2f);
        }
        return result;
    }
    static List<List<InkPointerSample>> batches(InkPointerSample[] samples) {
        List<List<InkPointerSample>> result = new ArrayList<>();
        for (int end = INPUTS_PER_FRAME; end <= INPUTS; end += INPUTS_PER_FRAME) {
            result.add(Arrays.asList(samples).subList(end == INPUTS_PER_FRAME ? 1 : end - INPUTS_PER_FRAME, end));
        }
        return result;
    }
    static List<InkPointerSample> prediction(InkPointerSample last, int index) {
        List<InkPointerSample> result = new ArrayList<>();
        for (int i = 1; i <= 12; i++) result.add(sample(last.getX() + i * 1.4f,
            last.getY() + (float)Math.sin((index + i) * .014) * 2f, last.getUptimeMillis() + i, .6f));
        return result;
    }

    static final class Scene implements AutoCloseable {
        final List<Stroke> strokes = new ArrayList<>();
        final InkMeshRenderer renderer;
        final Paint grid = new Paint();
        final Rect viewport;
        long calls;
        final Function3<Canvas, Integer, Integer, Unit> draw;
        Scene(Workload work, InkMeshRenderer renderer) {
            this.renderer = renderer;
            viewport = new Rect(0f, 0f, work.width, work.height);
            grid.setColor(0x287d8894); grid.setStrokeWidth(1f); grid.setAntiAlias(true);
            try (InkAuthoringController controller = new InkAuthoringController()) {
                for (int row = 0; row < SCENE_STROKES; row++) {
                    float y = 24f + (work.height - 48f) * row / Math.max(1, SCENE_STROKES - 1);
                    Brush brush = row % 6 == 0 ? highlighter() : marker();
                    controller.begin(brush, sample(35f, y, 1000L, .65f), AffineTransform.IDENTITY);
                    for (int i = 1; i < SCENE_INPUTS; i++) {
                        float x = 35f + (work.width - 70f) * i / (SCENE_INPUTS - 1);
                        controller.append(sample(x, y + (float)Math.sin(i * .15 + row) * 7f, 1000L + i, .65f));
                    }
                    strokes.add(controller.finish(null));
                }
            }
            draw = (canvas, width, height) -> {
                calls++;
                org.jetbrains.skia.Canvas skia = SkiaBackedCanvas_skikoKt.getSkiaCanvas(canvas);
                if (PAPER_GRID) for (int y = 24; y < height; y += 24) skia.drawLine(0f, y, width, y, grid);
                for (Stroke stroke : strokes) require(renderer.render(canvas, stroke, AffineTransform.IDENTITY, viewport, null), "scene stroke visible");
                return Unit.INSTANCE;
            };
        }
        @Override public void close() { grid.close(); }
    }

    static final class Painter implements AutoCloseable {
        final boolean retained;
        final Workload work;
        final InkMeshRenderer renderer;
        final Scene scene;
        final Object raster;
        final Method draw, invalidate, retire, close;
        final Map<String, Method> getters = new HashMap<>();
        List<InkLiveStroke> live = List.of();
        long fullRedraws;
        Painter(String mode, Workload work, InkMeshRenderer renderer, Scene scene) throws Exception {
            retained = mode.equals("retained"); this.work = work; this.renderer = renderer; this.scene = scene;
            if (retained) {
                Class<?> type = Class.forName("com.vivenotes.byteink.compose.InkRetainedAuthoringRaster");
                Constructor<?> constructor = type.getDeclaredConstructor(long.class);
                constructor.setAccessible(true); raster = constructor.newInstance(64L * 1024 * 1024);
                draw = type.getDeclaredMethod("draw", org.jetbrains.skia.Canvas.class, int.class, int.class, float.class, int.class,
                    InkRenderer.class, List.class, Function3.class);
                invalidate = type.getDeclaredMethod("invalidateContent");
                retire = type.getDeclaredMethod("retireLiveStroke", InProgressStroke.class);
                close = type.getDeclaredMethod("close");
                for (Method method : List.of(draw, invalidate, retire, close)) method.setAccessible(true);
                for (String getter : List.of("RetainedPixelBytes", "BackgroundBuildCount", "FullRedrawCount", "DirtyRedrawCount", "LastRedrawnPixelCount")) {
                    Method method = type.getDeclaredMethod("get" + getter); method.setAccessible(true); getters.put(getter, method);
                }
            } else { raster = null; draw = invalidate = retire = close = null; }
        }
        void draw(org.jetbrains.skia.Canvas canvas) {
            try {
                if (retained) draw.invoke(raster, canvas, work.physicalWidth(), work.physicalHeight(), (float)work.scale,
                    CLEAR, renderer, live, scene.draw);
                else {
                    canvas.clear(CLEAR); canvas.save();
                    try {
                        canvas.scale(work.scale, work.scale);
                        Canvas compose = SkiaBackedCanvas_skikoKt.asComposeCanvas(canvas);
                        scene.draw.invoke(compose, work.width, work.height);
                        for (InkLiveStroke stroke : live) require(renderer.render(compose, stroke.getStroke(), stroke.getStrokeToView(), scene.viewport, null), "wet stroke visible");
                    } finally { canvas.restore(); }
                    fullRedraws++;
                }
            } catch (Exception failure) { throw new RuntimeException(failure); }
        }
        long get(String key) throws Exception {
            if (retained) return ((Number)getters.get(key).invoke(raster)).longValue();
            return switch (key) {
                case "RetainedPixelBytes" -> 0L;
                case "FullRedrawCount", "BackgroundBuildCount" -> fullRedraws;
                case "DirtyRedrawCount" -> 0L;
                case "LastRedrawnPixelCount" -> work.pixelCount();
                default -> throw new IllegalArgumentException(key);
            };
        }
        void invalidate() throws Exception { if (retained) invalidate.invoke(raster); }
        void retire(InProgressStroke stroke) throws Exception { if (retained) retire.invoke(raster, stroke); }
        @Override public void close() throws Exception { if (retained) close.invoke(raster); }
    }

    interface Output extends AutoCloseable { void paint(); String pixels() throws Exception; }
    static final class RasterOutput implements Output {
        final Surface surface;
        final Painter painter;
        RasterOutput(Workload work, Painter painter) { this.painter = painter; surface = Surface.Companion.makeRaster(ImageInfo.Companion.makeS32(work.physicalWidth(), work.physicalHeight(), ColorAlphaType.PREMUL), 0, null); }
        @Override public void paint() { painter.draw(surface.getCanvas()); }
        @Override public String pixels() throws Exception { return sha(readPixels(surface)); }
        @Override public void close() { surface.close(); }
    }
    static final class SwingOutput implements Output {
        final BufferedImage image;
        final Graphics2D graphics;
        final SoftwareSwingRedrawer redrawer;
        SwingOutput(Workload work, Painter painter) {
            image = new BufferedImage(work.physicalWidth(), work.physicalHeight(), BufferedImage.TYPE_INT_ARGB_PRE);
            graphics = image.createGraphics();
            SwingLayerProperties properties = new SwingLayerProperties() {
                public int getWidth() { return work.physicalWidth(); }
                public int getHeight() { return work.physicalHeight(); }
                public GraphicsConfiguration getGraphicsConfiguration() { return graphics.getDeviceConfiguration(); }
                public GpuPriority getAdapterPriority() { return GpuPriority.Auto; }
                public long getGpuResourceCacheLimit() { return 64L * 1024 * 1024; }
            };
            redrawer = new SoftwareSwingRedrawer(properties, (canvas, width, height, time) -> painter.draw(canvas), SkiaLayerAnalytics.Companion.getEmpty());
        }
        @Override public void paint() { redrawer.redraw(graphics); }
        @Override public String pixels() throws Exception {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int[] pixels = ((java.awt.image.DataBufferInt)image.getRaster().getDataBuffer()).getData();
            for (int value : pixels) { digest.update((byte)value); digest.update((byte)(value >> 8)); digest.update((byte)(value >> 16)); digest.update((byte)(value >> 24)); }
            return HexFormat.of().formatHex(digest.digest());
        }
        @Override public void close() { redrawer.dispose(); graphics.dispose(); }
    }
    static byte[] readPixels(Surface surface) {
        try (Bitmap bitmap = new Bitmap()) {
            require(bitmap.allocPixels(surface.getImageInfo()), "pixel allocation");
            require(surface.readPixels(bitmap, 0, 0), "pixel readback");
            return bitmap.readPixels(bitmap.getImageInfo(), bitmap.getRowBytes(), 0, 0);
        }
    }
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    static void timing(Workload work, String mode, String suite) throws Exception {
        Brush brush = marker(); InkPointerSample[] samples = wetSamples(work); List<List<InkPointerSample>> batches = batches(samples);
        Samples measurement = new Samples(), initialDraw = new Samples();
        long redrawnPixels = 0, sceneCalls = 0, fullRedraws = 0, dirtyRedraws = 0, backgroundBuilds = 0, canonicalInputs = 0;
        String finalHash;
        try (InkAuthoringSession session = new InkAuthoringSession(null, 12L); InkMeshRenderer renderer = new InkMeshRenderer();
             Scene scene = new Scene(work, renderer); Painter painter = new Painter(mode, work, renderer, scene);
             Output output = suite.equals("swing") ? new SwingOutput(work, painter) : new RasterOutput(work, painter)) {
            for (int cycle = 0; cycle < WARMUP + MEASURED; cycle++) {
                boolean measured = cycle >= WARMUP;
                long oldSceneCalls = scene.calls, oldFull = painter.get("FullRedrawCount"), oldDirty = painter.get("DirtyRedrawCount"), oldBackground = painter.get("BackgroundBuildCount");
                session.handle(new InkInputEvent.Begin(samples[0], 0L), brush, AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
                for (int frame = 0; frame < batches.size(); frame++) {
                    List<InkPointerSample> real = batches.get(frame); InkPointerSample last = real.isEmpty() ? samples[0] : real.getLast();
                    session.handle(new InkInputEvent.Batch(real, prediction(last, (frame + 1) * INPUTS_PER_FRAME), 0L), brush, AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
                    session.advance(last.getUptimeMillis()); painter.live = session.getLiveStrokes();
                    Probe start = Probe.start(); output.paint(); Probe elapsed = start.end();
                    if (cycle == 0 && frame == 0) initialDraw.add(elapsed);
                    if (measured) { measurement.add(elapsed); redrawnPixels += painter.get("LastRedrawnPixelCount"); }
                }
                require(painter.live.getFirst().getStroke().getRealInputCount() == INPUTS, "real history count");
                if (measured) canonicalInputs += INPUTS;
                for (InkLiveStroke live : painter.live) { painter.retire(live.getStroke()); renderer.releaseLiveStroke(live.getStroke()); }
                session.cancelAll(); painter.live = session.getLiveStrokes();
                Probe start = Probe.start(); output.paint(); Probe elapsed = start.end();
                if (measured) {
                    measurement.add(elapsed); redrawnPixels += painter.get("LastRedrawnPixelCount");
                    sceneCalls += scene.calls - oldSceneCalls;
                    fullRedraws += painter.get("FullRedrawCount") - oldFull;
                    dirtyRedraws += painter.get("DirtyRedrawCount") - oldDirty;
                    backgroundBuilds += painter.get("BackgroundBuildCount") - oldBackground;
                }
            }
            finalHash = output.pixels();
            String name = work.name() + "/" + suite + "/" + mode;
            CASES.put(name, "{\"logical_width\":" + work.width + ",\"logical_height\":" + work.height + ",\"scale\":" + work.scale +
                ",\"physical_width\":" + work.physicalWidth() + ",\"physical_height\":" + work.physicalHeight() +
                ",\"inputs_per_gesture\":" + INPUTS + ",\"inputs_per_frame\":" + INPUTS_PER_FRAME + ",\"predictions_per_frame\":12" +
                ",\"finished_strokes\":" + SCENE_STROKES + ",\"finished_inputs_per_stroke\":" + SCENE_INPUTS + ",\"paper_grid\":" + PAPER_GRID +
                ",\"warmup_cycles\":" + WARMUP + ",\"measured_cycles\":" + MEASURED + ",\"measured_frames\":" + measurement.wall.size() +
                ",\"checked_real_inputs\":" + canonicalInputs + ",\"draw_content_calls\":" + sceneCalls +
                ",\"background_builds\":" + backgroundBuilds + ",\"full_redraws\":" + fullRedraws + ",\"dirty_redraws\":" + dirtyRedraws +
                ",\"redrawn_pixels\":" + redrawnPixels + ",\"possible_redrawn_pixels\":" + work.pixelCount() * measurement.wall.size() +
                ",\"retained_pixel_bytes\":" + painter.get("RetainedPixelBytes") + ",\"final_pixel_sha256\":\"" + finalHash + "\",\"initial_draw_scope\":\"First draw of a new painter; JIT warmed by correctness; includes new renderer preparation and first retained pixel allocation/background build; excludes target construction and input processing\",\"initial_draw_samples\":" + initialDraw.json() + ",\"samples\":" + measurement.json() + "}");
            System.out.println("Measured " + name + " frames=" + measurement.wall.size() + " drawContent=" + sceneCalls + " rasterPixels=" + redrawnPixels);
        }
    }

    /** Full-render oracle and retained paint use the exact same borrowed session state per frame. */
    static void correctness(Workload work) throws Exception {
        Brush brush = marker(); InkPointerSample[] samples = wetSamples(work); List<List<InkPointerSample>> batches = batches(samples);
        boolean compareRetained = Arrays.asList(MODES).contains("retained");
        MessageDigest fingerprint = MessageDigest.getInstance("SHA-256"); int compared = 0;
        try (InkAuthoringSession session = new InkAuthoringSession(null, 12L); InkMeshRenderer fullRenderer = new InkMeshRenderer();
             InkMeshRenderer retainedRenderer = new InkMeshRenderer(); Scene fullScene = new Scene(work, fullRenderer); Scene retainedScene = new Scene(work, retainedRenderer);
             Painter full = new Painter("full", work, fullRenderer, fullScene); Painter retained = compareRetained ? new Painter("retained", work, retainedRenderer, retainedScene) : null;
             RasterOutput expected = new RasterOutput(work, full); RasterOutput actual = compareRetained ? new RasterOutput(work, retained) : null) {
            session.handle(new InkInputEvent.Begin(samples[0], 0L), brush, AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
            for (int frame = 0; frame < batches.size(); frame++) {
                List<InkPointerSample> real = batches.get(frame); InkPointerSample last = real.isEmpty() ? samples[0] : real.getLast();
                session.handle(new InkInputEvent.Batch(real, prediction(last, (frame + 1) * INPUTS_PER_FRAME), 0L), brush, AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
                session.advance(last.getUptimeMillis());
                compare(session, full, retained, expected, actual, fingerprint, frame + "/predicted"); compared++;
                if (frame % 8 == 0) {
                    session.handle(new InkInputEvent.Predict(List.of(), 0L), brush, AffineTransform.IDENTITY, (id, stroke) -> Unit.INSTANCE);
                    session.advance(last.getUptimeMillis());
                    compare(session, full, retained, expected, actual, fingerprint, frame + "/retracted"); compared++;
                }
            }
            require(session.getLiveStrokes().getFirst().getStroke().getRealInputCount() == INPUTS, "correctness accepted history");
            compare(session, full, retained, expected, actual, fingerprint, "unchanged"); compared++;
            for (InkLiveStroke live : session.getLiveStrokes()) {
                if (retained != null) retained.retire(live.getStroke());
                fullRenderer.releaseLiveStroke(live.getStroke()); retainedRenderer.releaseLiveStroke(live.getStroke());
            }
            session.cancelAll();
            compare(session, full, retained, expected, actual, fingerprint, "cancel"); compared++;
            full.invalidate(); if (retained != null) retained.invalidate();
            compare(session, full, retained, expected, actual, fingerprint, "explicit-content-invalidation"); compared++;
            CORRECTNESS.put(work.name(), "{\"frames\":" + compared + ",\"pixel_bytes_per_frame\":" + work.pixelCount() * 4 +
                ",\"full_frame_sequence_sha256\":\"" + HexFormat.of().formatHex(fingerprint.digest()) + "\",\"full_vs_retained_exact\":" + compareRetained + "}");
            System.out.println("Verified " + work.name() + " frames=" + compared + " retainedExact=" + compareRetained);
        }
    }
    static void compare(InkAuthoringSession session, Painter full, Painter retained, RasterOutput expected, RasterOutput actual,
                        MessageDigest fingerprint, String label) throws Exception {
        full.live = session.getLiveStrokes(); expected.paint(); byte[] baseline = readPixels(expected.surface);
        fingerprint.update(MessageDigest.getInstance("SHA-256").digest(baseline));
        if (retained != null) {
            retained.live = full.live; actual.paint(); byte[] painted = readPixels(actual.surface);
            if (!Arrays.equals(baseline, painted)) {
                int mismatch = 0, first = -1;
                for (int i = 0; i < baseline.length; i++) if (baseline[i] != painted[i]) { mismatch++; if (first < 0) first = i; }
                throw new AssertionError("Pixel mismatch " + full.work.name() + " frame " + label + ": " + mismatch + " bytes, first at pixel " + first / 4 +
                    " (" + first / 4 % full.work.physicalWidth() + "," + first / 4 / full.work.physicalWidth() + ")");
            }
        }
    }

    static String jsonMap(Map<String, String> map) { return "{" + String.join(",", map.entrySet().stream().map(e -> "\"" + e.getKey() + "\":" + e.getValue()).toList()) + "}"; }
    static void run(Path output) throws Exception {
        if (BEAN.isThreadAllocatedMemorySupported()) BEAN.setThreadAllocatedMemoryEnabled(true);
        if (BEAN.isThreadCpuTimeSupported()) BEAN.setThreadCpuTimeEnabled(true);
        require(WARMUP >= 0 && MEASURED > 0 && SCENE_STROKES >= 0 && SCENE_INPUTS > 0 && INPUTS > 0 && INPUTS_PER_FRAME > 0 && INPUTS % INPUTS_PER_FRAME == 0, "valid cycle/scene/live counts");
        for (Workload work : WORKLOADS) correctness(work);
        for (Workload work : WORKLOADS) for (String suite : SUITES) for (String mode : MODES) timing(work, mode, suite);
        Files.writeString(output, "{\"schema\":1,\"status\":\"passed\",\"runtime\":{\"java\":\"" + System.getProperty("java.runtime.version") +
            "\",\"vendor\":\"" + System.getProperty("java.vendor") + "\",\"timing_scope\":\"Real Ink live frame raster over " + SCENE_STROKES + " finished strokes with " + SCENE_INPUTS + " inputs each; paper_grid=" + PAPER_GRID + "; live_inputs=" + INPUTS + "; inputs_per_frame=" + INPUTS_PER_FRAME + "; offscreen SoftwareSwingRedrawer includes full window transfer; excludes input processing, AWT window, compositor and physical pen latency\"," +
            "\"allocation_scope\":\"EDT JVM allocations only; excludes native Ink/Skia allocations and other threads\"},\"correctness\":" + jsonMap(CORRECTNESS) + ",\"cases\":" + jsonMap(CASES) + "}\n");
    }
    public static void main(String[] args) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { run(Path.of(args[0])); return null; });
        EventQueue.invokeAndWait(task); task.get();
    }
}
