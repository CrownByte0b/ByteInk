# Save and load

## Store new rows

Prefer `ViveInkTool.complete` for authored strokes: it keeps the captured brush and storage metadata together. Use `encodeStroke` when you already manage metadata, `encodeHighlighter` for the fixed highlighter metadata, or `encodeCopy` to preserve a source projection's family/version/theme settings.

```kotlin
val row = ViveInkCodec.encodeStroke(
    stroke = finished,
    id = "stroke-42",
    pageId = "page-1",
    seq = 42,
    brushFamily = ViveBrushes.MARKER,
    stabilization = 0,
    colorFollowsTheme = true,
    createdAt = System.currentTimeMillis(),
    groupId = null,
)
val restored = ViveInkCodec.decode(row) // Stroke?, null if unreadable
```

Allocate IDs and `seq` in the database transaction. Supply family/stabilization matching the stroke's actual brush: codecs record those values and reload uses them to rebuild geometry. Persist original source rows plus operations; projections are display state.

## Replay a page

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Storage.kt"
```

`load` filters tombstones, orders strokes by `seq` then ID, and replays operations by `createdAt` then ID. It returns:

| `LoadedInkPage` property | Meaning |
| --- | --- |
| `strokes` | Final live projections in drawing order |
| `erasedAway` | Readable row IDs whose last geometry was removed; the application decides whether to tombstone them |
| `unreadable` | Live stroke-row IDs whose data could not be decoded |
| `sourceStrokes` | Decoded original rows before replay, useful for native/portable handoff |
| `operations` | Validated `DecodedInkOperation.Erase` / `.Move` geometry |

Run large loads away from the UI thread. `executor` is an optional caller-owned decode pool; loading still blocks until completion. At most four 512-row jobs are queued. `onPartial` receives cumulative, ordered snapshots on the load caller's thread; marshal them to the UI yourself. Partial publication runs only when there are no live move rows, because moves need global bounds/clamping.

## Row fields

[The storage reference](../reference/storage.md) lists **every constructor field**, including required fields after defaulted parameters. Map your repository entities to these values without changing unknown fields or bytes.

| Value | Persistence behavior |
| --- | --- |
| `StoredInkStroke` | Input bytes, family/version, width/color, stabilization, bounds, grouping and tombstone |
| `StoredInkErase` | Mode, mask inputs/diameter, creation/undo clock and target row IDs |
| `StoredInkMove` | Lasso path, translation, scale/anchor, creation/undo clock and target row IDs |
| `deletedAt` | Non-null means deleted stroke or disabled operation |
| `targetIds` | Stroke-row IDs that existed when the operation was made; unrelated/newer ink stays untouched |

Stored erases/moves and their target links belong in one transaction. Undo normally toggles the operation's `deletedAt`; redo clears it. Keep the source stroke row so a disabled partial erase can restore its geometry through replay.

## Encoding and unknown data

| Encoding | Data |
| --- | --- |
| `ink/androidx1` | Android-compatible gzip/protobuf stroke or eraser inputs |
| `ink/lasso-f32le1` | Little-endian point count and x/y float pairs for lasso paths |

`decode`, `decodeErase`, and `decodeMove` return `null` for unsupported or damaged data. `hasValidInputData(points)` validates a stroke input blob without building its mesh. The decompression cap is **64 MiB** across gzip members, including trailer checks.

Unknown family IDs use the pressure-pen rendering fallback; retain the original ID. Unknown encodings/modes and damaged operations are skipped for display, with original storage left intact. Byte arrays are passed as values rather than defensively cloned by row constructors: treat them as immutable.

Use ByteInk's codec for ViveNotes rows. Calling upstream input encoding directly bypasses ByteInk's pinned Android wire compatibility and decode expansion policy.

## Automatic color

```kotlin
val canvasInk = automaticInkFor(isDark = true) // white
val color = automaticColorOr(
    stored = row.colorArgb,
    followsTheme = row.colorFollowsTheme,
    canvasInk = canvasInk,
)
```

`true` follows canvas ink; `false` keeps stored color; `null` follows canvas ink only for legacy black/white automatic colors. This is a draw override, so theme changes need no row rewrite. Highlighter color stays fixed.
