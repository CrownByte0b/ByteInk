# Desktop stroke authoring

Use `com.vivenotes.byteink:byteink-compose:0.1.0-SNAPSHOT` for the drawing surface and
`com.vivenotes.byteink:byteink-kit:0.1.0-SNAPSHOT` for catalog tools and stored rows.
The real Ink engine and packaged native loader arrive transitively. The consuming application
supplies Compose Desktop's platform runtime and enables JVM native access.

```kotlin
@Composable
fun DrawingPage(pageId: String, save: (StoredInkStroke) -> Unit) {
    val controller = rememberInkAuthoringController()
    val renderer = remember { InkPathRenderer() }
    val completed = remember(pageId) { mutableStateListOf<AuthoredViveStroke>() }
    val tool = remember { ViveInkTool(stabilization = 2, sizeDp = 4f) }
    // Page coordinates are dp; this transform maps them to local canvas pixels.
    val density = LocalDensity.current.density
    val pageToView = ImmutableAffineTransform(density, 0f, 0f, 0f, density, 0f)
    key(pageId) {
        InkDrawingSurface(
            controller = controller,
            brush = tool.brush,
            modifier = Modifier.fillMaxSize().background(Color.White),
            strokeToView = pageToView,
            renderer = renderer,
            onStrokeFinished = { stroke ->
                val authored = tool.complete(
                    stroke, UUID.randomUUID().toString(), pageId,
                    completed.size, System.currentTimeMillis(),
                )
                completed += authored
                save(authored.row)
            },
        ) {
            completed.forEach { drawInk(renderer, it.stroke, pageToView) }
        }
    }
}
```

The caller allocates sequence numbers from its own database when writing to an existing page.
The example uses list size only for a new page. Saving may enqueue work on the application's
repository; the callback runs on the UI thread. Existing stored rows are not rewritten.

Create a highlighter tool with
`ViveInkTool(ViveBrushes.HIGHLIGHTER, colorArgb = 0x80ffe000.toInt(), sizeDp = 18f)`.
Highlighters use DISCARD self overlap, store stabilization 0 and never follow theme colour.
Calligraphy ids come from `ViveBrushes.calligraphy(0..5)`; stabilization levels are 0..5.
`ViveInkTool.complete` rejects a stroke drawn with another tool's brush, preventing inconsistent
brush metadata in stored rows. Size and input coordinates are in the same page units.

The drawing surface captures its brush, transform and completion callback at pointer down.
Changing a tool midway affects the next gesture. Add completed strokes to drawing state in the
callback to replace the live stroke in the same UI update. Disabling or removing the surface
cancels the current gesture. `controller.cancel()` also discards it without invoking completion.
Only one drawing surface may use a controller at a time; `close()` releases its native references
and prevents reuse, while `cancel()` permits another gesture.

Compose mouse and touch input omit pressure rather than storing the default synthetic value.
Stylus input uses reported pressure. Historical observations preserve coordinates and event
timestamps; since Compose history has no pressure field, they retain the last measured pressure.
`InkInputSource.subscribe` is the adapter boundary for native pen APIs. Supply it to the surface
to replace Compose pointer input. Deliver Begin/Move/Finish/Cancel on the UI thread, and release
device listeners when the returned subscription closes. The first sample sets pressure
availability and tool type for the gesture. No prediction samples are fabricated.

Ink shape updates are scheduled only when pending inputs or timed behaviors require a frame.
Completion calls `finishInput` and then rebuilds the immutable stroke from its real inputs through
Ink's stored-stroke constructor. At the pinned release an incremental mesh can freeze slightly
different derivative-dependent vertices, so this boundary establishes the geometry used on reload.
Input storage also quantizes values; exactness checks use representable samples and explicit
geometry/pixel metrics where quantization matters. The uniform path renderer retains its documented
limits for per-vertex opacity, prediction fading, textures and ACCUMULATE paints.

The headless straight-marker case measures live-to-immutable vertex packing at 0.012878418 page
units (within the 0.25 Ink geometry epsilon), affecting seven antialiased pixels by at most 2/255.
Its finished stroke and decoded stored row have identical outlines and raster pixels. The tests
keep these two boundaries separate, so storage changes cannot hide behind a live-render tolerance.

