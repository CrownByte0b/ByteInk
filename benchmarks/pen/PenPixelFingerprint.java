import androidx.compose.ui.geometry.Rect;
import androidx.compose.ui.graphics.Canvas;
import androidx.compose.ui.graphics.SkiaBackedCanvas_skikoKt;
import androidx.ink.brush.Brush;
import androidx.ink.geometry.AffineTransform;
import androidx.ink.strokes.Stroke;
import androidx.ink.strokes.StrokeInput;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.kit.ViveBrushes;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import kotlin.Unit;
import org.jetbrains.skia.Bitmap;
import org.jetbrains.skia.Surface;

/** Exact synthetic pixel/input control run separately from all timed measurement phases. */
public class PenPixelFingerprint {
    static final Rect VIEW=new Rect(0,0,512,512);
    static String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static byte[] pixels(Surface surface) {
        try(Bitmap bitmap=new Bitmap()) {
            PenSessionBenchmark.require(bitmap.allocN32Pixels(surface.getWidth(),surface.getHeight(),false),"pixel allocation");
            PenSessionBenchmark.require(surface.readPixels(bitmap,0,0),"pixel readback");
            byte[] result=bitmap.readPixels(bitmap.getImageInfo(),bitmap.getRowBytes(),0,0);
            PenSessionBenchmark.require(result.length==surface.getWidth()*surface.getHeight()*4,"pixel byte count");
            return result;
        }
    }
    static String inputs(Stroke stroke) throws Exception {
        ByteBuffer data=ByteBuffer.allocate(stroke.getInputs().getSize()*40).order(ByteOrder.LITTLE_ENDIAN);
        StrokeInput input=new StrokeInput();
        for(int i=0;i<stroke.getInputs().getSize();i++) {
            stroke.getInputs().populate(i,input);
            data.putFloat(input.getX()).putFloat(input.getY()).putLong(input.getElapsedTimeMillis()).putInt(input.getToolType().getValue())
                .putFloat(input.getPressure()).putFloat(input.getTiltRadians()).putFloat(input.getOrientationRadians()).putFloat(input.getStrokeUnitLengthCm()).putInt(i);
        }
        return sha(data.array());
    }
    public static void main(String[] args) throws Exception {
        Map<String,String> cases=new TreeMap<>();
        for(String family:new String[]{ViveBrushes.MARKER,ViveBrushes.HIGHLIGHTER}) for(int count:new int[]{512,8192}) for(int scale:new int[]{1,2}) {
            Brush brush=ViveBrushes.INSTANCE.brush(family,0,family.equals(ViveBrushes.MARKER)?0xff2060c0:0x80ffff00,8f);
            List<Stroke> finished=new ArrayList<>();
            try(InkAuthoringSession session=new InkAuthoringSession(null,12); InkMeshRenderer renderer=new InkMeshRenderer();
                Surface surface=Surface.Companion.makeRasterN32Premul(512*scale,512*scale)) {
                InkPointerSample[] samples=PenSessionBenchmark.samples(count,0);
                session.handle(new InkInputEvent.Begin(samples[0],0),brush,AffineTransform.IDENTITY,(id,stroke)->{finished.add(stroke);return Unit.INSTANCE;});
                session.handle(new InkInputEvent.Batch(Arrays.asList(samples).subList(1,count),null,0),brush,AffineTransform.IDENTITY,(id,stroke)->{finished.add(stroke);return Unit.INSTANCE;});
                session.advance(1000+count-1);
                Canvas canvas=SkiaBackedCanvas_skikoKt.asComposeCanvas(surface.getCanvas());
                surface.getCanvas().scale(scale,scale);
                surface.getCanvas().clear(0);
                renderer.render(canvas,session.getLiveStrokes().getFirst().getStroke(),AffineTransform.IDENTITY,VIEW,null);
                String wet=sha(pixels(surface));
                surface.getCanvas().clear(0);
                renderer.render(canvas,session.getLiveStrokes().getFirst().getStroke(),AffineTransform.IDENTITY,VIEW,null);
                PenSessionBenchmark.require(wet.equals(sha(pixels(surface))),"stable wet cache changes pixels");
                session.handle(new InkInputEvent.Finish(null,0),brush,AffineTransform.IDENTITY,(id,stroke)->{finished.add(stroke);return Unit.INSTANCE;});
                PenSessionBenchmark.require(finished.size()==1&&finished.getFirst().getInputs().getSize()==count,"canonical input counts");
                Stroke stroke=finished.getFirst();
                surface.getCanvas().clear(0); renderer.render(canvas,stroke,AffineTransform.IDENTITY,VIEW,null);
                String dry=sha(pixels(surface));
                surface.getCanvas().clear(0); renderer.render(canvas,stroke,AffineTransform.IDENTITY,VIEW,null);
                PenSessionBenchmark.require(dry.equals(sha(pixels(surface))),"stable finished cache changes pixels");
                cases.put(family+"_"+count+"_scale"+scale,"{\"inputs\":"+count+",\"scale\":"+scale+",\"pixel_bytes\":"+(512L*scale*512*scale*4)+
                    ",\"wet_pixel_sha256\":\""+wet+"\",\"finished_pixel_sha256\":\""+dry+"\",\"canonical_inputs_sha256\":\""+inputs(stroke)+"\"}");
            }
        }
        Files.writeString(Path.of(args[0]),"{\"schema\":1,\"status\":\"passed\",\"pixel_format\":\"native N32 premultiplied raster bytes\",\"cases\":{"+
            String.join(",",cases.entrySet().stream().map(e->"\""+e.getKey()+"\":"+e.getValue()).toList())+"}}\n");
    }
}
