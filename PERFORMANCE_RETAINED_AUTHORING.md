# Retained native Wayland authoring

Implementation scope: item 1 of the pen/Wayland performance follow-up. Keep the real Ink engine,
native/codec pins, accepted input order and canonical prediction-free completion unchanged.

## Design and acceptance

- Own two physical sRGB N32 premultiplied rasters: finished content including the clear color,
  and the composed frame. This matches the pinned Wayland software painter's destination.
- Restore conservative integer damage from the finished raster with source replacement, then
  redraw all intersecting live strokes in their original order. Never accumulate translucent wet
  ink over its previous pixels. Consume engine damage only after successful painting.
- Include removed predictions and retired gestures; invalidate full content on host requests,
  callback/color changes, finish handoff, physical viewport/density changes and renderer version.
  Texture origins depending on the latest input and texture animation require whole-stroke damage.
- Account both physical buffers against an explicit byte budget; release them on detach, disable,
  replacement and close. Unsupported custom renderers and oversized views use direct full redraw.
- Retain only native Wayland's known software presentation path by default. Windows/X11 rendering
  remains on its current GPU path. Swing still presents a complete image and performs full copies.
- Compare every meaningful transition against an independent full redraw, including translucent
  overlapping pointers, prediction reversal/expiry, cancel/reuse, canonical handoff, partial erase,
  transforms, density/resize, invalidation, failures and budget fallback. Run actual Wayland at 1x
  and 2x and existing X11/OpenGL regressions. Measure serial fresh JVM forks separately from tests.

Prior production classes and dependency inventory were frozen before recompilation under
`build/retained-authoring/baseline/`. Earlier performance evidence remains intact.
The frozen original runtime has 64 classpath entries and 213 inventoried files at reviewed HEAD
`ba2724122372ed74b0fd12f335cb307f97feeeb6`. Of these, 212 match the prior verified renderer runtime;
the differing `WaylandWire.class` belongs to native capture, which this headless/offscreen harness
does not invoke. `baseline/previous-evidence-comparison.json` records that distinction. The input,
engine, renderer and packaged native controls used by painting match the earlier evidence.

## Implementation

`InkRetainedAuthoringRaster` owns the background and composed frame for `InkLowLatencyPanel` on
native Wayland. Native input and timer requests preserve finished content; public
`requestInkRender()` explicitly invalidates it. The captured completion callback invalidates the
background before canonical handoff. Callback/color, renderer version, dimensions and physical
scale also invalidate. The default combined pixel budget is 64 MiB, enough for two 3840×2160 N32
buffers (66,355,200 bytes); zero/oversized/custom-renderer cases draw directly. Detach, disable and
close release raster storage. No existing public panel constructor/accessor/method was removed.

The native engine supplies cumulative added/modified/removed bounds. Retirement records the last
presented pixels before an engine is cleared or reused. Conservative affine condition-number AA
inflation plus float-mapping rounding covers the shader's expanded vertices. Global texture
origin/animation changes redraw the whole affected stroke. Scoped full image snapshots copy with
SRC and are released before the next surface write; tests verify pixel storage addresses remain
stable across writes. Caching the background with its clear color preserves premultiplied-alpha
rounding, including translucent clear colors and overlapping pointers.

Triangle paints use native fine damage. Filled outlines (`InkPathRenderer` and any potential
DISCARD coat, including highlighters) use complete old/current bounds on version changes. Damage
expands to a fixed point across intersecting outlined live bounds before restoration: path-fill
AA can change prefix coverage outside native mesh damage and when an unchanged outline is
re-rendered through a partial clip. Intersecting strokes still render in their original order.

The helper is internal and targets the pinned native Wayland software painter's physical sRGB N32
destination. Generic GPU/recording/color-space destinations use their existing path. Custom
consumers must leave mutation and updated-region reset of the panel's borrowed live engines to
the panel. Finished-scene or animation changes outside completion explicitly request invalidation.

## Verification

- Fifteen independent exact full-redraw pixel cases pass: accumulated native damage, prediction
  reversal/shrink/expiry, translucent overlap, cancel/finish/reuse, actual partial erase/undo/redo,
  fractional/2x scale and incremental affine motion, timed opacity, last-input texture origins,
  animation/version/color changes, budget/custom fallback, exceptions, disposal and no raster COW.
  The additional outlined-paint matrix covers both path and mesh highlighter at 1.25x/2x under
  shear, including an unchanged overlapping outline during cancellation.
