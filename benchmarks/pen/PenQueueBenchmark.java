package com.vivenotes.byteink.compose;

import androidx.ink.brush.Brush;
import androidx.ink.brush.InputToolType;
import androidx.ink.geometry.AffineTransform;
import androidx.ink.strokes.Stroke;
import com.vivenotes.byteink.kit.ViveBrushes;
import java.awt.EventQueue;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.FutureTask;
import kotlin.Unit;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

/** Structural/CPU control for the new queue, not a historical native-window timing baseline. */
public class PenQueueBenchmark {
    static final Map<String,String> CASES=new LinkedHashMap<>();
    static final com.sun.management.ThreadMXBean BEAN=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
    static volatile long sink;
    static record Probe(long wall,long cpu,long allocated) {
        static Probe start() { return new Probe(System.nanoTime(),BEAN.getCurrentThreadCpuTime(),BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId())); }
        Probe end() { return new Probe(System.nanoTime()-wall,BEAN.getCurrentThreadCpuTime()-cpu,BEAN.getThreadAllocatedBytes(Thread.currentThread().threadId())-allocated); }
    }
    static final class Phase {
        final List<Probe> raw=new ArrayList<>();
        void add(Probe value,boolean measured) { if(measured) raw.add(value); }
        String json() { return "{\"wall_ns\":"+raw.stream().map(p->p.wall).toList()+",\"thread_cpu_ns\":"+raw.stream().map(p->p.cpu).toList()+
            ",\"jvm_allocated_bytes\":"+raw.stream().map(p->p.allocated).toList()+"}"; }
    }
    static void require(boolean value,String message) { if(!value) throw new AssertionError(message); }
    static NativePenBridge.Point point(int i) { return new NativePenBridge.Point(32+i*.04,64+Math.sin(i*.025)*16,i,.5f,10f,20f,NativePenBridge.PRESSURE|NativePenBridge.TILT); }
    static InkPointerSample sample(int i) { NativePenBridge.Point p=point(i); return new InkPointerSample((float)p.x(),(float)p.y(),1000+i,InputToolType.MOUSE,null,null,null,null); }
    static List<Object> packets(int moves,boolean nativePoints,boolean oneHistory) {
        List<Object> packets=new ArrayList<>();
        if(nativePoints) {
            packets.add(new NativePenBridge.Frame(1,NativePenBridge.BEGIN,NativePenBridge.PEN,List.of(point(0))));
            if(oneHistory) {
                List<NativePenBridge.Point> points=new ArrayList<>();
                for(int i=1;i<=moves;i++) points.add(point(i));
                packets.add(new NativePenBridge.Frame(1,NativePenBridge.MOVE,NativePenBridge.PEN,points));
            } else for(int i=1;i<=moves;i++) packets.add(new NativePenBridge.Frame(1,NativePenBridge.MOVE,NativePenBridge.PEN,List.of(point(i))));
            packets.add(new NativePenBridge.Frame(1,NativePenBridge.FINISH,NativePenBridge.PEN,List.of(point(moves+1))));
        } else {
            packets.add(new InkInputEvent.Begin(sample(0),1));
            for(int i=1;i<=moves;i++) packets.add(new InkInputEvent.Move(sample(i),1));
            packets.add(new InkInputEvent.Finish(sample(moves+1),1));
        }
        return packets;
    }
    static void unboundedNativeCoalescingModel(List<Object> packets,Function1<Object,Unit> consume) {
        // The old native drain already coalesced all adjacent native MOVE frames, while AWT
        // InkInputEvent moves stayed separate. Reproduce that distinction in this control.
        List<Object> copy=new ArrayList<>(packets);
        int i=0;
        while(i<copy.size()) {
            Object packet=copy.get(i++);
            if(packet instanceof NativePenBridge.Frame frame&&frame.phase()==NativePenBridge.MOVE) {
                List<NativePenBridge.Point> points=new ArrayList<>(frame.points());
                while(i<copy.size()&&copy.get(i) instanceof NativePenBridge.Frame next&&next.phase()==NativePenBridge.MOVE&&
                    next.pointerId()==frame.pointerId()&&next.tool()==frame.tool()) {
                    points.addAll(next.points());i++;
                }
                packet=new NativePenBridge.Frame(frame.pointerId(),frame.phase(),frame.tool(),points);
            }
            consume.invoke(packet);
        }
    }
    static void runCase(Brush brush,int moves,boolean nativePoints,boolean oneHistory,boolean bounded) throws Exception {
        List<Object> packets=packets(moves,nativePoints,oneHistory);
        Phase complete=new Phase(), firstPaint=new Phase();
        List<Integer> samplesAtPaint=new ArrayList<>(), callbacks=new ArrayList<>(), drains=new ArrayList<>();
        long canonicalInputs=0;
        for(int iteration=0;iteration<19;iteration++) {
            boolean measured=iteration>=10;
            ArrayDeque<Function0<Unit>> posted=new ArrayDeque<>();
            List<Stroke> finished=new ArrayList<>();
            int[] observed={0}, delivered={0}, paintAt={-1}, drainCount={0};
            Probe[] start={null}; boolean[] paintQueued={false};
            try(InkAuthoringSession session=new InkAuthoringSession()) {
                // Actual queue below isolates the bounded-drain policy. The unbounded model uses
                // identical consumer/session/normalizer work, rather than pretending it ran old code.
                NativePenNormalizer normalizer=new NativePenNormalizer(()->1f,null);
                Function1<Object,Unit> consume=packet->{
                    delivered[0]++;
                    List<InkInputEvent> events;
                    if(packet instanceof NativePenBridge.Frame frame) {
                        observed[0]+=frame.points().size();
                        events=normalizer.events(frame,1000+frame.points().getLast().ticks());
                    } else {
                        InkInputEvent event=(InkInputEvent)packet;
                        observed[0]+=event instanceof InkInputEvent.Batch batch?batch.getSamples().size():1;
                        events=List.of(event);
                    }
                    for(InkInputEvent event:events) session.handle(event,brush,AffineTransform.IDENTITY,(id,stroke)->{finished.add(stroke);return Unit.INSTANCE;});
                    if(!paintQueued[0]) {
                        paintQueued[0]=true;
                        posted.add(()->{
                            paintAt[0]=observed[0];
                            session.advance(1000+observed[0]-1);
                            firstPaint.add(start[0].end(),measured);
                            return Unit.INSTANCE;
                        });
                    }
                    return Unit.INSTANCE;
                };
                NativeInkPacketQueue actual=bounded?new NativeInkPacketQueue(consume,block->{posted.add(()->{drainCount[0]++;return block.invoke();});return Unit.INSTANCE;},()->0L):null;
                start[0]=Probe.start();
                try {
                    if(bounded) for(Object packet:packets) actual.enqueue(packet);
                    else posted.add(()->{drainCount[0]++;unboundedNativeCoalescingModel(packets,consume);return Unit.INSTANCE;});
                    while(!posted.isEmpty()) posted.removeFirst().invoke();
                    complete.add(start[0].end(),measured);
                    require(finished.size()==1,"one canonical handoff");
                    Stroke stroke=finished.getFirst();
                    require(stroke.getInputs().getSize()==moves+2,"all accepted real inputs, no predictions");
                    for(int i=0;i<moves+2;i++) {
                        require(stroke.getInputs().get(i).getElapsedTimeMillis()==i,"input ordering/time changed");
                        require(Math.abs(stroke.getInputs().get(i).getX()-(float)point(i).x())<.001f,"input coordinates changed");
                    }
                    require(observed[0]==moves+2,"sample delivery count");
                    require(session.getLiveStrokes().isEmpty(),"finish leaves no active pointer");
                    if(bounded&&!oneHistory) require(paintAt[0]<=512,"bounded queue must yield to paint before more than512 observations");
                    if(!bounded) require(paintAt[0]==moves+2,"model must drain the complete burst before paint");
                    canonicalInputs+=stroke.getInputs().getSize();
                    if(measured) { samplesAtPaint.add(paintAt[0]);callbacks.add(delivered[0]);drains.add(drainCount[0]); }
                    sink+=stroke.getInputs().getSize();
                } finally { if(actual!=null) actual.close(); }
            }
        }
        String name=(bounded?"bounded_queue":"unbounded_control_model")+"_"+(nativePoints?"native":"awt")+"_"+moves+(oneHistory?"_one_history":"_single_points");
        CASES.put(name,"{\"moves\":"+moves+",\"source_packets\":"+packets.size()+",\"single_history\":"+oneHistory+",\"samples_at_first_paint\":"+samplesAtPaint+
            ",\"consumer_callbacks\":"+callbacks+",\"drains\":"+drains+",\"canonical_inputs_checked\":"+canonicalInputs+",\"measured_batches\":9,\"warmup_batches\":10,"+
            "\"complete_delivery\":"+complete.json()+",\"first_paint_ready\":"+firstPaint.json()+"}");
        System.out.println("Measured "+name);
    }
    static void run(String[] args) throws Exception {
        Brush brush=ViveBrushes.INSTANCE.brush(ViveBrushes.MARKER,0,0xff000000,8f);
        for(boolean nativePoints:new boolean[]{false,true}) for(boolean bounded:new boolean[]{false,true}) runCase(brush,8192,nativePoints,false,bounded);
        runCase(brush,8192,true,true,true);
        String result="{\"schema\":1,\"status\":\"passed\",\"java\":\""+System.getProperty("java.runtime.version")+"\",\"vendor\":\""+System.getProperty("java.vendor")+
            "\",\"timing_scope\":\"New queue vs explanatory unbounded control model on real EDT; no native window or compositor; synthetic zero queue budget clock; first paint includes engine advance and excludes raster/presentation\","+
            "\"allocation_scope\":\"Current EDT JVM bytes; excludes native Ink memory\",\"cases\":{"+
            String.join(",",CASES.entrySet().stream().map(e->"\""+e.getKey()+"\":"+e.getValue()).toList())+"}}\n";
        Files.writeString(Path.of(args[0]),result);
    }
    public static void main(String[] args) throws Exception {
        FutureTask<Void> task=new FutureTask<>(()->{run(args);return null;});
        EventQueue.invokeAndWait(task);task.get();
    }
}
