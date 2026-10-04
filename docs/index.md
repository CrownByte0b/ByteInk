# ByteInk API wiki

Use the real AndroidX Ink engine in Kotlin/JVM applications on **Linux x86_64 and Windows x86_64**. ByteInk supplies native loading, Compose/Skia rendering, live input, and ViveNotes-compatible storage and operation replay.

| Start here | What you get |
| --- | --- |
| [Get started](getting-started.md) | Dependencies, runtime setup, first stroke |
| [Draw and capture input](guides/authoring.md) | Compose surface, pressure, custom adapters |
| [Render and cache](guides/rendering.md) | Coordinates, scenes, viewport caches, mesh export |
| [Save and load](guides/storage.md) | Stored rows, codecs, replay, unknown data |
| [Erase, select and transform](guides/operations.md) | Partial/Object erase, hit testing, lasso, copies |
| [Notebook utilities](guides/notebooks.md) | Optional `.vive` fixtures and PNG output |
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

Current development coordinates use group `com.vivenotes.byteink`, module version `0.1.0-SNAPSHOT`, and loader `1.1.0-alpha06-byteink.3`. AndroidX Ink is pinned to `1.1.0-alpha06`; Java **25+** is required. Use `jvmMain` in a multiplatform application.

The library provides partial-erase primitives and encoding. The ViveNotes desktop app's drag/preview/persistence integration for a partial eraser is still unfinished. [The erase example](guides/operations.md#partial-erasing) shows the supported library API.

## Conventions

Page coordinates and brush widths use **dp**; pointer samples and canvas viewports use **pixels**. A transform connects them. Monotonic input timestamps use **milliseconds**; stored operation clocks normally use epoch milliseconds.

The application owns database IDs, ordering, transactions and undo history. ByteInk returns values and geometry; it does not update your database.

[Run the examples](wiki.md#verify-the-examples) or [preview this wiki](wiki.md).
