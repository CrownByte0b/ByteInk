# Notebook utilities

Module: `byteink-testing`. [Conventions](index.md). Signatures and defaults follow the current source.

## ViveNotebook

### `ViveNotebook`

Optional testing/sample utility. Open a checksum-verified private SQLite copy; close deletes it. Copy helpers append strokes/erases and never overwrite source/destination.

```kotlin
public class ViveNotebook
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/ViveNotebook.kt#L24)

### `ViveNotebook.pageIds`

```kotlin
public val pageIds: List<String>
```

All notebook page IDs, including empty pages, sorted by ID.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/ViveNotebook.kt#L31)

### `ViveNotebook.page`

```kotlin
public fun page(pageId: String): NotebookPage
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/ViveNotebook.kt#L36)

### `ViveNotebook.writeCopyWithStrokes`

```kotlin
public fun writeCopyWithStrokes(destination: File, strokes: List<StoredInkStroke>)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `destination` | `File` | `Required` | New .vive copy path; must differ from source and must not already exist. |
| `strokes` | `List<StoredInkStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/ViveNotebook.kt#L103)

### `ViveNotebook.writeCopyWithInk`

```kotlin
public fun writeCopyWithInk( destination: File, strokes: List<StoredInkStroke>, erases: List<StoredInkErase>, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `destination` | `File` | `Required` | New .vive copy path; must differ from source and must not already exist. |
| `strokes` | `List<StoredInkStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |
| `erases` | `List<StoredInkErase>` | `Required` | Stored erase operations with their target links; tombstones are retained as input data. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/ViveNotebook.kt#L108)

### `ViveNotebook.close`

```kotlin
override fun close()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/ViveNotebook.kt#L227)

### `ViveNotebook.open`

```kotlin
public fun open(file: File): ViveNotebook
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `file` | `File` | `Required` | Input .vive archive or output PNG path, as specified by the operation. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/ViveNotebook.kt#L233)

### `NotebookPage`

```kotlin
public class NotebookPage( public val pageId: String, public val strokes: List<StoredInkStroke>, public val erases: List<StoredInkErase>, public val moves: List<StoredInkMove>, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `strokes` | `List<StoredInkStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |
| `erases` | `List<StoredInkErase>` | `Required` | Stored erase operations with their target links; tombstones are retained as input data. |
| `moves` | `List<StoredInkMove>` | `Required` | Stored move/resize operations with their target links. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/ViveNotebook.kt#L252)

## NotebookInkImages

### `NotebookInkImages`

Ink-only white-paper rasterization, automatic ink resolved to black, 16-pixel padding. Pass a reusable renderer and clear it afterward.

```kotlin
public object NotebookInkImages
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/NotebookInkImages.kt#L16)

### `NotebookInkImages.frame`

```kotlin
public fun frame(strokes: List<PageStroke>, maxDimension: Int = 2048): RasterPageFrame
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `strokes` | `List<PageStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |
| `maxDimension` | `Int` | `2048` | Maximum image dimension in pixels; must exceed 32; includes 16-pixel padding per side. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/NotebookInkImages.kt#L18)

### `NotebookInkImages.writePng`

```kotlin
public fun writePng( strokes: List<PageStroke>, file: File, maxDimension: Int = 2048, renderer: InkPathRenderer = InkPathRenderer(), ): RasterPage
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `strokes` | `List<PageStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |
| `file` | `File` | `Required` | Input .vive archive or output PNG path, as specified by the operation. |
| `maxDimension` | `Int` | `2048` | Maximum image dimension in pixels; must exceed 32; includes 16-pixel padding per side. |
| `renderer` | `InkPathRenderer` | `InkPathRenderer()` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/NotebookInkImages.kt#L37)

### `RasterPageFrame`

```kotlin
public data class RasterPageFrame(val width: Int, val height: Int, val scale: Float, val left: Float, val top: Float)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `width` | `Int` | `Required` | Raster/image width in pixels; cache viewport dimensions must be nonnegative. |
| `height` | `Int` | `Required` | Raster/image height in pixels; cache viewport dimensions must be nonnegative. |
| `scale` | `Float` | `Required` | Image pixels per page dp, at most 1 for the notebook fitting helper. |
| `left` | `Float` | `Required` | Left bound or image frame origin in page dp. |
| `top` | `Float` | `Required` | Top bound or image frame origin in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/NotebookInkImages.kt#L70)

### `RasterPage`

```kotlin
public data class RasterPage(val width: Int, val height: Int, val drawn: Int, val elapsedMillis: Double)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `width` | `Int` | `Required` | Raster/image width in pixels; cache viewport dimensions must be nonnegative. |
| `height` | `Int` | `Required` | Raster/image height in pixels; cache viewport dimensions must be nonnegative. |
| `drawn` | `Int` | `Required` | Number of projections actually drawn. |
| `elapsedMillis` | `Double` | `Required` | Elapsed rendering plus PNG-encoding time in milliseconds. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-testing/src/main/kotlin/com/vivenotes/byteink/testing/NotebookInkImages.kt#L73)