- All ten actual compositor/window cases pass at 1x and 2x, without skips. The added case verifies
  retained callback reuse, actual partial-region motion and exact full/retained software painter
  pixels through cancel, canonical handoff, partial erase and hide/show. Explicit disabled/hidden
  paints still execute the delegate with exact pixels, zero retained bytes and preserved budget;
  reenabling restores actual input and retention. Existing capture,
  pressure/tilt/history, multiple windows, touch, mouse, hotplug and reader tests remain passing.
- The fresh serial full Linux/JBR 25 build executes all 118 tasks: 2,377 cases in 195 suites, zero failures
  or errors, five expected optional/platform skips. Compose executes 150 cases, including all 15
  added pixel controls. Android path matrix: 280 cases / 2,240 exact encoding checks, no failing cases.
  Existing Android mesh reference comparisons also pass in the full testing suite.
- Actual X11 input/Skia and Mesa OpenGL regression checks pass three cases; the Windows-only
  native input case is skipped. Windows runtime was not rerun. The wiki consumer compiles/runs;
  all 316 generated public declarations validate and the strict MkDocs build passes.
- A javap comparison against frozen original classes finds no removed public panel declarations;
  seven additions expose the budget setter/getter and five raster diagnostics. Native engine,
  binaries, math, codec/storage and upstream pins remain unchanged.

The first actual-window attempt exposed constructor-parameter shadowing of the mutable
`drawContent` property inside the new delegate; the delegate now reads `this.drawContent`. That
failed log is preserved. The first full parallel build hit the previously observed test runtime
`ClassNotFoundException: InkPointerSample`; a fresh no-daemon serial build with recorded resolved
runtime classpaths passes without a production workaround. Initial fixture compile errors/logs
are also preserved. Successful final validation uses the corrected code, not omitted tests.

The first final outline-control attempts also exposed the seven-pixel path and 49-pixel
highlighter differences described below; their exact sources, failures and XML remain preserved.
A later verification command omitted the repository's explicit JBR test-runtime property, selecting
the default toolchain. Its full run passes 3,386 executions, including 1,009 additional JDK 25
cases, but cannot initialize the native Wayland toolkit. Those reports/preflight failures remain
supplemental evidence. The final result above uses `-PbyteinkTestJavaHome` explicitly; every
executed JUnit task is inventoried and the extra default-toolchain cases are excluded from it.

## Measurement method

The main synthetic page contains eight genuine finished strokes, 32 real inputs each, plus ruled
paper. Each live gesture supplies 8,192 real observations in 64 updates of 128 observations, with
12 replacement predictions per update, followed by cancellation. Three fresh serial JBR 25 JVMs
run two warmup and three measured gestures per case: 195 measured paints per fork. Workloads are
1920×1080 at 1x, 3840×2160 at 1x, and logical 1920×1080 at 2x (physical 3840×2160).

The frozen original binary control uses full redraw. The current binary control compares direct
full redraw with the retained helper, on the same machine and JVM. Raster timing encloses only
painting; offscreen `SoftwareSwingRedrawer` timing also includes its full pixel readback and Java
image transfer. Input preparation, prediction/session handling, AWT window presentation, compositor
and scanout are excluded. JVM allocations cover the EDT only, excluding native Ink/Skia storage.
Results aggregate the median of per-fork medians and P95 values; fork extrema show observed spread,
not confidence intervals. Modes run in fixed full-then-retained order, leaving possible JIT,
thermal and ordering bias; the original and current full controls help identify drift.

A separate, untimed correctness pass compares 75 transition frames per resolution: all live
updates, eight prediction retractions, unchanged presentation, cancellation and explicit content
invalidation. The current helper uses an independently implemented full redraw, separate renderer
and separately rebuilt finished strokes as its pixel oracle. Original/current full-frame sequence
hashes are also compared. Timed gestures check accepted real input counts and final background
hashes after cancellation; those checks do not measure canonical completion or persistence.
Canonical handoff and erase/undo/reload behavior are covered by the functional suites above.

The initial 48-finished-stroke / 128-input-per-stroke exploratory run was intentionally stopped
because its software workload made the measurement cycle impractical. Its partial logs, source
copies, classpath inventory and controlled-abort record are preserved under
`build/retained-authoring/before-jbr25/`. Both sides of the accepted main comparison use the same
explicit eight-stroke / 32-input workload; no 48-stroke result is claimed.

## Main measurements before the final outline fix

