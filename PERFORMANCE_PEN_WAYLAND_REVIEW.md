# Pen and Wayland performance review and implementation plan

Review started 2026-10-06 against clean HEAD
`48628e53014cba923764ba4c1aa4a3c7a5ddc175`. The user requested an in-depth review of
the last three commits, consideration of io_uring/SIMD, a written plan, and implementation.
Three parallel reviewers examined native input, authoring/rendering, and measurements.
Read AGENTS.md, initial, pen-authoring, wayland-pen, mesh-rendering and performance memory.
README.md and docs/ are protected. Earlier benchmark results must remain intact.

The initial implementation addresses redundant painting, unused mesh export, prediction overhead,
wet-cache lifetime, reader wakeups and EDT backlog. The largest remaining measured cost is software
mesh rasterization/full-window presentation. The follow-up plan therefore prioritizes retained
rendering and a supported Wayland GPU backend; io_uring and extra CPU workers are lower-value
fits for the current bottleneck. "midm" is interpreted as MIMD below.

## Scope and existing guarantees

| Commit | Change | Performance relevance |
| --- | --- | --- |
| 48628e5 | Native Wayland tablet-v2/touch/AWT mouse, borrowed JBR connection, immediate Swing/Skia painting | Reader wakeups, FFM, coordinate conversion, EDT backlog, full software presentation |
| a131e4c | Windows/XInput2 input, prediction, simultaneous sessions, direct Skia surface | Prediction replacement, native history batching, render scheduling, wet geometry lifetime |
| 23e9ae0 | Documentation, API generation and example updates | No published library runtime change; examples introduce mesh rendering usage |

The review includes code called by these commits, particularly the mesh renderer now used by
default in the direct authoring panel. Earlier phases 1–6 already optimized codec/replay,
path construction, spatial queries and finished-scene caching; those fixes are verified in code
and are not reported as new defects. Keep the real Ink engine, math/codec/native pins, all
accepted real observations, pointer order, frozen settings, predictions excluded from storage,
canonical finish, exact clipping/alpha/color behavior, and borrowed Wayland ownership.

## Findings ordered by practical impact

1. **Unchanged frames trigger full software paints.** `InkLowLatencyPanel`'s 4 ms timer always
   requests rendering while `InkAuthoringSession.needsAnimationTick()` is true. A pending
   forecast expiry keeps it true even if `advanceNow()` changes no geometry. Every paint clears
   the complete surface, calls the host's finished-scene callback and draws every wet stroke.
   The pinned Skiko 0.150.1 software painter also transfers a full pixel buffer through Skia
   readPixels and a Java BufferedImage before AWT painting. At 3840×2160 each 4-byte buffer is
   33,177,600 bytes. Two such transfers at a theoretical 250 paints/s exceed 16 GB/s, before
   raster work (analytical upper workload, not a measured memory-bandwidth claim).
   Fix scheduling first: advance on timer wakeup, paint only on geometry change, and schedule
   forecast expiry at its deadline when no timed Ink behavior needs intermediate updates.
   External repaint/view changes and accepted input must still render immediately.

2. **DISCARD paints export mesh data they never draw.** `InkMeshRenderer` eagerly calls
   `InkMeshes.rendering` for every coat/version. Its DISCARD branch uses one union/outline path,
   so the 15-float-per-vertex and triangle arrays are unused for ordinary outlined strokes.
   Load rendering snapshots lazily only for ANY/ACCUMULATE, or for the finished no-outline
   split-stroke fallback. Paint fallback/texture availability can change between draws; lazy
   loading must follow the actually selected paint, not freeze the initial selection.

3. **Mesh CPU preparation repeats constant transcendental work.** `prepareMesh` computes brush
   YIQ, atan2 and chroma for every vertex, then computes shifted hue/color even when the format
   has no HSL attribute. It also constructs a four-element FloatArray per vertex for edge flags.
   Hoist identical brush-color expressions, branch once on the HSL attribute, and write edge
   fields directly. Preserve the existing float operation order for actual HSL vertices.
   Chunked drawVertices/RuntimeEffect shader creation still scales with triangle count after
   this fix; do not extrapolate a local loop improvement to total paint time.

4. **Wet renderer geometry can outlive its gesture.** Sessions retain up to 16 reusable native
   engines. The renderer's weak-key live cache therefore cannot collect the last mesh/path of
   those idle engines. Finish/cancel removes them from the session before an empty render can
   retire their cached geometry, and this storage is outside the finished-cache byte budget.
   Add explicit live-stroke retirement from session to panel/renderer on finish/cancel/reuse,
   without clearing the finished scene or a caller-owned shared renderer. Panel-owned default
   mesh renderers must close compiled runtime effects/shaders as well as clear geometry.

