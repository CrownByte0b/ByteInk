# WebAssembly preview

`byteink-portable` supplies a common JVM/Wasm API for ViveNotes brushes, stored strokes, erase
masks, move/resize operations and page replay. Its Wasm adapter calls `byteink-wasm`, which runs
the pinned Google Ink C++ engine in a browser or Node. Both preview modules are experimental
source-checkout artifacts; they are **not included in `0.1.0-alpha.1`**. The opt-in
`byteink-compose-wasm` artifact adds Compose rendering; browser input lives in `byteink-wasm`.

The existing JVM APIs and coordinates remain available. Portable settings and stored-row types
retain their `com.vivenotes.byteink.kit` package and are re-exported from `byteink-model`.
The facade lives in `com.vivenotes.byteink.portable`; the lower-level live/finished engine API
lives in `com.vivenotes.byteink.wasm`. Browser normalization and authoring sessions live in
`com.vivenotes.byteink.wasm.input`; the Wasm renderers use `com.vivenotes.byteink.compose`.

## Prepare the preview artifacts

From a ByteInk checkout on Linux, with a JDK, CMake, Ninja, Python and Node 22+ installed:

```sh
native/setup-wasm.sh
native/build-wasm.sh
./gradlew -PbyteinkWasm=true \
  :byteink-model:publishAllPublicationsToBuildRepository \
  :byteink-portable:publishKotlinMultiplatformPublicationToBuildRepository \
  :byteink-portable:publishWasmJsPublicationToBuildRepository \
  :byteink-wasm:publishAllPublicationsToBuildRepository \
  :byteink-compose-wasm:publishAllPublicationsToBuildRepository
```

`JAVA_HOME` must point to the JDK for the native build. The scripts download the pinned SDK and
sources into ignored build directories. They use the same engine commit, Android angle/magnitude
patches and C++ dependency versions as desktop. Kotlin compilation alone does not build C++.

Add the checkout's `build/repo` Maven repository to your consumer's settings, then depend on:

```kotlin
kotlin {
    wasmJs { browser() }
    sourceSets.commonMain.dependencies {
        implementation("com.vivenotes.byteink:byteink-portable:0.1.0-SNAPSHOT")
    }
    sourceSets.wasmJsMain.dependencies {
        implementation("com.vivenotes.byteink:byteink-compose-wasm:0.1.0-SNAPSHOT")
    }
}
```

Kotlin 2.4.21 and Compose Multiplatform 1.12.2 are the tested versions. The client is a Kotlin library; the C++ engine is a separate
static asset bundle. Its Maven classifier is `engine`, extension `zip`:
`com.vivenotes.byteink:byteink-wasm:0.1.0-SNAPSHOT:engine@zip`.
The same ZIP is generated under `byteink-wasm/build/distributions/`.

Extract the ZIP into an application static asset directory, for example `public/ink/`. Serve
`byteink-engine.mjs` and `byteink-engine.wasm` beside each other, retaining the bundled licenses.
Serve `.wasm` as `application/wasm` and `.mjs` as JavaScript. Use HTTP(S), including for local
development; a browser cannot load the module from a local `file:` page. The initial backend is
single-threaded and does not require cross-origin isolation or `SharedArrayBuffer`.
The current client requires **bridge ABI 3**. Replace both engine assets together when updating
the preview. Compose also packages its own Skiko Wasm runtime, separately from the Ink engine.

## Shared brushes, stored rows and replay

Initialize one backend per application, then pass the `InkEngine` to common application code:

```kotlin
// wasmJsMain, from a coroutine:
val engine = WasmInkEngine.load("/ink/byteink-engine.mjs")
// jvmMain: val engine = JvmInkEngine()
```

The following code uses only common APIs:

```kotlin
import com.vivenotes.byteink.kit.*
import com.vivenotes.byteink.portable.*

val kit = InkKit(engine)
val pen = PenPreset(kind = PenKind.Calligraphy, thickness = 4f, pressure = 3, stabilization = 2)
val row = engine.createStroke(InkStyle.forPen(pen), listOf(
    InkSample(10f, 30f, 0, InkTool.STYLUS, pressure = 0.3f),
    InkSample(90f, 30f, 80, InkTool.STYLUS, pressure = 0.8f),
)).use { stroke ->
    kit.encode(stroke, pageId = "page", seq = 0, pen = pen)
}

val erase = kit.reloadedEraseMask(listOf(
    InkSample(50f, 0f, 0), InkSample(50f, 60f, 60),
), sizeDp = 10f)!!.use { mask ->
    kit.encodeErase(mask, id = "cut", pageId = "page", mode = InkEraseMode.Normal,
        createdAt = row.createdAt + 1, targetIds = listOf(row.id))
}

kit.load("page", strokes = listOf(row), erases = listOf(erase)).use { page ->
    for (projection in page.strokes) {
        val geometry = projection.stroke.geometry()
        val strokeToPage = projection.transform
        // Render each piece with this transform. Persist the original rows and operations.
    }
    check(page.storedStrokes.single() == row)
}
```

