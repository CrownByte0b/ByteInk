# ByteInk API wiki

Use the real AndroidX Ink engine in Kotlin/JVM applications on **Linux x86_64 and Windows x86_64**. ByteInk supplies native loading, Compose/Skia rendering, live input, and ViveNotes-compatible storage and operation replay.

| Start here | What you get |
| --- | --- |
| [Get started](getting-started.md) | Dependencies, runtime setup, first stroke |
| [Draw and capture input](guides/authoring.md) | Compose/native surfaces, simultaneous pointers, pen axes and prediction |
| [Render and cache](guides/rendering.md) | Mesh/shader rendering, textures, animation, scenes and caches |
| [Save and load](guides/storage.md) | Stored rows, codecs, replay, unknown data |
| [Erase, select and transform](guides/operations.md) | Partial/Object erase, hit testing, lasso, copies |
| [Notebook utilities](guides/notebooks.md) | Optional `.vive` fixtures and PNG output |
| [Positioning and benchmarks](guides/benchmarks.md) | Drawing-engine comparisons, current evidence and the planned benchmark program |
| [ByteInk vs Qt 6 and Electron](guides/toolkit-comparison.md) | Measured CPU raster/host resources, feature matrix, charts and reproducible evidence |
| [API reference](reference/index.md) | Public declarations, every parameter and default |

The header search indexes all guides, symbols and parameter tables. Search a name such as `encodeErase`, `rasterScale`, or `colorFollowsTheme`.

## Modules

| Dependency | Responsibility |
| --- | --- |
| `byteink-core` | AndroidX Ink types, native runtime, geometry snapshots, spatial queries |
| `byteink-compose` | Live authoring and Compose/Skia rendering |
| `byteink-kit` | Brushes, stored rows, codecs, erasing, lasso and replay |
| `byteink-testing` | Optional notebook fixtures and image utilities |
| `ink-nativeloader` | Transitive Linux/Windows native bundle |

Current development coordinates use group `com.vivenotes.byteink`, module version `0.1.0-SNAPSHOT`, and loader `1.1.0-alpha06-byteink.4`. AndroidX Ink is pinned to `1.1.0-alpha06`; Java **25+** is required. Use `jvmMain` in a multiplatform application.

The ViveNotes desktop app now implements Normal partial-erase interaction, preview, persistence and undo/redo with reload verification. ByteInk supplies the mask, codec and replay APIs; applications own gesture handling and database/history integration. [The erase guide](guides/operations.md#partial-erasing) explains that workflow.

`InkMeshRenderer` adds vertex effects, textures and animated stamps to finished and live ink. `InkDrawingSurface` defaults to path rendering; the native low-latency surfaces default to mesh rendering. See [rendering](guides/rendering.md#choose-a-renderer).

## Recent changes

- [`53a6466`](https://github.com/CrownByte0b/ByteInk/commit/53a646646cbdbac37e15786b69eb8b341e8079b6): change-aware native painting, lazy mesh export, live-cache retirement through `InkRenderer.releaseLiveStroke`, live cache diagnostics, and bounded lossless native delivery. See [cache retirement](guides/rendering.md#live-cache-retirement).
- [`48628e5`](https://github.com/CrownByte0b/ByteInk/commit/48628e53014cba923764ba4c1aa4a3c7a5ddc175): native JBR 25 Wayland pen/touch/mouse capture and software Skia authoring, with reconnection and cleanup. See [native platform setup](guides/authoring.md#native-platform-setup).
- [`a131e4c`](https://github.com/CrownByte0b/ByteInk/commit/a131e4c6e778ab57f637fb28bd82831ca99ca416): Windows `WM_POINTER` and Linux XInput2 capture, tilt/orientation/calibration, replaceable prediction, simultaneous-pointer sessions and direct authoring surfaces. See [authoring](guides/authoring.md).
- [`189b0c1`](https://github.com/CrownByte0b/ByteInk/commit/189b0c1bd5a2bd6969cf390b9c86ca40e0f91eb1): full mesh/shader rendering, textures and animated stamps; shared renderer integration for surfaces/scenes/caches; owned rendering snapshots and rebuilt Linux/Windows natives with loader byteink.4. See [rendering](guides/rendering.md).
- [`3a047b7`](https://github.com/CrownByte0b/ByteInk/commit/3a047b787c86d4715ff1ea03c13ada4abbd76c4e): decoded native page replay for desktop partial erasing and expanded Android round-trip fixtures to include newly authored Normal masks. See [partial erase](guides/operations.md#partial-erasing) and [decoded replay](guides/storage.md#replay-decoded-ink).

## Conventions

Page coordinates and brush widths use **dp**. Regular Compose pointer samples and viewports use **pixels**; native authoring panels use **AWT logical units** and apply device scale themselves. A transform connects the chosen input space to stroke units. Monotonic input timestamps use **milliseconds**; stored operation clocks normally use epoch milliseconds.

The application owns database IDs, ordering, transactions and undo history. ByteInk returns values and geometry; it does not update your database.

[Run the examples](wiki.md#verify-the-examples) or [preview this wiki](wiki.md).
