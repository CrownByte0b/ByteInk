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
| Speculative tail remains when the pen stops | A custom session loop must schedule while `needsAnimationTick()` is true, then advance to expire predictions. Custom controller loops retract forecasts explicitly. |
| A second pointer is ignored | `InkDrawingSurface` has one controller/active pointer; use `InkAuthoringSession` or the native direct surfaces for simultaneous pointers. |
| Native subscription/thread exception | Subscribe/close on the AWT EDT, with a displayable component, one subscriber and its matching live native handle. |
| Wayland backend still reports `LINUX_XINPUT2` | Selection uses the actual AWT toolkit. Run JBR 25 with `-Dawt.toolkit.name=WLToolkit` before AWT initializes. |
| Native Wayland package access fails | Add `--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED` and native access; see [runtime setup](guides/authoring.md#native-platform-setup). |
| Compose host fails under WLToolkit | Use `JFrame` plus `ComposePanel(renderSettings = RenderSettings.SwingGraphics())`, as in the native example, or a direct Swing panel. The tested heavyweight Compose/Skiko host assumes X11. |
| Native handle unavailable or stale after hide/show | Wayland surfaces exist after visible top-level configuration and change after hide/show. Recreate custom native sources; the built-in panel waits/reconnects. |
| Native Wayland pen axes are absent | Check device/compositor tablet-v2 support. Primary mouse and touch do not fabricate pen pressure. |
| XInput2 touch subscription cannot be acquired | Check for another exclusive touch selector on the same window and that the component's XID is correct. Acquisition failure is explicit. |
| Width/position wrong after zoom | Regular Compose maps page units to pixels with density/scroll/zoom. Direct panels use AWT logical units and apply device scale themselves. |
| Completed gestures retain wet renderer memory | Call `releaseLiveStroke` before custom-host completion/cancellation/reuse. Built-in direct panels wire this automatically; mesh live-cache diagnostics show retention. |
| Raster blurs under parent zoom | Use physical viewport sizing and the ancestor's uniform zoom as `rasterScale`. |
| Partial erase differs after reload | Round-trip mask geometry before preview/target collection; persist the erase and exact target links together. |
| Unknown/malformed row vanishes visually | Inspect `unreadable`; preserve the original row and bytes. Nullable decoding does not mean deletion. |
| Unsupported paint exception | Call `canDraw`; path rendering requires texture-free ANY/DISCARD paints. Mesh rendering needs compatible attributes and every selected paint's images in `InkTextureStore`; missing images try the next paint preference. |
| Texture replacement still shows the old image | Call `InkMeshRenderer.clearCache()` after replacing an image under the same client ID; this also invalidates retained view rasters. |
| Texture atlas stays on one frame | Advance the mesh renderer's nonnegative `animationTimeMillis`; live shape effects separately require `InProgressStroke.updateShape`. |
| Decoded replay cuts or moves ink twice | Supply `LoadedInkPage.sourceStrokes` and active decoded operations to `ViveInkPage.replay`, rather than already replayed `strokes`. |

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
