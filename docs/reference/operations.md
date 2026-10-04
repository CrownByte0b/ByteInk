# Page operations and selection

Module: `byteink-kit`. [Conventions](index.md). Signatures and defaults follow the current source.

## InkGeometry

### `InkPoint`

A value in page dp. Pointer observations use a separate pixel-based type.

```kotlin
public data class InkPoint(val x: Float, val y: Float)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `x` | `Float` | `Required` | Horizontal coordinate; surface pixels for pointer samples, page dp for InkPoint. |
| `y` | `Float` | `Required` | Vertical coordinate; surface pixels for pointer samples, page dp for InkPoint. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L4)

### `InkBounds`

Axis-aligned page rectangle; contains includes edges. unionBounds returns null for an empty list.

```kotlin
public data class InkBounds(val left: Float, val top: Float, val right: Float, val bottom: Float)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `left` | `Float` | `Required` | Left bound or image frame origin in page dp. |
| `top` | `Float` | `Required` | Top bound or image frame origin in page dp. |
| `right` | `Float` | `Required` | Right bound in page dp. |
| `bottom` | `Float` | `Required` | Bottom bound in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L7)

### `InkBounds.center`

```kotlin
public val center: InkPoint
```

Midpoint of the page rectangle.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L9)

### `InkBounds.contains`

```kotlin
public fun contains(point: InkPoint): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `point` | `InkPoint` | `Required` | Point in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L11)

### `InkBounds.translated`

```kotlin
public fun translated(dx: Float, dy: Float): InkBounds
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `dx` | `Float` | `Required` | Horizontal translation in page dp. |
| `dy` | `Float` | `Required` | Vertical translation in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L13)

### `InkBounds.scaled`

```kotlin
public fun scaled(anchor: InkPoint, scaleX: Float, scaleY: Float): InkBounds
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `anchor` | `InkPoint` | `Required` | Resize origin in page dp. |
| `scaleX` | `Float` | `Required` | Horizontal scale multiplier; 1 leaves that axis unchanged. |
| `scaleY` | `Float` | `Required` | Vertical scale multiplier; 1 leaves that axis unchanged. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L15)

### `List<InkBounds>.unionBounds`

```kotlin
public fun List<InkBounds>.unionBounds(): InkBounds?
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L25)

### `InkProjectionKey`

Live selection identity: stored row ID plus process-local piece number.

```kotlin
public data class InkProjectionKey(val strokeId: String, val projection: Int)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `strokeId` | `String` | `Required` | Stored stroke-row ID owning this process-local projection key. |
| `projection` | `Int` | `Required` | Process-local piece number; preserve with copy, never store as a database identifier. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L41)

### `InkLassoMove`

```kotlin
public data class InkLassoMove( val path: List<InkPoint>, val targetIds: Set<String>, val projections: Set<InkProjectionKey>, val dx: Float, val dy: Float, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `targetIds` | `Set<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |
| `projections` | `Set<InkProjectionKey>` | `Required` | Process-local keys of selected pieces; use only with the matching live page snapshot. |
| `dx` | `Float` | `Required` | Horizontal translation in page dp. |
| `dy` | `Float` | `Required` | Vertical translation in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L44)

### `InkLassoResize`

```kotlin
public data class InkLassoResize( val path: List<InkPoint>, val targetIds: Set<String>, val projections: Set<InkProjectionKey>, val anchor: InkPoint, val scaleX: Float, val scaleY: Float, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `targetIds` | `Set<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |
| `projections` | `Set<InkProjectionKey>` | `Required` | Process-local keys of selected pieces; use only with the matching live page snapshot. |
| `anchor` | `InkPoint` | `Required` | Resize origin in page dp. |
| `scaleX` | `Float` | `Required` | Horizontal scale multiplier; 1 leaves that axis unchanged. |
| `scaleY` | `Float` | `Required` | Vertical scale multiplier; 1 leaves that axis unchanged. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L53)

### `InkLassoSelection`

```kotlin
public data class InkLassoSelection( val path: List<InkPoint>, val targetIds: Set<String>, val projections: Set<InkProjectionKey>, val bounds: InkBounds, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `targetIds` | `Set<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |
| `projections` | `Set<InkProjectionKey>` | `Required` | Process-local keys of selected pieces; use only with the matching live page snapshot. |
| `bounds` | `InkBounds` | `Required` | Page-space bounds; a callback returning null omits that item's geometry from queries. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkGeometry.kt#L63)

## PageStroke

### `PageStroke`

A live projection, not a new stored row. Multiple pieces may share id. pageBounds/projectionKey are derived read-only properties.

```kotlin
public data class PageStroke( val id: String, val stroke: Stroke, val offsetX: Float = 0f, val offsetY: Float = 0f, val scaleX: Float = 1f, val scaleY: Float = 1f, val brushFamily: String = ViveBrushes.PRESSURE_PEN, val brushVersion: Int = ViveBrushes.BRUSH_VERSION, val stabilization: Int = 0, val colorFollowsTheme: Boolean? = null, val groupId: String? = null, val projection: Int = newProjection(), )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `offsetX` | `Float` | `0f` | Projection's horizontal translation into page dp. |
| `offsetY` | `Float` | `0f` | Projection's vertical translation into page dp. |
| `scaleX` | `Float` | `1f` | Horizontal scale multiplier; 1 leaves that axis unchanged. |
| `scaleY` | `Float` | `1f` | Vertical scale multiplier; 1 leaves that axis unchanged. |
| `brushFamily` | `String` | `ViveBrushes.PRESSURE_PEN` | Stored family ID, preserved independently of fallback rendering. |
| `brushVersion` | `Int` | `ViveBrushes.BRUSH_VERSION` | Stored brush-definition version; current writes use 1. |
| `stabilization` | `Int` | `0` | Input-model level 0–5; tool constructors validate, catalog lookup clamps. |
| `colorFollowsTheme` | `Boolean?` | `null` | true follows automatic ink; false fixes color; null enables legacy black/white detection. |
| `groupId` | `String?` | `null` | Optional logical group ID; grouped ink selects together. |
| `projection` | `Int` | `newProjection()` | Process-local piece number; preserve with copy, never store as a database identifier. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L22)

### `PageStroke.pageBounds`

```kotlin
val pageBounds: InkBounds?
```

Lazily computed page rectangle, or null for no geometry; PageStroke assumes axis-aligned positive scales.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L50)

### `PageStroke.projectionKey`

```kotlin
val projectionKey: InkProjectionKey
```

Stored row ID plus process-local piece number, carried through copy operations.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L62)