5. **Wayland reader polls every 25 ms while idle.** Each subscribed panel owns a reader and
   repeats prepare/flush/poll/cancel/dispatch (up to about 40 timeout wakeups/s/panel). Closing
   on EDT also waits for that timeout. Use a private eventfd to interrupt an indefinite poll,
   preserving the libwayland prepare/read/cancel protocol. Never close/read JBR's borrowed
   socket yourself. Signal through a typed handle independent of any lock held during poll.
   Descriptors and arenas must survive until the reader has stopped; failed display metadata
   retention and child ownership during shutdown remain necessary.

6. **Dynamic FFM and errno access are avoidable hot-boundary costs.** `WaylandWire.call` uses
   synchronized maps, Object varargs and invokeWithArguments for every loop call. Read/poll/
   flush/dispatch deserve fixed descriptors and cached invokeExact handles. `errno()` is fetched
   through a later FFM call after flush/poll: the FFM specification warns that the runtime can
   overwrite native call state. Capture errno with `Linker.Option.captureCallState("errno")`
   into reused thread-confined storage. Blocking or callback-capable calls must not be critical
   downcalls. Keep generic marshalling for cold discovery/control operations.

7. **The EDT drains an unbounded backlog in one invocation.** `NativeInkInputSource` copies the
   entire queue into a list and processes it before returning, coalescing an arbitrarily long
   run of moves into one batch. The render request is queued behind this work. AWT Wayland
   mouse moves are not coalesced and build forecasts per move. Drain bounded sample/packet
   batches and yield to queued rendering/UI work, preserving FIFO, every accepted sample,
   begin/finish/cancel/failure barriers and separate pointer IDs. Coalesce only adjacent moves
   from the same pointer/tool. Bounds are cooperative: one arbitrary host callback or native
   history packet can still take longer than the nominal drain budget.

8. **Smaller repeated work:** Wayland peer scale conversion looks up and opens the same reflected
   method on every packet; cache the accessor, but read the current scale each time. Tablet hover
   frames construct unused Points even without an active contact. Repeated empty prediction
   replacement creates/clears/copies native batches and marks updates unnecessarily; avoid
   redundant empty replacements while still retracting the engine's current speculative tail.

9. **Larger remaining opportunities require a separate renderer design.** Every changed wet
   shape exports and prepares its entire mesh. Each 16-triangle chunk gets a runtime shader and
   draw call; software drawing and full-window transfers remain. An owned retained finished
   raster plus correct dirty-region wet compositing, stable-partition reuse, or a supported
   native Wayland GPU surface could produce much larger gains on long strokes/large windows.
   Simply clipping `paintImmediately` is insufficient: the current Skiko redrawer clears the
   backing surface on every paint. A retained renderer must include old prediction/cancel
   bounds, AA outsets, coat overlap, transforms, texture animation and finished handoff.

10. **Attach stalls and incomplete latency diagnostics.** The bridge performs two blocking
    Wayland roundtrips on the EDT during construction. A compositor delay stalls the UI; a future
    asynchronous initialization protocol should own proxies on its reader and marshal readiness/
    failure/cancellation to EDT. Avoid moving the existing constructor to another thread without
    redesigning shutdown. `lastInputToRenderNanos` starts at listener delivery and ends in the
    Skia delegate; it excludes queue wait, normalization, pixel copies, compositor and scanout.
    Treat it as the documented software recording diagnostic, not pen-to-photon latency.

## io_uring and SIMD decision

**io_uring: do not add it to pen capture.** This path is Unix-socket readiness plus libwayland
protocol dispatch on a connection owned and read by JBR. Bypassing libwayland reads breaks its
multiple-reader/queue bookkeeping; a ring only polling one display FD adds lifecycle/cancellation
complexity and does not reduce geometry, FFM boxing, EDT work, rasterization or pixel copies.
poll + eventfd already provides the needed readiness/shutdown behavior. No cold-disk I/O bottleneck
was measured in these commits. A future notebook I/O experiment needs cold-load CPU/wait/syscall
profiles and a Windows fallback; it belongs below SQLite's VFS or an explicitly owned I/O layer.

