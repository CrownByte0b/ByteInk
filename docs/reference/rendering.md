# Rendering and caches

Module: `byteink-compose`. [Conventions](index.md). Signatures and defaults follow the current source.

## InkRenderer

### `InkRenderer`

Shared finished/live drawing contract. renderVersion invalidates retained view rasters when settings or textures change; the default is 0. releaseLiveStroke defaults to a no-op for renderers without wet caches.

```kotlin
public interface InkRenderer
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L11)

### `InkRenderer.renderVersion`

```kotlin
public val renderVersion: Long
```

Renderer revision used by viewport raster keys; mesh animation-time changes and clearCache increment it.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L13)

### `InkRenderer.canDraw`

```kotlin
public fun canDraw(stroke: Stroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L14)

### `InkRenderer.canDraw`

```kotlin
public fun canDraw(stroke: InProgressStroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L15)

### `InkRenderer.render`

```kotlin
public fun render( canvas: Canvas, stroke: Stroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect? = null, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `viewport` | `Rect?` | `null` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L16)

### `InkRenderer.render`

```kotlin
public fun render( canvas: Canvas, stroke: InProgressStroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect? = null, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `viewport` | `Rect?` | `null` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L23)

### `InkRenderer.releaseLiveStroke`

Retire only this engine stroke's cached wet geometry before it is cleared/recycled. Built-in direct panels do this automatically; custom controller/session hosts must call it on retirement. Finished/texture caches are retained.

```kotlin
public fun releaseLiveStroke(stroke: InProgressStroke)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L31)

### `InkRenderer.clearCache`

```kotlin
public fun clearCache()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L32)

### `DrawScope.drawInk`

```kotlin
public fun DrawScope.drawInk( renderer: InkRenderer, stroke: Stroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L36)

### `DrawScope.drawInk`

```kotlin
public fun DrawScope.drawInk( renderer: InkRenderer, stroke: InProgressStroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkRenderer.kt#L44)

## InkMeshRenderer

### `InkMeshRenderer`

Full pinned mesh/shader rendering: vertex HSL/opacity, prediction fade, derivative AA, textures and atlas animation. ANY/ACCUMULATE use meshes; outlined DISCARD uses a uniform outline with tiling textures and exports mesh attributes lazily only when needed. Single drawing thread; close is terminal. Missing textures try the next compatible paint before failing without partial coat drawing.

```kotlin
public class InkMeshRenderer( public val textureStore: InkTextureStore? = null, public val cacheCapacity: Int = 2048, public val cacheByteBudget: Long = 64L * 1024 * 1024, public val textureCacheCapacity: Int = 64, ) : InkRenderer, AutoCloseable
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `textureStore` | `InkTextureStore?` | `null` | Optional preloaded client-texture-ID lookup; images are borrowed and missing images make that paint unavailable. |
| `cacheCapacity` | `Int` | `2048` | Nonnegative retained-entry limit; zero disables retention. |
| `cacheByteBudget` | `Long` | `64L * 1024 * 1024` | Nonnegative retained finished-path or mesh-geometry byte ceiling, as specified by the renderer; excludes live geometry and provider images. |
| `textureCacheCapacity` | `Int` | `64` | Positive maximum number of retained texture shader entries; provider-owned images are outside this limit. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L57)

### `InkMeshRenderer.animationTimeMillis`

```kotlin
public var animationTimeMillis: Long
```

Writable nonnegative elapsed millisecond clock for texture atlases; changing it invalidates Compose drawing and view rasters. Live shape effects also need updateShape.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L69)

### `InkMeshRenderer.renderVersion`

```kotlin
override var renderVersion: Long
```

Renderer revision used by viewport raster keys; mesh animation-time changes and clearCache increment it. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L75)

### `InkMeshRenderer.meshBuildCount`

```kotlin
public var meshBuildCount: Long
```

Cumulative coat mesh preparations, retained across clears. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L77)

### `InkMeshRenderer.cachedShapeCount`

