# CPU mesh preparation experiments

Part 5 of the Wayland improvement plan evaluates optional SIMD and bounded CPU
workers after incremental vertex/chunk reuse. The experiment lives in
`benchmarks/run_cpu_preparation.py` and `benchmarks/pen/`; application artifacts
retain the existing scalar renderer and runtime requirements.

The investigation is complete. No candidate meets the combined preparation,
complete-paint and fidelity gates, so no production algorithm is promoted.

## Measured result, 2026-10-08

Three fresh serial JBR 25.0.4.1 JVMs ran on Linux x86_64, AMD Ryzen 9 7900X.
The frozen production reference is commit `31a08aa0e8a1fb272e75d52947149aa2cd6191b6`,
with 64 classpath entries and 45 production source files. The vector species was
512 bits. Each pure-loop case used 750 warmup and 300 measured calls per candidate;
each paint case used six warmup and twelve measured gestures, with eight updates
per gesture. Candidate order rotated across forks. Values below are medians of
fork medians; P95 values are medians of per-fork nearest-rank P95s.

The large native trace contains 10,831 vertices and 10,809 triangles, from 6,144
observations. These preparation timings exclude chunk construction and raster:

| Candidate | Dense loop median / P95, ms | Median fork range, ms | Owner + worker CPU, ms | JVM bytes/call |
| --- | --- | --- | --- | --- |
| Original Kotlin | 0.440 / 0.446 | 0.422–0.446 | 0.440 | 112 |
| Java scalar control | 0.390 / 0.396 | 0.370–0.407 | 0.390 | 176 |
| Optional SIMD | 0.397 / 0.410 | 0.395–0.398 | 0.396 | 2,953,264 |
| Two workers | 0.272 / 0.294 | 0.255–0.280 | 0.467 | 1,430,256 |

SIMD improves this loop by 9.8%, below the 20% gate. The scalar control improves
11.3%. Workers improve wall time 38.3%, while measured owner/worker CPU rises
6.2%. Dense worker array payload peaks at 1,429,692 bytes. SIMD's allocation
includes temporary Vector API objects/arrays on this runtime; its zero queued
worker payload does not mean zero temporary memory.

A separate 22-second JFR diagnostic fork, excluded from timings, samples float
array and `Float512Vector` allocations in the candidate's Vector API calls.
`profile-summary.json` preserves the sampled classes/stacks. These samples do not
measure steady allocation totals or CPU shares; the table uses thread allocation
counters. Executed Vector API blocks also do not prove every operation lowered
to a hardware vector instruction. This result qualifies this implementation and
runtime, rather than SIMD generally.

For the same long mesh with only 128 changed tail vertices, forced preparation
median/P95 is 0.0157/0.0158 ms original, 0.0475/0.0546 ms SIMD and
0.0777/0.0852 ms workers. With half the vertices changed, medians are 0.225,
0.399 and 0.182 ms respectively. Dense worker wall time crosses below the scalar
reference around 900 vertices in these samples, but the 2,048-active-vertex paint
threshold is deliberately conservative. A crossover is sensitive to JIT state,
layout, masking and scheduling; it is not a general dispatch recommendation.

Complete changed paints include the existing retained raster and software Swing
pixel transfers. Initial paint and cancellation are recorded separately in the
raw reports.

| Workload | Original median / P95, ms | SIMD median / P95, ms | Workers median / P95, ms |
| --- | --- | --- | --- |
| Small predicted stroke | 2.172 / 3.599 | 2.092 / 4.230 | 2.156 / 3.884 |
| Long predicted stroke | 3.138 / 8.030 | 3.213 / 6.854 | 3.259 / 7.990 |
| Two long pointers | 5.956 / 13.678 | 6.190 / 15.756 | 5.806 / 15.115 |
| Long stroke, changing affine transform | 194.988 / 265.128 | 252.404 / 261.320 | 173.405 / 257.710 |

