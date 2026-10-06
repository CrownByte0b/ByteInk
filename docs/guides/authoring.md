# Draw and capture input

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
| `inputSource` | Optional native adapter; default Compose input when `null` |
| `onStrokeFinished` | Captured completion callback; retain/store its `Stroke` |
| `drawContent` | Finished ink/paper drawn before the wet stroke |

Changing a tool or transform during a gesture does not alter its captured brush/transform. Explicitly call `cancel()` on a page/tool switch if the old gesture must be discarded. Removing or disabling the surface cancels it automatically. `rememberInkAuthoringController()` remembers a controller; **the owner still closes it**.

## Controller lifecycle

For application-managed input, use the same controller directly:

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Controller.kt"
```

| Call/state | Behavior |
| --- | --- |
| `begin(brush, sample, strokeToView)` | Starts a gesture and makes the first dot visible |
| `append(sample)` | Buffers input; returns `false` for idle, wrong-tool, older or duplicate observations |
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

## Pressure and device adapters

`InkPointerSample(x, y, uptimeMillis, toolType, pressure)` uses local surface pixels. Coordinates must be finite; time nonnegative; measured pressure must be finite in `[0, 1]`. Mouse and touch use `pressure = null`.

If pressure is absent at `begin`, the whole gesture stays pressure-free. If present, later missing values hold the last measured value. Calligraphy uses speed when pressure is unavailable.

An `InkInputSource` delivers `Begin`, `Move`, `Finish`, and `Cancel` serially on the Compose UI thread. Return a subscription that stops callbacks when closed. Preserve real event timestamps and tool/pressure data; report one active gesture at a time. Pass the adapter through `inputSource` to bypass Compose's pointer path.

Hardware pressure depends on what the desktop platform/Compose reports; a native adapter can supply it through this seam.

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
