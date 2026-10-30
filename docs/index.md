# ByteInk API wiki

Use the real AndroidX Ink engine in Kotlin/JVM applications on **Linux x86_64 and Windows x86_64**. ByteInk supplies native loading, Compose/Skia rendering, live input, and ViveNotes-compatible storage and operation replay.

| Start here                                          | What you get                                                            |
| --------------------------------------------------- | ----------------------------------------------------------------------- |
| [Get started](getting-started.md)                   | Dependencies, runtime setup, first stroke                               |
| [Draw and capture input](guides/authoring.md)       | Compose/native surfaces, simultaneous pointers, pen axes and prediction |
| [Render and cache](guides/rendering.md)             | Mesh/shader rendering, textures, animation, scenes and caches           |
| [Save and load](guides/storage.md)                  | Stored rows, codecs, replay, unknown data                               |
| [Erase, select and transform](guides/operations.md) | Partial/Object erase, hit testing, lasso, copies                        |
| [Notebook utilities](guides/notebooks.md)           | Optional `.vive` fixtures and PNG output                                |
| [API reference](reference/index.md)                 | Public declarations, every parameter and default                        |

The header search indexes all guides, symbols and parameter tables. Search a name such as `encodeErase`, `rasterScale`, or `colorFollowsTheme`.

## Modules

| Dependency         | Responsibility                                                          |
| ------------------ | ----------------------------------------------------------------------- |
| `byteink-core`     | AndroidX Ink types, native runtime, geometry snapshots, spatial queries |
| `byteink-compose`  | Live authoring and Compose/Skia rendering                               |
| `byteink-kit`      | Brushes, stored rows, codecs, erasing, lasso and replay                 |
| `byteink-testing`  | Optional notebook fixtures and image utilities                          |
| `ink-nativeloader` | Transitive Linux/Windows native bundle                                  |

The current release is `0.1.0-alpha.1` on [GitHub Packages](getting-started.md#add-byteink-to-your-project), group `com.vivenotes.byteink`. A source checkout builds module version `0.1.0-SNAPSHOT` and loader `1.1.0-alpha06-byteink.4`. AndroidX Ink is pinned to `1.1.0-alpha06`; Java **25+** is required. Use `jvmMain` in a multiplatform application.

The ViveNotes desktop app now implements Normal partial-erase interaction, preview, persistence and undo/redo with reload verification. ByteInk supplies the mask, codec and replay APIs; applications own gesture handling and database/history integration. [The erase guide](guides/operations.md#partial-erasing) explains that workflow.

`InkMeshRenderer` adds vertex effects, textures and animated stamps to finished and live ink. `InkDrawingSurface` defaults to path rendering; the native low-latency surfaces default to mesh rendering. See [rendering](guides/rendering.md#choose-a-renderer).

## Why use ?

- AndroidX Ink's desktop support stops at "compute strokes on Linux or macOS". ByteInk adds everything else a desktop notes app needs.
- 1 to 1 compatibility with AndroidX Ink **1.1.0-alpha06** (see [Compatible version](#compatible-version))
- Wayland native support.
- Only choice for Kotlin Multi platform on desktop

### Compatible version

| ByteInk         | AndroidX Ink    | Native engine (`google/ink`) |
| --------------- | --------------- | ---------------------------- |
| `0.1.0-alpha.1` | `1.1.0-alpha06` | `96e50239e1c8`               |

### Why not just use AndroidX Ink's KMP artifacts?

1. **Only the engine half runs on the JVM.** Google publishes JVM builds of `geometry`, `brush`, `strokes`, `storage` and
   `nativeloader`, but not of `ink-rendering`, `ink-authoring` or any `*-compose` module, since those depend on Android's
   Canvas, View and MotionEvent. You can compute a stroke on the desktop, but you can't draw it or capture a pen. ByteInk
   renders with Compose/Skia and captures pen input on Windows, X11 and Wayland.
2. **No Windows.** Google's JVM native loader ships only `linux-x86_64` and `macos-arm64` binaries and throws
   "Unsupported platform" anywhere else. ByteInk builds `ink.dll` and loads it with a narrow fork of the loader.
3. **Google's Linux binary doesn't produce exactly the same geometry as Android.** Android's math library rounds `atan2f`
   and `hypotf` slightly differently from desktop libm, which changes the shape of some real strokes. ByteInk's native
   build uses Android's arithmetic, so desktop geometry matches Android exactly.
4. **Erasing depends on the exact native version.** Partial-erase results changed between `google/ink` commits, so
   ByteInk builds from the commit that matches the AndroidX Ink release the Android app uses, not the latest one.
5. **The ViveNotes note format isn't part of AndroidX Ink.** How notes are saved (stroke rows, partial-erase operations,
   unknown fields, custom brushes) lives in the Android app. `byteink-kit` implements it, so notes written on the desktop
   open on Android and the other way round.
6. **An upstream use-after-free.** AndroidX Ink's JVM classes pass raw native pointers, so garbage collection can free
   an object while native code is still reading it, corrupting memory on Windows. ByteInk's loader delays those frees.

## Recent changes

- [`53a6466`](https://github.com/CrownByte0b/ByteInk/commit/53a646646cbdbac37e15786b69eb8b341e8079b6): change-aware native painting, lazy mesh export, live-cache retirement through `InkRenderer.releaseLiveStroke`, live cache diagnostics, and bounded lossless native delivery. See [cache retirement](guides/rendering.md#live-cache-retirement).
- [`48628e5`](https://github.com/CrownByte0b/ByteInk/commit/48628e53014cba923764ba4c1aa4a3c7a5ddc175): native JBR 25 Wayland pen/touch/mouse capture and software Skia authoring, with reconnection and cleanup. See [native platform setup](guides/authoring.md#native-platform-setup).
- [`a131e4c`](https://github.com/CrownByte0b/ByteInk/commit/a131e4c6e778ab57f637fb28bd82831ca99ca416): Windows `WM_POINTER` and Linux XInput2 capture, tilt/orientation/calibration, replaceable prediction, simultaneous-pointer sessions and direct authoring surfaces. See [authoring](guides/authoring.md).
- [`189b0c1`](https://github.com/CrownByte0b/ByteInk/commit/189b0c1bd5a2bd6969cf390b9c86ca40e0f91eb1): full mesh/shader rendering, textures and animated stamps; shared renderer integration for surfaces/scenes/caches; owned rendering snapshots and rebuilt Linux/Windows natives with loader byteink.4. See [rendering](guides/rendering.md).
- [`3a047b7`](https://github.com/CrownByte0b/ByteInk/commit/3a047b787c86d4715ff1ea03c13ada4abbd76c4e): decoded native page replay for desktop partial erasing and expanded Android round-trip fixtures to include newly authored Normal masks. See [partial erase](guides/operations.md#partial-erasing) and [decoded replay](guides/storage.md#replay-decoded-ink).

## Conventions

Page coordinates and brush widths use **dp**. Regular Compose pointer samples and viewports use **pixels**; native authoring panels use **AWT logical units** and apply device scale themselves. A transform connects the chosen input space to stroke units. Monotonic input timestamps use **milliseconds**; stored operation clocks normally use epoch milliseconds.

The application owns database IDs, ordering, transactions and undo history. ByteInk returns values and geometry; it does not update your database.
