# Notebook utilities

Add optional `com.vivenotes.byteink:byteink-testing:0.1.0-SNAPSHOT` for test/sample `.vive` archives and offscreen images. Your application repository remains responsible for normal import/export and synchronization.

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Notebook.kt"
```

`ViveNotebook.open` verifies the archive manifest/checksums, opens a private SQLite copy and returns every page ID. `page(id)` includes original strokes, erases, moves and tombstones; pass them to the page loader. `close` closes SQLite and deletes the temporary database.

| Helper | Behavior |
| --- | --- |
| `writeCopyWithStrokes(destination, strokes)` | Appends new strokes to a fresh archive copy |
| `writeCopyWithInk(destination, strokes, erases)` | Appends strokes, erases and target links transactionally |
| `NotebookInkImages.frame(strokes, maxDimension)` | Fitted width/height, scale and origin |
| `NotebookInkImages.writePng(strokes, file, maxDimension, renderer)` | Ink-only white-paper PNG and timing/drawn-count report |

Copy helpers preserve existing rows/operations/entries and update manifest checksums. Destination must differ from source and must not exist. Erase targets must exist on the same page; duplicates are refused. These helpers do not append new moves.

PNG output resolves automatic ink to black, keeps deliberate alpha and includes 16 pixels of padding per side. `maxDimension` must exceed `32`; an empty page produces 32 × 32 pixels. Pass a reusable renderer and clear it afterward. The OS-specific Compose runtime supplies Skia natives.

[All notebook/image parameters](../reference/testing.md)
