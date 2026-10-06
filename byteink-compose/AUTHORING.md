# Desktop stroke authoring

Use `com.vivenotes.byteink:byteink-compose:0.1.0-SNAPSHOT` for the drawing surface and
`com.vivenotes.byteink:byteink-kit:0.1.0-SNAPSHOT` for catalog tools and stored rows.
The real Ink engine and packaged native loader arrive transitively. The consuming application
supplies Compose Desktop's platform runtime and enables JVM native access.

## Native capture and immediate Skia authoring

The `byteink-compose` artifact includes real native capture on Windows x86_64 (`WM_POINTER`) and
Linux x86_64 (XInput2 for X11/XWayland, tablet-v2 for native Wayland on JBR). It uses the existing
pinned Ink engine and native libraries. No additional native binary or Java dependency is required. Launch Java 25+ with
`--enable-native-access=ALL-UNNAMED`, as required for Ink/Skiko and the FFM capture adapters.

Use the dedicated surface for authoring without a Compose frame-clock wait:

```kotlin
val finished = remember { mutableStateListOf<Stroke>() }
val renderer = remember { InkMeshRenderer() }
DisposableEffect(renderer) { onDispose { renderer.clearCache() } }

InkLowLatencySurface(
    brush = selectedBrush,
    modifier = Modifier.fillMaxSize(),
    strokeToView = pageToLocalPixels,
    renderer = renderer,
    onStrokeFinished = { pointerId, stroke -> finished.add(stroke) },
    drawContent = { canvas, _, _ ->
        finished.forEach { renderer.render(canvas, it, pageToLocalPixels) }
    },
)
```

Add the completed stroke to the finished scene synchronously in the callback to preserve the
handoff before the next draw. For large pages, supply the existing scene/raster cache through
`drawContent` instead of traversing every stroke. The surface owns its authoring session and
native subscription; a supplied renderer is borrowed. It releases its own renderer cache on
disposal. `InkLowLatencyPanel` provides the same surface directly to Swing hosts.

Native packets retain chronological real history and measured pressure. Projected pen tilt is
converted into the Ink polar tilt and shaft azimuth; Windows barrel twist is not shaft orientation.
Native pixels are converted to local coordinates using the current content scale. Each down freezes
its brush, optional-axis availability, page transform and completion callback. Orientation maps
through the inverse transform. Missing axes stay absent, or hold the last real value if the gesture
started with that axis. Physical length is populated only when the host supplies calibrated
`centimetersPerNativePixel`; logical DPI does not establish physical size. A scalar physical length
is omitted under anisotropic scaling or shear.

Each pointer has an independent real Ink engine and predictor. The default forecast holds measured
axes, caps its horizon at 24 ms and displacement at 32 local pixels, and resets on reversals, pauses
and tool changes. The session targets 12 ms ahead. Replacements and new real observations retract
the old speculative tail. A stopped-device forecast expires through the surface's timer. Finish
always removes prediction and reconstructs the canonical stroke from real inputs before encoding.
`InkAuthoringSession(predictorFactory = null)` disables forecasts; custom predictor factories and
native sources can supply other models. `InkInputEvent.Batch` carries chronological real inputs and
a replacement forecast; `Predict` can replace the forecast separately.

For a Compose canvas managed by the host, `InkDrawingSurface` remains available. Its single-controller
overloads choose the first active pointer and safely ignore other pointers until completion. They
accept native history, angles and prediction through `inputSource`; their geometry updates follow
the Compose frame clock. `InkAuthoringSession` supports simultaneous pointers and can also be driven
directly by a host render loop. Use `advanceNow` and `needsAnimationTick` for timed behavior and
forecast expiry, or `advance` with an explicit event-clock timestamp.

`NativeInkInputSource(component, windowHandle, ...)` attaches to a live drawing-area HWND/XID after
`addNotify`. Subscribe and close on AWT EDT; event delivery is serialized there. Linux capture owns
its X connection and follows dynamically created Skia child windows. Windows capture reads history
on the native message thread before it expires, then queues owned observations to EDT. Promoted
pen/touch mouse input is excluded. Disabling/removing the surface, loss of focus, capture loss and
device changes cancel affected gestures. Closing a subscription prevents queued or retained late
callbacks. The source does not intercept input from other application windows.
XInput2 touch selection is exclusive per window; where the toolkit already owns it, the adapter
preserves pen/mouse selection and captures touch on drawing child windows where selection is available.

For native Wayland, use JBR 25 with its Wayland toolkit and launch with:

