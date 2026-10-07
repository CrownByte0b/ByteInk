# Input and authoring

Module: `byteink-compose`. [Conventions](index.md). Signatures and defaults follow the current source.

## InkPointerSample

### `InkPointerSample`

Coordinates must be finite and uptime nonnegative. Optional axes must satisfy their documented ranges; availability/tool type is frozen at begin and later missing axes hold their last measured values.

```kotlin
public data class InkPointerSample( public val x: Float, public val y: Float, public val uptimeMillis: Long, public val toolType: InputToolType = InputToolType.MOUSE, public val pressure: Float? = null, public val tiltRadians: Float? = null, public val orientationRadians: Float? = null, public val strokeUnitLengthCm: Float? = null, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `x` | `Float` | `Required` | Horizontal coordinate; input-surface units for pointer samples, page dp for InkPoint. |
| `y` | `Float` | `Required` | Vertical coordinate; input-surface units for pointer samples, page dp for InkPoint. |
| `uptimeMillis` | `Long` | `Required` | Nonnegative monotonic event/frame milliseconds, using one clock per gesture. |
| `toolType` | `InputToolType` | `InputToolType.MOUSE` | Device tool type; keep it constant within a gesture. |
| `pressure` | `Float?` | `null` | Measured finite pressure in [0, 1], or null when unavailable; Int brush levels use 0–5. |
| `tiltRadians` | `Float?` | `null` | Measured finite shaft tilt in [0, pi/2], or null when unavailable; zero is perpendicular to the surface. |
| `orientationRadians` | `Float?` | `null` | Measured finite shaft azimuth in [0, 2*pi), from local +x toward +y, or null when unavailable; not barrel rotation. |
| `strokeUnitLengthCm` | `Float?` | `null` | Calibrated finite positive centimeters per input-surface unit, or null when unavailable; do not infer from logical DPI. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L13)

### `InkInputEvent`

Pointer-addressed variants: Begin, Move, Batch, Predict, Finish and CancelPointer; Cancel discards all pointers. Batch preserves real history and optionally replaces predictions. Default pointerId is 0L where declared.

```kotlin
public sealed interface InkInputEvent
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L45)

### `InkInputEvent.Begin`

```kotlin
public data class Begin(public val sample: InkPointerSample, public val pointerId: Long = 0L) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |
| `pointerId` | `Long` | `0L` | Stable pointer identifier within the event stream; default 0 preserves single-pointer callers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L46)

### `InkInputEvent.Move`

```kotlin
public data class Move(public val sample: InkPointerSample, public val pointerId: Long = 0L) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |
| `pointerId` | `Long` | `0L` | Stable pointer identifier within the event stream; default 0 preserves single-pointer callers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L47)

### `InkInputEvent.Batch`

```kotlin
public data class Batch( public val samples: List<InkPointerSample>, public val predictedSamples: List<InkPointerSample>? = null, public val pointerId: Long = 0L, ) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `samples` | `List<InkPointerSample>` | `Required` | Ordered real observations; all accepted history is submitted before updating the speculative tail. |
| `predictedSamples` | `List<InkPointerSample>?` | `null` | Replaceable speculative observations; empty clears predictions. Null requests automatic session prediction; the regular surface only forwards explicit predictions. |
| `pointerId` | `Long` | `0L` | Stable pointer identifier within the event stream; default 0 preserves single-pointer callers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L49)

### `InkInputEvent.Predict`

```kotlin
public data class Predict(public val samples: List<InkPointerSample>, public val pointerId: Long = 0L) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `samples` | `List<InkPointerSample>` | `Required` | Replaceable speculative observations in input-surface units; an empty list clears the tail. Never added to saved real inputs. |
| `pointerId` | `Long` | `0L` | Stable pointer identifier within the event stream; default 0 preserves single-pointer callers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L55)

### `InkInputEvent.Finish`

