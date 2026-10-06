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
limits, live geometry is weakly owned and refreshed by shape version, and texture shaders have
a separate entry limit. Rendering uses Compose `drawVertices` and Skia runtime shaders in batches
of sixteen triangles because Skiko 0.150.1 does not expose SkMesh. Performance depends on mesh
size and backend; this implementation does not establish low-latency pen input parity.

Verification commands:

```sh
./gradlew :byteink-core:test :byteink-compose:test :byteink-testing:test
xvfb-run -a ./gradlew :byteink-compose:meshGpuTest
```

The second command uses Linux GLX and requires Xvfb, Xauth and an OpenGL driver; Linux CI runs it
separately so normal headless and GPU reports are both retained. The tests cover vertex effects,
texture blending and placement, atlas animation, live predictions, erasure meshes, Compose
alpha/clipping, cache lifetime and color-managed surfaces. The Android hardware comparison
checks all 280 committed reference cases with MAE at most 1/255, SSIM at least 0.99 and no
unexplained interior pixels. Passing these gates does not imply identical hardware pixels.