```kotlin
public val cachedShapeCount: Int
```

Number of retained finished mesh shapes; excludes weakly owned live geometry.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L81)

### `InkMeshRenderer.cachedTextureCount`

```kotlin
public val cachedTextureCount: Int
```

Number of retained texture shaders, bounded by textureCacheCapacity.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L82)

### `InkMeshRenderer.cachedLiveShapeCount`

```kotlin
public val cachedLiveShapeCount: Int
```

Number of retained live engine-stroke cache entries; releaseLiveStroke removes one on retirement.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L84)

### `InkMeshRenderer.cachedLiveGeometryBytes`

```kotlin
public val cachedLiveGeometryBytes: Long
```

Retained copied live mesh/prepared-vertex/path bytes; excludes JVM headers, GPU uploads, provider images and native Ink owners. Outside cacheByteBudget.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L85)

### `InkMeshRenderer.cachedGeometryBytes`

```kotlin
public var cachedGeometryBytes: Long
```

Retained finished mesh/prepared-vertex/path bytes, bounded by cacheByteBudget; excludes live geometry, JVM headers, GPU uploads and provider images. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L86)

### `InkMeshRenderer.canDraw`

```kotlin
override fun canDraw(stroke: Stroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L121)

### `InkMeshRenderer.canDraw`

```kotlin
override fun canDraw(stroke: InProgressStroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L128)

### `InkMeshRenderer.draw`

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

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L134)

### `InkMeshRenderer.draw`

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

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L182)

### `InkMeshRenderer.render`

```kotlin
override fun render(canvas: Canvas, stroke: Stroke, strokeToCanvas: AffineTransform, viewport: Rect?, colorArgb: Int?): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `Required` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `viewport` | `Rect?` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `colorArgb` | `Int?` | `Required` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L223)

### `InkMeshRenderer.render`

```kotlin
override fun render(canvas: Canvas, stroke: InProgressStroke, strokeToCanvas: AffineTransform, viewport: Rect?, colorArgb: Int?): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `Required` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `viewport` | `Rect?` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `colorArgb` | `Int?` | `Required` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L225)

### `InkMeshRenderer.clearCache`

```kotlin
override fun clearCache()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L228)

### `InkMeshRenderer.releaseLiveStroke`

Releases only this live stroke's cached geometry, preserving finished shapes, texture shaders and renderVersion.

```kotlin
override fun releaseLiveStroke(stroke: InProgressStroke)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L237)

### `InkMeshRenderer.close`

```kotlin
override fun close()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L244)

### `rememberInkMeshRenderer`

Remembers the renderer by textureStore identity and closes it on disposal. Omit the store for texture-free brushes.

Composable: call within a Compose composition.

```kotlin
public fun rememberInkMeshRenderer(textureStore: InkTextureStore? = null): InkMeshRenderer
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `textureStore` | `InkTextureStore?` | `null` | Optional preloaded client-texture-ID lookup; images are borrowed and missing images make that paint unavailable. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkMeshRenderer.kt#L427)

## InkTextureStore

### `InkTextureStore`

Supplies preloaded Skia Images by client texture ID. Renderer borrows images and never closes them; cached shaders retain native references. Call renderer.clearCache() after changing an image under the same ID.

```kotlin
public fun interface InkTextureStore
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkTextureStore.kt#L11)

### `InkTextureStore.get`

```kotlin
public operator fun get(clientTextureId: String): Image?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `clientTextureId` | `String` | `Required` | BrushPaint client texture ID; return its decoded Skia Image, or null if unavailable. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkTextureStore.kt#L12)

## InkPathRenderer

### `InkPathRenderer`

InkPathRenderer() uses 2048 finished entries and 64 MiB. InkPathRenderer(cacheCapacity) keeps the same byte ceiling. Supports texture-free ANY/DISCARD; clearCache releases paths.

