# Draw and capture input

Choose the host according to how your application receives and presents input:

| Host | Input and presentation |
| --- | --- |
| `InkDrawingSurface` | Compose primary-pointer input and frame clock; one active gesture; path renderer by default |
| `InkLowLatencySurface` | Compose wrapper around the native panel; simultaneous pointers, prediction and direct Skia painting; mesh renderer by default |
| `InkLowLatencyPanel` | The same native authoring in a Swing application; configurable predictor factory |
| `InkAuthoringController` / `InkAuthoringSession` | Application-managed events and rendering; one pointer / simultaneous pointers |

## Compose surface

`InkDrawingSurface` handles primary mouse/touch/stylus gestures and frame updates. Render finished strokes in `drawContent`; retain the new stroke in `onStrokeFinished` so it stays visible after release.

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Drawing.kt"
```

The example renders page dp at device density, serializes each completed stroke, and displays its canonical geometry. It returns new rows through `onRowReady`; your repository persists them.

| Surface parameter | Use |
| --- | --- |
| `controller` | One controller per attached surface |
| `brush` | Native brush captured at pointer down |
| `modifier` | Layout size, background and other Compose modifiers |
| `strokeToView` | Page-to-local-pixel transform; include density and zoom |
| `renderer` | Defaults to path rendering; pass `rememberInkMeshRenderer()` for mesh effects, textures and prediction shading |
| `enabled` | `false` cancels and disables authoring |
| `inputSource` | Optional serial input adapter; default Compose input when `null` |
| `onStrokeFinished` | Captured completion callback; retain/store its `Stroke` |
| `drawContent` | Finished ink/paper drawn before the wet stroke |

Changing a tool or transform during a gesture does not alter its captured brush/transform. Explicitly call `cancel()` on a page/tool switch if the old gesture must be discarded. Removing or disabling the surface cancels it automatically. `rememberInkAuthoringController()` remembers a controller; **the owner still closes it**.

`InkDrawingSurface` keeps the first active pointer and ignores other pointers until that gesture ends. It forwards explicit predictions from an input source but does not create a predictor or expire a forecast automatically. Use a session or the direct surfaces below for simultaneous pointers and managed prediction expiry.

## Native low-latency surfaces

`InkLowLatencySurface` and `InkLowLatencyPanel` acquire native input when `inputSource = null`. Real device history reaches an `InkAuthoringSession`, followed by a coalesced direct Skia render request on the AWT event dispatch thread (EDT). Windows/X11 use a dedicated double-buffered Skia layer with vsync throttling disabled; native Wayland uses immediate software Swing painting. Timed brush behavior and forecast expiry schedule additional updates; settled ink does not require a continuous polling paint loop.

This example embeds the native surface in a Swing-hosted Compose application and retains completed ink synchronously:

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/NativeDrawing.kt"
```

| Direct surface parameter | Use |
| --- | --- |
| `brush`, `strokeToView`, `onStrokeFinished` | Captured independently at each pointer's Begin |
| `strokeToView` | Maps stroke/page units to **AWT component-local logical units**; the panel applies device scale |
| `renderer` | `null` creates an owned `InkMeshRenderer`; a supplied renderer remains caller-owned |
| `inputSource` | `null` acquires native capture; a supplied source overrides it |
| `onStrokeFinished` | Receives `(pointerId, Stroke)` with only real canonical inputs |
| `drawContent` | `(Canvas, width, height)` callback before live ink; dimensions and canvas coordinates are AWT logical units |
| `centimetersPerNativePixel` | Optional measured physical calibration; leave `null` when unknown |

Do not copy the density multiplication from the regular Compose example into this surface: native coordinates are already divided by the panel's device scale, and its canvas applies that scale during painting. Include page zoom/scroll in `strokeToView`, while keeping input and finished content in the same logical coordinate space.

Construct, update and close a panel on the EDT. Add completed strokes to finished content inside the captured completion callback, then enqueue database work on a worker thread. When updating finished content outside an input callback, call `panel.requestInkRender()`. The Compose wrapper requests redraws as it updates its panel.

