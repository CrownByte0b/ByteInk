# API reference

Reference pages list the public ByteInk declarations, constructor/method parameters, exact source defaults, constants, and returned properties. `Required` means no default is declared. Nullable types accept `null` only where shown.

| Package | Reference |
| --- | --- |
| `com.vivenotes.byteink.core` | [Engine and geometry](core.md) |
| `com.vivenotes.byteink.compose` | [Input and authoring](authoring.md), [rendering and caches](rendering.md) |
| `com.vivenotes.byteink.kit` | [Brushes/tools](brushes.md), [storage](storage.md), [operations](operations.md) |
| `com.vivenotes.byteink.nativeloader` | [Native loading](loader.md) |
| `com.vivenotes.byteink.testing` | [Notebook/image utilities](testing.md) |

Most constructor parameters declared `val` are also read-only properties. Standard Kotlin data-class `copy`, destructuring, equality and hash helpers follow Kotlin semantics. Keep arrays and lists immutable even when their underlying JVM type allows mutation.

Extensions show their receiver before the method name, for example `List<PageStroke>.subtract`. Import the extension from its package. Companion factories such as `SpatialIndex.of` and `ViveNotebook.open` are called on their class.

Rendering/controller state exposed as `var` has private setters. Read it for redraws or diagnostics; mutate through the documented methods. Threading and disposal rules are described in the guides.

## AndroidX types

ByteInk re-exports pinned AndroidX Ink **brush**, **geometry**, **strokes** and **storage** JVM modules. Their public types retain upstream signatures. The guides use these core inputs:

| Upstream type/call | Parameters used here |
| --- | --- |
| `Stroke(brush, inputs)` | Native brush; native input batch in stroke coordinates |
| `MutableStrokeInputBatch.add(...)` | `type`, `x`, `y`, `elapsedTimeMillis`; optional `strokeUnitLengthCm`, `pressure`, `tiltRadians`, `orientationRadians` |
| `ImmutableAffineTransform(...)` | Six coefficients: x-scale, x-shear, x-translation, y-shear, y-scale, y-translation |
| `ImmutableBox.fromTwoPoints(...)` | Two vectors bounding the query rectangle |

Use monotonic relative millisecond input times, finite coordinates, and constant tool/optional-property availability within a native batch. Native brush width and epsilon must be finite and positive, with width **at least** epsilon. See the pinned-source contracts before changing input properties.

Upstream references: [Stroke](https://developer.android.com/reference/kotlin/androidx/ink/strokes/Stroke), [MutableStrokeInputBatch](https://developer.android.com/reference/kotlin/androidx/ink/strokes/MutableStrokeInputBatch), [Brush](https://developer.android.com/reference/kotlin/androidx/ink/brush/Brush), [AffineTransform](https://developer.android.com/reference/kotlin/androidx/ink/geometry/AffineTransform). Online references can describe newer versions; the dependency's alpha06 API is authoritative.

The generated pages intentionally cover ByteInk's own public API. AndroidX internal JNI annotations and test-only native-pointer hooks are upstream implementation details.
