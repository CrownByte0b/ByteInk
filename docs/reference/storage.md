# Stored rows and codecs

Module: `byteink-kit`. [Conventions](index.md). Signatures and defaults follow the current source.

## StoredInk

### `StoredInkStroke`

Preserve original bytes/metadata. Constructor does not validate storage. No database mutation occurs in this library.

```kotlin
public data class StoredInkStroke( val id: String, val pageId: String, val seq: Int, val brushFamily: String, val brushVersion: Int, val sizeDp: Float, val colorArgb: Int, val colorFollowsTheme: Boolean?, val epsilon: Float, val stabilization: Int, val minX: Float, val minY: Float, val maxX: Float, val maxY: Float, val points: ByteArray, val enc: String, val createdAt: Long, val groupId: String? = null, val deletedAt: Long? = null, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `seq` | `Int` | `Required` | Caller-allocated stroke draw-order sequence; loading sorts by seq then ID. |
| `brushFamily` | `String` | `Required` | Stored family ID, preserved independently of fallback rendering. |
| `brushVersion` | `Int` | `Required` | Stored brush-definition version; current writes use 1. |
| `sizeDp` | `Float` | `Required` | Finite positive brush width or eraser diameter in page dp, at least epsilon. |
| `colorArgb` | `Int` | `Required` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |
| `colorFollowsTheme` | `Boolean?` | `Required` | true follows automatic ink; false fixes color; null enables legacy black/white detection. |
| `epsilon` | `Float` | `Required` | Native mesh tolerance in stroke units; catalog brushes use 0.25f. |
| `stabilization` | `Int` | `Required` | Input-model level 0–5; tool constructors validate, catalog lookup clamps. |
| `minX` | `Float` | `Required` | Stored lower horizontal mesh bound in page dp. |
| `minY` | `Float` | `Required` | Stored lower vertical mesh bound in page dp. |
| `maxX` | `Float` | `Required` | Stored upper horizontal mesh bound in page dp. |
| `maxY` | `Float` | `Required` | Stored upper vertical mesh bound in page dp. |
| `points` | `ByteArray` | `Required` | Original encoded bytes; keep unknown or unreadable blobs unchanged. |
| `enc` | `String` | `Required` | Stored encoding ID: ink/androidx1 for strokes/erases, ink/lasso-f32le1 for moves. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `groupId` | `String?` | `null` | Optional logical group ID; grouped ink selects together. |
| `deletedAt` | `Long?` | `null` | Nullable deletion/undo timestamp; non-null rows are excluded from replay. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/StoredInk.kt#L10)

### `InkEraseMode`

Case-sensitive stored names: Normal and Object. of returns null for unknown strings.

```kotlin
public enum class InkEraseMode
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/StoredInk.kt#L51)

### `InkEraseMode.stored`

```kotlin
public val stored: String
```

Case-sensitive enum name stored in ink_erases.mode: Normal or Object.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/StoredInk.kt#L60)

### `InkEraseMode.of`

```kotlin
public fun of(stored: String): InkEraseMode?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stored` | `String` | `Required` | Original stored ARGB color, or raw erase-mode name when the type is String. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/StoredInk.kt#L64)

### `StoredInkErase`

Persist the operation and its targets together. Non-null deletedAt disables the operation during replay.

```kotlin
public data class StoredInkErase( val id: String, val pageId: String, val mode: String, val sizeDp: Float, val points: ByteArray, val enc: String, val createdAt: Long, val deletedAt: Long? = null, val targetIds: List<String>, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `mode` | `String` | `Required` | Normal cuts geometry; Object removes touched disconnected components. Stored rows retain the raw string. |
| `sizeDp` | `Float` | `Required` | Finite positive brush width or eraser diameter in page dp, at least epsilon. |
| `points` | `ByteArray` | `Required` | Original encoded bytes; keep unknown or unreadable blobs unchanged. |
| `enc` | `String` | `Required` | Stored encoding ID: ink/androidx1 for strokes/erases, ink/lasso-f32le1 for moves. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `deletedAt` | `Long?` | `null` | Nullable deletion/undo timestamp; non-null rows are excluded from replay. |
| `targetIds` | `List<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/StoredInk.kt#L73)

