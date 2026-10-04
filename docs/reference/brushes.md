# Brushes and tools

Module: `byteink-vive`. [Conventions](index.md). Signatures and defaults follow the current source.

## ViveBrushes

### `ViveBrushes`

Families: marker, dashed-line, highlighter, pressure-pen, calligraphy-v1-p0 through p5. Unknown IDs render as pressure-pen; original metadata is preserved.

```kotlin
public object ViveBrushes
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L27)

### `ViveBrushes.MARKER`

```kotlin
public const val MARKER: String = "marker"
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L29)

### `ViveBrushes.DASHED_LINE`

```kotlin
public const val DASHED_LINE: String = "dashed-line"
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L30)

### `ViveBrushes.HIGHLIGHTER`

```kotlin
public const val HIGHLIGHTER: String = "highlighter"
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L31)

### `ViveBrushes.PRESSURE_PEN`

```kotlin
public const val PRESSURE_PEN: String = "pressure-pen"
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L34)

### `ViveBrushes.MAX_CALLIGRAPHY_PRESSURE`

```kotlin
public const val MAX_CALLIGRAPHY_PRESSURE: Int = 5
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L39)

### `ViveBrushes.DEFAULT_CALLIGRAPHY_PRESSURE`

```kotlin
public const val DEFAULT_CALLIGRAPHY_PRESSURE: Int = 3
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L42)

### `ViveBrushes.MAX_STABILIZATION`

```kotlin
public const val MAX_STABILIZATION: Int = 5
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L45)

### `ViveBrushes.BRUSH_VERSION`

```kotlin
public const val BRUSH_VERSION: Int = 1
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L48)

### `ViveBrushes.EPSILON`

```kotlin
public const val EPSILON: Float = 0.25f
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L54)

### `ViveBrushes.calligraphy`

```kotlin
public fun calligraphy(pressure: Int): String
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `pressure` | `Int` | `Required` | Measured finite pressure in [0, 1], or null when unavailable; Int brush levels use 0–5. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L60)

### `ViveBrushes.penFamilyId`

```kotlin
public fun penFamilyId(solidLine: Boolean, fountain: Boolean, pressure: Int): String
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `solidLine` | `Boolean` | `Required` | False selects the dashed family. |
| `fountain` | `Boolean` | `Required` | True selects marker for a solid pen; false selects calligraphy. |
| `pressure` | `Int` | `Required` | Measured finite pressure in [0, 1], or null when unavailable; Int brush levels use 0–5. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L68)

### `ViveBrushes.inputModelFor`

```kotlin
public fun inputModelFor(stabilization: Int): BrushFamily.InputModel
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stabilization` | `Int` | `Required` | Input-model level 0–5; tool constructors validate, catalog lookup clamps. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L83)

### `ViveBrushes.family`

```kotlin
public fun family(id: String, stabilization: Int): BrushFamily
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `id` | `String` | `Required` | Stored brush family ID; unknown IDs fall back to pressure-pen. |
| `stabilization` | `Int` | `Required` | Input-model level 0–5; tool constructors validate, catalog lookup clamps. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L107)

### `ViveBrushes.brush`

```kotlin
public fun brush(familyId: String, stabilization: Int, colorArgb: Int, size: Float): Brush
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `familyId` | `String` | `Required` | Brush family ID; use ViveBrushes constants or calligraphy(level). |
| `stabilization` | `Int` | `Required` | Input-model level 0–5; tool constructors validate, catalog lookup clamps. |
| `colorArgb` | `Int` | `Required` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |
| `size` | `Float` | `Required` | Finite positive brush width in page/stroke units, at least epsilon. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L222)

### `ViveBrushes.highlighter`

```kotlin
public fun highlighter(colorArgb: Int, size: Float): Brush
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `colorArgb` | `Int` | `Required` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |
| `size` | `Float` | `Required` | Finite positive brush width in page/stroke units, at least epsilon. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L234)

### `ViveBrushes.eraseMask`

```kotlin
public fun eraseMask(inputs: StrokeInputBatch, sizeDp: Float): Stroke
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `inputs` | `StrokeInputBatch` | `Required` | Native input batch in page/stroke units, with ordered relative timestamps. |
| `sizeDp` | `Float` | `Required` | Finite positive brush width or eraser diameter in page dp, at least epsilon. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveBrushes.kt#L237)

