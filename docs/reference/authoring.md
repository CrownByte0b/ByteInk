# Input and authoring

Module: `byteink-compose`. [Conventions](index.md). Signatures and defaults follow the current source.

## InkPointerSample

### `InkPointerSample`

Coordinates must be finite, uptime nonnegative, measured pressure finite within [0, 1].

```kotlin
public data class InkPointerSample( public val x: Float, public val y: Float, public val uptimeMillis: Long, public val toolType: InputToolType = InputToolType.MOUSE, public val pressure: Float? = null, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `x` | `Float` | `Required` | Horizontal coordinate; surface pixels for pointer samples, page dp for InkPoint. |
| `y` | `Float` | `Required` | Vertical coordinate; surface pixels for pointer samples, page dp for InkPoint. |
| `uptimeMillis` | `Long` | `Required` | Nonnegative monotonic event/frame milliseconds, using one clock per gesture. |
| `toolType` | `InputToolType` | `InputToolType.MOUSE` | Device tool type; keep it constant within a gesture. |
| `pressure` | `Float?` | `null` | Measured finite pressure in [0, 1], or null when unavailable; Int brush levels use 0–5. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L12)

### `InkInputEvent`

Variants: Begin(sample), Move(sample), Finish(sample = null), Cancel. Deliver one gesture at a time.

```kotlin
public sealed interface InkInputEvent
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L29)

### `InkInputEvent.Begin`

```kotlin
public data class Begin(public val sample: InkPointerSample) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L30)

### `InkInputEvent.Move`

```kotlin
public data class Move(public val sample: InkPointerSample) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L31)

### `InkInputEvent.Finish`

```kotlin
public data class Finish(public val sample: InkPointerSample? = null) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample?` | `null` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L32)

### `InkInputEvent.Cancel`

```kotlin
public data object Cancel : InkInputEvent
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L33)

### `InkInputSource`

subscribe returns an AutoCloseable subscription. Deliver original device observations serially on the UI thread.

```kotlin
public interface InkInputSource
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L43)

### `InkInputSource.subscribe`

```kotlin
public fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `listener` | `(InkInputEvent) -> Unit` | `Required` | Serial UI-thread event callback; closing the subscription stops callbacks. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L44)

## InkAuthoringController

### `InkAuthoringController`

begin draws the first dot; append buffers; advance processes a frame; finish returns canonical finished geometry or null when idle. close is terminal.

```kotlin
public class InkAuthoringController : AutoCloseable
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L29)

### `InkAuthoringController.revision`

```kotlin
public var revision: Long
```

Compose snapshot state, initially 0; changes after processed geometry/lifecycle updates. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L30)

### `InkAuthoringController.isDrawing`

```kotlin
public var isDrawing: Boolean
```

Compose snapshot state, initially false; whether a gesture is active. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L33)

### `InkAuthoringController.hasPendingInputs`

```kotlin
public var hasPendingInputs: Boolean
```

Compose snapshot scheduling state, initially false; append can set it before geometry changes. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L37)

### `InkAuthoringController.liveStroke`

```kotlin
public val liveStroke: InProgressStroke?
```

Active engine stroke, or null while idle. Read on the controller's authoring thread.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L41)

### `InkAuthoringController.strokeToView`

```kotlin
public var strokeToView: AffineTransform
```

Captured stroke-to-surface transform; initially identity. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L45)

### `InkAuthoringController.begin`

```kotlin
public fun begin( brush: Brush, sample: InkPointerSample, strokeToView: AffineTransform = AffineTransform.IDENTITY, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `brush` | `Brush` | `Required` | Native brush captured at gesture start; keep stored metadata consistent with it. |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |
| `strokeToView` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible stroke/page-to-surface-pixel transform, snapshotted at begin. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L71)

### `InkAuthoringController.append`

```kotlin
public fun append(sample: InkPointerSample): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L103)

### `InkAuthoringController.isUpdateNeeded`

```kotlin
public fun isUpdateNeeded(): Boolean
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L124)

### `InkAuthoringController.advance`

```kotlin
public fun advance(uptimeMillis: Long): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `uptimeMillis` | `Long` | `Required` | Nonnegative monotonic event/frame milliseconds, using one clock per gesture. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L132)

### `InkAuthoringController.finish`

```kotlin
public fun finish(sample: InkPointerSample? = null): Stroke?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample?` | `null` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L152)

### `InkAuthoringController.cancel`

```kotlin
public fun cancel()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L175)

### `InkAuthoringController.close`

```kotlin
override public fun close()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L186)

## InkDrawingSurface

### `rememberInkAuthoringController`

Composable: call within a Compose composition.

```kotlin
public fun rememberInkAuthoringController(): InkAuthoringController
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkDrawingSurface.kt#L29)

### `InkDrawingSurface`

Captures brush, transform and callback at pointer down. Disabling/removal cancels; the owner must close the controller and clear the renderer.

Composable: call within a Compose composition.

```kotlin
public fun InkDrawingSurface( controller: InkAuthoringController, brush: Brush, modifier: Modifier = Modifier, strokeToView: AffineTransform = AffineTransform.IDENTITY, renderer: InkPathRenderer = remember { InkPathRenderer() }, enabled: Boolean = true, inputSource: InkInputSource? = null, onStrokeFinished: (Stroke) -> Unit, drawContent: DrawScope.() -> Unit = {}, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `controller` | `InkAuthoringController` | `Required` | UI-thread authoring controller; attach to one surface at a time. |
| `brush` | `Brush` | `Required` | Native brush captured at gesture start; keep stored metadata consistent with it. |
| `modifier` | `Modifier` | `Modifier` | Compose layout, sizing and drawing modifiers. |
| `strokeToView` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible stroke/page-to-surface-pixel transform, snapshotted at begin. |
| `renderer` | `InkPathRenderer` | `remember { InkPathRenderer() }` | Reusable drawing-thread path renderer; clear its cache when its owner is disposed. |
| `enabled` | `Boolean` | `true` | Whether to accept input; false cancels the active gesture. |
| `inputSource` | `InkInputSource?` | `null` | Optional native adapter; null uses Compose primary-pointer gestures. |
| `onStrokeFinished` | `(Stroke) -> Unit` | `Required` | Completion callback captured at pointer down; retain/store the returned stroke. |
| `drawContent` | `DrawScope.() -> Unit` | `{}` | DrawScope block drawn before live ink, usually for finished ink and paper. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkDrawingSurface.kt#L45)
