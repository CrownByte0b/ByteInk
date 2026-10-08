# Full Ink rendering

`InkMeshRenderer` renders finished and in-progress native Ink meshes with vertex opacity and HSL
effects, prediction fade, derivative antialiasing, tiling textures, particle stamping and atlas
animation. It uses the pinned Ink shader math. ANY and ACCUMULATE draw mesh overlap; DISCARD
uses one outline fill with tiling textures, following the pinned engine's paint contract.

In an existing Compose drawing surface, opt in with:

```kotlin
val textureStore = remember(images) { InkTextureStore { id -> images[id] } }
val renderer = rememberInkMeshRenderer(textureStore)
InkDrawingSurface(
    controller = rememberInkAuthoringController(),
    brush = brush,
    renderer = renderer,
    onStrokeFinished = onStrokeFinished,
    drawContent = { completedStrokes.forEach { drawInk(renderer, it) } },
)
```

Here `images` maps the brush's client texture IDs to Skia `Image`s. Omit the store for brushes
without textures. The renderer borrows images; their creator owns closing them. Shader caching
retains native image references independently. After replacing an image under the same ID, call
`renderer.clearCache()`. A missing texture selects the next compatible paint preference; if
none is usable, drawing fails before rendering any coat. `canDraw` checks that selection.

`InkScene`, `InkSceneRasterCache` and the `drawInk` extensions accept either renderer. Existing
path-specific APIs and the drawing surface's default `InkPathRenderer` remain available.
The raster cache tracks renderer identity and revision, including texture invalidation and
animation time. Reuse a renderer on one drawing thread; `rememberInkMeshRenderer` closes it on
disposal. Outside composition, close `InkMeshRenderer` when finished.

Advance `renderer.animationTimeMillis` with an elapsed, nonnegative millisecond clock to animate
texture atlases. This snapshot state invalidates Compose drawing. Live brush time effects still
require `InProgressStroke.updateShape`; the authoring controller already advances those effects.
The renderer supports row/column atlas layouts, restart/reverse loops and per-particle offsets.

Geometry is cached by shape and effective linear canvas transform. Translation reuses prepared
vertices; zoom, recoloring and atlas frames rebuild them. Finished geometry has count and byte
limits, and live geometry is weakly owned. On shape changes the renderer compares owned snapshots
by raw vertex bits and triangle indices, prepares changed vertices and reuses only verified unchanged
sixteen-triangle chunks. Prediction shrink, earlier vertex mutation and moving partitions invalidate
affected chunks; this comparison does not consume native damage. Texture-free chunk shaders are
retained until replacement, eviction or retirement. Textured shaders retain their per-draw behavior
so input-relative origins and image selection stay current. Geometry metrics include primitive
scratch capacity and retained shader uniform bytes; JVM headers and additional native/driver
storage remain outside those estimates. Live bytes are separate from the finished byte budget;
custom hosts must call `releaseLiveStroke` before clearing/reusing an engine.

Texture shaders have a separate entry limit. Rendering uses Compose `drawVertices` and Skia runtime shaders in batches
of sixteen triangles because Skiko 0.150.1 does not expose SkMesh. Performance depends on mesh
size and backend; this implementation does not establish low-latency pen input parity.

Verification commands:

```sh
./gradlew :byteink-core:test :byteink-compose:test :byteink-testing:test
xvfb-run -a ./gradlew :byteink-compose:meshGpuTest
env -u DISPLAY ./gradlew :byteink-compose:meshEglTest
```

The second command uses Linux GLX and requires Xvfb, Xauth and an OpenGL driver; Linux CI runs it
separately so normal headless and GPU reports are both retained. The tests cover vertex effects,
texture blending and placement, atlas animation, live predictions, erasure meshes, Compose
alpha/clipping, cache lifetime and color-managed surfaces. The Android hardware comparison
checks all 280 committed reference cases with MAE at most 1/255, SSIM at least 0.99 and no
unexplained interior pixels. Passing these gates does not imply identical hardware pixels.

The EGL task is a separate investigation-only control using Skiko's public assembled GL
interface on an owned surfaceless context. It does not depend on X11 or replace the production
Wayland painter. It records the actual GL renderer and checks textures, overlap, predictions,
canonical geometry, scaling and context failure/teardown. Passing its diagnostic gates preserves
the strict promotion result separately: diagnostics expose an isolated reference gap inside a
triangle that exceeds promotion limits on both NVIDIA and Mesa. The recorded strict NVIDIA
check (`-PbyteinkEglPromotionCheck=true`) rejects it. Exact cause remains unresolved.
See [the Step 3 report](../PERFORMANCE_WAYLAND_GPU.md) for raw pixel/geometry evidence,
native JBR buffer controls and client paint/submission request measurements. Deferred
completion, compositor display and physical pen latency are outside those timings.

Incremental preparation controls also compare exact full-render pixels and prepared arrays through
timed prefix mutation, prediction changes, skipped updates, transforms/color/atlas changes and
native partitions crossing 65,535 vertices. The full owned native export and sixteen-triangle draw
granularity remain. See [the step 2 performance report](../PERFORMANCE_WET_MESH.md) for measured
benefits and remaining software paint costs.