```kotlin
public data class Finish(public val sample: InkPointerSample? = null, public val pointerId: Long = 0L) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample?` | `null` | Real pointer observation; a nullable finishing sample may be omitted. |
| `pointerId` | `Long` | `0L` | Stable pointer identifier within the event stream; default 0 preserves single-pointer callers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L56)

### `InkInputEvent.CancelPointer`

```kotlin
public data class CancelPointer(public val pointerId: Long) : InkInputEvent
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `pointerId` | `Long` | `Required` | Stable pointer identifier within the event stream; default 0 preserves single-pointer callers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L57)

### `InkInputEvent.Cancel`

```kotlin
public data object Cancel : InkInputEvent
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L58)

### `InkInputSource`

subscribe returns an AutoCloseable subscription. Deliver original device observations serially on the UI thread; native subscriptions and closing run on the AWT EDT.

```kotlin
public interface InkInputSource
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L68)

### `InkInputSource.subscribe`

```kotlin
public fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `listener` | `(InkInputEvent) -> Unit` | `Required` | Serial UI-thread event callback; closing the subscription stops callbacks. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkPointerSample.kt#L69)

## InkInputPredictor

### `InkInputPredictor`

Records real observations and returns replaceable predictions on the same input clock. Use one predictor per gesture; predictions never become saved inputs.

```kotlin
public interface InkInputPredictor
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkInputPredictor.kt#L6)

### `InkInputPredictor.record`

```kotlin
public fun record(sample: InkPointerSample)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkInputPredictor.kt#L7)

### `InkInputPredictor.predict`

```kotlin
public fun predict(targetUptimeMillis: Long): List<InkPointerSample>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `targetUptimeMillis` | `Long` | `Required` | Nonnegative target time on the same monotonic millisecond clock as recorded observations. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkInputPredictor.kt#L8)

### `InkInputPredictor.reset`

```kotlin
public fun reset()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkInputPredictor.kt#L9)

### `InkLinearPredictor`

Bounded linear position prediction from recent real observations; retains measured optional axes. Reversals, stationary input, tool changes and long gaps reset motion prediction.

```kotlin
public class InkLinearPredictor( public val maxPredictionMillis: Long = 24L, public val maxPredictionDistance: Float = 32f, ) : InkInputPredictor
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `maxPredictionMillis` | `Long` | `24L` | Maximum forecast horizon in milliseconds, within 1–100. |
| `maxPredictionDistance` | `Float` | `32f` | Finite positive forecast-distance ceiling in the same units as pointer coordinates. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkInputPredictor.kt#L17)

### `InkLinearPredictor.record`

```kotlin
override public fun record(sample: InkPointerSample)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkInputPredictor.kt#L30)

### `InkLinearPredictor.predict`

```kotlin
override public fun predict(targetUptimeMillis: Long): List<InkPointerSample>
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `targetUptimeMillis` | `Long` | `Required` | Nonnegative target time on the same monotonic millisecond clock as recorded observations. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkInputPredictor.kt#L56)

### `InkLinearPredictor.reset`

```kotlin
override public fun reset()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkInputPredictor.kt#L68)

## InkAuthoringController

### `InkAuthoringController`

Single-pointer authoring. begin draws the first dot; append buffers; advance processes geometry; finish clears predictions and returns canonical real-input geometry or null when idle. close is terminal.

```kotlin
public class InkAuthoringController : AutoCloseable
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L35)

### `InkAuthoringController.revision`

```kotlin
public var revision: Long
```

Compose snapshot state, initially 0; changes after processed geometry/lifecycle updates. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L36)

### `InkAuthoringController.isDrawing`

```kotlin
public var isDrawing: Boolean
```

Compose snapshot state, initially false; whether a gesture is active. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L39)

### `InkAuthoringController.hasPendingInputs`

```kotlin
public var hasPendingInputs: Boolean
```

Compose snapshot scheduling state, initially false; append can set it before geometry changes. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L43)

### `InkAuthoringController.liveStroke`

```kotlin
public val liveStroke: InProgressStroke?
```

Active engine stroke, or null while idle. Read on the controller's authoring thread.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L47)

### `InkAuthoringController.strokeToView`

```kotlin
public var strokeToView: AffineTransform
```

Captured stroke-to-surface transform; initially identity. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L51)

### `InkAuthoringController.begin`

```kotlin
public fun begin( brush: Brush, sample: InkPointerSample, strokeToView: AffineTransform = AffineTransform.IDENTITY, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `brush` | `Brush` | `Required` | Native brush captured at gesture start; keep stored metadata consistent with it. |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |
| `strokeToView` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible stroke/page-to-input-surface transform, snapshotted at begin; regular Compose uses pixels, direct panels use AWT logical units. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L83)