`InkStyle.forPen` shares Android's solid/dashed/dotted selection, calligraphy levels 0–5 and
stabilization windows 0/20/40/60/90/120 ms. `InkStyle.forHighlighter(HighlighterSettings())`
preserves alpha and DISCARD self overlap. Unknown family IDs retain the pressure-pen fallback;
captured metadata keeps the original ID and unclamped setting values.

`encode` generates a UUIDv7 row ID. `encodeStroke` accepts application-owned IDs and timestamps;
`encodeCopy` preserves the source's family/version/stabilization/theme metadata. The returned
`StoredInkStroke`, `StoredInkErase` and `StoredInkMove` map to the same Android database fields
as the JVM kit. Applications still own their database, transactions, target IDs and undo history.

`encodeMove(InkLassoMove(...))` and `encodeResize(InkLassoResize(...))` write Android's
`ink/lasso-f32le1` path format. Replay applies operations by creation time and ID, targets only
their recorded row IDs, tests the lasso against current pieces, and clamps movement/resize at
the page origin. A combined move/resize selects again after translation. A Normal erase splits
opaque strokes into pieces; DISCARD highlighters remain one projection. An Object erase removes
only touched pieces, or the whole highlighter. Tombstoning an operation and reloading undoes it.
The order returned by the native split within one row is unspecified.

`InkPage` owns its source and replayed strokes. `strokes` and `sourceStrokes` borrow those
handles; call `stroke.copy()` for an independent owner that must outlive the page. Close copied
strokes and pages explicitly. Wasm closes the C++ owner immediately; the JVM adapter releases its
reference and AndroidX's native owner remains subject to garbage collection. Geometry arrays are
copied snapshots and survive close. `nativeStroke` on each adapter provides a borrowed backend
handle for platform rendering; keep its portable owner open and close only the owner.
Both adapters expose native point/box coverage and transformed stroke intersection queries.

Loading keeps snapshots of all supplied records, including tombstones, unreadable encodings,
unknown brush metadata and operations it cannot apply. Inspect `unreadable` and
`ignoredOperations` for diagnostics. Stored records remain available after page close. Retain
unknown database columns in application storage too. Decoding is not a lossless storage rewrite:
do not replace original input blobs with re-encoded ones or bake erased geometry into an input
row, since input encoding does not contain the erase cuts.

## Lower-level live and finished strokes

Use `byteink-wasm` directly when you need its live authoring API or arbitrary serialized families.
It is also a transitive dependency of the Wasm portable facade. Load the engine once from a
coroutine, using an absolute URL or an origin-relative path:

```kotlin
import com.vivenotes.byteink.wasm.*

val engine = InkWasm.load("/ink/byteink-engine.mjs")
val brush = InkBrush(colorArgb = 0xff263366.toInt(), size = 4f)
val samples = listOf(
    InkSample(10f, 20f, 0.0, InkToolType.MOUSE),
    InkSample(40f, 20f, 16.0, InkToolType.MOUSE),
)
val blob = engine.createStroke(brush, samples).use { stroke ->
    check(stroke.contains(25f, 20f))
    stroke.encodeAndroidInputs()
}
engine.decodeAndroidStroke(brush, blob).use { reloaded ->
    val geometry = reloaded.geometry()
    // Draw geometry, then close the stroke when it is no longer needed.
}
```

`load` propagates fetch, compilation and initialization errors and rejects a bridge ABI or engine
pin mismatch. Node accepts an absolute `file:` module URL. Each load creates an isolated engine
instance; normally reuse one instance for an application.

Persist the brush specification with the input blob. Raw input encoding (`encodeInputs` /
`decodeStroke`) uses the upstream `CodedStrokeInputBatch` protobuf without gzip.
`encodeAndroidInputs` / `decodeAndroidStroke` use ViveNotes' Android gzip envelope, including
the private zero animation phase field. Decoding checks the gzip CRC and bounds inflation to
64 MiB; trailing bytes and truncated streams are rejected.