**SIMD: candidate for later, after scalar work removal.** Mesh preparation has contiguous float
arrays, bounds/transforms and edge/opacity operations. These are more suitable than event queues,
hash maps, per-sample JNI or trigonometric device normalization. Skia already dispatches CPU
specializations; custom pixel SIMD is not an established opportunity. Java 25's Vector API remains
an incubator module, adding a deployment requirement if made mandatory. First profile the remaining
loop and test scalar vs optional vector code across small/large meshes; require at least 20% gain
in the affected large loop and 5% in its end-to-end paint case outside three-fork spread, with no
small-case or geometry/pixel regression. Preserve float ordering, signed-zero/NaN behavior,
Android fidelity and Windows scalar fallback; no fast-math or global march-native flags.
The initial implementation deliberately uses simpler scalar changes rather than a speculative
vector dependency. SIMD is an evaluated future option, not a promised completed optimization.

**MIMD / multiple CPU workers:** interpreting the request's "midm" as MIMD, the existing native
reader and EDT already run different work concurrently. Extra input workers cannot safely mutate
one gesture or its Skia canvas concurrently, and adjacent observations/predictions are ordered.
Independent per-pointer engines, independent finished-scene tiles and pure preparation of owned
mesh arrays are plausible work units. The first worker experiment should prepare immutable
snapshots on a bounded executor, capture transform/color/animation keys on EDT, and apply results
only when gesture generation and shape version still match. Keep native engine mutation, renderer
cache/resource changes and presentation on their owning thread; reject stale derived results
without discarding real observations. Limit in-flight snapshots/bytes, cancel work on retirement,
and retain a synchronous small-mesh path. The measured representative preparation (~0.28 ms before
changes) is much smaller than software mesh rasterization (~18 ms), so worker scheduling/transfer
may outweigh its gain. Parallel CPU preparation cannot eliminate the software painter's full-frame
copies. Benchmark actual long/multiple-pointer cases and require an end-to-end gain before shipping
this architecture; already bounded codec worker parallelism from earlier phases is retained.

## Implementation sequence and acceptance gates

| Order | Work to implement now | Validation |
| --- | --- | --- |
| 0 | Freeze independent session, mesh and FFM baselines on committed classes before mutations; keep identical harness for after | Three serial fresh JBR JVM forks; CPU/wall/allocation reported separately; synthetic counts and input/pixel hashes |
| 1 | Change-aware/deadline-aware authoring timer; avoid redundant empty prediction work | Stopped forecast retracts once; timed brushes still advance; ordinary input/external repaint/handoff unchanged |
| 2 | Lazy coat snapshots; constant HSL work hoist and edge-array removal | Existing mesh texture/color/alpha/overlap/prediction/partial-erase tests, Android matrix and GPU regression; exact prepared data controls |
| 3 | Explicit wet-cache retirement; close owned panel renderer | Finish/cancel/reuse/multi-pointer lifetime regression without clearing other/finished geometry |
| 4 | Typed reader calls, captured errno, eventfd-driven shutdown | Actual Weston native suite at 1x/2x, repeated hotplug/detach; no idle timeout wakeups; bounded shutdown; borrowed-display ownership intact |
| 5 | Bounded FIFO EDT drain, adjacent native/mouse coalescing, cached peer scale accessor | Long burst retains every sample; renders/UI sentinel interleave; cancel/failure/multiple pointers stay ordered; native X11 and Wayland regressions |
| 6 | Repeat identical serial benchmark forks and summarize limits; update memory | Separate local loop, end-to-end draw, allocation and structural results; retain raw failures/variance; full relevant Linux build |

Follow-up plans (not part of the initial low-risk implementation): retained dirty-region renderer/
stable wet mesh partitions, Wayland GPU integration, asynchronous attach, queue-arrival/full-paint
diagnostics, bounded MIMD mesh preparation and an optional measured SIMD prototype. Each needs its own written design and fidelity
gates. Physical tablet-to-display latency requires a physical instrumented setup and is unmeasured.
Windows adapter code is outside the Linux reader change; shared Kotlin/rendering fixes must remain
portable and pass existing headless/native contracts. Actual Windows runtime evidence must be
reported separately if not rerun for this implementation.

### Follow-up implementation plan for larger remaining gains