### `InkAuthoringController.append`

```kotlin
public fun append(sample: InkPointerSample): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample` | `Required` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L121)

### `InkAuthoringController.setPredictedInputs`

Replaces the speculative tail; an empty list retracts it. Real input also retracts old forecasts. The caller schedules advance and forecast expiration.

```kotlin
public fun setPredictedInputs(samples: List<InkPointerSample>)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `samples` | `List<InkPointerSample>` | `Required` | Replaceable speculative observations in input-surface units; an empty list clears the tail. Never added to saved real inputs. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L150)

### `InkAuthoringController.isUpdateNeeded`

```kotlin
public fun isUpdateNeeded(): Boolean
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L191)

### `InkAuthoringController.advance`

```kotlin
public fun advance(uptimeMillis: Long): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `uptimeMillis` | `Long` | `Required` | Nonnegative monotonic event/frame milliseconds, using one clock per gesture. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L199)

### `InkAuthoringController.finish`

```kotlin
public fun finish(sample: InkPointerSample? = null): Stroke?
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `sample` | `InkPointerSample?` | `null` | Real pointer observation; a nullable finishing sample may be omitted. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L219)

### `InkAuthoringController.cancel`

```kotlin
public fun cancel()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L246)

### `InkAuthoringController.close`

```kotlin
override public fun close()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringController.kt#L260)

## InkAuthoringSession

### `InkLiveStroke`

Borrowed active engine stroke and its captured transform. Read on the session's authoring thread; do not mutate it or retain it across retirement/reuse.

```kotlin
public data class InkLiveStroke( public val pointerId: Long, public val stroke: InProgressStroke, public val strokeToView: AffineTransform, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `pointerId` | `Long` | `Required` | Stable pointer identifier within the event stream; default 0 preserves single-pointer callers. |
| `stroke` | `InProgressStroke` | `Required` | Native finished or live stroke, as specified by the type. |
| `strokeToView` | `AffineTransform` | `Required` | Finite invertible stroke/page-to-input-surface transform, snapshotted at begin; regular Compose uses pixels, direct panels use AWT logical units. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L12)

### `InkAuthoringSession`

Concurrent-pointer authoring with an independent controller/predictor per gesture. Captures brush, transform and completion callback at Begin; completion returns only real-input canonical strokes. close is terminal.

```kotlin
public class InkAuthoringSession( private val predictorFactory: (() -> InkInputPredictor)? = { InkLinearPredictor() }, public val predictionMillis: Long = 12L, ) : AutoCloseable
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `predictorFactory` | `(() -> InkInputPredictor)?` | `{ InkLinearPredictor() }` | Factory for a separate predictor per gesture; null disables automatic forecasting while allowing explicit predictions. |
| `predictionMillis` | `Long` | `12L` | Session forecast horizon in milliseconds, within 0–100; zero disables automatic forecasting. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L23)

### `InkAuthoringSession.revision`

```kotlin
public var revision: Long
```

Compose snapshot state, initially 0; changes after processed geometry/lifecycle updates. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L28)

### `InkAuthoringSession.activePointerIds`

```kotlin
public val activePointerIds: Set<Long>
```

Snapshot of currently active pointer identifiers; read on the session's authoring thread.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L31)

### `InkAuthoringSession.liveStrokes`

```kotlin
public val liveStrokes: List<InkLiveStroke>
```

