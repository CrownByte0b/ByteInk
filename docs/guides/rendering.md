# Render and cache

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

`InkPathRenderer.draw` also accepts a single finished or live stroke, a transform, optional viewport and color override. `DrawScope.drawInk` supplies the scope viewport automatically.

## Viewport raster cache

For unchanged finished ink, reuse `InkSceneRasterCache` and draw wet ink afterward. Cache hits require the same scene instance, exclusion identities, transform, viewport and scale.

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
| `InkSceneRasterCache` | One exact view, 64 MiB retained N32 pixels | `clearCache()` or terminal `close()` |
| `rememberInkSceneRasterCache(...)` | Explicit capacity/byte budget or defaults | Automatically closes on removal |

Capacity/budget `0` disables retention. Oversized paths/rasters draw transiently and release afterward. Live paths, native Ink meshes, scene metadata and wrapper objects are outside the finished-path/raster byte ceilings.

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

## Fidelity and ownership

The path renderer supports texture-free `ANY` and `DISCARD` paint choices. It rejects unsupported paints before drawing coats. Outline rendering does not reproduce per-vertex opacity or textured/animated mesh shading; translucent `ANY` self-overlap and antialiasing can differ from Android's hardware mesh renderer. `DISCARD` highlighters use their pinned uniform path behavior. See the [measured fidelity matrix](https://github.com/CrownByte0b/ByteInk/blob/master/conformance/android/FIDELITY.md).

Keep renderers, caches and Skia surfaces on one drawing thread. Clear paths and close raster/surface/image owners on disposal. Ink's native stroke/input owners use reachability-based cleanup; closing a controller releases references without guaranteeing immediate native-mesh destruction.