1. **Retain finished content and repaint changed wet regions.** First measure host `drawContent`
   separately and use the existing physical-viewport `InkSceneRasterCache` for unchanged finished
   scenes. For a retained authoring backend, own a persistent physical raster rather than rely on
   Skiko's cleared surface. The engine already exposes `populateUpdatedRegion/resetUpdatedRegion`;
   accumulate damage until a successful paint, transform it conservatively to device pixels and
   include old prediction/cancel/finished-handoff bounds plus derivative-AA outsets. Restore the
   affected background and redraw every overlapping wet coat/pointer in draw order, then composite
   retained content for Swing. A dirty region cannot simply draw onto previous translucent ink.
   Only the owner of all consumers may reset the engine's shared damage accumulator. View/density/
   scene/exclusion/texture changes require appropriate full invalidation; buffers need explicit
   byte budgets and deterministic close. Validate exact pixels against full redraw across input,
   prediction reversal/expiry, partial erase, alpha, overlapping pointers and 1x/2x transitions.
   Benchmark actual large viewports/long strokes; target reduced raster work and full-paint P95,
   while accounting for the remaining unavoidable full Swing copy.

2. **Incremental wet mesh export/preparation.** Profile real 8k+ vertex traces, not just 8k input
   observations (Ink can greatly simplify dense input). Determine the native engine's true stable
   vertex/index boundary; vertex count growth alone does not prove preceding vertices are stable.
   Add a narrow checked owned-range snapshot only if a measured whole-export/preparation share
   justifies a new native bridge. Reuse unaffected 16-triangle chunks and invalidate chunks whose
   indices reference any mutated vertex, including prediction shrink and partition changes.
   Cache keys still include canvas linear transform, paint/color and atlas animation. Hold exact
   Android geometry, color/coverage, unsigned indices and >65k-partition tests authoritative.
   Separately profile per-chunk `RuntimeShaderBuilder`/shader creation, boxed intermediate lists
   and the 16-triangle draw granularity. Compare reusable primitive scratch and larger shader
   batches only within actual SkSL/backend uniform limits; test edge ownership, overlap and AA
   against exact full-render references. These preparation/allocation changes need a complete
   draw-time benefit before claiming a serious gain over the measured software raster bottleneck.

3. **Investigate a supported Wayland GPU presentation path.** The shipped Skiko Linux `SkiaLayer`
   path assumes X11. Compare a pinned supported native Wayland/EGL path or an isolated upstream
   change against the current Swing software painter, using JBR's actual surface ownership.
   Measure complete render/present wall time and CPU bandwidth at 1080p/4K/2x. Handle surface
   hide/reconfiguration/device failure, alpha and scaling, and retain software fallback. Merely
   disabling vsync or requesting more buffers does not establish physical latency reduction.

4. **Move attach roundtrips off EDT with explicit readiness.** Construct/dispatch owned proxies
   on the reader thread, post readiness/failure to EDT and gate accepted contact begins until
   initialized. Use a generation token for detach/hide/show; stop+wake must work during discovery,
   not only after it. Test compositor delay/disconnect while opening, rapid reattach and hotplug.
   A shared display dispatcher across panels can follow if multi-panel duplication is measured.

5. **Conditional CPU parallel/vector experiments.** After the renderer changes above, measure
   pure mesh preparation with scalar, optional SIMD and bounded MIMD workers. Compare crossover
   sizes, CPU time, wall time, P95, in-flight memory and end-to-end paint. Keep small meshes
   synchronous; reject stale generations; retain portable scalar math and Windows coverage.
   Ship only an experiment meeting the earlier quantified loop/end-to-end gain and fidelity gates.

No follow-up above is represented as implemented by the initial changes.

Follow-up status (2026-10-07): the user requested item 1 above. Native Wayland now retains the
finished background and composed frame, restores conservative wet damage, preserves overlap and
prediction retraction, and exposes a combined 64 MiB pixel budget with full-redraw fallback.
Fifteen exact pixel cases, ten actual Wayland cases at both 1x/2x, the fresh complete Linux/JBR25 build
and existing X11/OpenGL checks pass. Implementation, matched measurements and remaining limits
are recorded in [PERFORMANCE_RETAINED_AUTHORING.md](PERFORMANCE_RETAINED_AUTHORING.md).
Items 2–5 and complete input-arrival/presentation diagnostics remain separate follow-ups.

## Primary sources checked