### `StoredInkMove`

Replay translates first, then scales about the anchor. Keep the original path and target rows.

```kotlin
public data class StoredInkMove( val id: String, val pageId: String, val dxDp: Float, val dyDp: Float, val scaleX: Float = 1f, val scaleY: Float = 1f, val anchorX: Float = 0f, val anchorY: Float = 0f, val points: ByteArray, val enc: String, val createdAt: Long, val deletedAt: Long? = null, val targetIds: List<String>, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `dxDp` | `Float` | `Required` | Stored horizontal translation in page dp. |
| `dyDp` | `Float` | `Required` | Stored vertical translation in page dp. |
| `scaleX` | `Float` | `1f` | Horizontal scale multiplier; 1 leaves that axis unchanged. |
| `scaleY` | `Float` | `1f` | Vertical scale multiplier; 1 leaves that axis unchanged. |
| `anchorX` | `Float` | `0f` | Stored horizontal resize anchor in page dp. |
| `anchorY` | `Float` | `0f` | Stored vertical resize anchor in page dp. |
| `points` | `ByteArray` | `Required` | Original encoded bytes; keep unknown or unreadable blobs unchanged. |
| `enc` | `String` | `Required` | Stored encoding ID: ink/androidx1 for strokes/erases, ink/lasso-f32le1 for moves. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `deletedAt` | `Long?` | `null` | Nullable deletion/undo timestamp; non-null rows are excluded from replay. |
| `targetIds` | `List<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/StoredInk.kt#L101)

## ViveInkCodec

### `ViveInkCodec`

Nullable decoders isolate unreadable rows. Input decompression is capped at 64 MiB across gzip members; writes use pinned Android-compatible protobuf/gzip.

```kotlin
public object ViveInkCodec
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L20)

### `ViveInkCodec.ENCODING`

```kotlin
public const val ENCODING: String = "ink/androidx1"
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L23)

### `ViveInkCodec.MOVE_ENCODING`

```kotlin
public const val MOVE_ENCODING: String = "ink/lasso-f32le1"
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L26)

### `ViveInkCodec.MAX_DECOMPRESSED_BYTES`

```kotlin
public const val MAX_DECOMPRESSED_BYTES: Int = 64 * 1024 * 1024
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L32)

### `ViveInkCodec.decode`

```kotlin
public fun decode(row: StoredInkStroke): Stroke?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `row` | `StoredInkStroke` | `Required` | Original stored row; nullable decoders return null for unsupported or damaged data. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L42)

### `ViveInkCodec.hasValidInputData`

```kotlin
public fun hasValidInputData(points: ByteArray): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `points` | `ByteArray` | `Required` | Original encoded bytes; keep unknown or unreadable blobs unchanged. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L56)

### `ViveInkCodec.encodeStroke`

```kotlin
public fun encodeStroke( stroke: Stroke, id: String, pageId: String, seq: Int, brushFamily: String, stabilization: Int, colorFollowsTheme: Boolean?, createdAt: Long, groupId: String? = null, ): StoredInkStroke
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `seq` | `Int` | `Required` | Caller-allocated stroke draw-order sequence; loading sorts by seq then ID. |
| `brushFamily` | `String` | `Required` | Stored family ID, preserved independently of fallback rendering. |
| `stabilization` | `Int` | `Required` | Input-model level 0–5; tool constructors validate, catalog lookup clamps. |
| `colorFollowsTheme` | `Boolean?` | `Required` | true follows automatic ink; false fixes color; null enables legacy black/white detection. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `groupId` | `String?` | `null` | Optional logical group ID; grouped ink selects together. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L62)

### `ViveInkCodec.encodeHighlighter`

```kotlin
public fun encodeHighlighter( stroke: Stroke, id: String, pageId: String, seq: Int, createdAt: Long, groupId: String? = null, ): StoredInkStroke
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `seq` | `Int` | `Required` | Caller-allocated stroke draw-order sequence; loading sorts by seq then ID. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `groupId` | `String?` | `null` | Optional logical group ID; grouped ink selects together. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L101)