Snapshot list of borrowed active engines and captured transforms; engines may be recycled after retirement.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L32)

### `InkAuthoringSession.handle`

Handles events serially; Begin replaces the same pointer's old gesture, inactive moves are ignored, CancelPointer discards one pointer, Cancel discards all. Completion runs synchronously using the callback captured at Begin.

```kotlin
public fun handle( event: InkInputEvent, brush: Brush, strokeToView: AffineTransform = AffineTransform.IDENTITY, onStrokeFinished: (Long, Stroke) -> Unit, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `event` | `InkInputEvent` | `Required` | Pointer event dispatched serially on the session's authoring thread. |
| `brush` | `Brush` | `Required` | Native brush captured at gesture start; keep stored metadata consistent with it. |
| `strokeToView` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible stroke/page-to-input-surface transform, snapshotted at begin; regular Compose uses pixels, direct panels use AWT logical units. |
| `onStrokeFinished` | `(Long, Stroke) -> Unit` | `Required` | Captured at Begin; synchronously receives pointerId and a canonical real-input Stroke on completion. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L50)

### `InkAuthoringSession.advance`

Advance with a time on the same input clock; retires expired forecasts and returns whether geometry changed.

```kotlin
public fun advance(uptimeMillis: Long): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `uptimeMillis` | `Long` | `Required` | Nonnegative monotonic event/frame milliseconds, using one clock per gesture. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L101)

### `InkAuthoringSession.advanceNow`

Maps a monotonic nanosecond clock to each gesture before advancing; convenient for native hosts.

```kotlin
public fun advanceNow(nanoTime: Long = System.nanoTime()): Boolean
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `nanoTime` | `Long` | `System.nanoTime()` | Current monotonic nanoseconds, normally System.nanoTime(); the session maps this to each gesture's input clock. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L112)

### `InkAuthoringSession.isUpdateNeeded`

```kotlin
public fun isUpdateNeeded(): Boolean
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L124)

### `InkAuthoringSession.needsAnimationTick`

Check alongside isUpdateNeeded when scheduling; includes forecast expiry even when current geometry needs no update.

```kotlin
public fun needsAnimationTick(): Boolean
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L127)

### `InkAuthoringSession.cancel`

```kotlin
public fun cancel(pointerId: Long)
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `pointerId` | `Long` | `Required` | Stable pointer identifier within the event stream; default 0 preserves single-pointer callers. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L157)

### `InkAuthoringSession.cancelAll`

```kotlin
public fun cancelAll()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L161)

### `InkAuthoringSession.close`

```kotlin
override public fun close()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkAuthoringSession.kt#L175)

## InkDrawingSurface

### `rememberInkAuthoringController`

Composable: call within a Compose composition.

