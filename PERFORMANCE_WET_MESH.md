# Incremental wet mesh preparation

This implements item 2 of the remaining pen/Wayland plan in
[PERFORMANCE_PEN_WAYLAND_REVIEW.md](PERFORMANCE_PEN_WAYLAND_REVIEW.md). Item 1's
retained authoring raster remains the presentation path. Implementation,
verification and matched three-fork measurements are complete. The main measured
benefit is lower JVM allocation: long changed retained paints fall from 7.288 MB
to 0.845 MB (88.4% lower), with exact output. Complete retained raster median
improves 8.7%; complete Swing paint timings overlap, and P95 does not improve.
Estimated retained wet geometry grows 85.7%.

The engine pin computes first-mutated vertex/index offsets internally, but the
live stroke API discards those offsets and regenerates its 16-bit partitions.
Partition ranges overlap and can move. Stable input counts or vertex growth do
not establish a stable prefix of rendering attributes, especially for predicted,
timed or self-intersecting strokes. The initial implementation therefore keeps
the checked owned full snapshots and compares actual raw float bits and indices.
A new native range export requires evidence that the remaining export cost
justifies a per-consumer mutation/lifetime protocol; it is a conditional part of
the plan, not a prerequisite for safe preparation reuse.

For each live coat/partition, retained primitive position/varying arrays update
only changed vertices. A 16-triangle chunk survives only when its triangle count,
indices and all referenced canonical vertex attributes match the prior snapshot.
Removed or replaced chunks release their native shaders. Changes to the linear
canvas transform, paint color or stamping animation rebuild preparation. Paths
and raw snapshots remain lazy for DISCARD paints. Complete snapshots also make
skipped updates, prediction rollback, partition movement and engine reuse safe
without consuming the shared native damage accumulator.

The implementation also shares immutable constructor lists for Compose vertex
objects and retains immutable shaders for texture-free chunks. Textured shaders
keep their existing per-draw behavior because texture providers and input-relative
origins can change. Shader uniform bytes and primitive scratch capacity count
toward geometry metrics/budgets; JVM headers and additional native/driver overhead
remain estimates outside these metrics. Retirement, cache eviction, clear and close
release retained shader resources. The shader batch size, vertex math, SkSL,
engine/native artifacts and codecs are unchanged.

Acceptance requires exact full-render and prepared-data controls, timed prefix
mutations, prediction replacement/shrink/removal, skipped draws, key changes,
unsigned indices and large/changed partitions. The existing Android rendering,
real OpenGL, retained-raster, X11 and actual Wayland 1x/2x suites remain gates.
Performance uses frozen original classes and serial fresh JBR 25 forks, at least
8,000 actual mesh vertices, a small control, wall/CPU/allocation attribution and
complete raster paints. Reports will disclose whole-export cost, native resource
costs and any overlapping fork ranges. Physical display latency remains unmeasured.

## Verification

The fresh Linux/JBR 25 full build executes all 118 actionable tasks: 2,394 cases
in the selected task reports, zero failures/errors and five existing optional or
platform skips. This includes the fourteen new incremental renderer controls,
twenty-nine exact full-frame pixel comparisons and three core engine-mutation
controls. Prepared arrays compare raw float bits, including signed zero; tests
cover native partition creation/removal and the maximum unsigned index 65,535.

All ten actual Wayland compositor/window cases pass at each of 1x and 2x, with
zero skips. Actual X11 capture/direct Skia and OpenGL pass three cases; the fourth
Windows-only input case is skipped on Linux. Combined verification is 2,418 test
executions with zero failures/errors and six expected skips. The Android path
matrix passes all 280 cases and 2,240 encoding comparisons; the existing 280-case
mesh hardware-reference test also passes. These are committed Android reference
comparisons, without a new Android device capture. Actual Windows was not rerun.

Standalone wiki examples compile/run (31 executed tasks); generated API freshness
(316 public declarations), strict MkDocs and whitespace checks pass. Independent
`javap` comparison of all 106 original production classes finds no removed class
or member of a public class. Loader/native artifacts and tracked README files
are byte-identical to the frozen original runtime. Additional counter getters
and preparation helpers are Kotlin-internal.

Commands and task-selected XML/hash audit are in
`build/wet-mesh-step2/verification-commands.json` and `verification-audit.json`.
Skipped alternate-runtime tasks' old XML is explicitly excluded, rather than
counted as a fresh run. `final-source-audit.json`/`.md` hold the independent
source, signature, resource-lifetime and native-artifact review. The initial
test-only opt-in and over-simplified long-fixture failures remain in the
targeted-test logs; vertex-count and exactness gates were preserved.

## Measurement method and native bridge decision