## ViveInkTool

### `AuthoredViveStroke`

canonicalStroke lazily rebuilds row through the codec; use that geometry for a save/reload-consistent preview.

```kotlin
public data class AuthoredViveStroke(public val stroke: Stroke, public val row: StoredInkStroke)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `row` | `StoredInkStroke` | `Required` | Original stored row; nullable decoders return null for unsupported or damaged data. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkTool.kt#L7)

### `AuthoredViveStroke.canonicalStroke`

```kotlin
public val canonicalStroke: Stroke
```

Lazy native rebuild of the authored stored row, matching save/reload geometry.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkTool.kt#L9)

### `ViveInkTool`

Authoring rejects unknown families and levels outside 0–5. Highlighter requires stabilization = 0 and colorFollowsTheme = false. complete requires the captured brush.

```kotlin
public class ViveInkTool( public val familyId: String = ViveBrushes.MARKER, public val stabilization: Int = 0, public val colorArgb: Int = 0xff202020.toInt(), public val sizeDp: Float = 3f, public val colorFollowsTheme: Boolean? = false, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `familyId` | `String` | `ViveBrushes.MARKER` | Brush family ID; use ViveBrushes constants or calligraphy(level). |
| `stabilization` | `Int` | `0` | Input-model level 0–5; tool constructors validate, catalog lookup clamps. |
| `colorArgb` | `Int` | `0xff202020.toInt()` | 32-bit ARGB including alpha; nullable drawing overrides use the brush color when null. |
| `sizeDp` | `Float` | `3f` | Finite positive brush width or eraser diameter in page dp, at least epsilon. |
| `colorFollowsTheme` | `Boolean?` | `false` | true follows automatic ink; false fixes color; null enables legacy black/white detection. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkTool.kt#L19)

### `ViveInkTool.brush`

```kotlin
public val brush: Brush
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkTool.kt#L36)

### `ViveInkTool.complete`

```kotlin
public fun complete( stroke: Stroke, id: String, pageId: String, seq: Int, createdAt: Long, groupId: String? = null, ): AuthoredViveStroke
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stroke` | `Stroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `id` | `String` | `Required` | Caller-supplied unique row identifier. |
| `pageId` | `String` | `Required` | Identifier of the page owning all supplied rows/targets. |
| `seq` | `Int` | `Required` | Caller-allocated stroke draw-order sequence; loading sorts by seq then ID. |
| `createdAt` | `Long` | `Required` | Creation clock in the application's stored timestamp units, normally epoch milliseconds. |
| `groupId` | `String?` | `null` | Optional logical group ID; grouped ink selects together. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/ViveInkTool.kt#L39)

## InkColors

### `AUTOMATIC_LIGHT`

```kotlin
public const val AUTOMATIC_LIGHT: Int = 0xFFFFFFFF.toInt()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/InkColors.kt#L4)

### `AUTOMATIC_DARK`

```kotlin
public const val AUTOMATIC_DARK: Int = 0xFF000000.toInt()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/InkColors.kt#L7)

### `automaticInkFor`

```kotlin
public fun automaticInkFor(isDark: Boolean): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `isDark` | `Boolean` | `Required` | True chooses white automatic ink; false chooses black. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/InkColors.kt#L10)

### `automaticColorOr`

```kotlin
public fun automaticColorOr(stored: Int, followsTheme: Boolean?, canvasInk: Int): Int
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `stored` | `Int` | `Required` | Original stored ARGB color, or raw erase-mode name when the type is String. |
| `followsTheme` | `Boolean?` | `Required` | Stored theme flag: true automatic, false fixed, null legacy behavior. |
| `canvasInk` | `Int` | `Required` | Resolved automatic canvas ARGB, usually automaticInkFor(isDark). |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-vive/src/main/kotlin/com/vivenotes/byteink/vive/InkColors.kt#L18)