### `PageStroke.strokeToPageTransform`

```kotlin
public fun strokeToPageTransform(): AffineTransform
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L65)

### `List<PageStroke>.keepingProjectionsOf`

```kotlin
public fun List<PageStroke>.keepingProjectionsOf(previous: List<PageStroke>): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `previous` | `List<PageStroke>` | `Required` | Prior page projections used to retain matching row/piece/bounds identities. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L85)

### `Stroke.hasGeometry`

```kotlin
public val Stroke.hasGeometry: Boolean
```

Whether the native bounding box exists; guard exact native geometry tests with it.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L108)

### `PageStroke.touches`

```kotlin
public fun PageStroke.touches(mask: Stroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `mask` | `Stroke` | `Required` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L114)

### `List<PageStroke>.targetsFor`

```kotlin
public fun List<PageStroke>.targetsFor(mask: Stroke): List<String>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `mask` | `Stroke` | `Required` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L124)

### `List<PageStroke>.subtract`

```kotlin
public fun List<PageStroke>.subtract(mask: Stroke, targetIds: Collection<String>): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `mask` | `Stroke` | `Required` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |
| `targetIds` | `Collection<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L140)

### `List<PageStroke>.eraseObjects`

```kotlin
public fun List<PageStroke>.eraseObjects(mask: Stroke, targetIds: Collection<String>): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `mask` | `Stroke` | `Required` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |
| `targetIds` | `Collection<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L172)

### `InkPieceErase`

Proved Object erase for one piece, with its resulting page.

```kotlin
public data class InkPieceErase(val mask: Stroke, val rowId: String, val after: List<PageStroke>)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `mask` | `Stroke` | `Required` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |
| `rowId` | `String` | `Required` | Stored stroke-row ID owning the projection. |
| `after` | `List<PageStroke>` | `Required` | Resulting page projections; commit matching stored operations separately. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L202)

### `InkProjectionDelete`

Persist erases plus wholeRows tombstones; after is the resulting display page. Check the plan: an isolated mask may be unprovable.

```kotlin
public data class InkProjectionDelete( val erases: List<InkPieceErase>, val wholeRows: List<String>, val after: List<PageStroke>, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `erases` | `List<InkPieceErase>` | `Required` | Proved per-piece Object erase plans; encode each mask with its rowId as target. |
| `wholeRows` | `List<String>` | `Required` | Rows whose every projection is deleted; persist row tombstones. |
| `after` | `List<PageStroke>` | `Required` | Resulting page projections; commit matching stored operations separately. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L205)

### `List<PageStroke>.planProjectionDelete`

```kotlin
public fun List<PageStroke>.planProjectionDelete(held: Set<InkProjectionKey>): InkProjectionDelete
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `held` | `Set<InkProjectionKey>` | `Required` | Selected projection keys to delete; returned plans may leave unprovable pieces intact. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L219)

### `PageStroke.pointOnInk`

```kotlin
public fun PageStroke.pointOnInk(): InkPoint?
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L271)

### `List<PageStroke>.recolor`