```text
-Dawt.toolkit.name=WLToolkit
--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

The built-in source reports `LINUX_WAYLAND_TABLET` and binds tablet-v2 and `wl_touch` on a private
event queue of JBR's existing display connection. It borrows the connection and window surface;
closing an adapter releases only its own proxies, queue, reader and callbacks. The compositor must
advertise tablet-v2 for pen capture. Primary mouse input comes from AWT without synthetic pressure.
Tablet frames preserve measured pressure and projected tilt; barrel rotation stays separate from
shaft azimuth. Multiple tools and touch contacts have independent lifetimes. Pad buttons, rings
and strips are accepted by the protocol binding but do not select brushes or author strokes.

Wayland coordinates are surface-local units, mapped on EDT through the current JBR peer scale and
the component's offset. Down outside the drawing component is ignored; an accepted stroke keeps
its captured pointer when it moves outside. For a directly constructed `NativeInkInputSource`,
pass the live top-level `wl_surface`, after JBR configures the visible window, as `windowHandle`.
Hiding and showing a window replaces that surface: recreate a direct source. The built-in panel
waits for configuration and automatically reconnects. It also cancels on proximity loss, device
removal, focus loss and disable/disposal. Hosts using another Wayland runtime can supply their own
`InkInputSource`.

The dedicated layer coalesces packet bursts into one direct Skia recording/presentation request,
uses double buffering and disables vsync throttling on Windows/X11. Native Wayland uses Skiko's
`SkiaSwingLayer` with software rasterization and immediate EDT painting, because the pinned
heavyweight `SkiaLayer` requires X11 on Linux. Both paths use the same mesh renderer, authoring
session and predictions. This removes the Compose frame-clock wait;
the desktop compositor still controls scanout. It does not implement Android's front-buffer API
or establish physical input-to-display latency. `lastInputToRenderNanos` measures software handler
to Skia recording only. Skia's heavyweight Swing integration also has the usual SwingPanel z-order
and clipping constraints; use the regular canvas surface when those constraints matter.

Verification:

```sh
./gradlew :byteink-compose:test
LIBGL_ALWAYS_SOFTWARE=1 xvfb-run -a ./gradlew :byteink-compose:desktopPenTest
# Native Wayland: install Weston, matching libweston development headers, wayland-scanner,
# a C compiler, pkg-config and Python 3. The script creates and stops a private headless compositor.
bash byteink-compose/src/test/wayland/run.sh -PbyteinkTestJavaHome=/absolute/path/to/jbr25
# Repeat at 2x compositor scaling:
BYTEINK_WAYLAND_TEST_SCALE=2 bash byteink-compose/src/test/wayland/run.sh -PbyteinkTestJavaHome=/absolute/path/to/jbr25
# Windows desktop:
./gradlew.bat :byteink-compose:desktopPenTest --no-daemon
```

The native-window suite injects XTEST/Win32 mouse input, checks capture cancellation/resubscription,
and exercises direct mesh rendering, burst coalescing and canonical finished-scene handoff. Its
Windows-only case uses the OS synthetic pen API to verify actual WM_POINTER pressure and tilt
retrieval. The Wayland suite uses C-generated protocol metadata to send real tablet-v2/touch wire
events to JBR windows, verifies pressure, tilt, history, prediction removal, pad child bindings,
simultaneous contacts, bounds filtering, device removal, hide/show, independent windows, repeated
hotplug during detach, visible wet/dry pixels and immediate Skia handoff. Real `wl_pointer` mouse events are delivered
through JBR and AWT. These tests do not replace a physical tablet and display-latency measurement.

The vendored tablet-v2 XML retains its MIT license and is pinned by SHA-256 in `WaylandProtocol`
and `src/test/wayland/generate.py`. To update it, review upstream protocol changes, update both pins,
run the generator and repeat the native suite. `generate.py --check` rejects stale Java metadata;
the test server is generated independently by `wayland-scanner` from the same XML.

Run the Windows suite in a logged-in desktop session. Windows denies OS pen injection from
SSH/service session 0; use `--no-daemon` so the test worker inherits the interactive session.

API references: [Windows pen history](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-getpointerpeninfohistory),
[pen axes](https://learn.microsoft.com/en-us/windows/win32/api/winuser/ns-winuser-pointer_pen_info),
[XInput2 protocol](https://www.x.org/releases/current/doc/inputproto/XI2proto.txt),
[pinned SkiaLayer](https://github.com/JetBrains/skiko/blob/v0.150.1/skiko/src/awtMain/kotlin/org/jetbrains/skiko/SkiaLayer.awt.kt),
[tablet-v2 protocol](https://gitlab.freedesktop.org/wayland/wayland-protocols/-/blob/main/stable/tablet/tablet-v2.xml),
[Wayland queue ownership and reading](https://wayland.freedesktop.org/docs/html/apb.html),
[JBR Wayland display](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr25/src/java.desktop/unix/classes/sun/awt/wl/WLDisplay.java).

## Compose canvas authoring

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
`InkInputSource.subscribe` accepts native pen input in place of Compose pointer input. Deliver
events on the UI thread and release device listeners when the subscription closes. The first
sample freezes tool type and optional-axis availability. The controller accepts tilt, orientation
and replaceable prediction batches; this canvas does not generate a forecast automatically.

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