Native Wayland retains finished content and a composed physical frame. Triangle mesh paints restore damaged pixels from the background and redraw overlapping live strokes in their original order. Filled outlines (`InkPathRenderer` and mesh-renderer DISCARD coats, including highlighters) restore the full old/current wet extent on geometry changes. Damage also expands across intersecting outlined strokes: antialiasing can change prefix coverage outside native mesh damage or through a partial clip. Prediction shrink/expiry and cancellation erase old pixels; completion refreshes the finished background. An OS repaint presents the existing frame. Keep `drawContent` stable until an explicit change: call `requestInkRender()` after modifying a scene, partial erase/exclusion, view, texture or animation outside completion, even when the callback object stays the same. Changing `drawContent` or `clearColorArgb` invalidates the background; request a render to show the change immediately.

`panel.retainedPixelBudgetBytes` defaults to **64 MiB for both physical N32 rasters combined** (8 × physical width × physical height). A zero budget disables retention. Oversized viewports and custom renderers use full redraw; the built-in `InkMeshRenderer` and `InkPathRenderer` support retention. Detach, disable and close release the buffers. Hidden or disabled panels still paint finished content without retaining pixels; showing or reenabling restores the configured budget. Windows/X11 keep their existing presentation path.

`retainedPixelBytes` reports retained raster storage. `retainedBackgroundBuildCount`, `retainedFullRedrawCount` and `retainedDirtyRedrawCount` count raster rebuilds; `retainedLastRedrawnPixelCount` reports restored/rasterized physical pixels in the last paint. These counts exclude the full-window Skiko presentation copies. The panel consumes and resets its session's native updated-region accumulator after successful painting; custom consumers may read borrowed live engines but must leave their mutation and damage reset to the panel.