A settled pressure-pen V1 case (stabilization 1, mouse, size 10) measures 0.28609192 page units
between incremental and one-shot outline vertices and segments. Mesh epsilon is a simplification
tolerance, not a bound on that modeling difference. `pressure-pen-fidelity.json` records the exact
inputs and update sequence; completion returns the canonical reconstruction rather than that
incremental mesh. The 240 family/stabilization/tool reconstruction cases retain strict shape checks.

For large finished pages, construct an `InkScene` from `InkSceneStroke` values once per page state.
Scene transforms and the spatial index are reusable across pan and zoom; the renderer retains a
bounded path cache. Scene entries keep projection transforms, colour overrides and drawing order.
Use `val pageRaster = rememberInkSceneRasterCache()` and
`drawCachedInkScene(pageRaster, scene, renderer, pageToView)` in the drawing block to retain one
transparent viewport image beneath the live stroke. It rebuilds when the scene, view transform or
pixel dimensions change, then composites the image without walking strokes on subsequent frames.
The Compose helper closes it on removal; a directly constructed `InkSceneRasterCache` must be
closed by its owner. Its retained pixel storage is bounded by viewport width × height × 4 bytes.
Keep new strokes in a small separately cached scene to avoid rebuilding the whole page after
each gesture. Call `renderer.clearCache()` when releasing a page.

Full-page Fit can expose every shape at once. Raw rendering of the synthetic 40k page takes about
300 ms on the measured Linux machine and exceeds the default 2,048-shape path cache. The viewport
image removes that repeated cost while drawing in a fixed view; the initial render and changed
pan/zoom views still pay the visible-stroke rendering cost. This is a measured limitation, and the
benchmark records raw Fit, cache generation and cached composition separately.

At 512 × 512 pixels, the measured Linux synthetic 40k page takes 0.083 ms P95 to composite and
0.131 ms P95 for controller input, cached page composition and live-stroke rasterization together.
The cache retains 1 MiB and performs zero background path or raster rebuilds during those live
frames. Initial generation takes 255 ms and changed views about 249 ms. Windows JBR 25 under Wine
measures 0.136 ms P95 for the same live workload; this does not establish Microsoft Windows VM
acceptance. These timings exclude physical input delivery and display latency.

The viewport cache preserves pixels exactly on transparent destinations in the measured three-view
fidelity case. Compositing that layer over opaque backgrounds changes colour channels by at most
1/255, and over a translucent background by at most 2/255, due to premultiplied 8-bit rounding.
`raster-cache-fidelity.json` records all twelve comparisons without changing the renderer tolerance.

## Validation and sample

`./gradlew :samples:viewer:run` opens a blank drawing page. Pen, Calligraphy, Highlighter and
Pan controls also work on an opened notebook. Drawings in this sample are unsaved previews; the
completion callback receives their Android-compatible rows. Notebook save/import validation is
a separate workflow.

`./gradlew build` includes headless Desktop pointer/raster tests and
`:byteink-testing:performanceCheck`. The latter loads 40,000 independently decoded synthetic
strokes, verifies spatial culling and bounded/reused caches, measures raw and cached full-page Fit
and live sample-to-raster CPU times, and checks repeated authoring native-peer cleanup. Reports are written to
`byteink-testing/build/reports/performance/`; headless UI frame timings are in
`byteink-compose/build/reports/performance/interaction.json`.

Use `-PbyteinkPerformanceNotebook=/absolute/path/notebook.vive` for an optional private notebook
measurement. It separately reports the largest real page and a virtual scene of the notebook's
pages; notebook contents and derived artifacts remain outside version control. Timing ceilings
detect gross regressions across CI machines. Headless raster timing measures scheduling and CPU
rendering, not hardware input delivery or physical display latency.

The implementation follows the pinned sources and the
[Ink InProgressStroke lifecycle](https://developer.android.com/reference/androidx/ink/strokes/InProgressStroke).