### `ViveInkCodec.encodeCopy`

```kotlin
public fun encodeCopy( source: PageStroke, stroke: Stroke, id: String, pageId: String, seq: Int, createdAt: Long, groupId: String? = null, ): StoredInkStroke
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `source` | `PageStroke` | `Required` | Source projection whose brush/version/theme metadata is copied. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `seq` | `Int` | `Required` | Caller-allocated stroke draw-order sequence; loading sorts by seq then ID. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `groupId` | `String?` | `null` | Optional logical group ID; grouped ink selects together. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L125)

### `ViveInkCodec.encodeErase`

```kotlin
public fun encodeErase( mask: Stroke, id: String, pageId: String, mode: InkEraseMode, createdAt: Long, targetIds: List<String>, ): StoredInkErase
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `mask` | `Stroke` | `Required` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `mode` | `InkEraseMode` | `Required` | Normal cuts geometry; Object removes touched disconnected components. Stored rows retain the raw string. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `targetIds` | `List<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L158)

### `ViveInkCodec.decodeErase`

```kotlin
public fun decodeErase(row: StoredInkErase): Stroke?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `row` | `StoredInkErase` | `Required` | Original stored row; nullable decoders return null for unsupported or damaged data. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L177)

### `ViveInkCodec.reloadedEraseMask`

```kotlin
public fun reloadedEraseMask(inputs: StrokeInputBatch, sizeDp: Float): Stroke?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `inputs` | `StrokeInputBatch` | `Required` | Native input batch in page/stroke units, with ordered relative timestamps. |
| `sizeDp` | `Float` | `Required` | Finite positive brush width or eraser diameter in page dp, at least epsilon. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L187)

### `ViveInkCodec.encodeMove`

```kotlin
public fun encodeMove(move: InkLassoMove, id: String, pageId: String, createdAt: Long): StoredInkMove
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `move` | `InkLassoMove` | `Required` | Completed lasso translation, including held projection keys and stored row targets. |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L191)

### `ViveInkCodec.encodeResize`

```kotlin
public fun encodeResize(resize: InkLassoResize, id: String, pageId: String, createdAt: Long): StoredInkMove
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `resize` | `InkLassoResize` | `Required` | Completed lasso resize, including anchor, scale, held keys and stored row targets. |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L204)

### `ViveInkCodec.decodeMove`

```kotlin
public fun decodeMove(row: StoredInkMove): List<InkPoint>?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `row` | `StoredInkMove` | `Required` | Original stored row; nullable decoders return null for unsupported or damaged data. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkCodec.kt#L221)

## ViveInkPage

### `LoadedInkPage`

strokes is replayed display geometry; sourceStrokes is pre-replay native geometry; operations contains validated erase/move objects. Do not persist projections as replacement rows.

```kotlin
public class LoadedInkPage
```

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `strokes` | `List<PageStroke>` | `Returned value` | Stroke entries or stored rows, as specified by the type; preserve their order. |
| `erasedAway` | `List<String>` | `Returned value` | Decoded stroke-row IDs whose last geometry was removed by replay. |
| `unreadable` | `List<String>` | `Returned value` | Live stroke-row IDs that failed decoding; keep their stored rows. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkPage.kt#L9)

### `LoadedInkPage.sourceStrokes`

```kotlin
public val sourceStrokes: List<PageStroke>
```

Immutable ordered list of decoded source-row projections before operation replay.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkPage.kt#L23)

### `LoadedInkPage.operations`

```kotlin
public val operations: List<DecodedInkOperation>
```