Every complete-paint median range overlaps its reference range. In the affine
case, original fork medians span 162.440–250.207 ms and workers
147.401–206.786 ms. The lower aggregate worker median does not establish the
required gain outside fork spread. Small paints and ordinary changed prediction
frames use the synchronous fallback; differences there cannot be attributed to
SIMD or worker execution. Large first paints and every affine update actually
execute the candidates, as the dispatch/vector-block counters confirm. The affine
case's broad variation also limits attribution to preparation.

All native-array and exact complete-pixel controls pass: 324 paired complete-frame
comparisons across three forks, plus 48 exact white-background cancellation
checks. Each fork performs 618 array cases and 12,492,144 float comparisons,
including diagnostic numeric cases and worker lifecycle controls. The worker
candidate has 52 raw NaN sign mismatches in one fork; the other two have zero.
Final scalar/vector diagnostic counts are zero, but an earlier stopped run also
observed a scalar NaN sign mismatch. This shared scalar translation remains
unqualified for exact exceptional-value behavior. No tolerance was introduced.

Worker byte limits, stale generation/version/render-key rejection, immutable
copies, owner-thread access, retirement, close with pending work and thread
termination all pass. Peak worker payload during paints is 1,239,826 bytes;
canonical/prepared renderer storage and retained pixels are separate. CPU/GC,
allocation and startup costs remain relevant even when a median loop is faster.

Evidence is under `build/cpu-preparation-step5/measured-complete/`, with the frozen
reference in `build/cpu-preparation-step5/baseline/`. Earlier smoke/aborted/failed
runs are preserved and excluded from the final timing aggregate. Source, binary,
JDK and input/native/pixel guards pass. The extracted production Linux native
SHA-256 remains `b483814bc0af81e27715b06d3bca9b4fba44b882aa3f46d5c111b1b795610012`.
Windows runtime and physical display latency were not measured. The existing Windows
scalar path is retained through unchanged production sources;
this investigation does not establish Windows candidate performance or fidelity.

The normal scalar Linux build passes all 119 executed Gradle tasks: 2,394 test
cases across 197 suites, zero failures/errors and five expected optional/platform
skips. The Android reference matrix passes 280 cases and 2,240 encoding checks;
Linux and Windows native artifact checks pass. Generated API freshness checks
318 declarations, and the strict wiki build passes. Those production checks use
the normal runtime without the benchmark agent or incubator flags. Numeric
candidate rejections above remain separate from these successful library checks.
All renderer binaries match the frozen runtime after the normal rebuild. One
unrelated `WaylandWire.class` gains three synthetic local-variable debug entries;
its disassembled method code is identical. The final audit records this metadata
difference rather than claiming every rebuilt class hash is identical.

## Candidates and boundaries

The reference is the current Kotlin `prepareVertices` implementation. A Java
scalar translation controls for changes unrelated to vectorization or threading.
The vector candidate uses the JDK 25 Vector API's preferred float species, gathers
the canonical fifteen-float vertex records, and scatters positions and sixteen
varyings. It preserves operation order and uses scalar `Math.sin`/`Math.cos` for
HSL shifts. Atlas preparation and exceptional numeric blocks use scalar fallback.
Vector execution counters distinguish Vector API blocks from fallback work.

The worker candidate copies owned vertex and changed-mask arrays and splits the
pure loop between two fixed platform threads. One job may be in flight, the queue
has two slots, and array payloads are capped at 16 MiB. Outputs apply only if the
captured gesture generation, shape version, transform, color and animation keys
still match. Retirement cancels derived results. Native stroke mutation, renderer
caches, chunk/shader construction and canvas access stay on the owner thread.

For complete-paint comparisons both candidates leave fewer than 2,048 changed
vertices synchronous. The crossover microbenchmarks also force each candidate on
small inputs to expose its overhead. The worker paint prototype waits for its job
at the preparation call; it measures copy, scheduling, compute, wait and apply
costs. It does not implement a production asynchronous frame scheduler.

A benchmark-only Java agent inserts a conditional entry into the private pure
loop at class load. The original body remains available. This lets the candidates
exercise the shipped incremental caches, runtime shaders, retained raster and
`SoftwareSwingRedrawer` without adding a production extension point. The
[JDK Class-File API](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/classfile/package-summary.html)
provides the transformation; it is not a published ByteInk API.