```kotlin
public fun rememberInkAuthoringController(): InkAuthoringController
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkDrawingSurface.kt#L29)

### `InkDrawingSurface`

Single-controller Compose frame loop; keeps the first active pointer and forwards explicit predictions without generating or expiring them automatically. Captures brush/transform/callback at Begin. Disabling/removal cancels; the owner closes the controller and renderer.

Composable: call within a Compose composition.

```kotlin
public fun InkDrawingSurface( controller: InkAuthoringController, brush: Brush, modifier: Modifier = Modifier, strokeToView: AffineTransform = AffineTransform.IDENTITY, renderer: InkPathRenderer = remember { InkPathRenderer() }, enabled: Boolean = true, inputSource: InkInputSource? = null, onStrokeFinished: (Stroke) -> Unit, drawContent: DrawScope.() -> Unit = {}, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `controller` | `InkAuthoringController` | `Required` | UI-thread authoring controller; attach to one surface at a time. |
| `brush` | `Brush` | `Required` | Native brush captured at gesture start; keep stored metadata consistent with it. |
| `modifier` | `Modifier` | `Modifier` | Compose layout, sizing and drawing modifiers. |
| `strokeToView` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible stroke/page-to-local-Compose-pixel transform, captured at Begin; include device density, zoom and scroll. |
| `renderer` | `InkPathRenderer` | `remember { InkPathRenderer() }` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `enabled` | `Boolean` | `true` | Whether to accept input; false cancels the active gesture. |
| `inputSource` | `InkInputSource?` | `null` | Optional native adapter; null uses Compose primary-pointer gestures. |
| `onStrokeFinished` | `(Stroke) -> Unit` | `Required` | Completion callback captured at pointer down; retain/store the returned stroke. |
| `drawContent` | `DrawScope.() -> Unit` | `{}` | DrawScope block drawn before live ink, usually for finished ink and paper. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkDrawingSurface.kt#L45)

### `InkDrawingSurface`

Single-controller Compose frame loop; keeps the first active pointer and forwards explicit predictions without generating or expiring them automatically. Captures brush/transform/callback at Begin. Disabling/removal cancels; the owner closes the controller and renderer.

Composable: call within a Compose composition.

```kotlin
public fun InkDrawingSurface( controller: InkAuthoringController, brush: Brush, modifier: Modifier = Modifier, strokeToView: AffineTransform = AffineTransform.IDENTITY, renderer: InkRenderer, enabled: Boolean = true, inputSource: InkInputSource? = null, onStrokeFinished: (Stroke) -> Unit, drawContent: DrawScope.() -> Unit = {}, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `controller` | `InkAuthoringController` | `Required` | UI-thread authoring controller; attach to one surface at a time. |
| `brush` | `Brush` | `Required` | Native brush captured at gesture start; keep stored metadata consistent with it. |
| `modifier` | `Modifier` | `Modifier` | Compose layout, sizing and drawing modifiers. |
| `strokeToView` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible stroke/page-to-local-Compose-pixel transform, captured at Begin; include device density, zoom and scroll. |
| `renderer` | `InkRenderer` | `Required` | Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal. |
| `enabled` | `Boolean` | `true` | Whether to accept input; false cancels the active gesture. |
| `inputSource` | `InkInputSource?` | `null` | Optional native adapter; null uses Compose primary-pointer gestures. |
| `onStrokeFinished` | `(Stroke) -> Unit` | `Required` | Completion callback captured at pointer down; retain/store the returned stroke. |
| `drawContent` | `DrawScope.() -> Unit` | `{}` | DrawScope block drawn before live ink, usually for finished ink and paper. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkDrawingSurface.kt#L62)

## NativeInkInputSource

### `InkNativeBackend`

Values: WINDOWS_POINTER (WM_POINTER), LINUX_XINPUT2 (X11/XWayland), LINUX_WAYLAND_TABLET (JBR WLToolkit). Selection follows the actual AWT toolkit, not only session environment variables.

```kotlin
public enum class InkNativeBackend
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/NativeInkInputSource.kt#L22)

### `NativeInkInputSource`

Java 25+ native pen/history adapter for Windows/Linux x86_64. Native Wayland requires JBR 25 WLToolkit, native access, and opening java.desktop/sun.awt.wl. Uses the matching configured top-level surface; no raw reads from JBR's borrowed display socket.

```kotlin
public class NativeInkInputSource( private val component: Component, private val windowHandle: Long, private val pixelsPerLocalUnit: () -> Float = { 1f }, private val centimetersPerNativePixel: Float? = null, private val onFailure: (Throwable) -> Unit = { throw IllegalStateException("Native ink capture failed", it) }, ) : InkInputSource
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `component` | `Component` | `Required` | Displayable AWT input component; construct and manage the adapter on the event dispatch thread. |
| `windowHandle` | `Long` | `Required` | Nonzero matching native HWND, XID, or configured JBR Wayland top-level wl_surface handle; not an AWT object identity. |
| `pixelsPerLocalUnit` | `() -> Float` | `{ 1f }` | Callback returning finite positive native pixels per component-local logical unit; native points are divided by this scale. |
| `centimetersPerNativePixel` | `Float?` | `null` | Optional calibrated finite positive centimeters per native pixel; logical display DPI is not physical calibration. |
| `onFailure` | `(Throwable) -> Unit` | `{ throw IllegalStateException("Native ink capture failed", it) }` | EDT callback after capture is stopped and gestures cancelled; the default throws an IllegalStateException. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/NativeInkInputSource.kt#L38)

### `NativeInkInputSource.backend`

```kotlin
public val backend: InkNativeBackend
```

Native capture backend selected from the operating system and actual AWT toolkit.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/NativeInkInputSource.kt#L45)

### `NativeInkInputSource.subscribe`

EDT-only; component must be displayable, with one subscription per source. Recreate after a Wayland hide/show. Acquisition failures are explicit; native runtime errors deliver Cancel, close capture, then invoke onFailure. Listener exceptions close capture and are rethrown.

```kotlin
override public fun subscribe(listener: (InkInputEvent) -> Unit): AutoCloseable
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `listener` | `(InkInputEvent) -> Unit` | `Required` | Serial UI-thread event callback; closing the subscription stops callbacks. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/NativeInkInputSource.kt#L58)

## InkLowLatencyPanel

### `InkLowLatencyPanel`

EDT-owned direct Skia authoring panel; native input and simultaneous-pointer prediction bypass Compose's frame clock. Uses AWT local logical coordinates. Default renderer is an owned InkMeshRenderer; supplied renderers are borrowed. Native Wayland presents through software Swing painting.

```kotlin
public class InkLowLatencyPanel( public var brush: Brush, public var onStrokeFinished: (Long, Stroke) -> Unit, public var strokeToView: AffineTransform = AffineTransform.IDENTITY, renderer: InkRenderer? = null, private val inputSource: InkInputSource? = null, predictorFactory: (() -> InkInputPredictor)? = { InkLinearPredictor() }, public var drawContent: (Canvas, Int, Int) -> Unit = { _, _, _ -> }, private val centimetersPerNativePixel: Float? = null, ) : JPanel(BorderLayout()), AutoCloseable
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `brush` | `Brush` | `Required` | Native brush captured at gesture start; keep stored metadata consistent with it. |
| `onStrokeFinished` | `(Long, Stroke) -> Unit` | `Required` | Captured (pointerId, canonical real-input Stroke) callback; update finished content synchronously and enqueue persistence. |
| `strokeToView` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible stroke/page-to-AWT-local-logical-unit transform, captured per pointer at Begin; the panel applies device scale itself. |
| `renderer` | `InkRenderer?` | `null` | Borrowed caller-owned renderer, or null for an owned InkMeshRenderer; the panel closes only its own default renderer. |
| `inputSource` | `InkInputSource?` | `null` | Optional caller-supplied serial input source; null acquires built-in native input, with explicit acquisition failures. |
| `predictorFactory` | `(() -> InkInputPredictor)?` | `{ InkLinearPredictor() }` | Factory for a separate predictor per gesture; null disables automatic forecasting while allowing explicit predictions. |
| `drawContent` | `(Canvas, Int, Int) -> Unit` | `{ _, _, _ -> }` | Compose Canvas callback drawn before wet ink; width/height and canvas coordinates use AWT component-local logical units. |
| `centimetersPerNativePixel` | `Float?` | `null` | Optional calibrated finite positive centimeters per native pixel; logical display DPI is not physical calibration. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L42)

### `InkLowLatencyPanel.session`

```kotlin
public val session: InkAuthoringSession
```

Panel-owned simultaneous-pointer session; mutate/read only on the AWT EDT.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L52)

### `InkLowLatencyPanel.renderer`

```kotlin
public val renderer: InkRenderer
```

Actual drawing renderer; owned when constructed by the panel, borrowed when supplied by the caller.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L53)

### `InkLowLatencyPanel.clearColorArgb`

```kotlin
public var clearColorArgb: Int
```

Writable EDT panel clear color, initially opaque white; call requestInkRender after changing it.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L54)

### `InkLowLatencyPanel.lastInputToRenderNanos`

```kotlin
public var lastInputToRenderNanos: Long
```

Last processed-handler-to-Skia-recording interval in nanoseconds; excludes device latency and presentation completion. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L56)

### `InkLowLatencyPanel.renderedFrameCount`

```kotlin
public var renderedFrameCount: Long
```

Cumulative recorded authoring frames; useful for checking idle rendering. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L58)

### `InkLowLatencyPanel.processedPacketCount`

```kotlin
public var processedPacketCount: Long
```

Cumulative input event packets delivered to the session; a Batch may contain many observations. Private setter.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L60)

### `InkLowLatencyPanel.nativeWindowHandle`

```kotlin
public val nativeWindowHandle: Long
```

Matching HWND/XID or configured top-level Wayland wl_surface; query on the EDT only after attachment.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L63)

### `InkLowLatencyPanel.authoringEnabled`

```kotlin
public var authoringEnabled: Boolean
```

Writable EDT input switch; disabling cancels active gestures and closes capture, enabling reacquires it.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L88)

### `InkLowLatencyPanel.addNotify`

```kotlin
override public fun addNotify()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L144)