Mesh preparation separately reuses verified unchanged wet vertices, triangle chunks and texture-free shaders. It compares full owned snapshots and invalidates chunks whose indices or referenced attributes changed, including prediction rollback and earlier vertex mutations. Linear transforms, color and atlas changes invalidate preparation keys. This reduces preparation/allocation within dirty paints; full native export and whole-window presentation copies remain. See [mesh cache behavior](rendering.md#live-cache-retirement) for byte accounting and disposal.

The built-in panel retires live renderer caches on completion/cancellation and reconnects native capture across attachment and Wayland hide/show. Closing it also closes its session and presentation resources. It closes only its own default mesh renderer; close a supplied mesh renderer yourself, or use `rememberInkMeshRenderer()` for automatic composition disposal. See [renderer ownership](rendering.md#live-cache-retirement).

`InkLowLatencyPanel` accepts `predictorFactory = null` to disable its automatic forecasting or a factory to replace it. `InkLowLatencySurface` uses the built-in predictor and does not expose that constructor option.

`lastInputToRenderNanos` measures packet handling through Skia recording. It excludes time before the handler, pixel transfer, compositor presentation and physical pen-to-photon latency. `renderedFrameCount` includes OS repaint requests; `processedPacketCount` counts packets rather than individual observations inside a `Batch`.

## Native platform setup

| Platform/backend | Requirements |
| --- | --- |
| Windows x86_64 / `WINDOWS_POINTER` | Java 25+, `WM_POINTER` pen/history, touch and primary mouse |
| Linux X11 or XWayland / `LINUX_XINPUT2` | Java 25+, `libX11.so.6`, `libXi.so.6`, `libc.so.6` |
| Linux native Wayland / `LINUX_WAYLAND_TABLET` | Tested JBR 25 with WLToolkit, `libwayland-client.so.0`, `libc.so.6`, a live compositor; tablet-v2 for pen axes, `wl_touch` for touch |

All native capture requires `--enable-native-access=ALL-UNNAMED`. For native Wayland, launch **JBR 25** with these additional arguments before AWT initializes:

```text
-Dawt.toolkit.name=WLToolkit
--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
```

Connect to a reachable compositor: use its `WAYLAND_DISPLAY`, or the default `$XDG_RUNTIME_DIR/wayland-0` socket when that variable is unset. Backend selection follows the actual AWT toolkit: running an ordinary Linux JVM on a Wayland desktop can still use X11/XWayland. Missing tablet-v2 leaves touch and primary mouse available without fabricating pen pressure. The toolkit flag follows [JetBrains' Wayland startup guidance](https://blog.jetbrains.com/platform/2024/07/wayland-support-preview-in-2024-2/) and [JBR 25 toolkit selection](https://github.com/JetBrains/JetBrainsRuntime/blob/jbr25/src/java.desktop/unix/classes/sun/awt/PlatformGraphicsInfo.java); the package-opening and native-access arguments are ByteInk's additional requirements.

For native Wayland Compose embedding, use the example's `JFrame` with `ComposePanel(renderSettings = RenderSettings.SwingGraphics())`. The tested Compose/Skiko heavyweight host assumes X11; use this Swing graphics host or a direct Swing `InkLowLatencyPanel` for WLToolkit. SwingGraphics is experimental off-screen presentation and adds a copy whose cost grows with panel size; ByteInk separately selects software painting for its Wayland ink panel. [Compose's Swing interoperability guide](https://kotlinlang.org/docs/multiplatform/compose-desktop-swing-interoperability.html#experimental-off-screen-rendering) describes this host option. Ordinary Compose windows can continue to use the regular surface under X11/XWayland.

`NativeInkInputSource` is the lower-level adapter for custom hosts. Subscribe and close on the EDT, use a displayable component and its matching live native handle, and allow one subscription per source. Windows/X11 use the drawing component's HWND/XID; Wayland requires its visible, configured top-level `wl_surface`. Recreate custom Wayland sources after hiding/showing the window, since the native surface changes. The built-in panel handles that lifecycle and waits for configuration. Native acquisition failures are explicit; runtime failures cancel gestures, close capture and invoke `onFailure`.

## Controller lifecycle

For application-managed input, use the same controller directly:

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Controller.kt"
```

| Call/state | Behavior |
| --- | --- |
| `begin(brush, sample, strokeToView)` | Starts a gesture and makes the first dot visible |
| `append(sample)` | Buffers input; returns `false` for idle, wrong-tool, older or duplicate observations |
| `setPredictedInputs(samples)` | Replaces the speculative tail; an empty list clears it |
| `hasPendingInputs` | Schedule a frame even when native shape state has not changed yet |
| `isUpdateNeeded()` | Buffered input or timed brush behavior needs a frame |
| `advance(uptimeMillis)` | Processes buffered input once; returns whether geometry needed updating |
| `revision` | Read in the draw block to observe processed geometry changes |
| `liveStroke` | Current wet stroke; read it on the controller's thread and leave mutation to the controller |
| `strokeToView` | Frozen transform for the active gesture |
| `finish(sample?)` | Settles inputs and returns independent canonical `Stroke`, or `null` when idle |
| `cancel()` | Discards active ink; safe while idle |
| `close()` | Discards input and releases references; controller cannot restart |

Custom frame loops must observe **both** `isDrawing` and `hasPendingInputs`, then use `isUpdateNeeded()`. `append()` alone does not advance geometry. Keep event and frame times in the same monotonic millisecond clock; passing epoch milliseconds to `advance` is incorrect.

If you supply predictions directly to a controller, retract them when their horizon expires and schedule an advance. Real input and completion also clear the previous speculative tail. Retire the controller's renderer cache before clearing or recycling its live engine.

## Simultaneous pointers and prediction

An `InkInputSource` emits events serially on the UI thread; native sources deliver on the EDT. Return a subscription that prevents callbacks after closing. Preserve original timestamps, all accepted real history and measured device axes.

| Event | Behavior |
| --- | --- |
| `Begin(sample, pointerId = 0L)` | Captures a new gesture; a session replaces any old gesture with that ID |
| `Move(sample, pointerId = 0L)` | Buffers a real observation for that pointer |
| `Batch(samples, predictedSamples = null, pointerId = 0L)` | Buffers real history; a session generates a forecast when predictions are `null`, or uses the supplied replacement |
| `Predict(samples, pointerId = 0L)` | Replaces the forecast without adding real observations |
| `Finish(sample = null, pointerId = 0L)` | Clears predictions and completes the real-input stroke |
| `CancelPointer(pointerId)` | Discards only that pointer |
| `Cancel` | Discards all active pointers |

An empty prediction list clears the speculative tail. Predictions never become saved inputs, even when the pointer lifts before a forecast expires. The regular `InkDrawingSurface` forwards only explicitly supplied forecasts; it does not generate the session forecast described in the table.

When updating an existing adapter, add `Batch`, `Predict` and `CancelPointer` branches to exhaustive `when` expressions. Calls that omit the new sample axes and pointer IDs retain their source defaults. Recompile JVM consumers against the updated library: the data-class constructor signatures have changed.

This example advances two pointers independently, replaces predictions and completes both strokes from real inputs:

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Session.kt"
```

`InkAuthoringSession` creates one controller/predictor per gesture. Its defaults are a 12 ms horizon and `InkLinearPredictor`, whose horizon/distance limits default to 24 ms and 32 input-coordinate units. The predictor holds measured optional axes and bounds position extrapolation; it resets motion prediction after reversals, stationary observations, tool changes or long gaps. Pass `predictorFactory = null` or `predictionMillis = 0L` to disable automatic forecasting; explicitly supplied predictions still work.

Custom session loops call `advance(uptimeMillis)` on the event clock or `advanceNow()` on the mapped monotonic wall clock. Schedule while `needsAnimationTick()` is true, including when `isUpdateNeeded()` is false but a forecast must expire. Render each borrowed `liveStrokes` entry with its captured `strokeToView`; never mutate or retain its engine across completion/cancellation/reuse. [Retire cached live geometry](rendering.md#live-cache-retirement) before those lifecycle operations. Closing a session is terminal.

## Pressure and device adapters

`InkPointerSample` uses the input surface's local units: pixels on the regular Compose canvas, AWT logical units on direct panels. Coordinates must be finite and monotonic time nonnegative. Preserve an axis only when the device reports it; primary mouse/default touch omit synthetic pressure. Some native touch devices can report measured pressure.

| Optional sample field | Valid value |
| --- | --- |
| `pressure` | Finite `[0, 1]`, or `null` |
| `tiltRadians` | Finite `[0, π/2]` from the surface normal, or `null` |
| `orientationRadians` | Finite `[0, 2π)` shaft azimuth from local +x toward +y, or `null`; distinct from barrel twist |
| `strokeUnitLengthCm` | Calibrated finite positive centimeters per input-surface unit, or `null` |

Optional-axis availability and tool type are frozen at `begin`. An absent axis stays absent for that gesture; later missing values for an initially present axis hold the last measured value. Calligraphy uses speed when pressure is unavailable. The controller maps shaft orientation through the inverse captured transform.

Native adapters derive `strokeUnitLengthCm` from supplied `centimetersPerNativePixel` and device scale. Logical DPI is insufficient for physical calibration. The controller converts that scale into stroke units for uniform/rotated transforms, and omits it for anisotropic or sheared transforms rather than inventing a scalar.

## Brush choices

```kotlin
val marker = ViveInkTool(familyId = ViveBrushes.MARKER, sizeDp = 3f)
val dashed = ViveInkTool(familyId = ViveBrushes.DASHED_LINE, sizeDp = 3f)
val nib = ViveInkTool(
    familyId = ViveBrushes.calligraphy(pressure = 3),
    stabilization = 2,
    sizeDp = 6f,
)
val highlight = ViveInkTool(
    familyId = ViveBrushes.HIGHLIGHTER,
    stabilization = 0,
    colorArgb = 0x80ffff00.toInt(),
    sizeDp = 18f,
    colorFollowsTheme = false,
)
```

| Stabilization | Model |
| --- | --- |
| `0` | Passthrough |
| `1`, `2`, `3`, `4`, `5` | Sliding windows of 20, 40, 60, 90, 120 ms at 180 Hz |

Highlighter uses its pinned stock input model and stores `0` as “not applicable.” `ViveInkTool` validates levels `0..5` and known authoring families; `ViveBrushes` lookup helpers clamp levels and provide legacy fallbacks.

[Complete input/surface parameters](../reference/authoring.md) · [Complete brush/tool parameters](../reference/brushes.md)
