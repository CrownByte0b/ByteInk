# Rendering and caches

Module: `byteink-compose`. [Conventions](index.md). Signatures and defaults follow the current source.

## InkPathRenderer

### `InkPathRenderer`

InkPathRenderer() uses 2048 finished entries and 64 MiB. InkPathRenderer(cacheCapacity) keeps the same byte ceiling. Supports texture-free ANY/DISCARD; clearCache releases paths.

```kotlin
public class InkPathRenderer(public val cacheCapacity: Int, public val cacheByteBudget: Long)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | `Int` | `Required` | Nonnegative retained-entry limit; zero disables retention. |
| `cacheByteBudget` | `Long` | `Required` | Nonnegative approximate retained finished-path byte ceiling; excludes live paths and meshes. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L46)

### `InkPathRenderer.constructor`

```kotlin
public constructor()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L47)

### `InkPathRenderer.constructor`

```kotlin
public constructor(cacheCapacity: Int = 2048)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | `Int` | `2048` | Nonnegative retained-entry limit; zero disables retention. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L48)

### `InkPathRenderer.DEFAULT_CACHE_BYTE_BUDGET`

```kotlin
public const val DEFAULT_CACHE_BYTE_BUDGET: Long = 64L * 1024 * 1024
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L52)

### `InkPathRenderer.pathBuildCount`

```kotlin
public var pathBuildCount: Long
```

Cumulative finished/live path builds, retained across clearCache calls. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L68)

### `InkPathRenderer.cachedShapeCount`

```kotlin
public val cachedShapeCount: Int
```

Number of retained finished shapes; excludes live paths.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L71)

### `InkPathRenderer.cachedPathBytes`

```kotlin
public var cachedPathBytes: Long
```

Estimated retained finished-path geometry bytes; bounded by cacheByteBudget. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L75)

### `InkPathRenderer.pathEvictionCount`

```kotlin
public var pathEvictionCount: Long
```

Cumulative finished-shape retirements caused by capacity/byte limits. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L79)

### `InkPathRenderer.canDraw`

```kotlin
public fun canDraw(stroke: Stroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L83)

### `InkPathRenderer.canDraw`

```kotlin
public fun canDraw(stroke: InProgressStroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L86)

### `InkPathRenderer.draw`

```kotlin
public fun draw( canvas: Canvas, stroke: Stroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect? = null, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `viewport` | `Rect?` | `null` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L95)

### `InkPathRenderer.draw`

```kotlin
public fun draw( canvas: Canvas, stroke: InProgressStroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect? = null, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `viewport` | `Rect?` | `null` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L138)

### `InkPathRenderer.clearCache`

```kotlin
public fun clearCache()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L175)

### `DrawScope.drawInk`

```kotlin
public fun DrawScope.drawInk( renderer: InkPathRenderer, stroke: Stroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L274)

### `DrawScope.drawInk`

```kotlin
public fun DrawScope.drawInk( renderer: InkPathRenderer, stroke: InProgressStroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L282)

## InkScene

### `InkSceneStroke`

One occurrence; the same native stroke may occur several times with distinct transforms/colors.

```kotlin
public data class InkSceneStroke( public val stroke: Stroke, public val strokeToScene: AffineTransform = AffineTransform.IDENTITY, public val colorArgb: Int? = null, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToScene` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-scene transform; InkScene snapshots mutable values. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L16)

### `InkScene`

Snapshots transforms and indexes bounds. visibleStrokes gives conservative candidates; draw returns the actual drawn count.

```kotlin
public class InkScene(strokes: List<InkSceneStroke>)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `strokes` | `List<InkSceneStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L28)

### `InkScene.strokes`

```kotlin
public val strokes: List<InkSceneStroke>
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L29)

### `InkScene.visibleStrokes`

```kotlin
public fun visibleStrokes( viewport: Rect, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, ): List<InkSceneStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L46)

### `InkScene.draw`

```kotlin
public fun draw( canvas: Canvas, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L62)

### `InkScene.draw`

```kotlin
public fun draw( canvas: Canvas, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect, excludedStrokes: Set<InkSceneStroke>, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `Required` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L77)

### `DrawScope.drawInkScene`

```kotlin
public fun DrawScope.drawInkScene( scene: InkScene, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L100)

### `DrawScope.drawInkScene`