Encoding quantizes inputs, as on desktop and Android. Keep existing stored bytes and unknown
row fields in application storage; decoding is not a lossless storage rewrite. Use the shared
`InkKit` above for complete ViveNotes rows and operation replay.

## Live input

```kotlin
engine.startStroke(brush).use { live ->
    live.enqueue(samples)
    live.update(16.0)
    val wetGeometry = live.geometry()
    live.finish(16.0).use { finished ->
        val saved = finished.encodeAndroidInputs()
    }
}
```

Pass incremental real samples to `enqueue`. Its optional `predictedInputs` list replaces the
speculative tail; an empty list clears prediction. Call `update` with nonnegative, nondecreasing
elapsed milliseconds before reading current geometry. `realInputCount` and `predictedInputCount`
describe samples consumed by the last update.

`finish` discards predictions and rebuilds the final geometry from real inputs, flushing the
stroke modeler even if the last rendered frame already consumed the final sample. This initial
implementation pays for a complete final rebuild. It returns a separate owned `InkStroke`;
close both it and the live object. Finishing twice or adding/updating inputs afterward fails.
Closing without finishing cancels the gesture.

## Compose rendering

Use `InkMeshRenderer` for the full canonical mesh, vertex opacity/HSL effects, derivative
antialiasing, prediction fade, textures and animation. It compiles the same vertex/fragment math
as the desktop renderer. ANY and ACCUMULATE paints draw mesh overlap; DISCARD uses one compound
path fill. `InkPathRenderer` offers uniform fills for texture-free ANY/DISCARD coats and rejects
ACCUMULATE paints. Path fills do not represent vertex effects or prediction fade.

```kotlin
import androidx.compose.foundation.Canvas
import androidx.compose.ui.Modifier
import com.vivenotes.byteink.compose.*

// In composition; engine is WasmInkEngine and page is an open InkPage.
val renderer = rememberInkMeshRenderer()
Canvas(Modifier) {
    for (piece in page.strokes) {
        drawInk(renderer, engine.nativeStroke(piece.stroke), piece.transform)
    }
}
```

Give the canvas a size using your application's layout modifiers. Coordinates use the drawing
canvas's pixel units; include density, zoom and scrolling in its canvas transform. `drawInk`
culls against the canvas bounds. The direct `renderer.draw(canvas, stroke, transform, viewport)`
overloads accept finished or live low-level strokes; `transform` is an `InkTransform`.

`rememberInkMeshRenderer` closes owned resources on composition disposal. For a manually created
renderer, call `close()`. Caches retain copied geometry and prepared shaders, bounded together
for finished/live entries by `cacheCapacity` (2048) and `cacheByteBudget` (64 MiB). The byte estimate
excludes object headers, provider images and driver allocations. `release(stroke)` retires one
entry; call it before closing/replacing a live stroke. `clearCache()` releases all geometry and
texture shaders, for example when leaving a page. Strokes and texture images remain caller-owned.

Pass `InkTextureStore { clientTextureId -> image }` with preloaded Skia images. The renderer borrows
images and retains its own shader references; clear its cache after replacing an ID. Missing images
select the next compatible paint preference, or fail before any coat is drawn. Texture wrapping,
rotation, offsets, brush/stroke units, first/last-input origins and all twelve pinned Ink texture
blend modes are supported. `textureCacheCapacity` defaults to 64 and must be positive.

Set `animationTimeMillis` and invalidate your drawing when advancing an atlas animation. The pinned
upstream brush protobuf **omits atlas layout/timing fields and cannot encode the animation-offset
behavior target**. Keep atlas definitions in application metadata and provide them explicitly:

```kotlin
val renderer = InkMeshRenderer(
    textureStore = InkTextureStore { id -> images[id] },
    textureAnimations = InkTextureAnimations { id ->
        if (id == "sparkles") InkTextureAnimation(
            frames = 4, rows = 2, columns = 2, durationMillis = 1000, reverse = true,
        ) else null
    },
)
```

Frames must fit the grid; frames ≤ 2²⁴, rows/columns ≤ 4096 and duration ≤ 2²⁴ ms. Zero duration
uses only per-vertex offsets. Without an override, the renderer uses the engine snapshot's
metadata. Development-version serialized families require explicit
`InkBrush.fromFamilyProto(..., allowDevelopmentVersion = true)`; the default rejects them.
That opt-in does not add missing upstream serialization fields. Persist texture definitions
separately; the row codec does not store them.

