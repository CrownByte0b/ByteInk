import androidx.ink.brush.Brush;
import androidx.ink.brush.InputToolType;
import androidx.ink.geometry.AffineTransform;
import androidx.ink.strokes.Stroke;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.kit.ViveBrushes;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;

public class PenSessionBenchmark {
    static final int WARMUP_GESTURES = Integer.getInteger("byteink.pen.sessionWarmup", 10);
    static final int MEASURED_GESTURES = Integer.getInteger("byteink.pen.sessionMeasured", 9);
    static final com.sun.management.ThreadMXBean BEAN = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
    static final long THREAD = Thread.currentThread().threadId();
    static volatile long sink;
    static volatile Object objectSink;
    static long allocated() { return BEAN.isThreadAllocatedMemorySupported() ? BEAN.getThreadAllocatedBytes(THREAD) : -1L; }
    static long cpu() { return BEAN.isCurrentThreadCpuTimeSupported() ? BEAN.getCurrentThreadCpuTime() : -1L; }
    static record Probe(long wall, long cpu, long allocated) {
        static Probe start() { return new Probe(System.nanoTime(), PenSessionBenchmark.cpu(), PenSessionBenchmark.allocated()); }
        Probe end() { return new Probe(System.nanoTime()-wall, PenSessionBenchmark.cpu()-cpu, PenSessionBenchmark.allocated()-allocated); }
    }
    static final class Phase {
        final List<Probe> raw = new ArrayList<>();
        void add(Probe p, boolean measured) { if (measured) raw.add(p); }
        String json() { return "{\"wall_ns\":"+raw.stream().map(p -> Long.toString(p.wall())).toList()+",\"thread_cpu_ns\":"+
            raw.stream().map(p -> Long.toString(p.cpu())).toList()+",\"jvm_allocated_bytes\":"+
            raw.stream().map(p -> Long.toString(p.allocated())).toList()+"}"; }
    }
    static InkPointerSample[] samples(int count, int pointer) {
        InkPointerSample[] result = new InkPointerSample[count];
        for (int i=0;i<count;i++) result[i]=new InkPointerSample(32f+i*.04f, 64f+pointer*8+(float)Math.sin(i*.025)*16,
            1000L+i, InputToolType.STYLUS, .5f, .4f, .5f, null);
        return result;
    }
    static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static Map<String,String> cases = new LinkedHashMap<>();
    static void runBurst(Brush brush, int count, int pointers, boolean batch, boolean predictions) {
        InkPointerSample[][] inputs = new InkPointerSample[pointers][];
        List<List<InkPointerSample>> histories = new ArrayList<>();
        for (int p=0;p<pointers;p++) { inputs[p]=samples(count,p); histories.add(Arrays.asList(inputs[p]).subList(1,count)); }
        Phase handle = new Phase(), advance = new Phase(), finish = new Phase(), settled = new Phase();
        int eligibleAfterUpdates=0, eligibleAfter64=0, idleShapeChanges=0;
        long outputCount=0;
        try (InkAuthoringSession session = new InkAuthoringSession(predictions ? () -> new InkLinearPredictor(24L,32f) : null, 12L)) {
            for (int iteration=0; iteration<WARMUP_GESTURES+MEASURED_GESTURES; iteration++) {
                boolean measured=iteration>=WARMUP_GESTURES;
                List<Stroke> finished = new ArrayList<>();
                Function2<Long,Stroke,Unit> callback = (id,stroke) -> { finished.add(stroke); return Unit.INSTANCE; };
                for (int p=0;p<pointers;p++) session.handle(new InkInputEvent.Begin(inputs[p][0],p),brush,AffineTransform.IDENTITY,callback);
                Probe h=Probe.start();
                for (int p=0;p<pointers;p++) {
                    if (batch) session.handle(new InkInputEvent.Batch(histories.get(p),null,p),brush,AffineTransform.IDENTITY,callback);
                    else for (int i=1;i<count;i++) session.handle(new InkInputEvent.Move(inputs[p][i],p),brush,AffineTransform.IDENTITY,callback);
                }
                handle.add(h.end(),measured);
                Probe a=Probe.start(); require(session.advance(1000L+count-1),"burst must update"); advance.add(a.end(),measured);
                for(InkLiveStroke live:session.getLiveStrokes()) require(live.getStroke().getRealInputCount()==count,"lost accepted observations");
                if (measured && session.needsAnimationTick()) eligibleAfterUpdates++;
                for(int tick=1;tick<=16;tick++) if(session.advance(1000L+count-1+4L*tick) && measured) idleShapeChanges++;
                if (measured && session.needsAnimationTick()) eligibleAfter64++;
                Probe n=Probe.start();
                for(int tick=1;tick<=1024;tick++) { sink+=session.advance(1000L+count+64+4L*tick) ? 1 : 0; sink+=session.needsAnimationTick() ? 1 : 0; }
                settled.add(n.end(),measured);
                Probe f=Probe.start();
                for(int p=0;p<pointers;p++) session.handle(new InkInputEvent.Finish(null,p),brush,AffineTransform.IDENTITY,callback);
                finish.add(f.end(),measured);
                require(finished.size()==pointers,"canonical handoff count");
                for(Stroke stroke:finished) { require(stroke.getInputs().getSize()==count,"prediction or observation corruption"); outputCount+=count; }
                require(session.getLiveStrokes().isEmpty(),"finish leaves active tools");
                objectSink=finished;
            }
        }
        String name=(batch?"batch":"move")+"_"+count+"_inputs_"+pointers+"_tools_"+(predictions?"prediction":"none");
        cases.put(name,"{\"inputs_per_pointer\":"+count+",\"pointers\":"+pointers+",\"warmup_gestures\":"+WARMUP_GESTURES+",\"measured_gestures\":"+MEASURED_GESTURES+","+
            "\"event_handle\":"+handle.json()+",\"advance\":"+advance.json()+",\"canonical_finish\":"+finish.json()+
            ",\"settled_1024_4ms_ticks\":"+settled.json()+",\"eligible_after_update\":"+eligibleAfterUpdates+",\"eligible_after_64ms\":"+
            eligibleAfter64+",\"idle_shape_changes\":"+idleShapeChanges+",\"canonical_inputs_checked\":"+outputCount+"}");
        System.out.println("Measured "+name);
    }
    static void snapshots(Brush brush,int pointers) {
        Phase read=new Phase();
        try(InkAuthoringSession session=new InkAuthoringSession(null,12L)) {
            for(int p=0;p<pointers;p++) session.handle(new InkInputEvent.Begin(samples(1,p)[0],p),brush,AffineTransform.IDENTITY,(id,stroke)->Unit.INSTANCE);
            for(int iteration=0;iteration<WARMUP_GESTURES+MEASURED_GESTURES;iteration++) {
                Probe q=Probe.start(); for(int i=0;i<10000;i++) { List<InkLiveStroke> snapshot=session.getLiveStrokes(); objectSink=snapshot; sink+=snapshot.size(); }
                read.add(q.end(),iteration>=WARMUP_GESTURES);
            }
        }
        cases.put("stable_live_snapshot_"+pointers+"_tools","{\"reads_per_sample\":10000,\"warmup_batches\":"+WARMUP_GESTURES+",\"measured_batches\":"+MEASURED_GESTURES+",\"read\":"+read.json()+"}");
    }
    public static void main(String[] args) throws Exception {
        require(WARMUP_GESTURES>=0 && MEASURED_GESTURES>0, "session warmup must be nonnegative and measured gestures positive");
        if(BEAN.isThreadAllocatedMemorySupported()) BEAN.setThreadAllocatedMemoryEnabled(true);
        if(BEAN.isThreadCpuTimeSupported()) BEAN.setThreadCpuTimeEnabled(true);
        Brush brush=ViveBrushes.INSTANCE.brush(ViveBrushes.MARKER,0,0xff000000,8f);
        for(int count:new int[]{512,8192}) for(boolean prediction:new boolean[]{false,true}) for(boolean batch:new boolean[]{false,true}) runBurst(brush,count,1,batch,prediction);
        runBurst(brush,512,8,true,true);
        snapshots(brush,1); snapshots(brush,8);
        String result="{\"schema\":1,\"status\":\"passed\",\"java\":\""+System.getProperty("java.runtime.version")+"\",\"vendor\":\""+
            System.getProperty("java.vendor")+"\",\"timing_scope\":\"Synthetic session CPU processing; no compositor, scanout or physical pen latency\","+
            "\"allocation_scope\":\"Current-thread JVM bytes only; excludes Ink native and Skia native allocations\",\"cases\":{"+
            String.join(",",cases.entrySet().stream().map(e->"\""+e.getKey()+"\":"+e.getValue()).toList())+"}}\n";
        Files.writeString(Path.of(args[0]),result);
        System.out.println("Saved "+args[0]);
    }
}