Frozen baseline runtime and production source manifests live in
`build/wet-mesh-step2/baseline`. The representative real long trace produces
10,831 vertices / 10,809 triangles / 676 chunks from 6,144 observations. Every
long prediction-transition frame exceeds 8,192 actual vertices. The 256-input
control covers the small case. Final controls use serial fresh JBR 25 JVMs,
EDT ownership, identical inputs, complete raw prepared-data/mesh hashes, exact
direct/retained/Swing pixels through prediction changes and cancellation, and
separate wall, EDT CPU and JVM-allocation measurements.

The preliminary one-fork 10-warmup/9-measured full-raster profile has native
export median 0.411 ms, full preparation 1.215 ms, standalone shader construction
0.535 ms and complete cold software raster 183.940 ms. The actual retained helper
smoke instead has 7.731 ms changed paints: reducing preparation matters after
damage clipping, while retaining full export costs about 0.4 ms. These separate
exploratory phase observations are not summed attribution of the same frame.
They do not justify adding a new native per-consumer mutation/range/lifetime
protocol for this step. Whole owned export remains an explicit measured cost;
the narrowly scoped native bridge is deferred. The shader batch size stays sixteen
within the pinned portable shader contract.


## Final measurements

All six accepted fresh-JVM forks use the same final harness and JBR 25 on the
AWT EDT: 30 warmup / 15 measured independent controls, and six warmup / three
measured eight-update gesture cycles. Each changed-paint case has 24 measured
paints per fork (72 total per version), with prediction lengths alternating
64/4/0/32. The viewport is 1024 x 512 physical pixels at 1x. The native long
trace, partial-damage counts, input/mesh/prepared fingerprints and every output
pixel hash match the original runtime. Final timings below are medians of the
three fork medians; P95 is the median of per-fork P95s. Bytes are current EDT
JVM allocation, excluding native Ink/Skia allocation. MB uses decimal bytes.

| Complete phase or isolated control | Wall median ms, before → after | Wall P95 ms, before → after | EDT CPU median ms, before → after | JVM MB/paint, before → after |
| --- | --- | --- | --- | --- |
| Small full preparation | 0.071 → 0.057 | 0.154 → 0.140 | 0.072 → 0.057 | 0.293 → 0.157 |
| Long full preparation | 1.223 → 1.026 | 3.999 → 1.326 | 1.216 → 1.023 | 6.926 → 3.678 |
| Small changed retained raster | 1.783 → 1.646 | 3.432 → 3.727 | 1.776 → 1.640 | 0.294 → 0.059 |
| Long changed retained raster | 7.812 → 7.132 | 15.201 → 16.599 | 7.776 → 7.106 | 7.288 → 0.845 |
| Small complete retained Swing | 2.207 → 2.350 | 4.005 → 4.386 | 2.196 → 2.341 | 0.296 → 0.060 |
| Long complete retained Swing | 8.433 → 8.354 | 15.402 → 16.834 | 8.326 → 8.321 | 7.289 → 0.846 |
| Small changed full raster | 7.667 → 8.453 | 10.308 → 10.850 | 7.642 → 8.420 | 0.292 → 0.057 |
| Long changed full raster | 187.324 → 195.544 | 247.816 → 247.601 | 186.742 → 194.777 | 7.286 → 0.843 |

Long complete retained-raster median fork ranges are 7.665–8.316 ms before and
6.401–7.485 ms after; they do not overlap. Its median falls 8.7%, while P95
increases 9.2%. Long complete Swing ranges are 7.696–11.108 ms before and
7.244–8.864 ms after; they overlap, with only a 0.9% median reduction and a 9.3%
P95 increase. Small Swing median increases 6.4% within overlapping fork ranges.
Small retained-raster, full-raster and first-paint ranges also overlap. These
results establish a modest long retained-raster median benefit and substantial
allocation reduction. They do not establish a complete presentation, tail-latency,
all-content, 4K/2x timing or physical pen-to-photon improvement. Fixed before/after
run ordering, warmed JIT, GC and CPU-frequency variation limit timing attribution;
all samples, extrema and CPU values remain in the raw reports.

The unchanged native export control is 0.402 → 0.403 ms / 779,720 JVM bytes for
the long trace. Its baseline median is approximately 0.19% of cold full raster
and 5.14% of retained raster. Full preparation is 1.223 → 1.026 ms (16.1% lower,
nonoverlapping median fork ranges), with 6.926 → 3.678 MB allocation (46.9% lower)
from primitive-array construction and shared immutable vertex-constructor lists.
Standalone builder plus 676 shader uploads/constructions is 0.549 → 0.519 ms,
with overlapping ranges and identical JVM allocation. These are separate timed
controls, not additive attribution of a single frame. Production avoids shader
construction for reused texture-free chunks. Increasing the sixteen-triangle
batch/uniform demand was not needed for this reuse and is not claimed as tested.
Whole-triangle raster/shader evaluation and full Swing presentation remain costs.