- [Wayland client API: event queues, polling, prepare/read/cancel, flush backpressure and roundtrips](https://wayland.freedesktop.org/docs/html/apb.html).
- [JDK 25 FFM call-state capture](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/foreign/Linker.Option.html#captureCallState(java.lang.String...)).
- [JDK 25 typed MethodHandle invocation](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/invoke/MethodHandle.html).
- [AWT EventQueue ordering and sequential dispatch](https://docs.oracle.com/en/java/javase/25/docs/api/java.desktop/java/awt/EventQueue.html).
- [liburing io_uring manual](https://github.com/axboe/liburing/blob/master/man/io_uring.7).
- [Java 25 release notes: Vector API tenth incubator](https://www.oracle.com/java/technologies/javase/25-relnote-issues.html).
- [Skia CPU dispatch](https://skia.googlesource.com/skia/+/refs/heads/main/src/core/SkOpts.h).
- Local pinned Skiko 0.150.1 source: SoftwareSwingRedrawer and SoftwareSwingPainter.

## Implementation and measurements

The plan above was written before production implementation. The initial implementation is in
the working tree; no native engine, pin, codec or storage implementation is changed.

### Implemented changes

| Area | Implementation | Practical effect and limits |
| --- | --- | --- |
| Authoring timer | One-shot timer; advances first, requests painting only on changed geometry, and schedules static prediction expiry at its deadline | Removes redundant full software paints during unchanged forecasts; timed brush behavior still gets the 4 ms cadence |
| Prediction replacement | Empty replacements avoid native work; real batches clear speculation once; accepted forecasts retain the existing reusable scratch/destination transfer | Preserves forecast removal and canonical real-only finish; per-Move benchmarks remain a separate acceptance check |
| DISCARD rendering | Mesh snapshots load only when the chosen coat actually needs them; finished no-outline fallback is retained | Ordinary outlined highlighters avoid unused mesh export; changing texture/fallback selection can still lazily select a mesh |
| Scalar shader preparation | Hoists brush color constants, skips absent HSL work, writes four edge fields without a temporary per-vertex array | Prepared varying/vertex/index/color fingerprints remain exact; chunk construction and software rasterization remain |
| Wet geometry lifetime | Session retires renderer entries before finish/cancel/engine reuse; default renderer closes with the panel | Reusable native engines no longer hold their last wet mesh indefinitely; other pointers and finished-scene caches remain intact |
| Wayland reader | Cached typed FFM calls, captured errno, private nonblocking CLOEXEC eventfd, indefinite readiness poll | Removes periodic idle timeout polling and wakes shutdown; retains libwayland prepare/read/cancel and borrowed-display ownership |
| EDT delivery | Lossless FIFO queue, 512-sample / 256-delivered-packet / 2 ms cooperative drain, adjacent same-pointer/tool Move coalescing | Gives queued paints/UI work a turn without dropping observations; a single large history or host callback can exceed a budget |
| Coordinate/event overhead | Caches reflected scale accessor while reading current scale; avoids inactive hover/late-frame allocations | Preserves 1x/2x mapping and contact routing |

The `InkRenderer.releaseLiveStroke` hook has a JVM default implementation; `javap` confirms an
actual default method, preserving existing custom renderer implementations. Lazy cache growth
recalculates retained bytes and evicts over-budget finished geometry. Tests cover no-outline
fallback, late texture availability, byte-budget growth, strong engine retention, simultaneous
pointers, forecast expiry/timed brushes, exception cleanup, FIFO barriers and rendering between
long input bursts. Wayland tests cover explicit wakeup, captured errno, descriptor lifetime,
idle polling and another roundtrip after cancelling a prepared read.

The native test runner accepts a separate evidence directory and uses a short temporary runtime
socket path, so longer output directory names do not exceed Unix socket address limits.
All temporary private Weston/Xvfb instances are stopped by their harnesses.

### Baseline measurements and attribution

The baseline is frozen from the original compiled classes at the reviewed HEAD, not from a
subsequently rebuilt checkout. Both sides use the same harness and JetBrains JBR
`25.0.4.1+1-b610.67`, G1, `-Xms512m -Xmx2g`, three fresh serial JVM forks and identical synthetic
workloads. Reported timing is the median of each fork's measured medians, with raw fork ranges
retained. Per-thread CPU and JVM allocation are recorded separately; allocation excludes native
Ink/Skia storage. Separate JFR forks are excluded from the timing aggregates. Hash guards reject
a run if production/harness source or runtime binaries change during measurement.
Initial session runs use 10 warmup/9 measured gestures per case. Matched session controls below
use 50 warmup/15 measured gestures on both frozen original and current binaries; only matching
counts are compared. Renderer controls use their original identical warmup/measurement counts.

| Baseline control | Measured wall time | Meaning |
| --- | --- | --- |
| 8,192 stylus observations, individual Moves with prediction | 1.616 ms handling, 1,310,600 JVM B | Synthetic session processing; excludes queue wait and presentation |
| Same observations as one Batch with prediction | 0.696 ms handling, 131,280 JVM B | Explanatory batching control: ~57% less handling time/~90% fewer bytes, not a before/after claim |
| Dense 8,192-observation engine advance / canonical finish | About 10 ms each | Native engine work remains substantial and is unchanged by this implementation |
| Cached marker software raster at 512×512 | 17.770 ms per draw | The same cached null-canvas control is 0.046 ms; actual software raster dominates |
| Representative no-HSL mesh preparation | 0.278 ms, 429,072 JVM B | Only 747 vertices / 615 triangles after Ink simplifies the dense input; not an 8k-vertex worst case |
| Pinned software Swing full paint, 512² / 1080p / 4K | 0.332 / 2.628 / 12.337 ms per paint | Offscreen full white CPU paint/readPixels/copy; no window/compositor/scanout, not additive to every other control |

### Final renderer and delivery results

The final renderer run uses the corrected documented runner entry point and the final production
sources/binaries. Three serial forks pass **all eight exact input/wet/finished pixel fingerprints,
all four prepared-data hashes, runtime/workload counts and source/binary guards**. Final production
source hashes still match the captured inventory after timing; the native library hash is unchanged.

| Rendering control | Original → current wall median | Original → current JVM bytes | Interpretation |
| --- | --- | --- | --- |
| 8k outlined DISCARD cold draw, null canvas | 0.060946 → 0.021731 ms, 64% lower | 63,280 → 7,968, 87% lower | Avoids unused snapshots; null canvas isolates recording/preparation, not display painting |
| Same DISCARD cold draw, 512² raster | 0.248862 → 0.221340 ms, 11% lower | 63,280 → 7,968 | Smaller end-to-end software draw benefit; three-fork ranges do not overlap |
| 512-input mesh prepare, HSL | 0.129576 → 0.115108 ms, 11% lower | 33,576 → 31,720 | Exact prepared output; local CPU stage only |
| 512-input mesh prepare, no HSL | 0.133264 → 0.116781 ms, 12% lower | 33,576 → 31,720 | Exact prepared output; local CPU stage only |
| 8k-input mesh prepare, no HSL | 0.278189 → 0.243633 ms, 12% lower | 429,072 → 405,168 | Exact output; preparation remains far smaller than software mesh rasterization |
| Cached 8k marker software raster | 17.770 → 17.955 ms per draw | 5,724 → 5,748 per draw | Broad overlapping ranges; no frame-rate improvement claimed |
| Software Swing 4K full white paint | 12.337 → 12.679 ms per paint | Unchanged | Unchanged presentation implementation; broad overlapping ranges |

The first five timing controls above have nonoverlapping three-fork ranges. Null DISCARD draw
ranges are 0.059423–0.061657 ms before and 0.019737–0.024857 ms after. Warm rendering adds **24 JVM
bytes per draw** for lazy-loader plumbing; this small tradeoff is retained alongside the much
larger cold snapshot reduction. Some standalone, unchanged native-snapshot controls are 0.6–3.3 µs
slower with nonoverlapping ranges (about 7–9% for selected tiny cases). Their code/allocation is
unchanged, and causal attribution is not established; raw regressions are retained. Results do
not establish a blanket renderer speedup.

The queue control delivers all 8,194 observations (begin + 8,192 moves + finish) in order with
exact canonical input checks. The explanatory old unbounded-drain model handles all 8,194 before
its first queued paint; the new single-point burst yields after 512. In the final queue control,
AWT first-paint readiness is 0.785 ms versus 21.831 ms for the model, and native first-paint readiness
is 0.923 versus 22.995 ms. These include an engine advance and exclude raster/presentation; the
model is not a historical native-window timing run and the queue uses a synthetic zero budget
clock to isolate sample-bounded yielding. A single indivisible 8,192-point history still consumes
8,193 observations before readiness (~12.406 ms). FIFO/sample preservation and UI interleaving
are the accepted outcome, not a claim of those ratios for actual physical pen latency.

### Prediction timing correction and steady-state controls

The initial 10/9 session aggregates showed 8k individual Move+prediction handling slowing from
1.616 to 1.835 ms. Removing a redundant scheduling-state write and reverting buffer swapping
did not eliminate that aggregate. Raw samples reveal the original workload transitioning from
about 1.9–2.1 ms to 1.55–1.62 ms after measured gesture three, while changed code transitions
after gesture five. Counting both phases in a nine-gesture median produces the apparent slowdown.
This is evidence of warmup dependence; it is not erased or claimed as an improvement.

The same updated harness, **50 warmup/15 measured gestures and three fresh forks on both sides**,
gives stable direct handling samples without that measured step transition:

| Session handling control | Original wall median (fork range), ms | Current wall median (fork range), ms | Current vs original JVM bytes |
| --- | --- | --- | --- |
| 8k individual Moves + prediction | 1.971034 (1.956657–1.999238) | 1.910940 (1.874280–1.936929), about 3% lower | 1,310,600 → 1,114,016, about 15% lower |
| 512 individual Moves + prediction | 0.132993 (0.129847–0.134906) | 0.128544 (0.128304–0.129215), about 3% lower | 81,800 → 69,536, about 15% lower |
| 8k Batch + prediction | 0.739635 | 0.683258, about 8% lower | 131,280 → 131,280 |
| 512 Batch + prediction | 0.054994 | 0.050566, about 8% lower | 8,400 → 8,400 |
| Eight pointers, 512-input Batches + prediction | 0.383709 | 0.349995, about 9% lower | No material allocation reduction |

CPU time agrees for the 8k Move control: 1.963624 → 1.903339 ms. The handling controls' three-fork
ranges do not overlap. These are small synthetic handling improvements, not physical latency
results or a blanket throughput claim. Engine advance/canonical finish remain about 10 ms and
their ranges overlap. Some no-op tick/getter phases still show warmup effects at 50/15, so no
speedup is claimed for those phases. Startup/short-gesture effects remain unquantified outside
the preserved initial synthetic runs; a physical interactive trace should include them.

The separate FFM control calls the same zero-timeout libc `poll(NULL, 0, 0)`: production generic
marshalling takes 507.738 ns/call and 296 JVM B; typed calls with errno capture take 316.543 ns
and 0 B (about 38% less boundary time). This isolates call marshalling, not active pen or
end-to-end latency. An actual reader test confirms one indefinite poll throughout a 150 ms idle
interval and successful explicit shutdown wakeup; ordinary socket events still wake it.

JFR's native-boundary samples concentrate in `Canvas._nDrawVertices` (174/255 renderer samples)
and native canonical-stroke construction / shape update (49/94 and 42/94 session samples).
These are JVM sample counts at native boundaries, not measured C++ CPU percentages. Allocation
profiles also contain harness hashing outside the measured phases, so total JFR allocation is
not attributed to the renderer.

### Verification completed

- Fresh complete Linux/JBR build, Linux/Windows native library contracts, Android fidelity matrix
  and arithmetic validation comparison pass: **2,362 test executions in 194 suites, zero
  failures/errors, five expected optional/platform skips**; all 123 tasks execute on this fresh
  run. Tests cover unchanged storage/native contracts as well as the new rendering/input code.
- Android path matrix: **280 cases / 2,240 encoding comparisons**, exact. Full mesh matrix:
  **280 cases**, zero unexplained interior pixels; maximum MAE 0.206793/255, minimum SSIM
  0.9986549434 against the Android references. These are fidelity measurements, not performance.
- The arithmetic `oracleCompare` passes **16,278 comparisons** against the unshipped validation
  baseline; it is not a comparison against a separate shipped production engine. The production
  Linux native library is unchanged: SHA-256
  `b483814bc0af81e27715b06d3bca9b4fba44b882aa3f46d5c111b1b795610012`.
- Following the final prediction-state correction, fresh affected headless suites pass
  **135 Compose cases plus 36 testing cases** (three expected private-notebook skips). Native
  Wayland reruns pass **all nine cases at 1x and all nine at 2x, zero skips**. Private X11 native
  pen and Mesa/OpenGL reruns pass three actual cases; the Windows-only fourth case is skipped.
- Following the decision to retain copy-on-write forecast transfer, the same affected headless
  suites pass again, and all nine native Wayland cases pass again at both 1x/2x. X11/OpenGL
  reruns are verified against this final source as well. Counts are separate reruns, not extra
  unique test cases added to the 2,362-case full-build count.
- Actual Windows/JBR runtime and physical pen-to-photon latency were not rerun/measured for
  these changes. Prior Windows evidence belongs to the previous implementation, not this run.

An earlier incremental full-build attempt encountered a stale testing classpath
(`ClassNotFoundException: InkPointerSample`). The fresh no-build-cache/no-configuration-cache
rerun resolved it without a production workaround. Its failed log and the initial focused-test
failures remain in the evidence. The exploratory after measurement also caught a direct
Move+prediction slowdown; its evidence is preserved separately from subsequent controls.
Removing a redundant scheduling-state write and reverting native scratch/destination swapping
did not remove the short-run aggregate slowdown. Inspection of the raw gesture samples then
showed compilation transitions inside the nine measured gestures, occurring later after the
changes. Matched extended-warmup controls against the frozen original/current binaries are used
to distinguish this from a steady-state regression; the earlier data are not discarded.

The native `StrokeInputBatch::Append` implementation shares copy-on-write data when its
destination is empty, rather than making the presumed deep copy. Swapping two Kotlin references
therefore has a smaller opportunity than initially assumed and is removed from the implementation.
No JVM inlining flags or native floating-point/compiler options are changed.

### Reproducing and inspecting evidence

The reviewable benchmark runner and Java harness are under `benchmarks/run_pen.py` and
`benchmarks/pen/`. The narrow `.gitignore` change exposes these new files while keeping existing
historical benchmark artifacts ignored. This report is also copied to the repository root so
it can be versioned even though persistent `memory/` is ignored.

```sh
python3 benchmarks/run_pen.py \
  --java-home /path/to/pinned-jbr-25 \
  --output build/pen-performance-new-run \
  --suite all --forks 3 --profile \
  --session-warmup 50 --session-measured 15
```

Run builds and benchmark JVMs serially. Supply `--classpath /path/to/runtime-classpath.txt` to
use already compiled outputs; otherwise the runner resolves the test runtime with an isolated
temporary Gradle task. `--compare /path/to/before/summary.json` generates explicit ratios,
structural changes and fork-range overlap. A fresh output directory is required. Optional JFR
profiling is an additional fork and cannot contaminate the timing medians.
Compare runs with identical session warmup/measurement counts. Defaults 10/9 are retained only
to reproduce the initial workload; the longer session settings above address the observed
direct-handling compilation transitions, without guaranteeing stability of every minor phase.
When `--classpath` points to the frozen original snapshot, source hashes describe the working
tree/harness during the run; the snapshot manifest and runtime binary hashes establish original
commit provenance. They must not be presented as hashes of the original production source.

Local evidence:

- `benchmarks/results/2026-10-06-pen-wayland/before/summary.json`: normalized frozen baseline.
- `build/performance-pen-review/baseline-binary/`, `baseline-session/`, `baseline-render/`:
  original binary snapshot and raw forks.
- `build/performance-pen-review/wire-ffm/summary.json`: independent typed/generic boundary control.
- `build/performance-pen-review/after-jbr25/`: preserved exploratory run, fingerprints and JFR.
- `build/performance-pen-review/after-final-jbr25/`: preserved repeat after removing the redundant
  state write; all fingerprints match, but the short-run prediction-Move aggregates remain slower.
- `build/performance-pen-review/prediction-copy-control-jbr25/`: preserved session control after
  reverting the speculative buffer swap.
- `build/performance-pen-review/{before,after}-steady-session-jbr25/`: paired 50/15 session
  controls, exact structural comparisons and guarded binary/source provenance.
- `build/performance-pen-review/linux-fresh-build.log`, `linux-fresh-test-audit.json`,
  `linux-fresh-fidelity-audit.json`: fresh build/test/fidelity provenance.
- `build/performance-pen-review/linux-prediction-final-tests.log`,
  `final-affected-test-counts.json`, `wayland-final-scale{1,2}/`, `x11-gpu-final.log`:
  affected headless and actual native reruns following the final controller correction.
- `build/performance-pen-review/linux-prediction-transfer-tests.log`, `wayland-verified{1,2}/`,
  `x11-gpu-verified.log`: final source verification after retaining copy-on-write transfer.
- `build/performance-pen-review/final-source-verification-audit.json`: exact final affected/native
  XML/log/source hashes and per-suite counts.
- `build/performance-pen-review/after-verified-render-resolved-jbr25/`: final renderer three-fork
  comparison and exact fingerprints, exercising the automatic Gradle classpath entry point.
- `benchmarks/results/2026-10-06-pen-wayland/`: normalized raw/summary/comparison JSON and
  explicit per-run provenance notes; original historical artifacts are retained.

The automatic Gradle resolver is verified: an initial init-script error applying to the included
`build-logic` project was fixed by skipping builds without `:byteink-compose`. The corrected
entry point resolves/compiles the harness and completes all three render forks. Permission-only
Gradle cache lock failure and the resolver failure logs are retained separately. Normal Gradle
cache access is needed in a restricted sandbox. Production code did not require a workaround.

The initial implementation, scoped verification and review are complete. The larger renderer,
GPU, asynchronous-attach and conditional SIMD/MIMD work remains the explicit follow-up plan above.
No commit, push, deployment, README edit or docs/ edit was performed.
