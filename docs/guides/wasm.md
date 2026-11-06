# WebAssembly preview

`byteink-wasm` runs the pinned Google Ink C++ engine in a browser or Node through Kotlin's
`wasmJs` target. It supports finished and live strokes, version 1 stock brushes, serialized
custom brush families, owned triangle/outline snapshots, point hit testing and Android input
blobs. This is an experimental source-checkout module; it is **not included in `0.1.0-alpha.1`**.

The existing `byteink-core`, `byteink-kit` and `byteink-compose` artifacts remain JVM libraries.
The Wasm API lives in `com.vivenotes.byteink.wasm`. It does not yet supply the desktop kit's
stored-row/operation replay APIs, Compose renderers or production browser pen adapter.

## Prepare the preview artifacts

From a ByteInk checkout on Linux, with a JDK, CMake, Ninja, Python and Node 22+ installed:

```sh
native/setup-wasm.sh
native/build-wasm.sh
./gradlew -PbyteinkWasm=true :byteink-wasm:publishAllPublicationsToBuildRepository
```

`JAVA_HOME` must point to the JDK for the native build. The scripts download the pinned SDK and
sources into ignored build directories. They use the same engine commit, Android angle/magnitude
patches and C++ dependency versions as desktop. Kotlin compilation alone does not build C++.

Add the checkout's `build/repo` Maven repository to your consumer's settings, then depend on:

```kotlin
kotlin {
    wasmJs { browser() }
    sourceSets.wasmJsMain.dependencies {
        implementation("com.vivenotes.byteink:byteink-wasm:0.1.0-SNAPSHOT")
    }
}
```

Kotlin 2.4.21 is the tested compiler. The client is a Kotlin library; the C++ engine is a separate
static asset bundle. Its Maven classifier is `engine`, extension `zip`:
`com.vivenotes.byteink:byteink-wasm:0.1.0-SNAPSHOT:engine@zip`.
The same ZIP is generated under `byteink-wasm/build/distributions/`.

Extract the ZIP into an application static asset directory, for example `public/ink/`. Serve
`byteink-engine.mjs` and `byteink-engine.wasm` beside each other, retaining the bundled licenses.
Serve `.wasm` as `application/wasm` and `.mjs` as JavaScript. Use HTTP(S), including for local
development; a browser cannot load the module from a local `file:` page. The initial backend is
single-threaded and does not require cross-origin isolation or `SharedArrayBuffer`.

## Create, save and reload

Load the engine once from a coroutine, using an absolute URL or an origin-relative path:

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
row fields in application storage; decoding is not a lossless storage rewrite. The preview does
not encode complete ViveNotes rows, erasure operations or transforms.

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
| `close()` | Releases the C++ owner; repeated close is safe. Other methods fail after close. |

The engine validates time ordering, tool consistency and optional attribute consistency across
an input batch. Batches are limited to one million samples. Serialized input/family payloads
are limited to 64 MiB. The client and engine have separate Wasm memories; snapshots are copies.

The preview exports position geometry, not the desktop renderer's full vertex attributes or
runtime shaders. A Canvas2D renderer can fill each group's outlines together as one compound
nonzero path. Filling each triangle separately causes translucent overlap artifacts. Serialized
families with textures or vertex effects need an appropriate renderer; loading their geometry
does not implement their paint.

## Browser example and current verification

Build the Kotlin/Wasm example from the checkout:

```sh
./gradlew -PbyteinkWasm=true :samples:wasm:wasmJsBrowserDistribution
python3 -m http.server 8080 --directory samples/wasm/build/dist/wasmJs/productionExecutable
```

Open `http://localhost:8080`. The example draws pen/highlighter outlines and supports save/reload,
cancel and clear. It is a minimal single-pointer adapter; pressure/tilt normalization, coalesced
events, browser prediction and simultaneous contacts remain planned.

The native-backed Kotlin tests and Chromium consumer checks cover loading, authoring, prediction
replacement, finishing, hit testing, ownership and Android input reload. All 840 committed Android
input fixtures re-encode to identical gzip bytes. Their closed boundaries, vertex counts/positions
and native coverage probes match within the existing float tolerance; the largest measured
coordinate difference is about 0.000023 stroke units.

**Buffer order differs:** 28 width-20 marker fixtures have reindexed/retriangulated caps and a
cyclic change of contour start. Counts, vertex positions, oriented closed boundaries and coverage
match, but direct triangle-index/contour-start parity is not established. No full mesh shader,
Android pixel equivalence, operation replay or physical input latency claim is made for Wasm.