All three original and three current forks completed with stable source/binary inventories.
The 675 current full/retained transition frames compare exactly; their full-render sequence hashes
match the frozen original. All six final raster/Swing full/retained background hashes match.
The production Ink library SHA remains
`b483814bc0af81e27715b06d3bca9b4fba44b882aa3f46d5c111b1b795610012`.

Wall time below is milliseconds, **median / P95**, aggregated across three forks. These paired
full and retained controls use the main measurement source version, preceding the final outline
and panel lifetime corrections described below. Original/current full controls show run
variation, so no improvement is attributed to the unchanged full renderer.

| Offscreen software Swing paint | Frozen original full | Main full | Main retained | Median speedup vs paired full |
| --- | ---: | ---: | ---: | ---: |
| 1920×1080, 1x | 72.379 / 90.505 | 80.711 / 101.153 | 3.647 / 4.016 | 22.1× |
| 3840×2160, 1x | 146.537 / 182.016 | 150.847 / 190.280 | 17.860 / 18.438 | 8.4× |
| Logical 1920×1080, 2x | 278.639 / 347.333 | 271.677 / 347.809 | 20.060 / 20.651 | 13.5× |

| Raster paint only | Frozen original full | Main full | Main retained | Median speedup vs paired full |
| --- | ---: | ---: | ---: | ---: |
| 1920×1080, 1x | 71.431 / 95.376 | 74.633 / 101.198 | 1.019 / 1.318 | 73.2× |
| 3840×2160, 1x | 136.332 / 186.587 | 134.758 / 179.857 | 2.290 / 2.557 | 58.9× |
| Logical 1920×1080, 2x | 264.541 / 353.933 | 243.634 / 344.589 | 3.532 / 4.004 | 69.0× |

Per-frame median EDT CPU time and JVM allocation, current full → retained:

| Workload | CPU milliseconds | JVM bytes |
| --- | ---: | ---: |
| 1080p raster | 74.428 → 1.015 | 225,416 → 215,496 |
| 1080p Swing | 80.458 → 3.632 | 226,184 → 216,816 |
| 4K raster | 134.341 → 2.281 | 260,128 → 251,816 |
| 4K Swing | 150.316 → 17.786 | 261,472 → 253,160 |
| 1080p at 2x raster | 242.898 → 3.522 | 223,592 → 215,280 |
| 1080p at 2x Swing | 270.847 → 19.989 | 224,936 → 216,624 |

Each measured case executes 195 paints per fork. Full redraw calls finished content 195 times;
retention calls it zero times after warmup. Restored/rasterized rectangle area totals
1,314,612 / 404,352,000 pixels at 1080p (0.325%), 2,192,232 / 1,617,408,000 at 4K (0.136%) and
4,886,088 / 1,617,408,000 at logical 1080p/2x (0.302%). Both raster and Swing controls retain
16,588,800 pixel bytes at 1080p and 66,355,200 at physical 4K. These are additional persistent
pixels; budgets exclude renderer geometry and Skiko presentation storage.

CPU closely follows wall time. JVM allocation improves only modestly because changed wet
geometry is still exported/prepared in full. The 4K Swing residual of about 18–20 ms is mostly
presentation transfer beyond the 2–4 ms raster control. Dirty-pixel fractions do not imply
proportional CPU or presentation savings, and these synthetic paint gains establish no physical
pen-to-photon latency or universal frame-rate improvement. Initial background construction,
changed scenes/canonical handoff and frequent view changes still pay full rebuild costs.

After main timing, a lifetime audit found that an explicit/queued disabled or hidden paint could
recreate rasters after disconnect released them. The panel now passes an effective zero pixel
budget while hidden or disabled, preserving the configured public budget and exact full painting.
This is a panel integration guard; the main benchmark directly invokes the helper and excludes
panel integration checks.
Measured runtime and panel/helper source snapshots are preserved before recompilation under
`build/retained-authoring/main-measured-runtime/` and each main run's `source-snapshots/`.
The subsequent outline-specific test found seven antialiased prefix pixels changing outside native
mesh damage after prediction under fractional shear, then 49 highlighter pixels changing when an
unchanged overlapping outline was re-rendered through cancellation damage. Filled-outline rendering
now restores complete old/current wet extents and expands damage across intersecting outlines.
Both failed attempts are preserved; all fifteen exact cases now pass. The final fresh full build,
native windows at both scales, X11/OpenGL and docs consumer also pass. Retained-only marker mesh
timing confirms the final helper separately from these preserved main measurements.

## Final implementation controls