```kotlin
public class InkPathRenderer(public val cacheCapacity: Int, public val cacheByteBudget: Long) : InkRenderer
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | `Int` | `Required` | Nonnegative retained-entry limit; zero disables retention. |
| `cacheByteBudget` | `Long` | `Required` | Nonnegative retained finished-path or mesh-geometry byte ceiling, as specified by the renderer; excludes live geometry and provider images. |

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
override fun canDraw(stroke: Stroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L83)

### `InkPathRenderer.canDraw`

```kotlin
override fun canDraw(stroke: InProgressStroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L86)

### `InkPathRenderer.render`

```kotlin
override fun render(canvas: Canvas, stroke: Stroke, strokeToCanvas: AffineTransform, viewport: Rect?, colorArgb: Int?): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `Required` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `viewport` | `Rect?` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `colorArgb` | `Int?` | `Required` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L88)

### `InkPathRenderer.render`

```kotlin
override fun render(canvas: Canvas, stroke: InProgressStroke, strokeToCanvas: AffineTransform, viewport: Rect?, colorArgb: Int?): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `Required` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `viewport` | `Rect?` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `colorArgb` | `Int?` | `Required` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L91)

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

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L101)

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

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L144)

### `InkPathRenderer.clearCache`

```kotlin
override fun clearCache()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L181)

### `InkPathRenderer.releaseLiveStroke`

Releases only this live stroke's cached path, preserving finished paths and renderVersion.

```kotlin
override fun releaseLiveStroke(stroke: InProgressStroke)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L190)

### `DrawScope.drawInk`

```kotlin
public fun DrawScope.drawInk( renderer: InkPathRenderer, stroke: Stroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L284)

### `DrawScope.drawInk`

```kotlin
public fun DrawScope.drawInk( renderer: InkPathRenderer, stroke: InProgressStroke, strokeToCanvas: AffineTransform = AffineTransform.IDENTITY, colorArgb: Int? = null, ): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite stroke-to-local-canvas transform, composed with the canvas's existing transform. |
| `colorArgb` | `Int?` | `null` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPathRenderer.kt#L292)

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
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L62)

### `InkScene.draw`

```kotlin
public fun draw( canvas: Canvas, renderer: InkRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L70)

### `InkScene.draw`

```kotlin
public fun draw( canvas: Canvas, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect, excludedStrokes: Set<InkSceneStroke>, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `Required` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L85)

### `InkScene.draw`

```kotlin
public fun draw( canvas: Canvas, renderer: InkRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, viewport: Rect, excludedStrokes: Set<InkSceneStroke>, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `Required` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L94)

### `DrawScope.drawInkScene`

```kotlin
public fun DrawScope.drawInkScene( scene: InkScene, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L117)

### `DrawScope.drawInkScene`

```kotlin
public fun DrawScope.drawInkScene( scene: InkScene, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, excludedStrokes: Set<InkSceneStroke>, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `Required` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L124)

### `DrawScope.drawInkScene`

```kotlin
public fun DrawScope.drawInkScene( scene: InkScene, renderer: InkRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, excludedStrokes: Set<InkSceneStroke> = emptySet(), ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `emptySet()` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkScene.kt#L132)

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
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `width` | `Int` | `Required` | Raster/image width in pixels; cache viewport dimensions must be nonnegative. |
| `height` | `Int` | `Required` | Raster/image height in pixels; cache viewport dimensions must be nonnegative. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L82)

### `InkSceneRasterCache.draw`

```kotlin
public fun draw( canvas: Canvas, scene: InkScene, renderer: InkRenderer, width: Int, height: Int, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `width` | `Int` | `Required` | Raster/image width in pixels; cache viewport dimensions must be nonnegative. |
| `height` | `Int` | `Required` | Raster/image height in pixels; cache viewport dimensions must be nonnegative. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L92)

### `InkSceneRasterCache.draw`

```kotlin
public fun draw( canvas: Canvas, scene: InkScene, renderer: InkPathRenderer, viewport: Rect, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, rasterScale: Float = 1f, excludedStrokes: Set<InkSceneStroke> = emptySet(), ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `rasterScale` | `Float` | `1f` | Finite positive physical pixels per local pixel, for uniform axis-aligned ancestor zoom. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `emptySet()` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L118)