`stroke.rendering()` and `live.rendering()` return owned snapshots with 15 floats per vertex,
attribute-presence masks, indices, outlines, paints and texture metadata. `live.version` changes
on shape updates. `renderer.draw(canvas, snapshot)` can draw a snapshot after its engine closes.
`release(snapshot)` retires that snapshot entry.
Treat its arrays as immutable while cached, and clear the cache when retiring snapshots.

## Browser pen input

`attachBrowserInkInput` attaches by element ID and returns an `AutoCloseable` subscription.
The element must coincide with the drawing area and have axis-aligned CSS layout. The supplied
`BrowserInkViewport` maps its rectangle to drawing units and carries an invertible six-coefficient
page-to-surface transform. Its width/height and optional calibrated physical scale are captured
independently at each pointer-down. Device pixel ratio is accounted for by your chosen drawing
units; it is not applied a second time by the adapter.

```kotlin
import com.vivenotes.byteink.wasm.*
import com.vivenotes.byteink.wasm.input.*

val session = BrowserInkAuthoringSession(
    // engine is WasmInkEngine; use the low-level InkWasm directly if preferred.
    engine = engine.wasm,
    brushFor = { begin -> InkBrush(size = if (begin.eraser) 20f else 4f) },
    onFinished = { pointerId, finished ->
        finished.use { stroke -> save(pointerId, stroke.encodeAndroidInputs()) }
    },
    onChanged = { invalidateDrawing() },
    onRetire = { renderer.release(it) },
)
val input = attachBrowserInkInput(
    elementId = "drawing-area",
    viewport = { BrowserInkViewport(width = 960f, height = 500f) },
    listener = session::handle,
)
```

