import androidx.compose.ui.graphics.Canvas;
import androidx.compose.ui.graphics.SkiaBackedCanvas_skikoKt;
import androidx.ink.brush.Brush;
import androidx.ink.brush.InputToolType;
import androidx.ink.geometry.AffineTransform;
import androidx.ink.geometry.ImmutableAffineTransform;
import androidx.ink.strokes.Stroke;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.core.InkMeshes;
import com.vivenotes.byteink.kit.ViveBrushes;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import kotlin.Unit;
import org.jetbrains.skia.Surface;
import org.jetbrains.skia.EncodedImageFormat;

/** Production InkPathRenderer, with the identical exported outlines supplied to both baselines. */
public final class ByteinkBenchmark {
    static final Brush BRUSH = ViveBrushes.INSTANCE.brush(ViveBrushes.MARKER, 0, 0xff334155, 2.2f);
    record Prototype(List<InkPointerSample> inputs, Stroke stroke, List<float[]> outlines) {}
    static Stroke model(List<InkPointerSample> inputs) {
        List<Stroke> completed = new ArrayList<>();
        try (InkAuthoringSession session = new InkAuthoringSession(null, 12L)) {
            var callback = (kotlin.jvm.functions.Function2<Long, Stroke, Unit>) (id, stroke) -> {
                completed.add(stroke); return Unit.INSTANCE;
            };
            session.handle(new InkInputEvent.Begin(inputs.getFirst(), 0L), BRUSH, AffineTransform.IDENTITY, callback);
            session.handle(new InkInputEvent.Batch(inputs.subList(1, inputs.size()), null, 0L), BRUSH, AffineTransform.IDENTITY, callback);
            session.advance(inputs.getLast().getUptimeMillis());
            session.handle(new InkInputEvent.Finish(null, 0L), BRUSH, AffineTransform.IDENTITY, callback);
        }
        if (completed.size() != 1) throw new AssertionError("canonical finish");
        return completed.getFirst();
    }
    static void generate(Path target) throws Exception {
        try (var out = new DataOutputStream(Files.newOutputStream(target))) {
            out.writeBytes("BYTCMP01"); out.writeInt(32);
            for (int p = 0; p < 32; p++) {
                List<InkPointerSample> inputs = new ArrayList<>();
                for (int i = 0; i < 64; i++) {
                    float x = 3f + i * .27f;
                    float y = 7f + (float) Math.sin(i * (.14 + p * .0017) + p * .2) * (2f + p % 4 * .35f);
                    float pressure = .2f + .65f * (float) Math.pow(Math.sin(Math.PI * i / 63), 2);
                    inputs.add(new InkPointerSample(x, y, 1000L + i * 4, InputToolType.STYLUS, pressure, null, null, null));
                }
                Stroke stroke = model(inputs);
                List<float[]> outlines = InkMeshes.INSTANCE.outlines(stroke.getShape(), 0);
                out.writeInt(inputs.size());
                for (var input : inputs) {
                    out.writeFloat(input.getX()); out.writeFloat(input.getY()); out.writeFloat(input.getPressure());
                    out.writeLong(input.getUptimeMillis());
                }
                out.writeInt(outlines.size());
                for (float[] outline : outlines) {
                    out.writeInt(outline.length);
                    for (float value : outline) out.writeFloat(value);
                }
            }
        }
    }
    static List<Prototype> load(Path source) throws Exception {
        List<Prototype> result = new ArrayList<>();
        try (var in = new DataInputStream(Files.newInputStream(source))) {
            if (!new String(in.readNBytes(8), java.nio.charset.StandardCharsets.US_ASCII).equals("BYTCMP01")) throw new IOException("scene magic");
            int count = in.readInt();
            for (int p = 0; p < count; p++) {
                int n = in.readInt(); List<InkPointerSample> inputs = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    float x = in.readFloat(), y = in.readFloat(), pressure = in.readFloat(); long t = in.readLong();
                    inputs.add(new InkPointerSample(x, y, t, InputToolType.STYLUS, pressure, null, null, null));
                }
                List<float[]> expected = new ArrayList<>();
                int outlines = in.readInt();
                for (int o = 0; o < outlines; o++) {
                    float[] values = new float[in.readInt()];
                    for (int i = 0; i < values.length; i++) values[i] = in.readFloat();
                    expected.add(values);
                }
                Stroke stroke = model(inputs);
                List<float[]> actual = InkMeshes.INSTANCE.outlines(stroke.getShape(), 0);
                if (actual.size() != expected.size()) throw new AssertionError("outline count");
                for (int i = 0; i < actual.size(); i++) if (!Arrays.equals(actual.get(i), expected.get(i))) throw new AssertionError("outline geometry");
                result.add(new Prototype(inputs, stroke, actual));
            }
            if (in.read() != -1) throw new IOException("trailing scene bytes");
        }
        return result;
    }
    static void message(String json) { System.out.println(json); System.out.flush(); }
    public static void main(String[] args) throws Exception {
        if (args[0].equals("generate")) { generate(Path.of(args[1])); return; }
        Path scene = Path.of(args[0]), output = Path.of(args[1]);
        int warmup = Integer.parseInt(args[2]), measured = Integer.parseInt(args[3]);
        var prototypes = load(scene);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(scene)));
        InkPathRenderer renderer = new InkPathRenderer(64);
        try (var input = new BufferedReader(new InputStreamReader(System.in))) {
            // Load native paths before the ready checkpoint, using the same 32 prototypes as the peers.
            try (Surface s = Surface.Companion.makeRasterN32Premul(32, 16)) {
                Canvas canvas = SkiaBackedCanvas_skikoKt.asComposeCanvas(s.getCanvas());
                for (var p : prototypes) renderer.render(canvas, p.stroke(), AffineTransform.IDENTITY, null, null);
            }
            message("{\"event\":\"ready\",\"runtime\":\"" + System.getProperty("java.runtime.version") + "\",\"scene_sha256\":\"" + hash + "\"}");
            String line;
            while ((line = input.readLine()) != null && !line.equals("quit")) {
                // name,width,height,count,alpha,zoom,warmup,measured (last two are process defaults).
                String[] c = line.split(","); String name = c[0];
                int width = Integer.parseInt(c[1]), height = Integer.parseInt(c[2]), count = Integer.parseInt(c[3]);
                boolean alpha = Boolean.parseBoolean(c[4]); float zoom = Float.parseFloat(c[5]);
                List<Long> times = new ArrayList<>();
                try (Surface surface = Surface.Companion.makeRasterN32Premul(width, height)) {
                    Canvas canvas = SkiaBackedCanvas_skikoKt.asComposeCanvas(surface.getCanvas());
                    int columns = Math.max(1, (int) Math.ceil(Math.sqrt(count * 1920.0 / 1080.0)));
                    int rows = Math.max(1, (count + columns - 1) / columns);
                    float sx = width / 1920f, sy = height / 1080f;
                    // Same precomputed float32 transforms, same nonzero-winding paths and SourceOver colors.
                    var transforms = new AffineTransform[count];
                    for (int i = 0; i < count; i++) {
                        float x = 6f + (i % columns) * (1908f / columns), y = 6f + (i / columns) * (1068f / rows);
                        transforms[i] = new ImmutableAffineTransform(sx * zoom, 0f, x * sx, 0f, sy * zoom, y * sy);
                    }
                    for (int i = -warmup; i < measured; i++) {
                        long start = System.nanoTime();
                        surface.getCanvas().clear(0xffffffff);
                        for (int j = 0; j < count; j++) renderer.render(canvas, prototypes.get(j % 32).stroke(), transforms[j], null, alpha ? 0x66334155 : null);
                        long elapsed = System.nanoTime() - start;
                        if (i >= 0) times.add(elapsed);
                    }
                    try (var image = surface.makeImageSnapshot(); var data = image.encodeToData(EncodedImageFormat.PNG, 100, 0)) {
                        Files.write(output.resolve(name + ".png"), data.getBytes());
                    }
                    message("{\"event\":\"case\",\"name\":\"" + name + "\",\"wall_ns\":" + times + ",\"cached_shapes\":" + renderer.getCachedShapeCount() + "}");
                    input.readLine(); // Parent samples the process tree at the loaded-image checkpoint.
                }
            }
        } finally { renderer.clearCache(); }
    }
}
