import androidx.compose.ui.geometry.Rect;
import androidx.compose.ui.graphics.Canvas;
import androidx.compose.ui.graphics.SkiaBackedCanvas_skikoKt;
import androidx.compose.ui.graphics.Vertices;
import androidx.ink.brush.Brush;
import androidx.ink.brush.InputToolType;
import androidx.ink.geometry.AffineTransform;
import androidx.ink.strokes.InProgressStroke;
import androidx.ink.strokes.MutableStrokeInputBatch;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.core.*;
import com.vivenotes.byteink.kit.ViveBrushes;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.function.Consumer;
import kotlin.Unit;
import kotlin.jvm.functions.Function3;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.ColorAlphaType;
import org.jetbrains.skia.ImageInfo;
import org.jetbrains.skia.RuntimeEffect;
import org.jetbrains.skia.RuntimeShaderBuilder;
import org.jetbrains.skia.Shader;
import org.jetbrains.skia.Surface;
import org.jetbrains.skiko.GpuPriority;
import org.jetbrains.skiko.SkiaLayerAnalytics;
import org.jetbrains.skiko.swing.SoftwareSwingRedrawer;
import org.jetbrains.skiko.swing.SwingLayerProperties;

/** Genuine native wet mesh workloads. Exact fingerprints/readback run outside all timings. */
public final class WetMeshBenchmark {
    static final int WIDTH = 1024, HEIGHT = 512;
    static final Rect VIEW = new Rect(0, 0, WIDTH, HEIGHT);
    static final int WARMUP = Integer.getInteger("byteink.wet.warmup", 30);
    static final int MEASURED = Integer.getInteger("byteink.wet.measured", 15);
    static final int WARMUP_CYCLES = Integer.getInteger("byteink.wet.warmupCycles", 6);
    static final int MEASURED_CYCLES = Integer.getInteger("byteink.wet.measuredCycles", 3);
    static final com.sun.management.ThreadMXBean BEAN = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final MeshLinearTransform PREP_TRANSFORM = new MeshLinearTransform(1.5f, .2f, -.15f, .9f);
    static final MeshColor PREP_COLOR = new MeshColor(.2f, .3f, .6f, .7f);
    static final Brush BRUSH = ViveBrushes.INSTANCE.brush(ViveBrushes.MARKER, 0, 0xff2060c0, 4f);
    static volatile Object objectSink;
    static volatile long sink;
    static final Map<String, Object> cases = new LinkedHashMap<>();
    static final Map<String, Object> correctness = new LinkedHashMap<>();

