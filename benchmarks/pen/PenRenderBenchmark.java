import androidx.compose.ui.geometry.Rect;
import androidx.compose.ui.graphics.Canvas;
import androidx.compose.ui.graphics.SkiaBackedCanvas_skikoKt;
import androidx.compose.ui.graphics.Vertices;
import androidx.ink.brush.Brush;
import androidx.ink.geometry.AffineTransform;
import androidx.ink.strokes.InProgressStroke;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.core.*;
import com.vivenotes.byteink.kit.ViveBrushes;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.EventQueue;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.FutureTask;
import kotlin.Unit;
import org.jetbrains.skia.Surface;
import org.jetbrains.skiko.*;
import org.jetbrains.skiko.swing.*;

public class PenRenderBenchmark {
    static Map<String,String> cases = new LinkedHashMap<>();
    static final Rect VIEW = new Rect(0,0,512,512);
    static final MeshLinearTransform TRANSFORM = new MeshLinearTransform(1.5f,.2f,-.15f,.9f);
    static final MeshColor COLOR = new MeshColor(.2f,.3f,.6f,.7f);
    static void require(boolean value,String message) { PenSessionBenchmark.require(value,message); }
    static void hashInt(MessageDigest digest,int value) { digest.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()); }
    static String preparedHash(List<MeshChunk> chunks) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(MeshChunk chunk:chunks) {
            for(float value:chunk.getVaryings()) hashInt(digest,Float.floatToRawIntBits(value));
            Vertices vertices=chunk.getVertices();
            for(float value:vertices.getPositions()) hashInt(digest,Float.floatToRawIntBits(value));
            for(float value:vertices.getTextureCoordinates()) hashInt(digest,Float.floatToRawIntBits(value));
            for(int value:vertices.getColors()) hashInt(digest,value);
            for(short value:vertices.getIndices()) hashInt(digest,value);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    static InkAuthoringSession begin(Brush brush,int count) {
        InkAuthoringSession session=new InkAuthoringSession(null,12);
        InkPointerSample[] samples=PenSessionBenchmark.samples(count,0);
        session.handle(new InkInputEvent.Begin(samples[0],0),brush,AffineTransform.IDENTITY,(id,stroke)->Unit.INSTANCE);
        session.handle(new InkInputEvent.Batch(Arrays.asList(samples).subList(1,count),null,0),brush,AffineTransform.IDENTITY,(id,stroke)->Unit.INSTANCE);
        session.advance(samples[count-1].getUptimeMillis());
        return session;
    }
    static void live(Brush brush,String family,int count,boolean nullCanvas) throws Exception {
        PenSessionBenchmark.Phase cold=new PenSessionBenchmark.Phase(), warm=new PenSessionBenchmark.Phase(), snapshot=new PenSessionBenchmark.Phase();
        long meshBuilds=0; int vertexCount=0,triangleCount=0;
        try(InkAuthoringSession session=begin(brush,count); InkMeshRenderer renderer=new InkMeshRenderer();
            Surface surface=nullCanvas?Surface.Companion.makeNull(512,512):Surface.Companion.makeRasterN32Premul(512,512)) {
            Canvas canvas=SkiaBackedCanvas_skikoKt.asComposeCanvas(surface.getCanvas());
            InProgressStroke stroke=session.getLiveStrokes().getFirst().getStroke();
            for(StrokeMesh mesh:InkMeshes.INSTANCE.rendering(stroke,0)) { vertexCount+=mesh.getVertexCount(); triangleCount+=mesh.getTriangleCount(); }
            for(int iteration=0;iteration<17;iteration++) {
                boolean measured=iteration>=8;
                PenSessionBenchmark.Probe s=PenSessionBenchmark.Probe.start();
                PenSessionBenchmark.objectSink=InkMeshes.INSTANCE.rendering(stroke,0); snapshot.add(s.end(),measured);
                renderer.clearCache(); surface.getCanvas().clear(0);
                PenSessionBenchmark.Probe c=PenSessionBenchmark.Probe.start();
                require(renderer.render(canvas,stroke,AffineTransform.IDENTITY,VIEW,null),"cold live visible"); cold.add(c.end(),measured);
                meshBuilds=renderer.getMeshBuildCount();
                PenSessionBenchmark.Probe w=PenSessionBenchmark.Probe.start();
                for(int repeat=0;repeat<10;repeat++) { surface.getCanvas().clear(0); require(renderer.render(canvas,stroke,AffineTransform.IDENTITY,VIEW,null),"warm live visible"); }
                warm.add(w.end(),measured);
                require(meshBuilds==renderer.getMeshBuildCount(),"unchanged shape must not prepare extra meshes");
            }
        }
        String name="live_"+family+"_"+count+"_"+(nullCanvas?"null":"raster512");
        cases.put(name,"{\"inputs\":"+count+",\"vertices\":"+vertexCount+",\"triangles\":"+triangleCount+",\"measured_batches\":9,\"warmup_batches\":8,"+
            "\"snapshot\":"+snapshot.json()+",\"cold_draw\":"+cold.json()+",\"warm_10_draws\":"+warm.json()+",\"stable_mesh_rebuilds\":0}");
        System.out.println("Measured "+name);
    }
    static void preparation(Brush brush,int count,boolean hsl) throws Exception {
        try(InkAuthoringSession session=begin(brush,count)) {
            StrokeMesh original=InkMeshes.INSTANCE.rendering(session.getLiveStrokes().getFirst().getStroke(),0).getFirst();
            float[] vertices=original.getVertices().clone();
            for(int i=0;i<original.getVertexCount();i++) { int offset=i*StrokeMesh.VERTEX_STRIDE; vertices[offset+3]=.1f+(i%7)*.02f; vertices[offset+4]=-.1f+(i%5)*.1f; vertices[offset+5]=(i%4)*.04f; }
            StrokeMesh mesh=new StrokeMesh(vertices,original.getTriangles().clone(),hsl?original.getAttributeMask()|4:original.getAttributeMask()&~4);
            PenSessionBenchmark.Phase phase=new PenSessionBenchmark.Phase(); String expected=null;
            for(int iteration=0;iteration<23;iteration++) {
                PenSessionBenchmark.Probe p=PenSessionBenchmark.Probe.start();
                List<MeshChunk> chunks=InkMeshShaderKt.prepareMesh(mesh,TRANSFORM,COLOR,null); phase.add(p.end(),iteration>=14);
                PenSessionBenchmark.objectSink=chunks;
                String hash=preparedHash(chunks);
                if(expected==null) expected=hash; else require(expected.equals(hash),"prepared vertex/varying/color/index hash changes");
            }
            cases.put("prepare_"+count+"_"+(hsl?"hsl":"no_hsl"),"{\"vertices\":"+mesh.getVertexCount()+",\"triangles\":"+mesh.getTriangleCount()+
                ",\"attribute_mask\":"+mesh.getAttributeMask()+",\"prepared_sha256\":\""+expected+"\",\"warmup_batches\":14,\"measured_batches\":9,\"prepare\":"+phase.json()+"}");
        }
    }
    static void softwarePresentation(int width,int height) {
        BufferedImage image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D graphics=image.createGraphics();
        SwingLayerProperties props=new SwingLayerProperties() {
            public int getWidth() { return width; }
            public int getHeight() { return height; }
            public GraphicsConfiguration getGraphicsConfiguration() { return graphics.getDeviceConfiguration(); }
            public GpuPriority getAdapterPriority() { return GpuPriority.Auto; }
            public long getGpuResourceCacheLimit() { return 64L*1024*1024; }
        };
        SoftwareSwingRedrawer redrawer=new SoftwareSwingRedrawer(props,(canvas,w,h,time)->canvas.clear(0xffffffff),SkiaLayerAnalytics.Companion.getEmpty());
        PenSessionBenchmark.Phase paint=new PenSessionBenchmark.Phase();
        try {
            for(int iteration=0;iteration<19;iteration++) {
                PenSessionBenchmark.Probe p=PenSessionBenchmark.Probe.start();
                for(int repeat=0;repeat<5;repeat++) redrawer.redraw(graphics);
                paint.add(p.end(),iteration>=10);
                require(image.getRGB(width/2,height/2)==0xffffffff,"complete painter must transfer rendered pixel");
            }
        } finally { redrawer.dispose(); graphics.dispose(); }
        cases.put("software_swing_"+width+"x"+height,"{\"width\":"+width+",\"height\":"+height+",\"paints_per_batch\":5,\"warmup_batches\":10,\"measured_batches\":9,\"full_paint\":"+paint.json()+"}");
        System.out.println("Measured software swing "+width+"x"+height);
    }
    static void run(String[] args) throws Exception {
        Brush marker=ViveBrushes.INSTANCE.brush(ViveBrushes.MARKER,0,0xff2060c0,8f);
        Brush highlighter=ViveBrushes.INSTANCE.brush(ViveBrushes.HIGHLIGHTER,0,0x80ffff00,8f);
        for(int count:new int[]{512,8192}) {
            live(highlighter,"highlighter_discard",count,true); live(highlighter,"highlighter_discard",count,false);
            live(marker,"marker_mesh",count,true); live(marker,"marker_mesh",count,false);
            preparation(marker,count,false); preparation(marker,count,true);
        }
        softwarePresentation(512,512); softwarePresentation(1920,1080); softwarePresentation(3840,2160);
        String result="{\"schema\":1,\"status\":\"passed\",\"java\":\""+System.getProperty("java.runtime.version")+"\",\"vendor\":\""+
            System.getProperty("java.vendor")+"\",\"timing_scope\":\"Synthetic native Ink and Skia software/null canvas; software Swing copies to offscreen BufferedImage; excludes actual AWT window/compositor/scanout\","+
            "\"allocation_scope\":\"Current-thread JVM bytes only; excludes Ink native and Skia native allocations\",\"cases\":{"+
            String.join(",",cases.entrySet().stream().map(e->"\""+e.getKey()+"\":"+e.getValue()).toList())+"}}\n";
        Files.writeString(Path.of(args[0]),result);
        System.out.println("Saved "+args[0]);
    }
    public static void main(String[] args) throws Exception {
        FutureTask<Void> task=new FutureTask<>(()-> { run(args); return null; });
        EventQueue.invokeAndWait(task);
        task.get();
    }
}
