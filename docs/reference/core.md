# Engine and geometry

Module: `byteink-core`. [Conventions](index.md). Signatures and defaults follow the current source.

## InkRuntime

### `InkRuntime`

load() verifies that ByteInk's loader supplies the native engine; call before importing ink.

```kotlin
public object InkRuntime
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkRuntime.kt#L12)

### `InkRuntime.load`

```kotlin
public fun load(): LoadedInkLibrary
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkRuntime.kt#L23)

## InkMeshes

### `TriangleMesh`

Geometry exports return independent snapshots; this constructor retains supplied arrays without copying or layout validation. vertexCount = positions.size / 2; triangleCount = triangles.size / 3.

```kotlin
public class TriangleMesh @UsedByNative constructor(public val positions: FloatArray, public val triangles: IntArray)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `positions` | `FloatArray` | `Required` | Copied x/y pairs in stroke coordinates; two floats per vertex. |
| `triangles` | `IntArray` | `Required` | Copied vertex indices; three indices per triangle. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L13)

### `TriangleMesh.vertexCount`

```kotlin
public val vertexCount: Int
```

Number of copied x/y vertex pairs.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L14)

### `TriangleMesh.triangleCount`

```kotlin
public val triangleCount: Int
```

Number of copied index triples.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L15)

### `InkMeshes`

Read live strokes on their authoring thread. Returned geometry survives later updates/clear.

```kotlin
public object InkMeshes
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L29)

### `InkMeshes.outlines`

```kotlin
public fun outlines(shape: PartitionedMesh, group: Int): List<FloatArray>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `shape` | `PartitionedMesh` | `Required` | Finished native mesh; retain its owner while reading. |
| `group` | `Int` | `Required` | Zero-based render-group index; less than shape.getRenderGroupCount(). |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L32)

### `InkMeshes.outlines`

```kotlin
public fun outlines(stroke: InProgressStroke, coat: Int): List<FloatArray>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `coat` | `Int` | `Required` | Zero-based brush-coat index; less than brush.family.coats.size. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L36)

### `InkMeshes.triangles`

```kotlin
public fun triangles(shape: PartitionedMesh, group: Int): List<TriangleMesh>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `shape` | `PartitionedMesh` | `Required` | Finished native mesh; retain its owner while reading. |
| `group` | `Int` | `Required` | Zero-based render-group index; less than shape.getRenderGroupCount(). |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L40)

### `InkMeshes.triangles`

```kotlin
public fun triangles(stroke: InProgressStroke, coat: Int): List<TriangleMesh>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `coat` | `Int` | `Required` | Zero-based brush-coat index; less than brush.family.coats.size. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L44)

### `InkMeshes.rendering`

```kotlin
public fun rendering(shape: PartitionedMesh, group: Int): List<StrokeMesh>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `shape` | `PartitionedMesh` | `Required` | Finished native mesh; retain its owner while reading. |
| `group` | `Int` | `Required` | Zero-based render-group index; less than shape.getRenderGroupCount(). |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L48)

### `InkMeshes.rendering`

```kotlin
public fun rendering(stroke: InProgressStroke, coat: Int): List<StrokeMesh>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `coat` | `Int` | `Required` | Zero-based brush-coat index; less than brush.family.coats.size. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/InkMeshes.kt#L52)

## StrokeMesh

### `StrokeMesh`

Owned rendering snapshot. Vertex floats: position XY (0–1), opacity shift (2), HSL shift (3–5), side derivative XY/label (6–8), forward derivative XY/label (9–11), surface UV (12–13), animation offset (14). Missing attributes are zero; indices are unsigned native values widened to Int. Constructor retains supplied arrays.

```kotlin
public class StrokeMesh @UsedByNative constructor( public val vertices: FloatArray, public val triangles: IntArray, public val attributeMask: Int, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `vertices` | `FloatArray` | `Required` | Canonical rendering attributes in stroke coordinates; 15 floats per vertex, in StrokeMesh.VERTEX_STRIDE order. |
| `triangles` | `IntArray` | `Required` | Copied vertex indices; three indices per triangle. |
| `attributeMask` | `Int` | `Required` | Bits 0–8 identify present position, opacity, HSL, side derivative/label, forward derivative/label, surface UV and animation offset attributes; missing values are zero. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/StrokeMesh.kt#L13)

### `StrokeMesh.VERTEX_STRIDE`

```kotlin
public const val VERTEX_STRIDE: Int = 15
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/StrokeMesh.kt#L19)

### `StrokeMesh.vertexCount`

```kotlin
public val vertexCount: Int
```

Number of canonical vertices: vertices.size / VERTEX_STRIDE.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/StrokeMesh.kt#L22)

### `StrokeMesh.triangleCount`

```kotlin
public val triangleCount: Int
```

Number of copied index triples.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/StrokeMesh.kt#L23)

### `StrokeMesh.hasSurfaceUv`

```kotlin
public val hasSurfaceUv: Boolean
```

Whether attributeMask bit 7 is set.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/StrokeMesh.kt#L24)

### `StrokeMesh.hasAnimationOffset`

```kotlin
public val hasAnimationOffset: Boolean
```

Whether attributeMask bit 8 is set.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/StrokeMesh.kt#L25)

## SpatialIndex

### `SpatialIndex`

Factory construction only. Queries include touching bounds, preserve list order and require exact hit tests afterward.

```kotlin
public class SpatialIndex<T>
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/SpatialIndex.kt#L17)

### `SpatialIndex.size`

```kotlin
public val size: Int
```

Total item count, including items with no queryable bounds.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/SpatialIndex.kt#L26)

### `SpatialIndex.query`

```kotlin
public fun query(box: Box): List<T>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `box` | `Box` | `Required` | Inclusive query bounds in the index's coordinate system. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/SpatialIndex.kt#L29)

### `SpatialIndex.query`

```kotlin
public fun query(xMin: Float, yMin: Float, xMax: Float, yMax: Float): List<T>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `xMin` | `Float` | `Required` | Left query edge in index units. |
| `yMin` | `Float` | `Required` | Top query edge in index units. |
| `xMax` | `Float` | `Required` | Right query edge in index units. |
| `yMax` | `Float` | `Required` | Bottom query edge in index units. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/SpatialIndex.kt#L32)

### `SpatialIndex.DEFAULT_CELL_SIZE`

```kotlin
public const val DEFAULT_CELL_SIZE: Float = 64f
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/SpatialIndex.kt#L95)

### `SpatialIndex.<T> of`

```kotlin
public fun <T> of(items: List<T>, cellSize: Float = DEFAULT_CELL_SIZE, bounds: (T) -> Box?): SpatialIndex<T>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `items` | `List<T>` | `Required` | Items to index; treat the list and indexed bounds as immutable. |
| `cellSize` | `Float` | `DEFAULT_CELL_SIZE` | Finite positive grid-cell size in the same units as the bounds. |
| `bounds` | `(T) -> Box?` | `Required` | Callback returning each item's immutable bounds, or null for no indexed geometry. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-core/src/main/kotlin/com/vivenotes/byteink/core/SpatialIndex.kt#L101)
