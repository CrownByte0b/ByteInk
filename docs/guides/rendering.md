# Render and cache

## Choose a renderer

Both renderers implement `InkRenderer`, which accepts finished and live strokes through `render` and `DrawScope.drawInk`. Scenes, raster caches and `InkDrawingSurface` accept either implementation. Existing path-specific overloads remain available; the drawing surface defaults to `InkPathRenderer`.

| Renderer | Behavior |
| --- | --- |
| `InkMeshRenderer` | Pinned Ink vertex opacity/HSL effects, prediction fade, derivative antialiasing, tiling textures, particle stamps and atlas animation. ANY/ACCUMULATE render mesh overlap; DISCARD uses one uniform outline with tiling textures, following the pinned engine's paint contract. |
| `InkPathRenderer` | Uniform outline fills for texture-free ANY/DISCARD paints. Useful when mesh effects are unnecessary. |

Use `rememberInkMeshRenderer()` in composition or `InkMeshRenderer().use { ... }` elsewhere. Reuse a renderer on one drawing thread. The [authoring example](authoring.md#compose-surface) opts into mesh rendering for live and finished ink.

## Textures and animated stamps

Supply preloaded Skia images keyed by the brush paint's client texture IDs. This complete surface example also advances the texture-atlas clock:

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/MeshDrawing.kt"
```

Omit the texture store for texture-free brushes. Images are borrowed: their creator owns closing them, while cached shaders retain native image references independently. After replacing an image under the same ID, call `renderer.clearCache()`. A missing image makes that paint unavailable; the renderer tries the next compatible preference and otherwise fails before drawing any coat. `canDraw` checks paint selection.

The mesh renderer supports texture size units, rotation, offset, origin, repeat/mirror/clamp, all twelve pinned blend modes, stamp surface UVs, per-particle offsets and row/column atlases with restart/reverse loops. `animationTimeMillis` is writable nonnegative elapsed milliseconds; changing it invalidates Compose drawing and retained view rasters. Live brush time effects additionally require `InProgressStroke.updateShape`; the authoring controller advances those effects.

## Coordinate transforms

With `ImmutableAffineTransform(a, b, c, d, e, f)`:

```text
xCanvas = a * xPage + b * yPage + c
yCanvas = d * xPage + e * yPage + f
```

| Coordinates | Unit |
| --- | --- |
| Native stroke inputs/mesh, `PageStroke`, lasso/erase paths | Page dp |
| `InkPointerSample`, `Rect` viewports | Local surface/canvas pixels |
| `sceneToCanvas` / `strokeToView` | Maps dp to pixels, including density/zoom/scroll |
| `rasterScale` | Physical pixels per local pixel from uniform ancestor zoom |

For a scrolled page: translation is `-scrollDp * density * zoom`; do not use the document's full extent as the raster viewport. [Upstream affine transform API](https://developer.android.com/reference/kotlin/androidx/ink/geometry/ImmutableAffineTransform).

## Draw a finished scene

Create `InkScene` when page content changes and keep it while panning, zooming or drawing wet ink. Convert each projection using its own transform; resolve automatic color for the current paper.

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Rendering.kt"
```

`InkScene.draw` draws nearby strokes in original order. `visibleStrokes` returns conservative bounds candidates; the renderer performs a second visibility check. `draw` returns the number actually drawn. Reuse a renderer across frames.

Each renderer's `draw` also accepts a single finished or live stroke, a transform, optional viewport and color override. `DrawScope.drawInk` supplies the scope viewport automatically.

## Viewport raster cache

For unchanged finished ink, reuse `InkSceneRasterCache` and draw wet ink afterward. Cache hits require the same scene instance, exclusion identities, transform, viewport, scale, renderer identity and `renderVersion`. Texture invalidation and atlas clock changes therefore rebuild the raster.

```kotlin
val cache = rememberInkSceneRasterCache(
    cacheCapacity = 3,
    pixelBudgetBytes = 64L * 1024 * 1024,
)
// Inside a DrawScope:
drawCachedInkScene(
    cache = cache,
    scene = scene,
    renderer = renderer,
    viewport = Rect(0f, 0f, size.width, size.height),
    sceneToCanvas = pageToPixels,
    rasterScale = 1f,
)
```

| Cache | Default/limits | Release |
| --- | --- | --- |
| `InkPathRenderer` | 2,048 finished shapes, approximate 64 MiB finished-path budget | `clearCache()` |
| `InkMeshRenderer` | 2,048 finished shapes, 64 MiB retained geometry/prepared-vertex budget, 64 texture shader entries | `clearCache()` or terminal `close()` |
| `rememberInkMeshRenderer(...)` | Default mesh limits; optional texture store | Automatically closes on removal |
| `InkSceneRasterCache` | One exact view, 64 MiB retained N32 pixels | `clearCache()` or terminal `close()` |
| `rememberInkSceneRasterCache(...)` | Explicit capacity/byte budget or defaults | Automatically closes on removal |

Geometry/view capacity or byte budget `0` disables that retention; `textureCacheCapacity` must be positive. Oversized geometry/rasters draw transiently and release afterward. Live geometry, native Ink meshes, provider-owned texture images, scene metadata, JVM headers and GPU uploads are outside the retained finished-geometry/raster byte ceilings.

Mesh geometry is cached by shape and effective linear canvas transform. Translation reuses prepared vertices; zoom, recoloring and atlas frames rebuild them. Live geometry is weakly owned and refreshed by shape version. The renderer uses Compose `drawVertices` and Skia runtime shaders in batches of sixteen triangles; performance depends on mesh size and backend.

Returning to a retained exact view reuses its raster. Each unseen pan/zoom view renders again in full; the cache does not tile the document. Raster dimensions are `ceil(viewport.width * rasterScale)` by `ceil(viewport.height * rasterScale)`; retained N32 bytes are width × height × 4.

For a canvas inside a uniformly zoomed ancestor, put device density in `sceneToCanvas`, pass ancestor zoom as `rasterScale`, and align the viewport origin to the destination's physical pixel origin. Preserve destination clipping. [Rendering/cache parameters](../reference/rendering.md).

## Exclude strokes during an eraser preview

`excludedStrokes` uses **occurrence identity** from `scene.strokes`. The scene copies constructor entries, so use its returned entries rather than the original input list. To exclude equal occurrences independently:

```kotlin
val excluded = java.util.Collections.newSetFromMap(
    java.util.IdentityHashMap<InkSceneStroke, Boolean>(),
)
excluded.add(scene.strokes.first())
scene.draw(canvas, renderer, pageToPixels, viewport, excluded)
```

Changing exclusions reuses the scene's spatial index and invalidates its retained rasters. For partial erasing, replace the affected projections with cut geometry for the preview.

## Read outlines and triangles

```kotlin
val groups = (0 until stroke.shape.getRenderGroupCount()).map { group ->
    InkMeshes.triangles(shape = stroke.shape, group = group)
}
val outlines = InkMeshes.outlines(shape = stroke.shape, group = 0)
// Each outline: FloatArray(x0, y0, x1, y1, ...).
// TriangleMesh.positions uses the same layout; triangles holds triples of vertex indices.
```

For live ink, use the overloads taking `InProgressStroke` and a zero-based `coat`. Read live geometry on the authoring thread without concurrent updates. Returned arrays are independent copies that remain valid after the stroke advances or clears. [Geometry parameters](../reference/core.md).

For all shader attributes, call `InkMeshes.rendering(shape, group)` or `InkMeshes.rendering(liveStroke, coat)`. Each returned `StrokeMesh` owns float vertices and widened unsigned triangle indices. Each vertex uses `StrokeMesh.VERTEX_STRIDE` (**15**) floats:

| Float offsets | Attribute | Presence bit |
| --- | --- | --- |
| 0–1 | Position XY | 0 |
| 2 | Opacity shift | 1 |
| 3–5 | HSL shift | 2 |
| 6–7 / 8 | Side derivative XY / label | 3 / 4 |
| 9–10 / 11 | Forward derivative XY / label | 5 / 6 |
| 12–13 | Surface UV | 7 |
| 14 | Animation offset | 8 |

`attributeMask` records source-format presence; missing attributes contain zero. Finished packed attributes are decoded to this canonical layout. `hasSurfaceUv` and `hasAnimationOffset` expose the last two bits. The same owned-copy/thread rules apply.

## Fidelity and ownership

The path renderer supports texture-free `ANY` and `DISCARD` paint choices. It rejects unsupported paints before drawing coats. Outline rendering does not reproduce per-vertex opacity or textured/animated mesh shading; translucent `ANY` self-overlap and antialiasing can differ from Android's hardware mesh renderer. `DISCARD` highlighters use their pinned uniform path behavior. See the [measured fidelity matrix](https://github.com/CrownByte0b/ByteInk/blob/master/conformance/android/FIDELITY.md).

Mesh rendering passes all 280 saved Android hardware reference cases on Linux and Windows (mean RGB error at most 1/255, SSIM at least 0.99, no unexplained interior pixels), plus Linux NVIDIA and Mesa OpenGL checks. The measured Windows/Linux difference is 21 pixels across 280 images, each at most 1/255 per channel. These are saved-reference comparisons, not a fresh Android capture or a claim of identical hardware pixels or low-latency pen input parity.

Keep renderers, caches and Skia surfaces on one drawing thread. Clear path caches and close mesh renderers and raster/surface/image owners on disposal. Ink's native stroke/input owners use reachability-based cleanup; closing a controller releases references without guaranteeing immediate native-mesh destruction.
