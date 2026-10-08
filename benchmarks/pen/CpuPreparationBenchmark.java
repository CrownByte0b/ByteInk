import androidx.ink.geometry.AffineTransform;
import androidx.ink.geometry.ImmutableAffineTransform;
import androidx.ink.strokes.InProgressStroke;
import com.vivenotes.byteink.compose.*;
import com.vivenotes.byteink.core.StrokeMesh;
import java.awt.EventQueue;
import java.lang.invoke.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** Part 5: exact pure-loop candidates and the shipped retained software Swing paint path. */
public final class CpuPreparationBenchmark {
    static final int WARMUP = Integer.getInteger("byteink.cpu.warmup", 500);
    static final int MEASURED = Integer.getInteger("byteink.cpu.measured", 200);
    static final int WARMUP_CYCLES = Integer.getInteger("byteink.cpu.warmupCycles", 4);
    static final int MEASURED_CYCLES = Integer.getInteger("byteink.cpu.measuredCycles", 8);
    static final int FORK = Integer.getInteger("byteink.cpu.fork", 0);
    static final MeshLinearTransform TRANSFORM = WetMeshBenchmark.PREP_TRANSFORM;
    static final MeshColor COLOR = WetMeshBenchmark.PREP_COLOR;
    static final MethodHandle ORIGINAL;
    static final Map<String,Object> cases = new LinkedHashMap<>(), controls = new LinkedHashMap<>();
    static final MessageDigest arrayDigest, nativeArrayDigest;
    static final Map<String,Long> numericMismatches = new LinkedHashMap<>();
    static final Map<String,List<Object>> numericFailures = new LinkedHashMap<>();
    static long arrayCases, comparedFloats;
    static volatile float sink;
    static {
        try {
            ORIGINAL = MethodHandles.privateLookupIn(InkMeshShaderKt.class, MethodHandles.lookup()).findStatic(
                InkMeshShaderKt.class, "prepareVertices", MethodType.methodType(void.class,
                    StrokeMesh.class, MeshLinearTransform.class, MeshColor.class, StampAnimation.class,
                    float[].class, float[].class, boolean[].class));
            arrayDigest = WetMeshBenchmark.sha();
            nativeArrayDigest = WetMeshBenchmark.sha();
            for (MeshPreparation.Mode mode : List.of(MeshPreparation.Mode.SCALAR,MeshPreparation.Mode.VECTOR,MeshPreparation.Mode.WORKERS)) {
                numericMismatches.put(mode.name().toLowerCase(Locale.ROOT),0L);
                numericFailures.put(mode.name().toLowerCase(Locale.ROOT),new ArrayList<>());
            }
        } catch (Exception failure) { throw new ExceptionInInitializerError(failure); }
    }
    static void require(boolean ok, String message) { WetMeshBenchmark.require(ok, message); }
    static void original(StrokeMesh mesh, MeshLinearTransform transform, MeshColor color, StampAnimation stamp,
            float[] positions, float[] varying, boolean[] changed) {
        try { ORIGINAL.invokeExact(mesh, transform, color, stamp, positions, varying, changed); }
        catch (Throwable failure) { throw new IllegalStateException("Production pure loop failed", failure); }
    }
    static void direct(MeshPreparation.Mode mode, StrokeMesh mesh, MeshLinearTransform transform, MeshColor color,
            StampAnimation stamp, float[] positions, float[] varying, boolean[] changed) {
        MeshPreparation.mode = MeshPreparation.Mode.ORIGINAL;
        if (mode == MeshPreparation.Mode.ORIGINAL) { original(mesh, transform, color, stamp, positions, varying, changed); return; }
        MeshPreparation.Context context = new MeshPreparation.Context(transform, color, stamp, mesh.getAttributeMask());
        switch (mode) {
            case SCALAR -> MeshPreparation.scalar(mesh.getVertices(), 0, mesh.getVertexCount(), context, positions, varying, changed);
            case VECTOR -> VectorPreparation.prepare(mesh.getVertices(), mesh.getVertexCount(), context, positions, varying, changed);
            case WORKERS -> {
                MeshPreparation.Key key = new MeshPreparation.Key(MeshPreparation.generation, MeshPreparation.version, transform, color, stamp);
                MeshPreparation.Job job = MeshPreparation.pool.submit(mesh, context, changed, key);
                require(job != null && MeshPreparation.pool.apply(job, key, positions, varying), "accepted direct worker job");
            }
            default -> throw new AssertionError(mode);
        }
    }
    static List<MeshPreparation.Mode> order() {
        List<MeshPreparation.Mode> modes = new ArrayList<>(List.of(MeshPreparation.Mode.values()));
        Collections.rotate(modes, FORK % modes.size()); return modes;
    }
    static void equal(float[] expected, float[] actual, String label) {
        require(expected.length == actual.length, label + " length");
        for (int i = 0; i < expected.length; i++) {
            int a = Float.floatToRawIntBits(expected[i]), b = Float.floatToRawIntBits(actual[i]);
            if (a != b) throw new AssertionError(label + " float " + i + ": " + Integer.toHexString(a) + " != " + Integer.toHexString(b));
        }
        comparedFloats += expected.length;
    }
    static float[] sentinel(int n) { float[] a = new float[n]; Arrays.fill(a, Float.intBitsToFloat(0x7fc54321)); return a; }
    static void verifyArrays(StrokeMesh mesh, MeshLinearTransform transform, MeshColor color, StampAnimation stamp, boolean[] changed) {
        verifyArrays(mesh,transform,color,stamp,changed,false);
    }
    static void candidateEqual(float[] expected,float[] actual,MeshPreparation.Mode mode,String array,boolean diagnostic) {
        if (!diagnostic) { equal(expected,actual,mode+" "+array+" native case "+arrayCases); return; }
        String key=mode.name().toLowerCase(Locale.ROOT);
        require(expected.length==actual.length,"candidate diagnostic length");
        for (int i=0;i<expected.length;i++) {
            int a=Float.floatToRawIntBits(expected[i]), b=Float.floatToRawIntBits(actual[i]);
            if (a!=b) {
                numericMismatches.put(key,numericMismatches.get(key)+1);
                if (numericFailures.get(key).size()<32) numericFailures.get(key).add(WetMeshBenchmark.map(
                    "case",arrayCases,"array",array,"offset",i,"expected_raw_bits",Integer.toHexString(a),"actual_raw_bits",Integer.toHexString(b)));
            }
        }
        comparedFloats+=expected.length;
    }
    static void verifyArrays(StrokeMesh mesh, MeshLinearTransform transform, MeshColor color, StampAnimation stamp, boolean[] changed,boolean diagnostic) {
        float[] positions = sentinel(mesh.getVertexCount()*2), varying = sentinel(mesh.getVertexCount()*16);
        direct(MeshPreparation.Mode.ORIGINAL, mesh, transform, color, stamp, positions, varying, changed);
        WetMeshBenchmark.floats(arrayDigest, positions); WetMeshBenchmark.floats(arrayDigest, varying);
        if (!diagnostic) { WetMeshBenchmark.floats(nativeArrayDigest,positions); WetMeshBenchmark.floats(nativeArrayDigest,varying); }
        for (MeshPreparation.Mode mode : List.of(MeshPreparation.Mode.SCALAR, MeshPreparation.Mode.VECTOR, MeshPreparation.Mode.WORKERS)) {
            float[] p = sentinel(positions.length), v = sentinel(varying.length);
            direct(mode, mesh, transform, color, stamp, p, v, changed);
            candidateEqual(positions,p,mode,"positions",diagnostic); candidateEqual(varying,v,mode,"varyings",diagnostic);
        }
        arrayCases++;
    }
    static void arithmeticControls(StrokeMesh real) {
        float[] special = {0f, -0f, .005f, -.005f, 1f, -1f, 127f, -127f, Float.MIN_VALUE,
            Float.MIN_NORMAL, Float.MAX_VALUE, -Float.MAX_VALUE, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY,
            Float.intBitsToFloat(0x7fc01234), Float.intBitsToFloat(0xffc05678)};
        float[] attributes = new float[65*15];
        for (int i = 0; i < 65; i++) System.arraycopy(real.getVertices(), (i%real.getVertexCount())*15, attributes, i*15, 15);
        boolean[] changed = new boolean[65]; for (int i=0;i<65;i++) changed[i] = i%3 != 0;
        List<MeshLinearTransform> transforms = List.of(TRANSFORM, new MeshLinearTransform(-1, .3f, -.2f, 2),
            new MeshLinearTransform(0, -0f, 0, 0), new MeshLinearTransform(Float.MAX_VALUE, 1, -1, Float.MIN_VALUE));
        for (int attribute=0; attribute<15; attribute++) for (int value=0; value<special.length; value++) {
            float[] data = attributes.clone();
            for (int i=0;i<65;i++) data[i*15+attribute] = special[value];
            StrokeMesh mesh = new StrokeMesh(data, new int[0], real.getAttributeMask() & ~(1<<2));
            verifyArrays(mesh, transforms.get(value%transforms.size()), COLOR, null, value%2==0 ? null : changed,true);
        }
        Random random = new Random(0x42595445494e4bL);
        for (int iteration=0; iteration<64; iteration++) {
            float[] data = attributes.clone();
            for (int i=0;i<data.length;i++) data[i] += (random.nextFloat()-.5f)*.3f;
            // Include HSL, transparent/black colors, atlas progress, and changed-mask holes.
            StrokeMesh mesh = new StrokeMesh(data, new int[0], real.getAttributeMask() | (1<<2));
            MeshColor color = iteration%3==0 ? new MeshColor(0,0,0,-0f) : COLOR;
            verifyArrays(mesh, transforms.get(iteration%transforms.size()), color,
                iteration%2==0 ? null : new StampAnimation(-.25f, 8, 2, 4), iteration%3==0 ? null : changed,true);
        }
        float[] maximum = new float[65536*15];
        for (int i=0;i<65536;i++) System.arraycopy(real.getVertices(), (i%real.getVertexCount())*15, maximum, i*15, 15);
        verifyArrays(new StrokeMesh(maximum, new int[]{0,32768,65535}, real.getAttributeMask()), TRANSFORM, COLOR, null, null);
    }
    static void lifecycleControls(StrokeMesh mesh) throws Exception {
        MeshPreparation.Context context = new MeshPreparation.Context(TRANSFORM, COLOR, null, mesh.getAttributeMask());
        MeshPreparation.Key key = new MeshPreparation.Key(42, 9, TRANSFORM, COLOR, null);
        List<MeshPreparation.Key> stale = List.of(
            new MeshPreparation.Key(43,9,TRANSFORM,COLOR,null), new MeshPreparation.Key(42,10,TRANSFORM,COLOR,null),
            new MeshPreparation.Key(42,9,new MeshLinearTransform(1,0,0,1),COLOR,null),
            new MeshPreparation.Key(42,9,TRANSFORM,new MeshColor(0,0,0,0),null),
            new MeshPreparation.Key(42,9,TRANSFORM,COLOR,new StampAnimation(0,1,1,1)));
        try (MeshPreparation.WorkerPool pool = new MeshPreparation.WorkerPool()) {
            for (MeshPreparation.Key current : stale) {
                MeshPreparation.Job job = pool.submit(mesh, context, null, key);
                require(pool.submit(mesh, context, null, key) == null, "at most one in-flight snapshot");
                float[] p = sentinel(mesh.getVertexCount()*2), v = sentinel(mesh.getVertexCount()*16);
                require(!pool.apply(job, current, p, v), "reject stale generation/version/transform/color/stamp");
                equal(sentinel(p.length), p, "stale positions untouched"); equal(sentinel(v.length), v, "stale varyings untouched");
            }
            MeshPreparation.Job retired = pool.submit(mesh, context, null, key); pool.retire();
            require(!pool.apply(retired, key, new float[0], new float[0]), "retirement rejects derived work");
            StrokeMesh tooLarge = new StrokeMesh(new float[130000*15], new int[0], 0);
            require(pool.submit(tooLarge, context, null, key) == null && pool.pending == null, "16 MiB cap rejects before snapshot copy");
            CountDownLatch entered = new CountDownLatch(2), release = new CountDownLatch(1);
            Runnable blocked = () -> { entered.countDown(); try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } };
            pool.executor.execute(blocked); pool.executor.execute(blocked); require(entered.await(5,TimeUnit.SECONDS), "workers blocked deterministically");
            try {
                float[] input = mesh.getVertices().clone(); boolean[] changed = new boolean[mesh.getVertexCount()]; Arrays.fill(changed,true);
                StrokeMesh owned = new StrokeMesh(input,new int[0],mesh.getAttributeMask());
                MeshPreparation.Job immutable = pool.submit(owned,context,changed,key);
                Arrays.fill(input, 10000f); Arrays.fill(changed,false); release.countDown();
                float[] p = new float[mesh.getVertexCount()*2], v = new float[mesh.getVertexCount()*16];
                require(pool.apply(immutable,key,p,v), "apply copied snapshot");
                float[] ep = new float[p.length], ev = new float[v.length]; direct(MeshPreparation.Mode.ORIGINAL,mesh,TRANSFORM,COLOR,null,ep,ev,null);
                equal(ep,p,"copied input positions"); equal(ev,v,"copied input varyings");
            } finally { release.countDown(); }
            AtomicReference<Throwable> wrongOwner = new AtomicReference<>();
            Thread thread = new Thread(() -> { try { pool.submit(mesh,context,null,key); } catch (Throwable e) { wrongOwner.set(e); } });
            thread.start(); thread.join(5000); require(wrongOwner.get() instanceof IllegalStateException, "worker cannot mutate owner pool");
            require(pool.pending == null && pool.peakBytes <= MeshPreparation.BYTE_LIMIT, "all job ownership and budget released");
        }
        MeshPreparation.WorkerPool closing = new MeshPreparation.WorkerPool();
        require(closing.submit(mesh,context,null,key)!=null,"pending job before close"); closing.close();
        require(closing.pending==null && closing.executor.isTerminated() && closing.submit(mesh,context,null,key)==null,
            "close drains/cancels pending ownership, terminates workers, and prevents resubmission");
        controls.put("workers", WetMeshBenchmark.map("stale_keys_rejected",5,"retirement_rejected",true,"snapshot_immutable",true,
            "owner_enforced",true,"close_with_pending_job",true,"in_flight_job_limit",1,"workers",2,"queue_slots",2,"array_byte_limit",MeshPreparation.BYTE_LIMIT,
            "memory_scope","Input/output/mask array payloads only; excludes JVM headers, executor and stacks"));
    }
    record Probe(long wall, long ownerCpu, long workerCpu, long ownerAllocation, long workerAllocation) {
        static Probe start() {
            long workerCpu=MeshPreparation.pool.cpu(), workerAllocation=MeshPreparation.pool.allocation();
            long cpu=WetMeshBenchmark.cpu(), allocation=WetMeshBenchmark.allocated();
            return new Probe(System.nanoTime(),cpu,workerCpu,allocation,workerAllocation);
        }
        Probe end() { return new Probe(System.nanoTime()-wall,WetMeshBenchmark.cpu()-ownerCpu,MeshPreparation.pool.cpu()-workerCpu,
            WetMeshBenchmark.allocated()-ownerAllocation,MeshPreparation.pool.allocation()-workerAllocation); }
    }
    static final class Samples {
        final Map<String,List<Long>> data = new LinkedHashMap<>();
        Samples() { for (String name : List.of("wall_ns","owner_cpu_ns","worker_cpu_ns","total_cpu_ns","owner_jvm_bytes","worker_jvm_bytes","total_jvm_bytes")) data.put(name,new ArrayList<>()); }
        void add(Probe p, boolean measured) {
            if (!measured) return;
            long[] values={p.wall,p.ownerCpu,p.workerCpu,p.ownerCpu+p.workerCpu,p.ownerAllocation,p.workerAllocation,p.ownerAllocation+p.workerAllocation};
            int i=0; for (List<Long> samples : data.values()) samples.add(values[i++]);
        }
    }
    static void loop(String name, StrokeMesh mesh, boolean[] changed) throws Exception {
        verifyArrays(mesh,TRANSFORM,COLOR,null,changed);
        Map<String,Object> variants=new LinkedHashMap<>();
        int active=changed==null ? mesh.getVertexCount() : 0;
        if (changed!=null) for (boolean b : changed) if (b) active++;
        for (MeshPreparation.Mode mode : order()) {
            float[] p=new float[mesh.getVertexCount()*2], v=new float[mesh.getVertexCount()*16]; Samples samples=new Samples();
            MeshPreparation.pool.peakBytes=0;
            long blocksBefore=VectorPreparation.vectorBlocks,scalarFallbackBefore=VectorPreparation.scalarFallbackVertices;
            for (int i=0;i<WARMUP+MEASURED;i++) {
                Probe probe=Probe.start(); direct(mode,mesh,TRANSFORM,COLOR,null,p,v,changed); Probe elapsed=probe.end();
                sink=v.length==0 ? 0 : v[v.length-1]; samples.add(elapsed,i>=WARMUP);
            }
            variants.put(mode.name().toLowerCase(Locale.ROOT),WetMeshBenchmark.map("samples",samples.data,"peak_in_flight_array_bytes",MeshPreparation.pool.peakBytes,
                "vector_blocks",VectorPreparation.vectorBlocks-blocksBefore,"scalar_fallback_vertices",VectorPreparation.scalarFallbackVertices-scalarFallbackBefore));
        }
        cases.put(name,WetMeshBenchmark.map("kind","pure_loop","vertices",mesh.getVertexCount(),"active_vertices",active,
            "triangles",mesh.getTriangleCount(),"attribute_mask",mesh.getAttributeMask(),"mesh_sha256",WetMeshBenchmark.meshHash(List.of(mesh)),
            "warmup",WARMUP,"measured",MEASURED,"adaptive_fallback",false,"variants",variants));
        System.out.println(name+": "+mesh.getVertexCount()+" vertices, "+active+" active");
    }
    static List<InkLiveStroke> live(List<InProgressStroke> strokes, boolean affine, int step) {
        List<InkLiveStroke> result=new ArrayList<>();
        for (int i=0;i<strokes.size();i++) {
            AffineTransform transform=affine ? new ImmutableAffineTransform(1f+step*.001f,.02f,0,-.01f,1f,i*3f)
                : new ImmutableAffineTransform(1,0,0,0,1,i*3f);
            result.add(new InkLiveStroke(i,strokes.get(i),transform));
        }
        return result;
    }
    static List<InProgressStroke> begin(int start,int pointers) {
        List<InProgressStroke> result=new ArrayList<>(); for (int i=0;i<pointers;i++) result.add(WetMeshBenchmark.begin(start)); return result;
    }
    static void advance(List<InProgressStroke> strokes,int start,int append,int step) {
        for (InProgressStroke stroke : strokes) WetMeshBenchmark.update(stroke,start+append*step,append,step);
        MeshPreparation.version++;
    }
    static void retire(List<InProgressStroke> strokes,InkMeshRenderer renderer,WetMeshBenchmark.RetainedPainter painter) throws Exception {
        for (InProgressStroke stroke : strokes) { painter.retire(stroke); renderer.releaseLiveStroke(stroke); stroke.clear(); }
        painter.live=List.of(); MeshPreparation.generation++; MeshPreparation.pool.retire();
        require(renderer.getCachedLiveGeometryBytes()==0 && MeshPreparation.pool.pending==null,"retire all live resources");
    }
    static List<Object> pixelControl(int start,int pointers,boolean affine,MeshPreparation.Mode mode) throws Exception {
        List<InProgressStroke> strokes=begin(start,pointers); List<Object> frames=new ArrayList<>();
        try (InkMeshRenderer renderer=new InkMeshRenderer(); WetMeshBenchmark.RetainedPainter painter=new WetMeshBenchmark.RetainedPainter(renderer,0xffffffff);
                WetMeshBenchmark.SwingOutput output=new WetMeshBenchmark.SwingOutput(canvas -> WetMeshBenchmark.paintUnchecked(painter,canvas))) {
            for (int step=0;step<=8;step++) {
                if (step>0) advance(strokes,start,16,step-1);
                painter.live=live(strokes,affine,step); MeshPreparation.mode=mode; output.paint();
                List<StrokeMesh> meshes=new ArrayList<>(); for (InProgressStroke stroke : strokes) meshes.addAll(WetMeshBenchmark.export(stroke));
                frames.add(WetMeshBenchmark.map("step",step,"mesh_sha256",WetMeshBenchmark.meshHash(meshes),
                    "vertices",WetMeshBenchmark.vertexCount(meshes),"pixel_sha256",output.pixels()));
            }
            retire(strokes,renderer,painter); output.paint();
            byte[] white=new byte[WetMeshBenchmark.WIDTH*WetMeshBenchmark.HEIGHT*4]; Arrays.fill(white,(byte)0xff);
            require(output.pixels().equals(HexFormat.of().formatHex(WetMeshBenchmark.sha().digest(white))),"exact cancel pixels");
            frames.add(WetMeshBenchmark.map("cancel_sha256",output.pixels(),"retired_bytes",renderer.getCachedLiveGeometryBytes()));
        } finally { for (InProgressStroke stroke : strokes) stroke.clear(); MeshPreparation.mode=MeshPreparation.Mode.ORIGINAL; }
        return frames;
    }
    static void paints(String name,int start,int pointers,boolean affine) throws Exception {
        List<Object> expected=pixelControl(start,pointers,affine,MeshPreparation.Mode.ORIGINAL);
        for (MeshPreparation.Mode mode : List.of(MeshPreparation.Mode.SCALAR,MeshPreparation.Mode.VECTOR,MeshPreparation.Mode.WORKERS))
            require(expected.equals(pixelControl(start,pointers,affine,mode)),"exact complete paint pixels: "+name+" / "+mode);
        controls.put(name,expected);
        Map<String,Object> variants=new LinkedHashMap<>();
        for (MeshPreparation.Mode mode : order()) {
            Samples first=new Samples(), changed=new Samples(), cancellation=new Samples();
            long vectorBefore=MeshPreparation.vectorCalls,workersBefore=MeshPreparation.workerCalls,fallbackBefore=MeshPreparation.fallbackCalls;
            long blocksBefore=VectorPreparation.vectorBlocks,scalarFallbackBefore=VectorPreparation.scalarFallbackVertices;
            MeshPreparation.pool.peakBytes=0; long maxLiveBytes=0;
            try (InkMeshRenderer renderer=new InkMeshRenderer(); WetMeshBenchmark.RetainedPainter painter=new WetMeshBenchmark.RetainedPainter(renderer,0xffffffff);
                    WetMeshBenchmark.SwingOutput output=new WetMeshBenchmark.SwingOutput(canvas -> WetMeshBenchmark.paintUnchecked(painter,canvas))) {
                for (int cycle=0;cycle<WARMUP_CYCLES+MEASURED_CYCLES;cycle++) {
                    boolean measured=cycle>=WARMUP_CYCLES; List<InProgressStroke> strokes=begin(start,pointers);
                    try {
                        painter.live=live(strokes,affine,0); MeshPreparation.mode=mode;
                        Probe probe=Probe.start(); output.paint(); first.add(probe.end(),measured);
                        for (int step=0;step<8;step++) {
                            advance(strokes,start,16,step); painter.live=live(strokes,affine,step+1);
                            probe=Probe.start(); output.paint(); changed.add(probe.end(),measured);
                            maxLiveBytes=Math.max(maxLiveBytes,renderer.getCachedLiveGeometryBytes());
                        }
                        retire(strokes,renderer,painter); probe=Probe.start(); output.paint(); cancellation.add(probe.end(),measured);
                    } finally { for (InProgressStroke stroke : strokes) stroke.clear(); }
                }
                variants.put(mode.name().toLowerCase(Locale.ROOT),WetMeshBenchmark.map("phases",WetMeshBenchmark.map("first_paint",first.data,
                    "changed_paint",changed.data,"cancel_paint",cancellation.data),"peak_in_flight_array_bytes",MeshPreparation.pool.peakBytes,
                    "peak_estimated_live_geometry_bytes",maxLiveBytes,"retained_pixel_bytes",painter.bytes(),
                    "vector_calls",MeshPreparation.vectorCalls-vectorBefore,"worker_calls",MeshPreparation.workerCalls-workersBefore,
                    "vector_blocks",VectorPreparation.vectorBlocks-blocksBefore,"scalar_fallback_vertices",VectorPreparation.scalarFallbackVertices-scalarFallbackBefore,
                    "synchronous_fallback_calls",MeshPreparation.fallbackCalls-fallbackBefore));
            }
        }
        cases.put(name,WetMeshBenchmark.map("kind","complete_retained_swing","initial_inputs_per_pointer",start,"pointers",pointers,
            "affine_changes",affine,"width",WetMeshBenchmark.WIDTH,"height",WetMeshBenchmark.HEIGHT,"scale",1,
            "warmup_cycles",WARMUP_CYCLES,"measured_cycles",MEASURED_CYCLES,"updates_per_cycle",8,"adaptive_fallback",true,"variants",variants));
        System.out.println(name+": complete software Swing paints including copies and worker waits");
    }
    static void run(Path target) throws Exception {
        require(WARMUP>=0 && MEASURED>0 && WARMUP_CYCLES>=0 && MEASURED_CYCLES>0,"valid iteration counts");
        WetMeshBenchmark.BEAN.setThreadCpuTimeEnabled(true); WetMeshBenchmark.BEAN.setThreadAllocatedMemoryEnabled(true);
        require(PreparationAgent.transformed==1,"agent replaced exactly the pure-loop entry");
        try (MeshPreparation.WorkerPool pool=new MeshPreparation.WorkerPool()) {
            MeshPreparation.pool=pool;
            InProgressStroke longStroke=WetMeshBenchmark.begin(6144);
            try {
                List<StrokeMesh> large=WetMeshBenchmark.export(longStroke); require(large.size()==1,"single native partition for crossover controls");
                StrokeMesh mesh=large.getFirst(); require(mesh.getVertexCount()>=8192,"actual large native vertices");
                arithmeticControls(mesh); lifecycleControls(mesh);
                for (int count : new int[]{16,128,512,1024,2048,6144}) {
                    InProgressStroke stroke=WetMeshBenchmark.begin(count);
                    try { loop("full_"+count,WetMeshBenchmark.export(stroke).getFirst(),null); } finally { stroke.clear(); }
                }
                boolean[] tail=new boolean[mesh.getVertexCount()], alternating=new boolean[mesh.getVertexCount()];
                Arrays.fill(tail,Math.max(0,tail.length-128),tail.length,true);
                for (int i=0;i<alternating.length;i++) alternating[i]=i%2==0;
                loop("long_tail_128",mesh,tail); loop("long_half_changed",mesh,alternating);
                // Recheck after JIT compilation: raw float expectations are not weakened.
                arithmeticControls(mesh);
                require(VectorPreparation.vectorBlocks>0,"actual SIMD blocks executed");
            } finally { longStroke.clear(); }
            paints("small_prediction",128,1,false); paints("long_prediction",5120,1,false);
            paints("two_long_pointers",5120,2,false); paints("long_affine_changes",5120,1,true);
            Map<String,Object> numeric=new LinkedHashMap<>();
            for (String mode : numericMismatches.keySet()) numeric.put(mode,WetMeshBenchmark.map("exact",numericMismatches.get(mode)==0,
                "raw_float_mismatches",numericMismatches.get(mode),"first_failures",numericFailures.get(mode)));
            controls.put("arrays",WetMeshBenchmark.map("cases",arrayCases,"raw_float_values_compared",comparedFloats,
                "expected_output_sha256",HexFormat.of().formatHex(arrayDigest.digest()),
                "native_expected_output_sha256",HexFormat.of().formatHex(nativeArrayDigest.digest()),
                "pre_and_post_jit",true,"candidate_fidelity",numeric));
        }
        MeshPreparation.pool=null;
        require(Thread.getAllStackTraces().keySet().stream().noneMatch(t -> t.isAlive() && t.getName().startsWith("byteink-preparation-experiment-")),"all workers terminated");
        Map<String,Object> report=WetMeshBenchmark.map("schema",1,"status","completed","runtime",WetMeshBenchmark.map(
            "java",System.getProperty("java.runtime.version"),"vendor",System.getProperty("java.vendor"),
            "vector_bits",VectorPreparation.SPECIES.vectorBitSize(),"candidate_order",order().stream().map(Enum::name).toList(),
            "timing_scope","Pure loop includes context, queue, snapshot copy, wait and apply. Complete paints use shipped retained renderer and SoftwareSwingRedrawer into 1024x512 BufferedImage, including Skia raster and pixel copies; exclude native input modeling, native window/compositor/scanout and cold JVM startup.",
            "cpu_scope","Owner plus both preparation threads including dispatch/queue costs; excludes JIT/GC/other JVM threads and native threads.",
            "allocation_scope","Owner plus both preparation threads including executor dispatch; excludes native allocations and JVM headers in the in-flight payload estimate.",
            "small_path_active_vertex_threshold",MeshPreparation.THRESHOLD,"worker_apply","Synchronous join at pure-loop substitution, with independent async stale-result controls; no production scheduler integration"),
            "controls",controls,"cases",cases,"all_workers_terminated",true);
        Files.writeString(target,WetMeshBenchmark.json(report)+"\n"); System.out.println("Saved "+target);
    }
    public static void main(String[] args) throws Exception {
        FutureTask<Void> task=new FutureTask<>(() -> { run(Path.of(args[0])); return null; });
        EventQueue.invokeAndWait(task); task.get();
    }
}