    static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static long cpu() { return BEAN.getCurrentThreadCpuTime(); }
    static long allocated() { return BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId()); }
    record Probe(long wall, long cpu, long allocation) {
        static Probe start() { return new Probe(System.nanoTime(), WetMeshBenchmark.cpu(), allocated()); }
        Probe end() { return new Probe(System.nanoTime() - wall, WetMeshBenchmark.cpu() - cpu, allocated() - allocation); }
    }
    static class Samples {
        final List<Long> wall = new ArrayList<>(), cpu = new ArrayList<>(), allocation = new ArrayList<>();
        void add(Probe value, boolean measured) {
            if (measured) { wall.add(value.wall); cpu.add(value.cpu); allocation.add(value.allocation); }
        }
        Map<String, Object> json() { return Map.of("wall_ns", wall, "thread_cpu_ns", cpu, "jvm_allocated_bytes", allocation); }
    }
    static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String)entries[i], entries[i + 1]);
        return result;
    }
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (value instanceof Map<?, ?> entries) return "{" + String.join(",", entries.entrySet().stream().map(e -> json(e.getKey()) + ":" + json(e.getValue())).toList()) + "}";
        if (value instanceof Collection<?> entries) return "[" + String.join(",", entries.stream().map(WetMeshBenchmark::json).toList()) + "]";
        throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
    }
    static float x(int observation) {
        int row = observation / 512, column = observation % 512;
        return 24f + (row % 2 == 0 ? column : 511 - column) * 1.9f;
    }
    static float y(int observation) { return 28f + (observation / 512) * 38f + (float)Math.sin(observation * .5) * 16f; }
    static MutableStrokeInputBatch batch(int start, int count, boolean prediction) {
        MutableStrokeInputBatch result = new MutableStrokeInputBatch();
        for (int i = start; i < start + count; i++) result.add(InputToolType.STYLUS, x(i), y(i) + (prediction ? (float)Math.sin((i-start) * .12) * 5f : 0f),
            i * 4L, StrokeInputDefaults.NO_UNIT, .5f, .4f, .5f);
        return result;
    }
    // All input batches share the explicit default sentinel, including predictions.
    static class StrokeInputDefaults { static final float NO_UNIT = androidx.ink.strokes.StrokeInput.NO_STROKE_UNIT_LENGTH; }
    static InProgressStroke begin(int count) {
        InProgressStroke result = new InProgressStroke();
        result.start(BRUSH, 0);
        result.enqueueInputs(batch(0, count, false), new MutableStrokeInputBatch());
        result.updateShape((count - 1) * 4L);
        require(result.getRealInputCount() == count, "native real input count");
        return result;
    }
    static List<StrokeMesh> export(InProgressStroke stroke) { return InkMeshes.INSTANCE.rendering(stroke, 0); }
    static List<MeshChunk> prepare(List<StrokeMesh> meshes) {
        List<MeshChunk> result = new ArrayList<>();
        for (StrokeMesh mesh : meshes) result.addAll(InkMeshShaderKt.prepareMesh(mesh, PREP_TRANSFORM, PREP_COLOR, null));
        return result;
    }
    static int vertexCount(List<StrokeMesh> meshes) { return meshes.stream().mapToInt(StrokeMesh::getVertexCount).sum(); }
    static int triangleCount(List<StrokeMesh> meshes) { return meshes.stream().mapToInt(StrokeMesh::getTriangleCount).sum(); }
    static void integer(MessageDigest digest, int value) {
        digest.update((byte)value); digest.update((byte)(value >>> 8)); digest.update((byte)(value >>> 16)); digest.update((byte)(value >>> 24));
    }
    static void floats(MessageDigest digest, float[] data) { for (float value : data) integer(digest, Float.floatToRawIntBits(value)); }
    static MessageDigest sha() throws Exception { return MessageDigest.getInstance("SHA-256"); }
    static String meshHash(List<StrokeMesh> meshes) throws Exception {
        MessageDigest digest = sha(); integer(digest, meshes.size());
        for (StrokeMesh mesh : meshes) {
            integer(digest, mesh.getAttributeMask()); integer(digest, mesh.getVertices().length); floats(digest, mesh.getVertices());
            integer(digest, mesh.getTriangles().length); for (int index : mesh.getTriangles()) integer(digest, index);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String preparedHash(List<MeshChunk> chunks) throws Exception {
        MessageDigest digest = sha(); integer(digest, chunks.size());
        for (MeshChunk chunk : chunks) {
            floats(digest, chunk.getVaryings());
            Vertices vertices = chunk.getVertices();
            floats(digest, vertices.getPositions()); floats(digest, vertices.getTextureCoordinates());
            for (int color : vertices.getColors()) integer(digest, color);
            for (short index : vertices.getIndices()) integer(digest, index & 0xffff);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String inputHash(int count) throws Exception {
        MessageDigest digest = sha();
        for (int i = 0; i < count; i++) { integer(digest, Float.floatToRawIntBits(x(i))); integer(digest, Float.floatToRawIntBits(y(i))); integer(digest, i); }
        return HexFormat.of().formatHex(digest.digest());
    }
    static String pixelHash(Surface surface) throws Exception {
        try (Bitmap bitmap = new Bitmap()) {
            require(bitmap.allocN32Pixels(WIDTH, HEIGHT, false), "pixel allocation");
            require(surface.readPixels(bitmap, 0, 0), "pixel readback");
            byte[] pixels = bitmap.readPixels(bitmap.getImageInfo(), bitmap.getRowBytes(), 0, 0);
            require(pixels.length == WIDTH * HEIGHT * 4, "pixel byte count");
            return HexFormat.of().formatHex(sha().digest(pixels));
        }
    }
    static long optionalCounter(InkMeshRenderer renderer, String property) throws Exception {
        for (Method method : InkMeshRenderer.class.getMethods()) if (method.getName().startsWith("get" + property) && method.getParameterCount() == 0)
            return ((Number)method.invoke(renderer)).longValue();
        return -1;
    }
    static void render(InkMeshRenderer renderer, Surface surface, Canvas canvas, InProgressStroke stroke) {
        surface.getCanvas().clear(0);
        require(renderer.render(canvas, stroke, AffineTransform.IDENTITY, VIEW, null), "wet draw visible");
    }
    static Method shaderMaker() {
        for (Method method : RuntimeShaderBuilder.class.getMethods()) if (method.getName().startsWith("makeShader-") && method.getParameterCount() == 1) return method;
        throw new IllegalStateException("Pinned Skiko RuntimeShaderBuilder.makeShader entry missing");
    }
    static void buildShaders(RuntimeEffect effect, Shader white, List<MeshChunk> chunks, Method make) throws Exception {
        try (RuntimeShaderBuilder builder = new RuntimeShaderBuilder(effect)) {
            builder.child("coatTexture", white); builder.uniform("hasTexture", 0); builder.uniform("textureBlend", 0);
            builder.uniform("colorSpaceProbe", .5f, .5f, .5f, 1f);
            for (MeshChunk chunk : chunks) {
                builder.uniform("vertexData", chunk.getVaryings());
                try (Shader shader = (Shader)make.invoke(builder, new Object[] { null })) { sink += System.identityHashCode(shader); }
            }
        }
    }
    static void control(String name, int count) throws Exception {
        InProgressStroke stroke = begin(count);
        List<StrokeMesh> meshes = export(stroke); List<MeshChunk> chunks = prepare(meshes);
        int vertices = vertexCount(meshes), triangles = triangleCount(meshes);
        if (count >= 6144) require(vertices >= 8192, "Long trace must contain >=8192 real vertices, not merely input observations");
        Samples snapshot = new Samples(), preparation = new Samples(), shader = new Samples(), coldNull = new Samples(), coldRaster = new Samples(), warmRaster = new Samples();
        try (InkMeshRenderer renderer = new InkMeshRenderer();
            Surface raster = Surface.Companion.makeRasterN32Premul(WIDTH, HEIGHT);
            Surface nullSurface = Surface.Companion.makeNull(WIDTH, HEIGHT);
            RuntimeEffect effect = RuntimeEffect.Companion.makeForShader(InkMeshShaderKt.getINK_MESH_SKSL());
            Shader white = Shader.Companion.makeColor(0xffffffff)) {
            Canvas canvas = SkiaBackedCanvas_skikoKt.asComposeCanvas(raster.getCanvas());
            Canvas nullCanvas = SkiaBackedCanvas_skikoKt.asComposeCanvas(nullSurface.getCanvas());
            Method make = shaderMaker();
            // Compile renderer effect, initialize Skia and establish cache before steady phases.
            render(renderer, raster, canvas, stroke);
            for (int iteration = 0; iteration < WARMUP + MEASURED; iteration++) {
                boolean measured = iteration >= WARMUP;
                Probe probe = Probe.start(); objectSink = export(stroke); snapshot.add(probe.end(), measured);
                probe = Probe.start(); objectSink = prepare(meshes); preparation.add(probe.end(), measured);
                probe = Probe.start(); buildShaders(effect, white, chunks, make); shader.add(probe.end(), measured);
                renderer.clearCache(); probe = Probe.start(); render(renderer, nullSurface, nullCanvas, stroke); coldNull.add(probe.end(), measured);
                renderer.clearCache(); probe = Probe.start(); render(renderer, raster, canvas, stroke); coldRaster.add(probe.end(), measured);
                probe = Probe.start(); render(renderer, raster, canvas, stroke); warmRaster.add(probe.end(), measured);
            }
            correctness.put(name, map("inputs", count, "partitions", meshes.size(), "vertices", vertices, "triangles", triangles,
                "chunks", chunks.size(), "input_sha256", inputHash(count), "mesh_sha256", meshHash(meshes),
                "prepared_sha256", preparedHash(chunks), "pixel_sha256", pixelHash(raster)));
            cases.put(name, map("inputs", count, "vertices", vertices, "triangles", triangles, "chunks", chunks.size(),
                "warmup_samples", WARMUP, "measured_samples", MEASURED, "retained_live_geometry_bytes", renderer.getCachedLiveGeometryBytes(),
                "samples", map("native_full_export", snapshot.json(), "full_prepare", preparation.json(), "builder_and_chunk_shaders", shader.json(),
                    "cold_null_draw", coldNull.json(), "cold_raster_draw", coldRaster.json(), "warm_raster_draw", warmRaster.json())));
        } finally { stroke.clear(); }
        System.out.println("Measured " + name + ": " + vertices + " vertices / " + triangles + " triangles / " + chunks.size() + " chunks");
    }
    static int predicted(int step) { return switch (step % 4) { case 0 -> 64; case 1 -> 4; case 2 -> 0; default -> 32; }; }
    static void update(InProgressStroke stroke, int realCount, int append, int step) {
        int next = realCount + append, predicted = predicted(step);
        stroke.enqueueInputs(batch(realCount, append, false), batch(next, predicted, true));
        stroke.updateShape((next - 1) * 4L);
        require(stroke.getRealInputCount() == next && stroke.getPredictedInputCount() == predicted, "prediction replacement/real counts");
    }
    static void incrementalCorrectness(String name, int start, int append) throws Exception {
        InProgressStroke stroke = begin(start);
        List<Object> frames = new ArrayList<>();
        try (InkMeshRenderer renderer = new InkMeshRenderer(); Surface raster = Surface.Companion.makeRasterN32Premul(WIDTH, HEIGHT)) {
            Canvas canvas = SkiaBackedCanvas_skikoKt.asComposeCanvas(raster.getCanvas());
            for (int step = 0; step <= 8; step++) {
                int realCount = start + append * step;
                if (step > 0) update(stroke, realCount - append, append, step - 1);
                List<StrokeMesh> meshes = export(stroke); List<MeshChunk> chunks = prepare(meshes);
                if (start >= 5120) require(vertexCount(meshes) >= 8192, "Every long updated frame contains >=8192 actual vertices");
                render(renderer, raster, canvas, stroke);
                frames.add(map("step", step, "real_inputs", realCount, "predicted_inputs", stroke.getPredictedInputCount(),
                    "partitions", meshes.size(), "vertices", vertexCount(meshes), "triangles", triangleCount(meshes),
                    "mesh_sha256", meshHash(meshes), "prepared_sha256", preparedHash(chunks), "pixel_sha256", pixelHash(raster)));
            }
            renderer.releaseLiveStroke(stroke);
            require(renderer.getCachedLiveGeometryBytes() == 0 && renderer.getCachedLiveShapeCount() == 0, "retirement releases all wet preparation");
            correctness.put(name, map("exact_transition_frames", frames, "final_input_sha256", inputHash(start + append * 8), "retired_live_bytes", 0));
        } finally { stroke.clear(); }
    }
    static void incremental(String name, int start, int append) throws Exception {
        incrementalCorrectness(name, start, append);
        Samples initial = new Samples(), update = new Samples(), retirement = new Samples();
        List<Long> retainedBytes = new ArrayList<>(); Map<String, Long> counters = new LinkedHashMap<>();
        for (String property : List.of("PreparedVertexCount", "PreparedChunkCount", "ReusedChunkCount", "ShaderBuildCount")) counters.put(property, 0L);
        try (InkMeshRenderer renderer = new InkMeshRenderer(); Surface raster = Surface.Companion.makeRasterN32Premul(WIDTH, HEIGHT)) {
            Canvas canvas = SkiaBackedCanvas_skikoKt.asComposeCanvas(raster.getCanvas());
            // Warm effect/Skia initialization, outside the fresh-stroke preparation sample.
            InProgressStroke tiny = begin(16); render(renderer, raster, canvas, tiny); renderer.releaseLiveStroke(tiny); tiny.clear();
            for (int cycle = 0; cycle < WARMUP_CYCLES + MEASURED_CYCLES; cycle++) {
                boolean measured = cycle >= WARMUP_CYCLES;
                InProgressStroke stroke = begin(start);
                try {
                    Map<String, Long> before = new LinkedHashMap<>();
                    for (String property : counters.keySet()) before.put(property, optionalCounter(renderer, property));
                    Probe probe = Probe.start(); render(renderer, raster, canvas, stroke); initial.add(probe.end(), measured);
                    for (int step = 0; step < 8; step++) {
                        update(stroke, start + append * step, append, step);
                        probe = Probe.start(); render(renderer, raster, canvas, stroke); update.add(probe.end(), measured);
                        if (measured) retainedBytes.add(renderer.getCachedLiveGeometryBytes());
                    }
                    if (measured) for (String property : counters.keySet()) {
                        long current = optionalCounter(renderer, property), prior = before.get(property);
                        counters.put(property, prior < 0 || current < 0 ? -1L : counters.get(property) + current - prior);
                    }
                    probe = Probe.start(); renderer.releaseLiveStroke(stroke); retirement.add(probe.end(), measured);
                    require(renderer.getCachedLiveGeometryBytes() == 0 && renderer.getCachedLiveShapeCount() == 0, "timed cycle retirement");
                } finally { stroke.clear(); }
            }
        }
        cases.put(name, map("initial_real_inputs", start, "append_inputs_per_update", append, "updates_per_cycle", 8,
            "warmup_cycles", WARMUP_CYCLES, "measured_cycles", MEASURED_CYCLES,
            "retained_live_geometry_bytes_per_update", retainedBytes, "implementation_counters", counters,
            "samples", map("initial_raster_draw", initial.json(), "changed_raster_draw", update.json(), "retire_live_cache", retirement.json())));
        System.out.println("Measured " + name + ": " + update.wall.size() + " changed complete raster draws");
    }
    /** The actual Step 1 Wayland helper, unchanged between compared runtime versions. */
    static final class RetainedPainter implements AutoCloseable {
        final Object raster;
        final Method draw, retire, close, pixelBytes, redrawn;
        final InkMeshRenderer renderer;
        final int clearColor;
        final Function3<Canvas, Integer, Integer, Unit> emptyContent = (canvas, width, height) -> Unit.INSTANCE;
        List<InkLiveStroke> live = List.of();
        RetainedPainter(InkMeshRenderer renderer) throws Exception { this(renderer, 0); }
        RetainedPainter(InkMeshRenderer renderer, int clearColor) throws Exception {
            this.renderer = renderer; this.clearColor = clearColor;
            Class<?> type = Class.forName("com.vivenotes.byteink.compose.InkRetainedAuthoringRaster");
            Constructor<?> constructor = type.getDeclaredConstructor(long.class); constructor.setAccessible(true);
            raster = constructor.newInstance(64L * 1024 * 1024);
            draw = type.getDeclaredMethod("draw", org.jetbrains.skia.Canvas.class, int.class, int.class, float.class, int.class,
                InkRenderer.class, List.class, Function3.class);
            retire = type.getDeclaredMethod("retireLiveStroke", InProgressStroke.class);
            close = type.getDeclaredMethod("close"); pixelBytes = type.getDeclaredMethod("getRetainedPixelBytes");
            redrawn = type.getDeclaredMethod("getLastRedrawnPixelCount");
            for (Method method : List.of(draw, retire, close, pixelBytes, redrawn)) method.setAccessible(true);
        }
        void stroke(InProgressStroke stroke) { live = List.of(new InkLiveStroke(0, stroke, AffineTransform.IDENTITY)); }
        void paint(Surface surface) throws Exception { paint(surface.getCanvas()); }
        void paint(org.jetbrains.skia.Canvas canvas) throws Exception { draw.invoke(raster, canvas, WIDTH, HEIGHT, 1f, clearColor, renderer, live, emptyContent); }
        void retire(InProgressStroke stroke) throws Exception { retire.invoke(raster, stroke); live = List.of(); }
        long bytes() throws Exception { return ((Number)pixelBytes.invoke(raster)).longValue(); }
        long pixels() throws Exception { return ((Number)redrawn.invoke(raster)).longValue(); }
        public void close() throws Exception { close.invoke(raster); }
    }
    static final class SwingOutput implements AutoCloseable {
        final BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_ARGB_PRE);
        final Graphics2D graphics = image.createGraphics();
        final SoftwareSwingRedrawer redrawer;
        SwingOutput(Consumer<org.jetbrains.skia.Canvas> draw) {
            SwingLayerProperties properties = new SwingLayerProperties() {
                public int getWidth() { return WIDTH; }
                public int getHeight() { return HEIGHT; }
                public GraphicsConfiguration getGraphicsConfiguration() { return graphics.getDeviceConfiguration(); }
                public GpuPriority getAdapterPriority() { return GpuPriority.Auto; }
                public long getGpuResourceCacheLimit() { return 64L * 1024 * 1024; }
            };
            redrawer = new SoftwareSwingRedrawer(properties, (canvas, width, height, time) -> draw.accept(canvas), SkiaLayerAnalytics.Companion.getEmpty());
        }
        void paint() { redrawer.redraw(graphics); }
        String pixels() throws Exception {
            MessageDigest digest = sha();
            for (int value : ((DataBufferInt)image.getRaster().getDataBuffer()).getData()) integer(digest, value);
            return HexFormat.of().formatHex(digest.digest());
        }
        public void close() { redrawer.dispose(); graphics.dispose(); }
    }
    static void paintUnchecked(RetainedPainter painter, org.jetbrains.skia.Canvas canvas) {
        try { painter.paint(canvas); } catch (Exception failure) { throw new RuntimeException(failure); }
    }
    static void swingCorrectness(String name, int start, int append) throws Exception {
        InProgressStroke stroke = begin(start); List<Object> frames = new ArrayList<>();
        try (InkMeshRenderer renderer = new InkMeshRenderer(); InkMeshRenderer oracle = new InkMeshRenderer();
            RetainedPainter painter = new RetainedPainter(renderer, 0xffffffff);
            SwingOutput actual = new SwingOutput(canvas -> paintUnchecked(painter, canvas));
            SwingOutput expected = new SwingOutput(canvas -> {
                canvas.clear(0xffffffff); require(oracle.render(SkiaBackedCanvas_skikoKt.asComposeCanvas(canvas), stroke,
                    AffineTransform.IDENTITY, VIEW, null), "full Swing oracle visible");
            })) {
            painter.stroke(stroke);
            for (int step = 0; step <= 8; step++) {
                if (step > 0) update(stroke, start + append * (step - 1), append, step - 1);
                oracle.clearCache(); expected.paint(); actual.paint();
                String reference = expected.pixels(), result = actual.pixels();
                require(reference.equals(result), "Complete software Swing retained/full pixels differ: " + name + "/" + step);
                frames.add(map("step", step, "real_inputs", stroke.getRealInputCount(), "predicted_inputs", stroke.getPredictedInputCount(),
                    "pixel_sha256", result, "redrawn_pixels", painter.pixels()));
            }
            painter.retire(stroke); renderer.releaseLiveStroke(stroke); actual.paint();
            byte[] empty = new byte[WIDTH * HEIGHT * 4]; Arrays.fill(empty, (byte)0xff);
            require(actual.pixels().equals(HexFormat.of().formatHex(sha().digest(empty))), "Swing cancel transfers every exact opaque background pixel");
            correctness.put(name, map("exact_swing_retained_full_frames", frames, "cancel_pixel_sha256", actual.pixels(), "retired_live_bytes", 0));
        } finally { stroke.clear(); }
    }
    static void swing(String name, int start, int append) throws Exception {
        swingCorrectness(name, start, append);
        Samples initial = new Samples(), changed = new Samples(), cancellation = new Samples(), retirement = new Samples();
        List<Long> retainedBytes = new ArrayList<>(), redrawnPixels = new ArrayList<>(); long pixelBytes;
        try (InkMeshRenderer renderer = new InkMeshRenderer(); RetainedPainter painter = new RetainedPainter(renderer, 0xffffffff);
            SwingOutput output = new SwingOutput(canvas -> paintUnchecked(painter, canvas))) {
            InProgressStroke tiny = begin(16); painter.stroke(tiny); output.paint();
            painter.retire(tiny); renderer.releaseLiveStroke(tiny); tiny.clear(); output.paint();
            for (int cycle = 0; cycle < WARMUP_CYCLES + MEASURED_CYCLES; cycle++) {
                boolean measured = cycle >= WARMUP_CYCLES; InProgressStroke stroke = begin(start);
                try {
                    painter.stroke(stroke); Probe probe = Probe.start(); output.paint(); initial.add(probe.end(), measured);
                    for (int step = 0; step < 8; step++) {
                        update(stroke, start + append * step, append, step);
                        probe = Probe.start(); output.paint(); changed.add(probe.end(), measured);
                        if (measured) { retainedBytes.add(renderer.getCachedLiveGeometryBytes()); redrawnPixels.add(painter.pixels()); }
                    }
                    probe = Probe.start(); painter.retire(stroke); renderer.releaseLiveStroke(stroke); retirement.add(probe.end(), measured);
                    probe = Probe.start(); output.paint(); cancellation.add(probe.end(), measured);
                    require(renderer.getCachedLiveGeometryBytes() == 0, "Swing cycle releases preparation");
                } finally { stroke.clear(); }
            }
            pixelBytes = painter.bytes();
        }
        cases.put(name, map("width", WIDTH, "height", HEIGHT, "initial_real_inputs", start, "append_inputs_per_update", append,
            "updates_per_cycle", 8, "warmup_cycles", WARMUP_CYCLES, "measured_cycles", MEASURED_CYCLES,
            "retained_pixel_bytes", pixelBytes, "retained_live_geometry_bytes_per_update", retainedBytes,
            "redrawn_pixels_per_update", redrawnPixels, "samples", map("initial_retained_swing_paint", initial.json(),
                "changed_retained_swing_paint", changed.json(), "cancel_retained_swing_paint", cancellation.json(), "retire_live_cache", retirement.json())));
        System.out.println("Measured " + name + ": " + changed.wall.size() + " complete retained SoftwareSwingRedrawer paints");
    }
    static Surface taggedRaster() { return Surface.Companion.makeRaster(ImageInfo.Companion.makeS32(WIDTH, HEIGHT, ColorAlphaType.PREMUL), 0, null); }
    static void retainedCorrectness(String name, int start, int append) throws Exception {
        InProgressStroke stroke = begin(start); List<Object> frames = new ArrayList<>();
        try (InkMeshRenderer renderer = new InkMeshRenderer(); InkMeshRenderer oracle = new InkMeshRenderer();
            RetainedPainter painter = new RetainedPainter(renderer); Surface raster = taggedRaster(); Surface reference = taggedRaster()) {
            Canvas canvas = SkiaBackedCanvas_skikoKt.asComposeCanvas(reference.getCanvas()); painter.stroke(stroke);
            for (int step = 0; step <= 8; step++) {
                if (step > 0) update(stroke, start + append * (step - 1), append, step - 1);
                // An independently cleared renderer forces complete preparation, without prior chunks/shaders.
                oracle.clearCache(); render(oracle, reference, canvas, stroke); painter.paint(raster);
                String expected = pixelHash(reference), actual = pixelHash(raster);
                require(expected.equals(actual), "Actual retained/full exact pixels differ at " + name + "/" + step);
                List<StrokeMesh> meshes = export(stroke);
                if (start >= 5120) require(vertexCount(meshes) >= 8192, "Retained long frame must contain >=8192 actual vertices");
                frames.add(map("step", step, "real_inputs", stroke.getRealInputCount(), "predicted_inputs", stroke.getPredictedInputCount(),
                    "vertices", vertexCount(meshes), "mesh_sha256", meshHash(meshes), "pixel_sha256", actual,
                    "redrawn_pixels", painter.pixels()));
            }
            painter.retire(stroke); renderer.releaseLiveStroke(stroke); painter.paint(raster); reference.getCanvas().clear(0);
            require(pixelHash(reference).equals(pixelHash(raster)), "cancel restores exact empty background");
            correctness.put(name, map("exact_retained_full_frames", frames, "cancel_pixel_sha256", pixelHash(raster),
                "retained_pixel_bytes", painter.bytes(), "retired_live_bytes", renderer.getCachedLiveGeometryBytes()));
        } finally { stroke.clear(); }
    }
    static void retained(String name, int start, int append) throws Exception {
        retainedCorrectness(name, start, append);
        Samples initial = new Samples(), changed = new Samples(), cancellation = new Samples(), retirement = new Samples();
        List<Long> retainedBytes = new ArrayList<>(), redrawnPixels = new ArrayList<>(); Map<String, Long> counters = new LinkedHashMap<>();
        for (String property : List.of("PreparedVertexCount", "PreparedChunkCount", "ReusedChunkCount", "ShaderBuildCount")) counters.put(property, 0L);
        long pixelBytes;
        try (InkMeshRenderer renderer = new InkMeshRenderer(); RetainedPainter painter = new RetainedPainter(renderer); Surface raster = taggedRaster()) {
            InProgressStroke tiny = begin(16); painter.stroke(tiny); painter.paint(raster);
            painter.retire(tiny); renderer.releaseLiveStroke(tiny); tiny.clear(); painter.paint(raster);
            for (int cycle = 0; cycle < WARMUP_CYCLES + MEASURED_CYCLES; cycle++) {
                boolean measured = cycle >= WARMUP_CYCLES; InProgressStroke stroke = begin(start);
                try {
                    Map<String, Long> before = new LinkedHashMap<>();
                    for (String property : counters.keySet()) before.put(property, optionalCounter(renderer, property));
                    painter.stroke(stroke); Probe probe = Probe.start(); painter.paint(raster); initial.add(probe.end(), measured);
                    for (int step = 0; step < 8; step++) {
                        update(stroke, start + append * step, append, step);
                        probe = Probe.start(); painter.paint(raster); changed.add(probe.end(), measured);
                        if (measured) { retainedBytes.add(renderer.getCachedLiveGeometryBytes()); redrawnPixels.add(painter.pixels()); }
                    }
                    if (measured) for (String property : counters.keySet()) {
                        long current = optionalCounter(renderer, property), prior = before.get(property);
                        counters.put(property, prior < 0 || current < 0 ? -1L : counters.get(property) + current - prior);
                    }
                    probe = Probe.start(); painter.retire(stroke); renderer.releaseLiveStroke(stroke); retirement.add(probe.end(), measured);
                    probe = Probe.start(); painter.paint(raster); cancellation.add(probe.end(), measured);
                    require(renderer.getCachedLiveGeometryBytes() == 0, "retained cycle releases preparation");
                } finally { stroke.clear(); }
            }
            pixelBytes = painter.bytes();
        }
        cases.put(name, map("initial_real_inputs", start, "append_inputs_per_update", append, "updates_per_cycle", 8,
            "warmup_cycles", WARMUP_CYCLES, "measured_cycles", MEASURED_CYCLES, "retained_pixel_bytes", pixelBytes,
            "retained_live_geometry_bytes_per_update", retainedBytes, "redrawn_pixels_per_update", redrawnPixels,
            "implementation_counters", counters, "samples", map("initial_retained_raster_draw", initial.json(),
                "changed_retained_raster_draw", changed.json(), "cancel_retained_raster_draw", cancellation.json(), "retire_live_cache", retirement.json())));
        System.out.println("Measured " + name + ": " + changed.wall.size() + " changed complete retained raster paints");
    }
    static void run(String[] args) throws Exception {
        require(WARMUP >= 0 && MEASURED > 0 && WARMUP_CYCLES >= 0 && MEASURED_CYCLES > 0, "valid bounded iteration counts");
        require(BEAN.isThreadAllocatedMemorySupported() && BEAN.isCurrentThreadCpuTimeSupported(), "JBR CPU/allocation counters required");
        BEAN.setThreadAllocatedMemoryEnabled(true); BEAN.setThreadCpuTimeEnabled(true);
        control("small_wavy_256", 256); control("long_wavy_6144", 6144);
        incremental("small_changed_prediction", 128, 16); incremental("long_changed_prediction", 5120, 128);
        retained("small_retained_prediction", 128, 16); retained("long_retained_prediction", 5120, 128);
        swing("small_retained_swing", 128, 16); swing("long_retained_swing", 5120, 128);
        Map<String, Object> report = map("schema", 1, "status", "passed", "runtime", map("java", System.getProperty("java.runtime.version"),
            "vendor", System.getProperty("java.vendor"), "timing_scope", "Native owned snapshots, CPU preparation, builder/shader construction, complete software raster clear+draw or actual retained helper restore/clipped draw/composition, software Swing full-frame presentation copy to offscreen BufferedImage; excludes actual AWT window/compositor/scanout",
            "allocation_scope", "Current-thread JVM allocation only; excludes native Ink/Skia memory",
            "shader_scope", "One RuntimeShaderBuilder plus each uniform upload/makeShader/close; Java reflection invokes pinned mangled makeShader entry; excludes raster",
            "pixel_format", "Native N32 premultiplied 1024x512 bytes"), "correctness", correctness, "cases", cases);
        Files.writeString(Path.of(args[0]), json(report) + "\n");
        System.out.println("Saved " + args[0]);
    }
    public static void main(String[] args) throws Exception {
        FutureTask<Void> task = new FutureTask<>(() -> { run(args); return null; });
        EventQueue.invokeAndWait(task); task.get();
    }
}