```kotlin
public fun List<PageStroke>.recolor(ids: Collection<String>, colorArgb: Int): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `ids` | `Collection<String>` | `Required` | Stored stroke-row IDs to recolor. |
| `colorArgb` | `Int` | `Required` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L318)

### `List<PageStroke>.regroup`

```kotlin
public fun List<PageStroke>.regroup(groups: Map<String, String?>): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `groups` | `Map<String, String?>` | `Required` | Map of stored row ID to group ID; null removes a row from a group. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L333)

### `PageStroke.translatedCopy`

```kotlin
public fun PageStroke.translatedCopy(dx: Float, dy: Float): Stroke
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `dx` | `Float` | `Required` | Horizontal translation in page dp. |
| `dy` | `Float` | `Required` | Vertical translation in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L343)

### `List<PageStroke>.moveSelected`

```kotlin
public fun List<PageStroke>.moveSelected(move: InkLassoMove): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `move` | `InkLassoMove` | `Required` | Completed lasso translation, including held projection keys and stored row targets. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L373)

### `List<PageStroke>.resizeSelected`

```kotlin
public fun List<PageStroke>.resizeSelected(resize: InkLassoResize): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `resize` | `InkLassoResize` | `Required` | Completed lasso resize, including anchor, scale, held keys and stored row targets. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L382)

### `List<PageStroke>.replayMove`

```kotlin
public fun List<PageStroke>.replayMove( path: List<InkPoint>, targetIds: Collection<String>, dx: Float, dy: Float, ): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `targetIds` | `Collection<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |
| `dx` | `Float` | `Required` | Horizontal translation in page dp. |
| `dy` | `Float` | `Required` | Vertical translation in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L399)

### `List<PageStroke>.replayResize`

```kotlin
public fun List<PageStroke>.replayResize( path: List<InkPoint>, targetIds: Collection<String>, anchor: InkPoint, scaleX: Float, scaleY: Float, ): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `targetIds` | `Collection<String>` | `Required` | Stored stroke-row IDs the operation applies to; preserve exact same-page targets. |
| `anchor` | `InkPoint` | `Required` | Resize origin in page dp. |
| `scaleX` | `Float` | `Required` | Horizontal scale multiplier; 1 leaves that axis unchanged. |
| `scaleY` | `Float` | `Required` | Vertical scale multiplier; 1 leaves that axis unchanged. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageStroke.kt#L423)

## Lasso

### `DEFAULT_LASSO_EDGE_TOLERANCE`

```kotlin
public const val DEFAULT_LASSO_EDGE_TOLERANCE: Float = 4f
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L16)

### `List<PageStroke>.selectInkWithLasso`

```kotlin
public fun List<PageStroke>.selectInkWithLasso( path: List<InkPoint>, edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE, ): InkLassoSelection?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `edgeTolerance` | `Float` | `DEFAULT_LASSO_EDGE_TOLERANCE` | Lasso edge reach in page dp; default is 4f. Use finite nonnegative values. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L22)

### `List<PageStroke>.selectWithLasso`

```kotlin
public fun List<PageStroke>.selectWithLasso( path: List<InkPoint>, edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE, ): InkLassoSelection?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `edgeTolerance` | `Float` | `DEFAULT_LASSO_EDGE_TOLERANCE` | Lasso edge reach in page dp; default is 4f. Use finite nonnegative values. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L36)

### `LassoShape`

With fewer than three vertices, usable is false and contains returns false. contains tests ink containment, including edge reach, rather than only a center point.

```kotlin
public class LassoShape( public val path: List<InkPoint>, private val edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `edgeTolerance` | `Float` | `DEFAULT_LASSO_EDGE_TOLERANCE` | Lasso edge reach in page dp; default is 4f. Use finite nonnegative values. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L75)

### `LassoShape.usable`

```kotlin
public val usable: Boolean
```

Whether the lasso has at least three vertices.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L79)

### `LassoShape.acceptsWholeBox`

```kotlin
public val acceptsWholeBox: Boolean
```

Whether the polygon is convex, enabling the four-corner containment shortcut.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L87)

### `LassoShape.couldContain`

```kotlin
public fun couldContain(bounds: InkBounds): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `bounds` | `InkBounds` | `Required` | Page-space bounds; a callback returning null omits that item's geometry from queries. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L90)

### `LassoShape.contains`

```kotlin
public fun contains(stroke: PageStroke): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `PageStroke` | `Required` | Native finished or live stroke, as specified by the type. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L93)

### `pointInOrNearPolygon`

