# Positioning and comparative benchmarks

ByteInk targets developers adding editable handwriting to Kotlin/JVM applications
on Windows x86_64 and Linux x86_64. Its distinguishing combination is the pinned
AndroidX/Google Ink engine, Compose/Skia rendering, native pen input and
ViveNotes-compatible Android storage and operation replay. Completing the
optimization plan does not establish superiority over other drawing applications;
comparative measurements must establish each performance claim.

The benchmark program below is planned. Existing measurements compare ByteInk
implementations and controls; no Xournal++, GIMP or Krita performance comparison
has been run. Architecture references were reviewed on 2026-10-08 and are not pins
for a measured competitor release.

## What other applications use

The word "ink" describes a drawing activity; it does not imply Google Ink.
There is no single default desktop implementation.

| Application | Drawing approach | Relevant comparison |
| --- | --- | --- |
| Original Xournal | Its own point/variable-width stroke model and editing operations; its manual documents GTK/libgnomecanvas and a coordinate-based journal format. | Historical note-taking reference. [Original manual](https://xournal.sourceforge.net/manual.html#file-format). |
| Xournal++ | Its own C++ stroke model and Cairo renderer, including pressure-dependent drawing and translucent masks. | The closest application comparison for handwriting and annotation. [Stroke renderer](https://github.com/xournalpp/xournalpp/blob/master/src/core/view/StrokeView.cpp), [stroke model](https://github.com/xournalpp/xournalpp/blob/master/src/core/model/Stroke.cpp). |
| GIMP | Its own brush/paint core working with GEGL buffers; a separate MyPaint tool uses libmypaint. | Common simple pen workloads, with the chosen tool recorded. [Paintbrush implementation](https://github.com/GNOME/gimp/blob/master/app/paint/gimppaintbrush.c), [MyPaint tool](https://docs.gimp.org/3.0/en/gimp-tool-mypaint-brush.html). |
| Krita | Multiple brush engines; the Pixel Brush paints brush-tip impressions whose properties respond to input. Krita also supports MyPaint brushes. | Common simple pen workloads, with the exact engine and preset recorded. [Pixel Brush](https://docs.krita.org/en/reference_manual/brushes/brush_engines/pixel_brush_engine.html), [MyPaint engine](https://docs.krita.org/en/reference_manual/brushes/brush_engines/mypaint_engine.html). |

These documented drawing paths use their own systems, rather than simply compiling
Google Ink. They also illustrate that reusing a brush library is an established
approach: libmypaint is another example. This review describes the cited drawing
paths, not an exhaustive audit of every dependency or optional plugin.

Pressure, smoothing and erasing are already present in other applications.
Xournal++ already has editable strokes. Those features alone cannot establish a
unique advantage. Krita's painting engines also address artistic work beyond
ByteInk's handwriting scope; a smudge brush is not equivalent to an Ink marker.
[Xournal++ features](https://github.com/xournalpp/xournalpp#features),
[Krita brush engines](https://docs.krita.org/en/reference_manual/brushes/brush_engines.html).

## Draft pitch

> Bring Google's Ink stroke engine to Compose Desktop: pressure-aware editable
> handwriting, native Windows/Linux pen input, and tested Android-compatible
> ViveNotes storage, delivered as reusable Kotlin/JVM modules.

For an application developer, this packages engine integration, rendering,
prediction lifetime, native loading, input adapters and compatibility verification
in one component. Applications still own their document model, database transactions,
undo history and UI. See [authoring](authoring.md), [storage](storage.md) and
[operations](operations.md).

Google Ink provides modeled stroke geometry and brush effects; it supplies the core
of AndroidX Ink. ByteInk adds the desktop integration. The upstream project does not
guarantee interface stability, so upgrades need reviewed pins and repeated conformance
checks. [Google Ink](https://github.com/google/ink).

A measured pitch can append a specific result: "On [hardware/runtime], [workload]
uses [measured resource or time] less than [named baseline], with [quality gate] and
[timing boundary]." Fill those fields only from the final verified release's data.
Library packaging itself is not a speedup over embedding the same implementation
inside the application. Android interoperability is a capability claim, not a latency
claim; ByteInk's ViveNotes codecs do not automatically support every Android ink format.

## Existing evidence and its limits

These are historical internal measurements, not final-release or competitor scores.
They must not be combined into one speedup multiplier.

| Measurement | Existing result | Required qualification |
| --- | --- | --- |
| Matched retained rendering control, physical 4K | Offscreen software Swing paint median 150.847 → 17.860 ms; P95 190.280 → 18.438 ms, across three forks. | Paired full/retained controls used the version before final outline/lifetime corrections. Final retained-only confirmation measured 18.491 ms median / 18.992 ms P95, with matching frame controls; it is a separate source version, not a newly paired measurement. |
| Incremental preparation, long retained stroke | JVM allocation per changed Swing paint fell 88.4%. Retained-raster median 7.812 → 7.132 ms. | Complete Swing median ranges overlap and P95 does not improve; estimated retained wet storage rises 85.7%. |
| Blank-page retained rendering control, physical 4K | Offscreen Swing median 13.179 → 13.643 ms. | Approximately 3.5% slower; report this workload alongside populated-page gains. Fresh-resource first-paint costs are separate. |
| Isolated hardware EGL/readback candidate | SHM client-request medians were 31.1–34.3% lower than retained software across 1080p/4K/2× workloads. | Experimental, with failed fidelity promotion gates; deferred sends/copies and physical display latency excluded. It is not a shipped backend or accepted-output speedup. |
| Conditional CPU preparation, after incremental caching | A two-worker prototype reduces the 10,831-vertex loop median 38.3%; SIMD reduces it 9.8%. | No complete-paint case passes the 5% gain outside three-fork spread. Workers also fail a raw NaN fidelity control. Production keeps scalar preparation; see [CPU preparation experiments](cpu-preparation.md). |

The retained and incremental controls exclude native window presentation, compositor
completion and physical pen latency. Their offscreen Swing measurements include the
software painter's pixel transfers. The later EGL investigation uses a real private
JBR Wayland window, but JBR can coalesce multiple requests into a later send. Paint
request counts and elapsed request time cannot establish displayed frame rate.
Source-derived transfer sizes are not measured memory bandwidth.

The raw local evidence is in `build/retained-authoring/`, `build/wet-mesh-step2/`,
`build/wayland-gpu-step3/` and `build/cpu-preparation-step5/`, with source/binary
snapshots, controls and commands.
The reproducible harnesses are `benchmarks/run_retained_authoring.py`,
`benchmarks/run_wet_mesh.py`, `benchmarks/run_wayland_gpu.py` and
`benchmarks/run_cpu_preparation.py`.
Publish self-contained evidence for the final release before using these numbers
in external marketing. The current plans do not supply missing competitor or
physical latency measurements.

## Comparison baselines

| Baseline | Question it answers | Conditions |
| --- | --- | --- |
| Frozen ByteInk full redraw and prior preparation | What did ByteInk's optimizations improve? | Same input, engine, canonical geometry and pixels, with individual optimizations controlled. Existing harnesses cover part of this. |
| Ordinary Compose canvas using the same Ink engine and equivalent renderer | What does the dedicated native authoring/retained path improve over normal integration? | Same brushes, prediction policy, content and accepted output. Keep the ordinary path competently cached; separate frame scheduling from renderer work. |
| Small custom pressure-aware path implementation | What does an application gain over implementing basic drawing? | Compare features, visual quality, compatibility and integration effort. Different geometry/quality cannot support an equivalent-output speed claim. |
| ByteInk consumer application versus Xournal++ | How responsive is handwriting in a real application? | Same device/display, document workload and common pen/highlighter tasks; time both through equivalent boundaries. |
| ByteInk consumer application versus GIMP/Krita | How does a common pen task compare with established painting applications? | Record exact engine, preset and layer/compositing configuration. Compare common tasks; classify unsupported artistic features explicitly. |

Comparing an engine microbenchmark with a complete application's display latency
would attribute unrelated work to the engine. A consumer-app result belongs to that
host and configuration; it is not automatically a result for every ByteInk consumer.
An unoptimized demo's full redraw is not representative of every mature drawing app.

## Workloads and controls

Freeze a public, content-free trace corpus before collecting results. Each trace
records monotonically increasing event timestamps, coordinates and their units,
tool/contact IDs, pressure and available tilt/orientation. Hash the original trace
bytes; publish transforms, density, brush settings and any input adaptation.
Missing pressure or mouse-only injection must not be described as real pen capture.

Include short handwriting, dots, slow diagonals, fast curves, sharp reversals,
pressure ramps and long stress strokes. Use blank, 100-, 1,000- and 10,000-stroke
pages, translucent crossings, partially erased strokes and canonical finish/cancel.
Test pan/zoom, first stroke, new-page construction, resize, scale changes, hide/show
and recovery. Do not omit small scenes or startup because a long-stroke cache wins.

Use physical 1080p and 4K, plus logical 1080p at 2×. Count actual modeled vertices,
triangles/dabs and changed area when available: equal input counts do not establish
equal rendering work. Keep UI capture, normalization, modeling, preparation,
rasterization, copy/submission and presentation measurements distinguishable.

Compare default brush/stabilization settings as users encounter them, and separately
test matched quality profiles. Smoothing can trade responsiveness for shape quality;
Krita's stabilizer explicitly adds lag. A lower-latency profile with visibly worse
geometry is a different result. [Krita smoothing controls](https://docs.krita.org/en/reference_manual/tools/freehand_brush.html).

Run prediction-disabled controls for the common baseline. Test supported prediction
separately, recording tail error/overshoot, rollback and canonical completion. Keep
those errors visible alongside apparent tip-to-ink lag.

For final comparisons, use at least five fresh serial process runs, counterbalance
case/baseline order, and retain per-run raw observations. Warm steady-state runs
separately from cold startup. Choose enough events for tail statistics; publish
sample counts, warmup, exclusions, per-run variability and confidence estimates.
Do not treat many correlated frames from one process as independent machines.

Record CPU/GPU, power profile, RAM, OS/kernel, input device/report rate, display
refresh, compositor, app/build hashes, toolkit/runtime, drivers, actual graphics
configuration and scale. Windows, Linux X11/XWayland and native Wayland are separate
results. CPU GL/Vulkan is separate from hardware acceleration. Compare native
Wayland paths together; label any XWayland fallback rather than pooling it.

## Evidence required for each claim

| Intended claim | Measurement required |
| --- | --- |
| More responsive handwriting | Physical contact-to-first-visible-mark and steady pen-to-ink lag on the same tablet/display, plus matching quality profiles. Publish capture timing resolution and uncertainty. |
| Lower rendering overhead | Per-stage median/P95/P99 wall and CPU costs for equivalent accepted output, including copy/submission where the claim covers it. Report deferred/coalesced work separately. |
| Less resource use | Whole-process/worker CPU, JVM allocation, native/RSS and GPU memory, with idle and steady peaks. Cache pixel budgets alone do not establish total memory use. |
| Handles larger pages | Matched density/content and stroke counts, with first input, pan/zoom, redraw, erasing and reload costs; record stalls, backlog and dropped/merged samples. |
| Better stroke quality | Pressure/taper behavior, smoothness and corner retention, zoomed edge quality, translucent overlap, prediction errors and finished handoff. Exact pixels for equivalent ByteInk controls; documented quality matching for different engines. |
| Android compatibility | Bidirectional saved-stroke/operation round trips, canonical geometry, unknown-byte preservation and edit/undo/reload checks against the pinned Android implementation. |
| Easier integration | A compiling consumer example, supported-platform requirements and an explicit accounting of features the app still implements. Development-time savings need a measured study before a numeric claim. |

Protocol acknowledgments, buffer release, Java counters and presentation feedback
can explain scheduling. They do not replace physical display measurement. Publish
missing measurements as missing; an unsupported feature has no invented timing.

The opt-in [software latency diagnostics](software-latency.md) now capture native
queue wait through synchronous immediate request return, with synthetic native
Wayland and custom-source controls. They label software raster versus recording,
report transfer/Java2D drawing together, and leave deferred presentation and
physical latency null. These verification captures do not establish a speedup
or complete the physical comparison program above.

## Publication after the improvements

Freeze the accepted release and rerun its quality/conformance gates before timing.
Publish the trace corpus, exact commands/configurations, hashes, raw per-run results,
failed/inconclusive cases and chart-generation code. Include wins, regressions,
startup/blank-page costs and memory tradeoffs in the same report. Leave an
experimental renderer out of the release comparison until its fidelity and lifecycle
gates pass.

The eventual headline should name the workload, baseline, platform and measured
benefit. Claims such as "faster than Krita", "lowest pen latency" or "better than
the default way" require corresponding fair measurements; completing internal
optimizations alone does not establish them. Android-compatible editable ink and
reusable Kotlin integration can remain useful product advantages even in workloads
where a mature application is faster.