### `InkLowLatencyPanel.removeNotify`

```kotlin
override public fun removeNotify()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L152)

### `InkLowLatencyPanel.requestInkRender`

Coalesces an EDT render request; finished ink and other drawContent changes made outside input callbacks must request a redraw.

```kotlin
public fun requestInkRender()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L158)

### `InkLowLatencyPanel.close`

Terminal EDT cleanup: cancels input, retires wet caches, closes session/presentation and only the panel-owned default renderer.

```kotlin
override public fun close()
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L191)

### `InkLowLatencySurface`

Compose wrapper around InkLowLatencyPanel. Uses native input by default, AWT logical coordinates, an owned mesh renderer when omitted, and automatic panel disposal. A caller-supplied renderer remains caller-owned. Native Wayland needs a compatible SwingGraphics ComposePanel host.

Composable: call within a Compose composition.

```kotlin
public fun InkLowLatencySurface( brush: Brush, modifier: Modifier = Modifier, strokeToView: AffineTransform = AffineTransform.IDENTITY, renderer: InkRenderer? = null, enabled: Boolean = true, inputSource: InkInputSource? = null, centimetersPerNativePixel: Float? = null, onStrokeFinished: (Long, Stroke) -> Unit, drawContent: (Canvas, Int, Int) -> Unit = { _, _, _ -> }, )
```

| Parameter | Type | Default | Meaning |
| --- | --- | --- | --- |
| `brush` | `Brush` | `Required` | Native brush captured at gesture start; keep stored metadata consistent with it. |
| `modifier` | `Modifier` | `Modifier` | Compose layout, sizing and drawing modifiers. |
| `strokeToView` | `AffineTransform` | `AffineTransform.IDENTITY` | Finite invertible stroke/page-to-AWT-local-logical-unit transform, captured per pointer at Begin; the panel applies device scale itself. |
| `renderer` | `InkRenderer?` | `null` | Borrowed caller-owned renderer, or null for an owned InkMeshRenderer; the panel closes only its own default renderer. |
| `enabled` | `Boolean` | `true` | Whether to acquire input; false cancels all active pointers and closes capture. |
| `inputSource` | `InkInputSource?` | `null` | Optional caller-supplied serial input source; null acquires built-in native input, with explicit acquisition failures. |
| `centimetersPerNativePixel` | `Float?` | `null` | Optional calibrated finite positive centimeters per native pixel; logical display DPI is not physical calibration. |
| `onStrokeFinished` | `(Long, Stroke) -> Unit` | `Required` | Captured (pointerId, canonical real-input Stroke) callback; update finished content synchronously and enqueue persistence. |
| `drawContent` | `(Canvas, Int, Int) -> Unit` | `{ _, _, _ -> }` | Compose Canvas callback drawn before wet ink; width/height and canvas coordinates use AWT component-local logical units. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/byteink-compose/src/main/kotlin/com/vivenotes/byteink/compose/InkLowLatencyPanel.kt#L249)