```kotlin
public fun DrawScope.drawInkScene( scene: InkScene, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, excludedStrokes: Set<InkSceneStroke>, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `Required` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L107)

## InkSceneRasterCache

### `InkSceneRasterCache`

InkSceneRasterCache() retains one exact view within 64 MiB. Reuse identical scene/view keys; new views rasterize in full. close is terminal.

```kotlin
public class InkSceneRasterCache( public val cacheCapacity: Int, public val pixelBudgetBytes: Long, ) : AutoCloseable
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | `Int` | `Required` | Nonnegative retained-entry limit; zero disables retention. |
| `pixelBudgetBytes` | `Long` | `Required` | Nonnegative retained N32 raster-byte ceiling; excludes paths, scenes and wrappers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L38)

### `InkSceneRasterCache.constructor`

```kotlin
public constructor()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L42)

### `InkSceneRasterCache.DEFAULT_PIXEL_BUDGET_BYTES`

```kotlin
public const val DEFAULT_PIXEL_BUDGET_BYTES: Long = 64L * 1024 * 1024
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L51)

### `InkSceneRasterCache.rasterBuildCount`

```kotlin
public var rasterBuildCount: Long
```

Cumulative viewport raster builds, retained across clears. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L54)

### `InkSceneRasterCache.retainedPixelBytes`

```kotlin
public var retainedPixelBytes: Long
```

Retained N32 raster bytes, bounded by pixelBudgetBytes; zero after clear/close. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L58)

### `InkSceneRasterCache.cachedViewCount`

```kotlin
public val cachedViewCount: Int
```

Number of retained exact viewport images, bounded by cacheCapacity.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L62)

### `InkSceneRasterCache.rasterEvictionCount`

```kotlin
public var rasterEvictionCount: Long
```

Cumulative LRU image retirements caused by capacity/byte limits. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L65)

### `InkSceneRasterCache.draw`

```kotlin
public fun draw( canvas: Canvas, scene: InkScene, renderer: InkPathRenderer, width: Int, height: Int, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `width` | `Int` | `Required` | Raster/image width in pixels; cache viewport dimensions must be nonnegative. |
| `height` | `Int` | `Required` | Raster/image height in pixels; cache viewport dimensions must be nonnegative. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L80)

### `InkSceneRasterCache.draw`

```kotlin
public fun draw( canvas: Canvas, scene: InkScene, renderer: InkPathRenderer, viewport: Rect, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, rasterScale: Float = 1f, excludedStrokes: Set<InkSceneStroke> = emptySet(), ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `rasterScale` | `Float` | `1f` | Finite positive physical pixels per local pixel, for uniform axis-aligned ancestor zoom. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `emptySet()` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L106)

### `InkSceneRasterCache.clearCache`

```kotlin
public fun clearCache()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L207)

### `InkSceneRasterCache.close`

```kotlin
override public fun close()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L216)

### `rememberInkSceneRasterCache`

Composable: call within a Compose composition.

```kotlin
public fun rememberInkSceneRasterCache(): InkSceneRasterCache
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L276)

### `rememberInkSceneRasterCache`

Composable: call within a Compose composition.

```kotlin
public fun rememberInkSceneRasterCache(cacheCapacity: Int, pixelBudgetBytes: Long): InkSceneRasterCache
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | `Int` | `Required` | Nonnegative retained-entry limit; zero disables retention. |
| `pixelBudgetBytes` | `Long` | `Required` | Nonnegative retained N32 raster-byte ceiling; excludes paths, scenes and wrappers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L284)

### `DrawScope.drawCachedInkScene`

```kotlin
public fun DrawScope.drawCachedInkScene( cache: InkSceneRasterCache, scene: InkScene, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cache` | `InkSceneRasterCache` | `Required` | Drawing-thread raster cache; close when its owner is disposed. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L291)

### `DrawScope.drawCachedInkScene`

```kotlin
public fun DrawScope.drawCachedInkScene( cache: InkSceneRasterCache, scene: InkScene, renderer: InkPathRenderer, viewport: Rect, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, rasterScale: Float = 1f, excludedStrokes: Set<InkSceneStroke> = emptySet(), ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cache` | `InkSceneRasterCache` | `Required` | Drawing-thread raster cache; close when its owner is disposed. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `rasterScale` | `Float` | `1f` | Finite positive physical pixels per local pixel, for uniform axis-aligned ancestor zoom. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `emptySet()` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L300)