### `InkSceneRasterCache.draw`

```kotlin
public fun draw( canvas: Canvas, scene: InkScene, renderer: InkRenderer, viewport: Rect, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, rasterScale: Float = 1f, excludedStrokes: Set<InkSceneStroke> = emptySet(), ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `canvas` | `Canvas` | `Required` | Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `rasterScale` | `Float` | `1f` | Finite positive physical pixels per local pixel, for uniform axis-aligned ancestor zoom. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `emptySet()` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L129)

### `InkSceneRasterCache.clearCache`

```kotlin
public fun clearCache()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L232)

### `InkSceneRasterCache.close`

```kotlin
override public fun close()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L242)

### `rememberInkSceneRasterCache`

Composable: call within a Compose composition.

```kotlin
public fun rememberInkSceneRasterCache(): InkSceneRasterCache
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L302)

### `rememberInkSceneRasterCache`

Composable: call within a Compose composition.

```kotlin
public fun rememberInkSceneRasterCache(cacheCapacity: Int, pixelBudgetBytes: Long): InkSceneRasterCache
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cacheCapacity` | `Int` | `Required` | Nonnegative retained-entry limit; zero disables retention. |
| `pixelBudgetBytes` | `Long` | `Required` | Nonnegative retained N32 raster-byte ceiling; excludes paths, scenes and wrappers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L310)

### `DrawScope.drawCachedInkScene`

```kotlin
public fun DrawScope.drawCachedInkScene( cache: InkSceneRasterCache, scene: InkScene, renderer: InkPathRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cache` | `InkSceneRasterCache` | `Required` | Drawing-thread raster cache; close when its owner is disposed. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L317)

### `DrawScope.drawCachedInkScene`

```kotlin
public fun DrawScope.drawCachedInkScene( cache: InkSceneRasterCache, scene: InkScene, renderer: InkPathRenderer, viewport: Rect, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, rasterScale: Float = 1f, excludedStrokes: Set<InkSceneStroke> = emptySet(), ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cache` | `InkSceneRasterCache` | `Required` | Drawing-thread raster cache; close when its owner is disposed. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkPathRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `rasterScale` | `Float` | `1f` | Finite positive physical pixels per local pixel, for uniform axis-aligned ancestor zoom. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `emptySet()` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L326)

### `DrawScope.drawCachedInkScene`

```kotlin
public fun DrawScope.drawCachedInkScene( cache: InkSceneRasterCache, scene: InkScene, renderer: InkRenderer, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cache` | `InkSceneRasterCache` | `Required` | Drawing-thread raster cache; close when its owner is disposed. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L337)

### `DrawScope.drawCachedInkScene`

```kotlin
public fun DrawScope.drawCachedInkScene( cache: InkSceneRasterCache, scene: InkScene, renderer: InkRenderer, viewport: Rect, sceneToCanvas: AffineTransform = AffineTransform.IDENTITY, rasterScale: Float = 1f, excludedStrokes: Set<InkSceneStroke> = emptySet(), ): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `cache` | `InkSceneRasterCache` | `Required` | Drawing-thread raster cache; close when its owner is disposed. |
| `scene` | `InkScene` | `Required` | Immutable finished-ink snapshot; reuse the same instance until entries change. |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `viewport` | `Rect` | `Required` | Visible rectangle in local canvas pixels; supply the actually visible area. |
| `sceneToCanvas` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible scene-to-local-canvas transform; include device density here. |
| `rasterScale` | `Float` | `1f` | Finite positive physical pixels per local pixel, for uniform axis-aligned ancestor zoom. |
| `excludedStrokes` | `Set<InkSceneStroke>` | `emptySet()` | Occurrence instances from scene.strokes; matching uses identity, not value equality. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkSceneRasterCache.kt#L346)