The [Vector API](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/package-summary.html)
remains an incubator module in the tested runtime. The runner supplies its module
flag only to benchmark compilation/processes. Floating-point lane operations
follow their documented Java semantics, but transcendental accuracy does not
establish bit identity with every scalar implementation. The candidate uses
scalar trig, preserves float grouping and checks raw output bits, including NaNs
and signed zero. It uses no fused multiply-add or cross-lane float reduction.
[Vector operator semantics](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.incubator.vector/jdk/incubator/vector/VectorOperators.html).

## Measurement and acceptance

Freeze the current compiled runtime, dependencies and corresponding sources
before timing. The runner checks their hashes, its harness sources and selected
JDK files before and after the experiment. Serialize timing against builds and
other benchmark jobs. Retain the raw fork files and exact commands.

Pure-loop cases use real native meshes from 16 through 6,144 input observations,
plus a long mesh with 128 changed tail vertices and one with alternating changes.
Actual vertex/triangle counts and mesh hashes are recorded; input observations
are not a proxy for vertex count. Separate controls exercise all fifteen input
attributes, reflected/singular/extreme transforms, transparent/black colors, HSL,
atlas progress, changed-mask holes and unsigned index 65,535, before and after JIT
warmup. Worker controls check stale keys, immutable copies, owner access, budget,
retirement, close with pending work and termination.

Native-array and complete-pixel mismatches stop the run. Synthetic numeric
mismatches are retained as candidate rejections, with expected/actual raw bits,
while timing continues. `completed` means the experiment collected its evidence;
it does not mean every candidate passed fidelity. The summary requires stable
native output hashes across forks and keeps synthetic NaN reference hashes per
fork, since raw NaN sign/payload is not a cross-JVM language guarantee. A mismatch
is still a failed promotion gate; the report applies no NaN tolerance.
[Raw float bits](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Float.html#floatToRawIntBits(float)),
[Math exceptional-value contracts](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Math.html).

Complete paints cover a small predicted stroke, a long predicted stroke, two long
simultaneous pointers and a long stroke with changing affine transforms. Every
candidate is compared with the original renderer using exact complete pixels for
the initial frame, eight updates and cancellation. Timing includes rasterization
and full software Swing pixel transfers to a 1024 × 512 `BufferedImage`, at 1×.
Input modeling, native window submission, compositor completion, scanout and
physical pen latency are excluded.

Each of three fresh serial JVMs rotates candidate order. Reports retain per-fork
median and P95 wall time, owner/worker CPU, owner/worker JVM allocation and peak
in-flight array payload. CPU counters include both worker threads' dispatch and
queue work, but exclude JIT/GC and other JVM/native threads. Array byte estimates
exclude object headers, executor/stack memory, native allocations and retained
renderer caches; those are not whole-process memory measurements.

Promotion requires at least 20% improvement in the affected large preparation
loop and at least 5% improvement in its complete paint, with candidate and
reference fork ranges separated. Small-work behavior, exact geometry/pixels,
lifecycle and supported-platform fallback must also pass. A loop-only gain is
insufficient; three successful forks on one machine do not establish a universal
speedup. Windows runtime validation is required before promoting a platform-wide
candidate.

## Reproduce

Use JDK 25 and a resolved, preferably copied, Compose test runtime classpath.
The runner does not build the library or modify production classes on disk.

```sh
python3 benchmarks/run_cpu_preparation.py \
  --java-home /path/to/jbr25 \
  --classpath /path/to/frozen/runtime-classpath.txt \
  --source-root /path/to/frozen/source-snapshots \
  --output build/cpu-preparation-reproduction \
  --forks 3 --warmup 750 --measured 300 \
  --warmup-cycles 6 --measured-cycles 12
```

Choose a new output directory. `fork-*.json` contains every sample and exact
control; `summary.json`, `comparison.json` and `gates.json` aggregate results.
`commands.json` and `start-provenance.json` record invocation and hashes.
`--profile` adds a JFR fork excluded from timing aggregates. Do not use the Java
agent or the experimental classes in an application launch.