```kotlin
public fun pointInOrNearPolygon(point: InkPoint, polygon: List<InkPoint>, edgeTolerance: Float): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `point` | `InkPoint` | `Required` | Point in page dp. |
| `polygon` | `List<InkPoint>` | `Required` | Nonempty page-space polygon for point tests; use at least three finite vertices. |
| `edgeTolerance` | `Float` | `Required` | Lasso edge reach in page dp; default is 4f. Use finite nonnegative values. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L228)

### `InkPoint.distanceSquaredToSegment`

```kotlin
public fun InkPoint.distanceSquaredToSegment(start: InkPoint, end: InkPoint): Float
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `start` | `InkPoint` | `Required` | Segment start in page dp. |
| `end` | `InkPoint` | `Required` | Segment end in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L241)

### `List<InkPoint>.closesIntoALoop`

```kotlin
public fun List<InkPoint>.closesIntoALoop(touch: Float): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `touch` | `Float` | `Required` | Loop-closing reach in page dp; the algorithm applies a minimum of 2 dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/Lasso.kt#L266)

## InkPageIndex

### `InkPageIndex`

Build per page snapshot. Results use exact native tests after bounds culling, in page order. crossing uses a rectangular band without rounded end caps.

```kotlin
public class InkPageIndex(public val strokes: List<PageStroke>)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `strokes` | `List<PageStroke>` | `Required` | Stroke entries or stored rows, as specified by the type; preserve their order. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkPageIndex.kt#L19)

### `InkPageIndex.touching`

```kotlin
public fun touching(mask: Stroke): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `mask` | `Stroke` | `Required` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkPageIndex.kt#L26)

### `InkPageIndex.targetsFor`

```kotlin
public fun targetsFor(mask: Stroke): List<String>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `mask` | `Stroke` | `Required` | Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkPageIndex.kt#L32)

### `InkPageIndex.selectWithLasso`

```kotlin
public fun selectWithLasso(path: List<InkPoint>, edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE): InkLassoSelection?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `edgeTolerance` | `Float` | `DEFAULT_LASSO_EDGE_TOLERANCE` | Lasso edge reach in page dp; default is 4f. Use finite nonnegative values. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkPageIndex.kt#L35)

### `InkPageIndex.selectInkWithLasso`

```kotlin
public fun selectInkWithLasso(path: List<InkPoint>, edgeTolerance: Float = DEFAULT_LASSO_EDGE_TOLERANCE): InkLassoSelection?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `List<InkPoint>` | `Required` | Page-space lasso vertices; codec requires at least three finite points. |
| `edgeTolerance` | `Float` | `DEFAULT_LASSO_EDGE_TOLERANCE` | Lasso edge reach in page dp; default is 4f. Use finite nonnegative values. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkPageIndex.kt#L48)

### `InkPageIndex.at`

```kotlin
public fun at(point: InkPoint, reach: Float): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `point` | `InkPoint` | `Required` | Point in page dp. |
| `reach` | `Float` | `Required` | Half-side of the square point-hit region in page dp; use finite nonnegative values. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkPageIndex.kt#L54)

### `InkPageIndex.crossing`

```kotlin
public fun crossing(from: InkPoint, to: InkPoint, width: Float): List<PageStroke>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `from` | `InkPoint` | `Required` | Band start in page dp. |
| `to` | `InkPoint` | `Required` | Band end in page dp. |
| `width` | `Float` | `Required` | Finite nonnegative eraser-band diameter in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/InkPageIndex.kt#L68)

## PageBounds

### `PageBounds`

Origin walls are MIN_X = MIN_Y = 0f. clampTranslation returns the applied delta; clampScale caps growth toward that corner. Supply finite positive scale multipliers.

```kotlin
public object PageBounds
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageBounds.kt#L9)

### `PageBounds.MIN_X`

```kotlin
public const val MIN_X: Float = 0f
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageBounds.kt#L11)

### `PageBounds.MIN_Y`

```kotlin
public const val MIN_Y: Float = 0f
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageBounds.kt#L12)

### `PageBounds.clampTranslation`

```kotlin
public fun clampTranslation(bounds: InkBounds, dx: Float, dy: Float): InkPoint
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `bounds` | `InkBounds` | `Required` | Page-space bounds; a callback returning null omits that item's geometry from queries. |
| `dx` | `Float` | `Required` | Horizontal translation in page dp. |
| `dy` | `Float` | `Required` | Vertical translation in page dp. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageBounds.kt#L19)

### `PageBounds.clampScale`

```kotlin
public fun clampScale(bounds: InkBounds, anchor: InkPoint, scaleX: Float, scaleY: Float): InkPoint
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `bounds` | `InkBounds` | `Required` | Page-space bounds; a callback returning null omits that item's geometry from queries. |
| `anchor` | `InkPoint` | `Required` | Resize origin in page dp. |
| `scaleX` | `Float` | `Required` | Horizontal scale multiplier; 1 leaves that axis unchanged. |
| `scaleY` | `Float` | `Required` | Vertical scale multiplier; 1 leaves that axis unchanged. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-kit/src/main/kotlin/com/vivenotes/byteink/vive/PageBounds.kt#L27)