The final retained-only control repeats the same populated-page workload in three fresh serial
JVMs. Its untimed full/retained sequence must match the frozen original and main sequence hashes,
and its input counts, damage areas and final painter hashes must match the main retained controls.
The final helper conservatively handles path/DISCARD antialiasing while marker triangle paints
continue using fine native damage. Results are collected in `final-retained-8strokes-jbr25/`.

All three final forks pass: 675 exact transition frames match both original/main full-render
sequence hashes; all six final raster/Swing painter hashes and every shared structural count
match. Background rebuilds and finished-content calls remain zero during measured frames, and
damage areas and retained bytes remain exactly those reported above. Start/end production,
harness and binary guards pass. Final helper source SHA is
`9c07ea43cb4d64937857f05a5091da021f5f50ef7e050ac0a2e5d046d9b89336`;
the final panel SHA is `30ddc6264b54279971be308b4e1a6630244ed24f37b525a075d4a7279e92d597`.

Final wall paint timings in milliseconds, with observed fork spread:

| Populated-page workload | Steady median / P95 | Per-fork median range | New painter's first live paint, median |
| --- | ---: | ---: | ---: |
| 1080p raster | 1.037 / 1.249 | 0.995–1.133 | 62.789 |
| 1080p software Swing | 3.752 / 4.060 | 3.711–3.850 | 68.374 |
| 4K raster | 2.380 / 2.699 | 2.364–3.478 | 142.743 |
| 4K software Swing | 18.491 / 18.992 | 17.592–19.960 | 160.984 |
| Logical 1080p/2x raster | 4.115 / 5.249 | 3.556–4.697 | 214.353 |
| Logical 1080p/2x software Swing | 15.051 / 16.308 | 14.649–20.787 | 254.126 |

These confirm the large populated-page paint savings against the preserved full controls. The
2x Swing range overlaps the prior retained measurements; its lower aggregate establishes no
further improvement from the outline correction. Final median EDT CPU times follow wall time
(1.029/3.735 ms at 1080p raster/Swing, 2.371/18.425 ms at 4K, 4.089/14.948 ms at 2x). Median EDT
JVM allocations are 215,688/217,000, 252,192/253,536 and 215,656/217,000 bytes respectively. Whole
changed wet geometry and full Swing transfers remain; first cache construction remains costly.

The separate blank-page control has zero finished strokes and no ruled paper. Each gesture uses
the first 128 real observations of the same 8,192-observation path, sixteen eight-observation
updates with twelve replacement predictions each, then cancellation. It does not stretch a short
gesture across the full screen: the path prefix spans roughly 29×32 logical units at 1080p and
59×32 at 4K. Three fresh JVMs execute ten warmup and thirty measured cycles, or 510 measured paints
per case/fork, at 1080p and 4K/1x. Full and retained modes remain in fixed order. This control
isolates the cost of retention when almost no finished drawing needs to be avoided; it is not
representative of a populated notebook page. Results are in `final-blank-short-jbr25/`.

Both new controls record the first live paint of each new painter separately. The correctness
pass has warmed the JIT; these samples include fresh renderer preparation and the retained
buffer allocation/background construction, and exclude destination construction and input
processing. They are the existing first warmup paint, not an added empty paint, and do not enter
steady paint medians or P95 values. With three samples per case, they describe observed startup
cost rather than a cold-start distribution.

All three blank forks pass: 126 exact transition frames, four matching final raster/Swing
full/retained hashes, stable source/binary guards and the same final runtime inventory as the
populated-page confirmation. Blank full redraw calls finished content 510 times per case/fork;
retention calls it zero times after warmup. Physical dirty area totals are 628,860 / 1,057,536,000
pixels at 1080p and 762,480 / 4,230,144,000 at 4K; the full-frame retained presentation blit remains.

| Blank-page workload | Full median / P95, ms | Retained median / P95, ms | Retained per-fork median range, ms | First live paint median, full → retained, ms |
| --- | ---: | ---: | ---: | ---: |
| 1080p raster | 0.656 / 0.874 | 0.555 / 0.709 | 0.550–0.598 | 1.597 → 2.711 |
| 1080p software Swing | 3.127 / 3.377 | 3.214 / 3.394 | 3.183–3.329 | 8.863 → 6.528 |
| 4K raster | 1.379 / 1.761 | 1.919 / 2.111 | 1.812–2.920 | 2.151 → 25.242 |
| 4K software Swing | 13.179 / 13.678 | 13.643 / 14.190 | 13.584–14.687 | 25.443 → 47.860 |

