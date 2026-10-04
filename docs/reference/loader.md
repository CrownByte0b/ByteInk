# Native loading

Module: `ink-nativeloader`. [Conventions](index.md). Signatures and defaults follow the current source.

## InkNativeLibrary

### `InkNativeLibrary`

Set JVM properties before the first native use. load is thread-safe and idempotent; use InkRuntime.load to additionally detect loader conflicts.

```kotlin
public object InkNativeLibrary
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/ink-nativeloader/src/jvmMain/kotlin/com/vivenotes/byteink/nativeloader/InkNativeLibrary.kt#L19)

### `InkNativeLibrary.LIBRARY_PROPERTY`

```kotlin
public const val LIBRARY_PROPERTY: String = "byteink.ink.library"
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/ink-nativeloader/src/jvmMain/kotlin/com/vivenotes/byteink/nativeloader/InkNativeLibrary.kt#L26)

### `InkNativeLibrary.CACHE_PROPERTY`

```kotlin
public const val CACHE_PROPERTY: String = "byteink.ink.cache"
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/ink-nativeloader/src/jvmMain/kotlin/com/vivenotes/byteink/nativeloader/InkNativeLibrary.kt#L29)

### `InkNativeLibrary.loaded`

```kotlin
public val loaded: LoadedInkLibrary?
```

Loaded native metadata, or null before the first successful load.

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/ink-nativeloader/src/jvmMain/kotlin/com/vivenotes/byteink/nativeloader/InkNativeLibrary.kt#L35)

### `InkNativeLibrary.load`

```kotlin
public fun load(): LoadedInkLibrary
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/ink-nativeloader/src/jvmMain/kotlin/com/vivenotes/byteink/nativeloader/InkNativeLibrary.kt#L44)

### `LoadedInkLibrary`

Returned metadata; constructor is internal. Public properties: path: Path, sha256: String, origin: Origin.

```kotlin
public class LoadedInkLibrary
```

| Property | Type | Default | Meaning |
| --- | --- | --- | --- |
| `path` | `Path` | `Returned value` | Absolute path to the native file loaded by this JVM. |
| `sha256` | `String` | `Returned value` | Loaded native file's lowercase SHA-256 digest. |
| `origin` | `Origin` | `Returned value` | BUNDLED for the packaged native, PROPERTY for an explicit library override. |

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/ink-nativeloader/src/jvmMain/kotlin/com/vivenotes/byteink/nativeloader/InkNativeLibrary.kt#L90)

### `LoadedInkLibrary.Origin`

Values: BUNDLED (packaged native) and PROPERTY (explicit path override).

```kotlin
public enum class Origin
```

[Source](https://github.com/CrownByte0b/ByteInk/blob/master/ink-nativeloader/src/jvmMain/kotlin/com/vivenotes/byteink/nativeloader/InkNativeLibrary.kt#L99)