Applications supply `save` and `invalidateDrawing`. Render `session.liveStrokes` in order and call
`session.advance(performance.now())` from animation frames while `needsAnimationFrame` is true.
The [compiled sample source](https://github.com/crownbyte0b/ByteInk/blob/wasm/samples/wasm/src/wasmJsMain/kotlin/com/vivenotes/byteink/sample/WasmMain.kt)
shows Compose invalidation/frame scheduling, stored-row encoding and operation replay.
Close the input subscription before the session and renderer when disposing the view.

The adapter consumes coalesced pointermove history, falling back to the event itself when absent.
It uses advertised browser predictions as a replacement tail, holds measured axes during prediction,
and clears expired predictions after 64 ms by default. It preserves fractional event timestamps
until the engine's float duration boundary. The shared portable DTO still uses whole milliseconds;
use the low-level inputs when you need fractional observations.

Only pen observations record reported pressure and shaft tilt/azimuth. Mouse/touch synthetic
pressure is omitted; barrel twist is not treated as shaft orientation. Optional channel presence
is fixed per gesture and temporarily missing axes hold the last accepted real value. Orientation
is mapped through the inverse drawing transform. Physical centimeters per CSS pixel require an
application calibration; CSS's nominal DPI does not measure hardware size. A scalar physical
unit is omitted for anisotropic or sheared mappings.

Contacts have independent captured brushes and lifetimes. Capture loss, pointercancel, blur,
pagehide, hidden-document transitions and element resize/scroll cancel affected gestures without
saving them. Cancel active gestures before changing your viewport transform. Closing the subscription
removes listeners, releases capture and restores its previous inline `touch-action`. The authoring
session removes every prediction before finishing; its callback receives ownership of the finished
stroke, and callback failure closes that owner. Browser APIs are described in the
[W3C Pointer Events specification](https://www.w3.org/TR/pointerevents3/).

## Parameters and geometry

| Type / parameter | Meaning |
| --- | --- |
| `InkBrush.family` | `MARKER` (default), `PRESSURE_PEN`, `HIGHLIGHTER` or `DASHED_LINE`, all version 1. Highlighter uses DISCARD overlap. Null for a serialized family. |
| `colorArgb` | 32-bit ARGB, including alpha; default opaque black. |
| `size`, `epsilon` | Same units as input positions. Defaults 4 and 0.25; finite positive values, size at least epsilon. |
| `InkBrush.fromFamilyProto` | Copies an uncompressed upstream `BrushFamily` protobuf. Supports custom tips, behaviors and smoothing models without converting them to a stock family. Uses the same color/size/epsilon parameters. |
| `InkSample.x`, `y` | Finite stroke-space coordinates. Use one consistent space per gesture. |
| `elapsedTimeMillis` | Nonnegative finite `Double`, relative to gesture start. The C++ engine uses float duration precision. |
| `toolType` | `UNKNOWN` (default), `MOUSE`, `TOUCH` or `STYLUS`; keep consistent within a gesture. |
| `pressure` | Null when unavailable; otherwise finite and in [0, 1]. |
| `tiltRadians` | Null when unavailable; otherwise finite and in [0, pi/2], with zero perpendicular to the surface. |
| `orientationRadians` | Null when unavailable; otherwise finite and in [0, 2*pi), from positive x toward positive y. |
| `strokeUnitLengthCm` | Null when unavailable; otherwise a finite positive physical distance per coordinate unit. |
| `InkStroke.geometry()` | Groups in coat order. Each group has `meshes` and closed `outlines`. Arrays belong to the caller and survive later updates or close. |
| `InkMesh.positions` | x/y pairs; `vertexCount` equals array length / 2. |
| `InkMesh.triangles` | Three indices per triangle; `triangleCount` equals array length / 3. |
| `InkStroke.inputs()` | Owned input values. Optional attribute presence is preserved; encoded inputs may have quantized coordinates/times. |
| `InkStroke.contains(x, y)` | Native triangle intersection in stroke coordinates; false for an empty shape. |
| `InkStroke.coverage(left, top, right, bottom)` | Native box coverage in stroke coordinates; zero for an empty shape. The portable stroke takes an `InkBounds` instead. |
| `close()` | Releases the C++ owner; repeated close is safe. Other methods fail after close. |

The engine validates time ordering, tool consistency and optional attribute consistency across
an input batch. Batches are limited to one million samples. Serialized input/family payloads
are limited to 64 MiB. The client and engine have separate Wasm memories; snapshots are copies.

The lightweight `geometry()` API exports positions; `rendering()` additionally exports the full
canonical vertex attributes and engine-applied paints. A Canvas2D renderer can fill each group's outlines together as one compound
nonzero path. Filling each triangle separately causes translucent overlap artifacts. Serialized
families with textures or vertex effects need an appropriate renderer; loading their geometry
does not implement their paint.

## Browser example and current verification

Build the Kotlin/Wasm example from the checkout:

```sh
./gradlew -PbyteinkWasm=true :samples:wasm:wasmJsBrowserDistribution
python3 -m http.server 8080 --directory samples/wasm/build/dist/wasmJs/productionExecutable
```

Open `http://localhost:8080`. The example uses Compose mesh/path drawing and supports partial
erase, Object erase, moving ink, operation undo, stored-row reload, cancel and clear. It uses
`InkKit.load` for every replay and the reusable browser input/session APIs for pen axes, coalesced
history, prediction and simultaneous contacts.

The browser software raster checks cover 63 synthetic desktop controls (all texture blends,
wrapping/origins, vertex effects, atlas frames/reverse loops, prediction/timed effects and
multi-coat paints), within 2/255 per premultiplied channel. Cache retirement/budgets, clipping,
alpha, prediction replacement and split shapes without outlines are also checked. Actual Compose
window drawing is smoke-tested with pen/highlighter; a full hardware GPU/browser matrix remains
part of release acceptance.

The native-backed Kotlin tests and Chromium consumer checks cover loading, authoring, prediction
replacement, finishing, hit testing, ownership and Android input reload. All 840 committed Android
input fixtures re-encode to identical gzip bytes. Their closed boundaries, vertex counts/positions
and native coverage probes match within the existing float tolerance; the largest measured
coordinate difference is about 0.000023 stroke units. The same common facade contract runs
against JVM and Wasm: all 280 Android pages replay their erase/move/resize records into 891
projections, with matching family protobufs, bounds/transforms and spatial geometry. Ownership,
unknown-data retention, tombstones and invalid-data handling are also checked. Chromium checks
operation reload and undo at desktop and phone widths.

**Buffer order differs:** 28 width-20 marker fixtures have reindexed/retriangulated caps and a
cyclic change of contour start. Counts, vertex positions, oriented closed boundaries and coverage
match, but direct triangle-index/contour-start parity is not established. Android pixel equivalence,
physical input latency, WASI, complete custom-brush serialization and official preview publication
remain outside this acceptance. The existing desktop APIs remain unchanged.