The cheap-scene tradeoff is real. Blank 4K raster median rises about 39%; software Swing median
rises about 0.47 ms (3.5%), with nonoverlapping observed median ranges. At 1080p the Swing increase
is about 0.09 ms (2.8%) and the fork ranges overlap. New-painter blank 4K first-live painting also
costs more: about 23 ms additional raster work and 22 ms additional Swing paint. First-live
timings include fresh resources/preparation and have only three observations; they do not measure
cold application startup or isolate every allocation. No automatic cheap-scene bypass is added;
hosts can set `retainedPixelBudgetBytes = 0` when full redraw better suits their content.

Blank-page median EDT CPU full → retained is 0.653 → 0.551 ms and 3.113 → 3.200 ms at 1080p
raster/Swing, and 1.374 → 1.912 ms and 13.128 → 13.591 ms at 4K. Median EDT JVM bytes rise
19,152 → 21,024 and 20,408 → 22,088 at 1080p, and 20,584 → 22,264 and 21,928 → 23,344 at 4K.
Retained tracking adds overhead and persistent physical pixels even on an empty page. The large
populated-page improvement does not imply a gain for all content, brushes or page sizes.

## Reproduce

Supply a resolved test runtime classpath from `benchmarks/run_pen.py`, or use the preserved
`runtime-audit.gradle` init script to run `:byteink-compose:retainedBenchmarkClasspath` after
building. Freeze the original classpath before compiling changed production classes. The retained
runner itself never invokes Gradle; run builds/tests and timing serially. Use a fresh output
directory for each invocation and a pinned JBR 25 installation.

```sh
RETAINED_JAVA_HOME=/path/to/jbr25
python3 benchmarks/run_retained_authoring.py \
  --output build/retained-authoring/reproduce-populated \
  --classpath build/retained-authoring/current-runtime-classpath.txt \
  --java-home "$RETAINED_JAVA_HOME" --forks 3 \
  --scene-strokes 8 --scene-inputs 32 --live-inputs 8192 --inputs-per-frame 128 \
  --warmup-cycles 2 --measured-cycles 3 --workloads all --modes full,retained
python3 benchmarks/run_retained_authoring.py \
  --output build/retained-authoring/reproduce-blank \
  --classpath build/retained-authoring/current-runtime-classpath.txt \
  --java-home "$RETAINED_JAVA_HOME" --forks 3 \
  --scene-strokes 0 --scene-inputs 32 --live-inputs 128 --inputs-per-frame 8 \
  --no-paper-grid --warmup-cycles 10 --measured-cycles 30 \
  --workloads 1080p,4k --modes full,retained
```

The original control uses `--modes full` and the frozen baseline classpath. The final confirmation
uses `--modes retained` with the populated-page arguments; it refers to the preserved original/main
full controls and does not label them newly measured final paired controls. Raw command records,
class inventories, exact observed sources and extracted-native hashes accompany each run.

## Sources and evidence

- Pinned local AndroidX `InProgressStroke.populateUpdatedRegion/resetUpdatedRegion` and
  `InkInProgressShape` explain cumulative removed vertices, cancellation and animated texture damage.
- [Pinned Skiko software Swing painter](https://github.com/JetBrains/skiko/blob/v0.150.1/skiko/src/awtMain/kotlin/org/jetbrains/skiko/swing/SoftwareSwingRedrawer.kt)
  constructs an sRGB N32 premultiplied raster and still performs full presentation transfers.
- [Pinned Skiko Skia revision](https://github.com/JetBrains/skiko/blob/v0.150.1/skiko/gradle.properties)
  and [its raster snapshot implementation](https://github.com/JetBrains/skia/blob/m150-1f14f1166a/src/image/SkSurface_Raster.cpp)
  distinguish scoped full snapshot sharing from the mutable bitmap surface-draw copy path.
- Raw build/test/compositor/source/binary evidence: `build/retained-authoring/`.
- Normalized raw forks, summaries, audits, commands, source snapshots and SHA manifest:
  `benchmarks/results/2026-10-07-retained-authoring/` (ignored benchmark artifacts). Heavy frozen
  classpath/native files and archived earlier failures remain in the raw build evidence.
- Reproducible timing harness: `benchmarks/pen/PenRetainedAuthoringBenchmark.java` and
  `benchmarks/run_retained_authoring.py`. Frozen original binary manifest is under
  `build/retained-authoring/baseline/`. All twelve accepted original/main/final/blank JVM forks
  completed serially; no timing, Gradle or private-compositor workload remains running.

Full Swing transfers, compositor/scanout and physical pen-to-photon latency remain separate from
retained raster work.