Across three measured long retained gesture cycles, full preparation would run
269,301 vertex preparations and build 16,806 chunks/shaders. The implementation
runs 33,723 vertex preparations (87.5% fewer), builds 2,121 chunks/shaders
(87.4% fewer), and reuses 14,685 chunks. These deterministic counts agree across
all after forks. Removed predictions and retired gestures still release resources.

## Resource and first-paint costs

Long estimated wet bytes peak at 3,589,926 → 6,667,014 (85.7% higher), counting
scratch capacity and retained vertex-uniform bytes. Additional native shader and
JVM overhead is excluded. Persistent retained pixels are unchanged at 4,194,304
bytes. Every retirement and cancellation returns live geometry bytes to zero;
retained background/frame pixels remain until helper disposal, as in item 1.

| Warmed-renderer fresh-stroke or retirement phase | Wall median ms, before → after | JVM MB, before → after |
| --- | --- | --- |
| Small first retained raster | 4.720 → 5.262 | 0.168968 → 0.101424 |
| Long first retained raster | 176.705 → 193.615 | 6.491720 → 3.786648 |
| Small first retained Swing | 4.878 → 6.555 | 0.170312 → 0.102768 |
| Long first retained Swing | 155.807 → 165.759 | 6.493064 → 3.787992 |
| Long wet-cache retirement, raster run | 0.013 → 0.104 | 0.000128 → 0.000160 |
| Long wet-cache retirement, Swing run | 0.014 → 0.143 | 0.000128 → 0.000160 |

The first-paint phases have only three measured fresh-stroke observations per
fork (nine per version), on an already warmed renderer. They are not cold-app
startup timings. Closing hundreds of retained shaders increases retirement work;
this is measured separately from the following background-restoration paint.
Cache retirement, eviction, clear and close keep that cost deterministic. The
finished byte budget includes retained uniform bytes, so dense finished meshes
may evict sooner; live geometry is outside that finished budget.

## Reproduction and evidence

`benchmarks/run_wet_mesh.py` never rebuilds the library. It requires a resolved
Compose test runtime classpath, cached dependencies and the pinned JBR 25. Freeze
baseline/current project entries and source snapshots before rebuilding or timing;
serialize against Gradle/native jobs. The runner records all selected source,
class, JAR, harness and extracted-native hashes and refuses changed provenance.
The runtime/source freezes used here are `build/wet-mesh-step2/baseline` and
`build/wet-mesh-step2/after-runtime`.

```sh
python3 benchmarks/run_wet_mesh.py \
  --output build/wet-mesh-step2/reproduce-before \
  --classpath build/wet-mesh-step2/baseline/runtime-classpath.txt \
  --source-root build/wet-mesh-step2/baseline/source-snapshots \
  --java-home /home/mahalo/.gradle/jdks/jetbrains_s_r_o_-25-amd64-linux.2
python3 benchmarks/run_wet_mesh.py \
  --output build/wet-mesh-step2/reproduce-after \
  --classpath build/wet-mesh-step2/after-runtime/runtime-classpath.txt \
  --source-root build/wet-mesh-step2/after-runtime/source-snapshots \
  --java-home /home/mahalo/.gradle/jdks/jetbrains_s_r_o_-25-amd64-linux.2 \
  --compare build/wet-mesh-step2/reproduce-before/summary.json
```

Accepted evidence: `before-jbr25/summary.json`, `after-jbr25/summary.json`,
`after-jbr25/comparison.json`, per-fork JSON/logs/commands/source snapshots,
`timing-milliseconds.json` and `benchmark-audit.json` under `build/wet-mesh-step2`.
The same 324 transition fingerprints match across six forks; 216 paired full vs
retained raster/Swing frames and 24 cancellation checks pass exactly. The loader
Ink native and pinned Skiko native hashes agree before/after. Final production
sources match the measured after snapshot. Initial exploratory runs, fixture-only
failures and their source versions remain preserved separately. A Swing fixture
initially reused transparent SRC_OVER output over its prior offscreen image;
final controls use the normal opaque white panel background and check complete
cancellation pixels without relaxing exactness.

All owned verification and benchmark jobs ended and their private displays were
cleaned up. Item 2 is complete with the conditional native range bridge deferred
by the measured cost. Supported Wayland GPU presentation, asynchronous attach and
conditional SIMD/MIMD work remain separate follow-ups.
