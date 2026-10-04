# Troubleshooting

| Symptom | Check / fix |
| --- | --- |
| Unsupported platform | Use Linux or Windows x86_64 with a matching JVM; macOS/ARM64 are not bundled. |
| Class-file version error | Run Java 25+; match the application's bytecode target. |
| Native access warning | Add `--enable-native-access=ALL-UNNAMED` to application JVM args. |
| Gradle loader capability conflict | Remove Google's loader dependencies, or exclude both `androidx.ink:ink-nativeloader` and `ink-nativeloader-jvm` where introduced. |
| `InkRuntime.load` reports a loader conflict | Check runtime classpath for a second `androidx.ink.nativeloader.NativeLoader`; use ByteInk's transitive fork. |
| Bundled native missing while building | Build both pinned native outputs or supply `-PbyteinkLinuxLibrary` / `-PbyteinkWindowsLibrary`; use matching ABI/pins. |
| Native cache cannot be written | Default loading falls back to a private temp directory. An explicit `byteink.ink.cache` is used as supplied and has no default-location fallback. |
| Windows native path fails under Zulu | Try an ASCII cache path such as `-Dbyteink.ink.cache=C:/byteink-cache`; pinned JBR 25 supports broader Unicode paths. |
| Drawing disappears at pointer release | Add the completed stroke to finished content and keep it in `drawContent`. |
| Pointer samples arrive but wet shape stalls | Schedule frames from `hasPendingInputs` and `isUpdateNeeded`; call `advance`, not only `append`. |
| Width/position wrong after zoom | Separate page dp from surface pixels; include density/scroll/zoom in transforms. |
| Raster blurs under parent zoom | Use physical viewport sizing and the ancestor's uniform zoom as `rasterScale`. |
| Partial erase differs after reload | Round-trip mask geometry before preview/target collection; persist the erase and exact target links together. |
| Unknown/malformed row vanishes visually | Inspect `unreadable`; preserve the original row and bytes. Nullable decoding does not mean deletion. |
| Unsupported paint exception | Call `canDraw`; the path renderer requires a texture-free ANY/DISCARD choice per coat. |

## Inspect native provenance

```kotlin
import com.vivenotes.byteink.core.InkRuntime

val native = InkRuntime.load()
println("${native.origin}: ${native.path} SHA-256=${native.sha256}")
```

Configure loader properties **before any Ink class triggers native loading**:

| JVM property | Value |
| --- | --- |
| `byteink.ink.cache` | Extraction directory; explicit cache replaces default locations |
| `byteink.ink.library` | Absolute path to a matching native build; bypasses bundled extraction |

Default caches: `$XDG_CACHE_HOME/byteink/natives` or `~/.cache/byteink/natives` on Linux; `%LOCALAPPDATA%/byteink/natives` on Windows. Each binary is stored under its SHA-256 directory. Changing properties after loading does not replace the process's native library.

[Loader API](reference/loader.md) · [Rendering ownership](guides/rendering.md#fidelity-and-ownership)
