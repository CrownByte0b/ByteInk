# Erase, select and transform

Operations act on `List<PageStroke>` and return new display lists. Persist the matching stored operation separately, then replay it to confirm the saved result. Each `PageStroke.id` is a **stored row ID**; `projectionKey` identifies one live disconnected piece.

## Partial erasing

Build an eraser mask in page coordinates. Round-trip it before hit testing so the preview uses the quantized geometry that a reload will reconstruct. `Normal` removes only crossed ink.

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Erasing.kt"
```

The example returns the stored erase plus preview projections. Persist the erase row and target links together. Keep the original stroke rows. On reload, pass the original rows and erase operations to `ViveInkPage.load`.

This library API is implemented; the ViveNotes desktop app still needs the interactive partial-eraser tool wired to it.

## Object erase and whole-row erase

| Behavior | API / storage |
| --- | --- |
| Cut crossed portions | `subtract(mask, targetIds)`; stored `InkEraseMode.Normal` |
| Remove touched disconnected components | `eraseObjects(mask, targetIds)`; stored `InkEraseMode.Object` |
| Remove every piece of a row | Filter/tombstone its row ID; the desktop app's current whole-stroke eraser uses this |

For `Object`, use the same mask/target workflow as the example and change both mode and display operation. Highlighters keep their outline geometry as one projection; touching an Object-erased highlighter removes it entirely.

Never apply an old mask to every newer stroke on reload. Persist the exact targets collected for that gesture.

## Hit testing

```kotlin
val index = InkPageIndex(page)
val nearPointer = index.at(point = InkPoint(100f, 80f), reach = 6f)
val crossed = index.crossing(
    from = InkPoint(90f, 80f),
    to = InkPoint(110f, 80f),
    width = 12f,
)
val touchedRows = index.targetsFor(mask)
```

`at` uses a square of half-side `reach`; `crossing` uses a rectangular band of diameter `width` between samples. For rounded brush-mask hits, use `touching(mask)` or `targetsFor(mask)`. Build a new index when the page geometry changes; previews can reuse an unchanged index.

`SpatialIndex` in core offers conservative bounds queries for your own items. Follow those candidates with exact native geometry tests when required.

## Lasso move and resize

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/Selection.kt"
```

`selectInkWithLasso` requires a gesture that closes into a loop; `selectWithLasso` accepts the polygon directly for stored/replayed paths. Default edge tolerance is `4f` page dp. Selecting one grouped stroke includes all projections in that group. A selected disconnected piece carries its own projection key.

Before committing a live move/resize, use `PageBounds.clampTranslation` or `clampScale` with the combined selection bounds. `moveSelected` and `resizeSelected` apply supplied deltas directly; replay clamps the final result against the page's origin. Persist the actual applied delta/scale.

For a stored move row, apply translation first and resize second, using its path and target IDs. `ViveInkPage.load` handles that order. The encode helpers record the original lasso path so replay can re-identify pieces.

## Delete, recolor, group and copy

| Operation | Use and persistence |
| --- | --- |
| `planProjectionDelete(held)` | Returns proved piece Object erases, whole-row tombstones and `after`; persist both operation kinds. Some pieces remain if a safe isolated mask cannot be proved. |
| `recolor(ids, colorArgb)` | Returns recolored geometry and clears automatic color flags; update source row color/theme fields in your repository. |
| `regroup(groups)` | Updates projection group IDs; persist row group changes. A `null` value ungroups. |
| `translatedCopy(dx, dy)` | Bakes a projection's current page transform and new offset into copied inputs; encode with `encodeCopy` and a new row ID/sequence. |
| `keepingProjectionsOf(previous)` | Retains live keys for matching rebuilt row/piece/bounds; helps keep selections across a reload. |
| `pointOnInk()` | Finds a page-space point on the projection, or returns `null` for no provable geometry. |

Projection numbers are process-local and must never be written as row identity. Keep partial-erase and move operations when copying a notebook; materializing display projections as replacement rows loses replay/undo history.

[Every operation parameter](../reference/operations.md) · [Codec parameters](../reference/storage.md)