Immutable creation-time/ID ordered validated erase/move geometry; skipped operations are omitted.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkPage.kt#L25)

### `LoadedInkPage.constructor`

```kotlin
public constructor(strokes: List<PageStroke>, erasedAway: List<String>, unreadable: List<String>)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `strokes` | `List<PageStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |
| `erasedAway` | `List<String>` | `Required` | Decoded stroke-row IDs whose last geometry was removed by replay. |
| `unreadable` | `List<String>` | `Required` | Live stroke-row IDs that failed decoding; keep their stored rows. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkPage.kt#L27)

### `ViveInkPage`

Filter tombstones; sort strokes by seq/ID and operations by createdAt/ID. load blocks, decodes in 512-row chunks with at most four queued jobs, and leaves storage untouched.

```kotlin
public object ViveInkPage
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkPage.kt#L36)

### `ViveInkPage.load`

```kotlin
public fun load( strokes: List<StoredInkStroke>, erases: List<StoredInkErase>, moves: List<StoredInkMove>, executor: Executor? = null, onPartial: ((List<PageStroke>) -> Unit)? = null, ): LoadedInkPage
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `strokes` | `List<StoredInkStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |
| `erases` | `List<StoredInkErase>` | `Required` | Stored erase operations with their target links; tombstones are retained as input data. |
| `moves` | `List<StoredInkMove>` | `Required` | Stored move/resize operations with their target links. |
| `executor` | `Executor?` | `null` | Optional caller-owned decode executor; load waits for jobs and never shuts it down. |
| `onPartial` | `((List<PageStroke>) -> Unit)?` | `null` | Cumulative draw-order snapshots on the load caller's thread; enabled only when no live move rows exist. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkPage.kt#L47)

### `ViveInkPage.decode`

```kotlin
public fun decode(row: StoredInkStroke): PageStroke?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `row` | `StoredInkStroke` | `Required` | Original stored row; nullable decoders return null for unsupported or damaged data. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkPage.kt#L96)

## DecodedInkOperation

### `DecodedInkOperation`

Returned by page loading; Erase/Move constructors are internal. Inspect their public properties, retaining original stored rows for persistence.

```kotlin
public sealed class DecodedInkOperation
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L7)

### `DecodedInkOperation.id`

```kotlin
public abstract val id: String
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L8)

### `DecodedInkOperation.createdAt`

```kotlin
public abstract val createdAt: Long
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L9)

### `DecodedInkOperation.targetIds`

```kotlin
public abstract val targetIds: Set<String>
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L10)

### `DecodedInkOperation.Erase`

```kotlin
public class Erase
```

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | `String` | `Returned value` | Caller-supplied unique row identifier. |
| `createdAt` | `Long` | `Returned value` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `mode` | `InkEraseMode` | `Returned value` | Normal cuts geometry; Object removes touched disconnected components. Stored rows retain the raw string. |
| `mask` | `Stroke` | `Returned value` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L12)

### `DecodedInkOperation.Erase.targetIds`

```kotlin
override val targetIds: Set<String>
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L19)

### `DecodedInkOperation.Move`

```kotlin
public class Move
```

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | `String` | `Returned value` | Caller-supplied unique row identifier. |
| `createdAt` | `Long` | `Returned value` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `dx` | `Float` | `Returned value` | Horizontal translation in page dp. |
| `dy` | `Float` | `Returned value` | Vertical translation in page dp. |
| `scaleX` | `Float` | `Returned value` | Horizontal scale multiplier; 1 leaves that axis unchanged. |
| `scaleY` | `Float` | `Returned value` | Vertical scale multiplier; 1 leaves that axis unchanged. |
| `anchor` | `InkPoint` | `Returned value` | Resize origin in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L22)

### `DecodedInkOperation.Move.targetIds`

```kotlin
override val targetIds: Set<String>
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L33)

### `DecodedInkOperation.Move.path`

```kotlin
public val path: List<InkPoint>
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/DecodedInkOperation.kt#L34)
