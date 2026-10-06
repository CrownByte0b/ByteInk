#!/usr/bin/env python3
"""Render the current ByteInk public declarations, with reviewed parameter descriptions.

This is a narrow source reader for this repository's Kotlin declaration style, not a Kotlin
compiler. Unknown parameter names fail generation rather than producing undocumented tables.
Use --check in documentation validation to detect source/reference drift.
"""
from __future__ import annotations

import argparse
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GROUPS = {
    "core": ("Engine and geometry", "byteink-core", ["InkRuntime", "InkMeshes", "StrokeMesh", "SpatialIndex"]),
    "authoring": ("Input and authoring", "byteink-compose", ["InkPointerSample", "InkAuthoringController", "InkDrawingSurface"]),
    "rendering": ("Rendering and caches", "byteink-compose", ["InkRenderer", "InkMeshRenderer", "InkTextureStore", "InkPathRenderer", "InkScene", "InkSceneRasterCache"]),
    "brushes": ("Brushes and tools", "byteink-kit", ["ViveBrushes", "ViveInkTool", "InkColors"]),
    "storage": ("Stored rows and codecs", "byteink-kit", ["StoredInk", "ViveInkCodec", "ViveInkPage", "DecodedInkOperation"]),
    "operations": ("Page operations and selection", "byteink-kit", ["InkGeometry", "PageStroke", "Lasso", "InkPageIndex", "PageBounds"]),
    "loader": ("Native loading", "ink-nativeloader", ["InkNativeLibrary"]),
    "testing": ("Notebook utilities", "byteink-testing", ["ViveNotebook", "NotebookInkImages"]),
}

PARAMETERS = {
    "positions": "Copied x/y pairs in stroke coordinates; two floats per vertex.",
    "triangles": "Copied vertex indices; three indices per triangle.",
    "vertices": "Canonical rendering attributes in stroke coordinates; 15 floats per vertex, in StrokeMesh.VERTEX_STRIDE order.",
    "attributeMask": "Bits 0–8 identify present position, opacity, HSL, side derivative/label, forward derivative/label, surface UV and animation offset attributes; missing values are zero.",
    "shape": "Finished native mesh; retain its owner while reading.",
    "group": "Zero-based render-group index; less than shape.getRenderGroupCount().",
    "coat": "Zero-based brush-coat index; less than brush.family.coats.size.",
    "stroke": "Native finished or live stroke, as specified by the type.",
    "strokes": "Stroke entries or stored rows, as specified by the type; preserve their order.",
    "items": "Items to index; treat the list and indexed bounds as immutable.",
    "cellSize": "Finite positive grid-cell size in the same units as the bounds.",
    "bounds": "Page-space bounds; a callback returning null omits that item's geometry from queries.",
    "box": "Inclusive query bounds in the index's coordinate system.",
    "xMin": "Left query edge in index units.", "yMin": "Top query edge in index units.",
    "xMax": "Right query edge in index units.", "yMax": "Bottom query edge in index units.",
    "x": "Horizontal coordinate; surface pixels for pointer samples, page dp for InkPoint.",
    "y": "Vertical coordinate; surface pixels for pointer samples, page dp for InkPoint.",
    "uptimeMillis": "Nonnegative monotonic event/frame milliseconds, using one clock per gesture.",
    "toolType": "Device tool type; keep it constant within a gesture.",
    "pressure": "Measured finite pressure in [0, 1], or null when unavailable; Int brush levels use 0–5.",
    "sample": "Real pointer observation; a nullable finishing sample may be omitted.",
    "listener": "Serial UI-thread event callback; closing the subscription stops callbacks.",
    "brush": "Native brush captured at gesture start; keep stored metadata consistent with it.",
    "strokeToView": "Finite invertible stroke/page-to-surface-pixel transform, snapshotted at begin.",
    "controller": "UI-thread authoring controller; attach to one surface at a time.",
    "modifier": "Compose layout, sizing and drawing modifiers.",
    "renderer": "Reusable drawing-thread renderer; clear path caches or close owned mesh renderers on disposal.",
    "textureStore": "Optional preloaded client-texture-ID lookup; images are borrowed and missing images make that paint unavailable.",
    "clientTextureId": "BrushPaint client texture ID; return its decoded Skia Image, or null if unavailable.",
    "textureCacheCapacity": "Positive maximum number of retained texture shader entries; provider-owned images are outside this limit.",
    "enabled": "Whether to accept input; false cancels the active gesture.",
    "inputSource": "Optional native adapter; null uses Compose primary-pointer gestures.",
    "onStrokeFinished": "Completion callback captured at pointer down; retain/store the returned stroke.",
    "drawContent": "DrawScope block drawn before live ink, usually for finished ink and paper.",
    "cacheCapacity": "Nonnegative retained-entry limit; zero disables retention.",
    "cacheByteBudget": "Nonnegative retained finished-path or mesh-geometry byte ceiling, as specified by the renderer; excludes live geometry and provider images.",
    "pixelBudgetBytes": "Nonnegative retained N32 raster-byte ceiling; excludes paths, scenes and wrappers.",
    "canvas": "Compose Canvas on the drawing thread; use asComposeCanvas() for a Skia canvas.",
    "strokeToCanvas": "Finite stroke-to-local-canvas transform, composed with the canvas's existing transform.",
    "strokeToScene": "Finite stroke-to-scene transform; InkScene snapshots mutable values.",
    "sceneToCanvas": "Finite invertible scene-to-local-canvas transform; include device density here.",
    "viewport": "Visible rectangle in local canvas pixels; supply the actually visible area.",
    "colorArgb": "32-bit ARGB including alpha; nullable drawing overrides use the brush color when null.",
    "scene": "Immutable finished-ink snapshot; reuse the same instance until entries change.",
    "width": "Raster/image width in pixels; cache viewport dimensions must be nonnegative.",
    "height": "Raster/image height in pixels; cache viewport dimensions must be nonnegative.",
    "rasterScale": "Finite positive physical pixels per local pixel, for uniform axis-aligned ancestor zoom.",
    "excludedStrokes": "Occurrence instances from scene.strokes; matching uses identity, not value equality.",
    "cache": "Drawing-thread raster cache; close when its owner is disposed.",
    "solidLine": "False selects the dashed family.",
    "fountain": "True selects marker for a solid pen; false selects calligraphy.",
    "stabilization": "Input-model level 0–5; tool constructors validate, catalog lookup clamps.",
    "id": "Caller-supplied row identifier; ViveBrushes.family instead accepts a stored family ID.",
    "familyId": "Brush family ID; use ViveBrushes constants or calligraphy(level).",
    "size": "Finite positive brush width in page/stroke units, at least epsilon.",
    "sizeDp": "Finite positive brush width or eraser diameter in page dp, at least epsilon.",
    "inputs": "Native input batch in page/stroke units, with ordered relative timestamps.",
    "row": "Original stored row; nullable decoders return null for unsupported or damaged data.",
    "pageId": "Identifier of the page owning all supplied rows/targets.",
    "seq": "Caller-allocated stroke draw-order sequence; loading sorts by seq then ID.",
    "brushFamily": "Stored family ID, preserved independently of fallback rendering.",
    "brushVersion": "Stored brush-definition version; current writes use 1.",
    "colorFollowsTheme": "true follows automatic ink; false fixes color; null enables legacy black/white detection.",
    "createdAt": "Creation clock in the application's stored timestamp units, normally epoch milliseconds.",
    "groupId": "Optional logical group ID; grouped ink selects together.",
    "epsilon": "Native mesh tolerance in stroke units; catalog brushes use 0.25f.",
    "minX": "Stored lower horizontal mesh bound in page dp.", "minY": "Stored lower vertical mesh bound in page dp.",
    "maxX": "Stored upper horizontal mesh bound in page dp.", "maxY": "Stored upper vertical mesh bound in page dp.",
    "points": "Original encoded bytes; keep unknown or unreadable blobs unchanged.",
    "enc": "Stored encoding ID: ink/androidx1 for strokes/erases, ink/lasso-f32le1 for moves.",
    "deletedAt": "Nullable deletion/undo timestamp; non-null rows are excluded from replay.",
    "targetIds": "Stored stroke-row IDs the operation applies to; preserve exact same-page targets.",
    "mode": "Normal cuts geometry; Object removes touched disconnected components. Stored rows retain the raw string.",
    "mask": "Eraser geometry in page coordinates; canonicalize through the codec before preview/target selection.",
    "source": "Source projection whose brush/version/theme metadata is copied.",
    "dxDp": "Stored horizontal translation in page dp.", "dyDp": "Stored vertical translation in page dp.",
    "dx": "Horizontal translation in page dp.", "dy": "Vertical translation in page dp.",
    "scaleX": "Horizontal scale multiplier; 1 leaves that axis unchanged.",
    "scaleY": "Vertical scale multiplier; 1 leaves that axis unchanged.",
    "anchorX": "Stored horizontal resize anchor in page dp.", "anchorY": "Stored vertical resize anchor in page dp.",
    "move": "Completed lasso translation, including held projection keys and stored row targets.",
    "resize": "Completed lasso resize, including anchor, scale, held keys and stored row targets.",
    "path": "Page-space lasso vertices; codec requires at least three finite points.",
    "erases": "Stored erase operations with their target links; tombstones are retained as input data.",
    "moves": "Stored move/resize operations with their target links.",
    "operations": "Already decoded active erase/move operations; replay sorts by createdAt then ID without modifying the supplied list.",
    "executor": "Optional caller-owned decode executor; load waits for jobs and never shuts it down.",
    "onPartial": "Cumulative draw-order snapshots on the load caller's thread; enabled only when no live move rows exist.",
    "erasedAway": "Decoded stroke-row IDs whose last geometry was removed by replay.",
    "unreadable": "Live stroke-row IDs that failed decoding; keep their stored rows.",
    "offsetX": "Projection's horizontal translation into page dp.",
    "offsetY": "Projection's vertical translation into page dp.",
    "projection": "Process-local piece number; preserve with copy, never store as a database identifier.",
    "previous": "Prior page projections used to retain matching row/piece/bounds identities.",
    "held": "Selected projection keys to delete; returned plans may leave unprovable pieces intact.",
    "rowId": "Stored stroke-row ID owning the projection.",
    "strokeId": "Stored stroke-row ID owning this process-local projection key.",
    "sha256": "Loaded native file's lowercase SHA-256 digest.",
    "origin": "BUNDLED for the packaged native, PROPERTY for an explicit library override.",
    "after": "Resulting page projections; commit matching stored operations separately.",
    "wholeRows": "Rows whose every projection is deleted; persist row tombstones.",
    "ids": "Stored stroke-row IDs to recolor.",
    "groups": "Map of stored row ID to group ID; null removes a row from a group.",
    "projections": "Process-local keys of selected pieces; use only with the matching live page snapshot.",
    "anchor": "Resize origin in page dp.",
    "point": "Point in page dp.", "left": "Left bound or image frame origin in page dp.",
    "top": "Top bound or image frame origin in page dp.", "right": "Right bound in page dp.",
    "bottom": "Bottom bound in page dp.",
    "edgeTolerance": "Lasso edge reach in page dp; default is 4f. Use finite nonnegative values.",
    "polygon": "Nonempty page-space polygon for point tests; use at least three finite vertices.",
    "start": "Segment start in page dp.", "end": "Segment end in page dp.",
    "touch": "Loop-closing reach in page dp; the algorithm applies a minimum of 2 dp.",
    "reach": "Half-side of the square point-hit region in page dp; use finite nonnegative values.",
    "from": "Band start in page dp.", "to": "Band end in page dp.",
    "stored": "Original stored ARGB color, or raw erase-mode name when the type is String.",
    "followsTheme": "Stored theme flag: true automatic, false fixed, null legacy behavior.",
    "canvasInk": "Resolved automatic canvas ARGB, usually automaticInkFor(isDark).",
    "isDark": "True chooses white automatic ink; false chooses black.",
    "destination": "New .vive copy path; must differ from source and must not already exist.",
    "file": "Input .vive archive or output PNG path, as specified by the operation.",
    "maxDimension": "Maximum image dimension in pixels; must exceed 32; includes 16-pixel padding per side.",
    "scale": "Image pixels per page dp, at most 1 for the notebook fitting helper.",
    "drawn": "Number of projections actually drawn.",
    "elapsedMillis": "Elapsed rendering plus PNG-encoding time in milliseconds.",
}

NOTES = {
    "InkRuntime": "load() verifies that ByteInk's loader supplies the native engine; call before importing ink.",
    "TriangleMesh": "Geometry exports return independent snapshots; this constructor retains supplied arrays without copying or layout validation. vertexCount = positions.size / 2; triangleCount = triangles.size / 3.",
    "StrokeMesh": "Owned rendering snapshot. Vertex floats: position XY (0–1), opacity shift (2), HSL shift (3–5), side derivative XY/label (6–8), forward derivative XY/label (9–11), surface UV (12–13), animation offset (14). Missing attributes are zero; indices are unsigned native values widened to Int. Constructor retains supplied arrays.",
    "InkMeshes": "Read live strokes on their authoring thread. Returned geometry survives later updates/clear.",
    "SpatialIndex": "Factory construction only. Queries include touching bounds, preserve list order and require exact hit tests afterward.",
    "InkPointerSample": "Coordinates must be finite, uptime nonnegative, measured pressure finite within [0, 1].",
    "InkInputEvent": "Variants: Begin(sample), Move(sample), Finish(sample = null), Cancel. Deliver one gesture at a time.",
    "InkInputSource": "subscribe returns an AutoCloseable subscription. Deliver original device observations serially on the UI thread.",
    "InkAuthoringController": "begin draws the first dot; append buffers; advance processes a frame; finish returns canonical finished geometry or null when idle. close is terminal.",
    "InkDrawingSurface": "Captures brush, transform and callback at pointer down. Disabling/removal cancels; the owner must close the controller and clear the renderer.",
    "InkPathRenderer": "InkPathRenderer() uses 2048 finished entries and 64 MiB. InkPathRenderer(cacheCapacity) keeps the same byte ceiling. Supports texture-free ANY/DISCARD; clearCache releases paths.",
    "InkRenderer": "Shared finished/live drawing contract for path and mesh renderers. renderVersion invalidates retained view rasters when settings or textures change; the default is 0.",
    "InkMeshRenderer": "Full pinned Ink mesh/shader rendering: vertex HSL/opacity, prediction fade, derivative AA, textures and atlas animation. ANY/ACCUMULATE use meshes; DISCARD uses a uniform outline with tiling textures. Single drawing thread; close is terminal and releases shaders/effects. Missing textures try the next compatible paint before failing without partial coat drawing.",
    "rememberInkMeshRenderer": "Remembers the renderer by textureStore identity and closes it on disposal. Omit the store for texture-free brushes.",
    "InkTextureStore": "Supplies preloaded Skia Images by client texture ID. Renderer borrows images and never closes them; cached shaders retain native references. Call renderer.clearCache() after changing an image under the same ID.",
    "InkSceneStroke": "One occurrence; the same native stroke may occur several times with distinct transforms/colors.",
    "InkScene": "Snapshots transforms and indexes bounds. visibleStrokes gives conservative candidates; draw returns the actual drawn count.",
    "InkSceneRasterCache": "InkSceneRasterCache() retains one exact view within 64 MiB. Reuse identical scene/view keys; new views rasterize in full. close is terminal.",
    "ViveBrushes": "Families: marker, dashed-line, highlighter, pressure-pen, calligraphy-v1-p0 through p5. Unknown IDs render as pressure-pen; original metadata is preserved.",
    "ViveInkTool": "Authoring rejects unknown families and levels outside 0–5. Highlighter requires stabilization = 0 and colorFollowsTheme = false. complete requires the captured brush.",
    "AuthoredViveStroke": "canonicalStroke lazily rebuilds row through the codec; use that geometry for a save/reload-consistent preview.",
    "StoredInkStroke": "Preserve original bytes/metadata. Constructor does not validate storage. No database mutation occurs in this library.",
    "StoredInkErase": "Persist the operation and its targets together. Non-null deletedAt disables the operation during replay.",
    "StoredInkMove": "Replay translates first, then scales about the anchor. Keep the original path and target rows.",
    "InkEraseMode": "Case-sensitive stored names: Normal and Object. of returns null for unknown strings.",
    "ViveInkCodec": "Nullable decoders isolate unreadable rows. Input decompression is capped at 64 MiB across gzip members; writes use pinned Android-compatible protobuf/gzip.",
    "LoadedInkPage": "strokes is replayed display geometry; sourceStrokes is pre-replay native geometry; operations contains validated erase/move objects. Do not persist projections as replacement rows.",
    "ViveInkPage": "load filters tombstones, sorts strokes by seq/ID and operations by createdAt/ID, and decodes in 512-row chunks with at most four queued jobs. replay reuses original sourceStrokes and decoded active operations without decoding again. Both block, belong on a worker thread and leave storage untouched.",
    "DecodedInkOperation": "Returned by page loading; Erase/Move constructors are internal. Inspect their public properties, retaining original stored rows for persistence.",
    "InkPoint": "A value in page dp. Pointer observations use a separate pixel-based type.",
    "InkBounds": "Axis-aligned page rectangle; contains includes edges. unionBounds returns null for an empty list.",
    "InkProjectionKey": "Live selection identity: stored row ID plus process-local piece number.",
    "PageStroke": "A live projection, not a new stored row. Multiple pieces may share id. pageBounds/projectionKey are derived read-only properties.",
    "InkPieceErase": "Proved Object erase for one piece, with its resulting page.",
    "InkProjectionDelete": "Persist erases plus wholeRows tombstones; after is the resulting display page. Check the plan: an isolated mask may be unprovable.",
    "LassoShape": "With fewer than three vertices, usable is false and contains returns false. contains tests ink containment, including edge reach, rather than only a center point.",
    "InkPageIndex": "Build per page snapshot. Results use exact native tests after bounds culling, in page order. crossing uses a rectangular band without rounded end caps.",
    "PageBounds": "Origin walls are MIN_X = MIN_Y = 0f. clampTranslation returns the applied delta; clampScale caps growth toward that corner. Supply finite positive scale multipliers.",
    "InkNativeLibrary": "Set JVM properties before the first native use. load is thread-safe and idempotent; use InkRuntime.load to additionally detect loader conflicts.",
    "LoadedInkLibrary": "Returned metadata; constructor is internal. Public properties: path: Path, sha256: String, origin: Origin.",
    "LoadedInkLibrary.Origin": "Values: BUNDLED (packaged native) and PROPERTY (explicit path override).",
    "ViveNotebook": "Optional testing/sample utility. Open a checksum-verified private SQLite copy; close deletes it. Copy helpers append strokes/erases and never overwrite source/destination.",
    "NotebookInkImages": "Ink-only white-paper rasterization, automatic ink resolved to black, 16-pixel padding. Pass a reusable renderer and clear it afterward.",
}

PROPERTIES = {
    "vertexCount": "Number of copied x/y vertex pairs.",
    "StrokeMesh.vertexCount": "Number of canonical vertices: vertices.size / VERTEX_STRIDE.",
    "hasSurfaceUv": "Whether attributeMask bit 7 is set.",
    "hasAnimationOffset": "Whether attributeMask bit 8 is set.",
    "triangleCount": "Number of copied index triples.",
    "SpatialIndex.size": "Total item count, including items with no queryable bounds.",
    "revision": "Compose snapshot state, initially 0; changes after processed geometry/lifecycle updates.",
    "isDrawing": "Compose snapshot state, initially false; whether a gesture is active.",
    "hasPendingInputs": "Compose snapshot scheduling state, initially false; append can set it before geometry changes.",
    "liveStroke": "Active engine stroke, or null while idle. Read on the controller's authoring thread.",
    "strokeToView": "Captured stroke-to-surface transform; initially identity.",
    "pathBuildCount": "Cumulative finished/live path builds, retained across clearCache calls.",
    "cachedShapeCount": "Number of retained finished shapes; excludes live paths.",
    "InkMeshRenderer.cachedShapeCount": "Number of retained finished mesh shapes; excludes weakly owned live geometry.",
    "renderVersion": "Renderer revision used by viewport raster keys; mesh animation-time changes and clearCache increment it.",
    "animationTimeMillis": "Writable nonnegative elapsed millisecond clock for texture atlases; changing it invalidates Compose drawing and view rasters. Live shape effects also need updateShape.",
    "meshBuildCount": "Cumulative coat mesh preparations, retained across clears.",
    "cachedTextureCount": "Number of retained texture shaders, bounded by textureCacheCapacity.",
    "cachedGeometryBytes": "Retained finished mesh/prepared-vertex/path bytes, bounded by cacheByteBudget; excludes live geometry, JVM headers, GPU uploads and provider images.",
    "cachedPathBytes": "Estimated retained finished-path geometry bytes; bounded by cacheByteBudget.",
    "pathEvictionCount": "Cumulative finished-shape retirements caused by capacity/byte limits.",
    "rasterBuildCount": "Cumulative viewport raster builds, retained across clears.",
    "retainedPixelBytes": "Retained N32 raster bytes, bounded by pixelBudgetBytes; zero after clear/close.",
    "cachedViewCount": "Number of retained exact viewport images, bounded by cacheCapacity.",
    "rasterEvictionCount": "Cumulative LRU image retirements caused by capacity/byte limits.",
    "canonicalStroke": "Lazy native rebuild of the authored stored row, matching save/reload geometry.",
    "sourceStrokes": "Immutable ordered list of decoded source-row projections before operation replay.",
    "operations": "Immutable creation-time/ID ordered validated erase/move geometry; skipped operations are omitted.",
    "pageBounds": "Lazily computed page rectangle, or null for no geometry; PageStroke assumes axis-aligned positive scales.",
    "projectionKey": "Stored row ID plus process-local piece number, carried through copy operations.",
    "Stroke.hasGeometry": "Whether the native bounding box exists; guard exact native geometry tests with it.",
    "usable": "Whether the lasso has at least three vertices.",
    "acceptsWholeBox": "Whether the polygon is convex, enabling the four-corner containment shortcut.",
    "loaded": "Loaded native metadata, or null before the first successful load.",
    "pageIds": "All notebook page IDs, including empty pages, sorted by ID.",
    "center": "Midpoint of the page rectangle.",
    "stored": "Case-sensitive enum name stored in ink_erases.mode: Normal or Object.",
}


def masks(text: str) -> tuple[str, str]:
    """Keep offsets/newlines while masking comments and strings for brace matching."""
    pattern = re.compile(r'"""[\s\S]*?"""|"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|/\*[\s\S]*?\*/|//[^\n]*')
    code, masked = list(text), list(text)
    for match in pattern.finditer(text):
        comment = match.group().startswith(("//", "/*"))
        for i in range(match.start(), match.end()):
            if text[i] != "\n":
                masked[i] = " "
                if comment:
                    code[i] = " "
    return "".join(code), "".join(masked)


def pairs(mask: str) -> dict[int, int]:
    result, stack = {}, []
    for i, char in enumerate(mask):
        if char in "({[":
            stack.append((char, i))
        elif char in ")}]":
            opening, at = stack.pop()
            if "({[".index(opening) != ")}]".index(char):
                raise ValueError(f"Unbalanced Kotlin delimiters at {i}")
            result[at] = i
    return result


def end_header(mask: str, start: int, paired: dict[int, int]) -> int:
    i = start
    while i < len(mask):
        if mask[i] in "([":
            i = paired[i] + 1
        elif mask[i] in "={\n":
            return i
        else:
            i += 1
    return i


def split_parameters(text: str) -> list[tuple[str, str, str, bool]]:
    _, mask = masks(text)
    stack, parts, start = [], [], 0
    for i, char in enumerate(mask):
        if char in "(<[{":
            stack.append(char)
        elif char in ")>]}":
            if char == ">" and (i > 0 and mask[i - 1] == "-" or not stack or stack[-1] != "<"):
                continue
            if stack:
                stack.pop()
        elif char == "," and not stack:
            parts.append(text[start:i].strip())
            start = i + 1
    parts.append(text[start:].strip())
    result = []
    for part in filter(None, parts):
        part = re.sub(r"@\w+(?:\([^)]*\))?\s*", "", part)
        match = re.fullmatch(r"(?:(?:public|private|internal|override|val|var|vararg)\s+)*(\w+)\s*:\s*([\s\S]+)", part)
        if not match:
            raise ValueError(f"Cannot read parameter {part!r}")
        name, rest = match.groups()
        value = rest.split("=", 1)
        result.append((name, " ".join(value[0].split()), " ".join(value[1].split()) if len(value) > 1 else "Required", bool(re.search(r"\b(val|var)\b", part))))
    return result


DECL = re.compile(r"^[ \t]*(?P<mods>(?:(?:public|private|internal|protected|data|sealed|abstract|const|override|open|final|suspend|inline|operator|companion|enum|fun(?=\s+interface))\s+)*)(?P<kind>class|object|interface|fun|constructor|val|var)\b", re.M)


def declarations(path: Path) -> list[dict]:
    code, mask = masks(path.read_text())
    paired = pairs(mask)
    types, found, constructor_spans = [], [], []
    matches = list(DECL.finditer(mask))
    for match in matches:
        if match["kind"] not in ("class", "object", "interface"):
            continue
        end = end_header(mask, match.end(), paired)
        header = code[match.start():end].strip()
        name_match = re.match(r"\s*(\w+)", mask[match.end():])
        name = name_match[1] if name_match else "Companion"
        companion = "companion" in match["mods"]
        body = end if end < len(mask) and mask[end] == "{" else None
        opening = mask.find("(", match.end(), end)
        ctor = None if opening < 0 else (opening, paired[opening])
        if ctor:
            constructor_spans.append(ctor)
        types.append(dict(start=match.start(), end=paired[body] if body is not None else end,
                          body=body, name="Companion" if companion else name, companion=companion,
                          private=bool(re.search(r"\b(private|internal|protected)\b", match["mods"])),
                          header=header, ctor=ctor, kind=match["kind"]))

    def parents(at):
        return sorted([t for t in types if t["body"] is not None and t["body"] < at < t["end"]], key=lambda t: t["start"])

    for match in matches:
        mods, kind, at = match["mods"], match["kind"], match.start()
        owner_types = parents(at)
        if re.search(r"\b(private|internal|protected)\b", mods) or any(t["private"] for t in owner_types):
            continue
        if any(start < at < end for start, end in constructor_spans):
            continue
        owner = ".".join(t["name"] for t in owner_types if not t["companion"])
        if "public" not in mods:
            # Include implicit public direct properties and close overrides, never locals.
            braces = [start for start, end in paired.items() if mask[start] == "{" and start < at < end]
            if not owner_types or not braces or max(braces) != owner_types[-1]["body"]:
                continue
        end = end_header(mask, match.end(), paired)
        header = code[at:end].strip()
        header = re.sub(r"\s+", " ", header)
        if kind == "constructor":
            header = header.split(" :", 1)[0].rstrip(": ")
        if kind in ("class", "object", "interface"):
            current = next(t for t in types if t["start"] == at)
            if current["companion"]:
                continue
            name = current["name"]
            parameters, fields = [], []
            ctor = current["ctor"]
            if ctor:
                opening, closing = ctor
                all_params = split_parameters(code[opening + 1:closing])
                if re.search(r"\b(private|internal)\s+constructor\b", header):
                    # These types are returned by factories; only expose their public val/var fields.
                    for raw, param in zip([p for p in re.split(r",\s*(?![^()]*\))", code[opening + 1:closing]) if p.strip()], all_params):
                        if param[3] and not re.search(r"\b(private|internal)\b", raw):
                            fields.append(param)
                    header = re.sub(r"\s+(private|internal)\s+constructor.*", "", header)
                else:
                    parameters = all_params
            found.append(dict(name=".".join(filter(None, [owner, name])), kind=kind, header=header,
                              parameters=parameters, fields=fields, source=path, line=code.count("\n", 0, at) + 1))
            continue
        opening = mask.find("(", match.end(), end) if kind in ("fun", "constructor") else -1
        if kind in ("fun", "constructor"):
            if opening < 0:
                raise ValueError(header)
            before = code[match.end():opening].strip()
            name = before if kind == "fun" else "constructor"
            if name in ("equals", "hashCode", "toString"):
                continue
            params = split_parameters(code[opening + 1:paired[opening]])
        else:
            name = re.match(r"\s*(\w+(?:\.\w+)*)", code[match.end():])[1]
            params = []
            header = re.split(r"\s+(?:get|by)\b", header)[0]
            if end < len(mask) and mask[end] == "=" and "const" in mods:
                header += " = " + code[end + 1:code.find("\n", end)].strip()
        found.append(dict(name=".".join(filter(None, [owner, name])), kind=kind, header=header,
                          parameters=params, fields=[], source=path, line=code.count("\n", 0, at) + 1))
    return found


def meaning(name: str, param: str) -> str:
    if name == "ViveInkPage.replay" and param == "strokes":
        return "Original pre-operation projections, such as LoadedInkPage.sourceStrokes; never supply already cut/moved display projections. Their draw order is preserved."
    if name == "LoadedInkLibrary" and param == "path":
        return "Absolute path to the native file loaded by this JVM."
    if param == "id":
        return "Stored brush family ID; unknown IDs fall back to pressure-pen." if name == "ViveBrushes.family" else "Caller-supplied unique row identifier."
    if name == "InkProjectionDelete" and param == "erases":
        return "Proved per-piece Object erase plans; encode each mask with its rowId as target."
    if param == "width" and name.endswith("crossing"):
        return "Finite nonnegative eraser-band diameter in page dp."
    if param == "bounds" and name.endswith("<T> of"):
        return "Callback returning each item's immutable bounds, or null for no indexed geometry."
    if param not in PARAMETERS:
        raise ValueError(f"Missing reviewed parameter description: {name}.{param}")
    return PARAMETERS[param]


def table(name: str, params: list[tuple], properties=False) -> str:
    if not params:
        return ""
    output = ["", "| " + ("Property" if properties else "Parameter") + " | Type | Default | Meaning |",
              "| --- | --- | --- | --- |"]
    for param, type_, default, _ in params:
        if properties:
            default = "Returned value"
        output.append(f"| `{param}` | `{type_}` | `{default}` | {meaning(name, param)} |")
    return "\n".join(output) + "\n"


def render(title: str, module: str, files: list[str]) -> tuple[str, int]:
    result = [f"# {title}", "", f"Module: `{module}`. [Conventions](index.md). Signatures and defaults follow the current source.", ""]
    count = 0
    for filename in files:
        paths = list((ROOT / module / "src").glob(f"*/kotlin/com/vivenotes/byteink/**/{filename}.kt"))
        if len(paths) != 1:
            raise ValueError(f"Expected one source for {module}/{filename}, got {paths}")
        path = paths[0]
        source_lines = path.read_text().splitlines()
        entries = declarations(path)
        if not entries:
            continue
        result += [f"## {filename}", ""]
        for entry in entries:
            count += 1
            name = entry["name"]
            result += [f"### `{name}`", ""]
            note = NOTES.get(name, NOTES.get(filename) if name == filename else None)
            if note:
                result += [note, ""]
            # The original line retains declaration annotations despite normalized headers.
            source_prefix = "\n".join(source_lines[max(0, entry["line"] - 5):entry["line"] - 1])
            if entry["kind"] == "fun" and re.search(r"@Composable\s*(?:@[^\n]+\s*)*$", source_prefix):
                result += ["Composable: call within a Compose composition.", ""]
            relative = path.relative_to(ROOT).as_posix()
            result += [f"```kotlin\n{entry['header']}\n```", ""]
            if entry["kind"] in ("val", "var"):
                description = PROPERTIES.get(name, PROPERTIES.get(name.rsplit(".", 1)[-1]))
                if description:
                    following = "\n".join(source_lines[entry["line"]:])
                    next_declaration = DECL.search(following)
                    if next_declaration:
                        following = following[:next_declaration.start()]
                    private_setter = entry["kind"] == "var" and re.search(r"\bprivate\s+set\b", following)
                    result += [description + (" Private setter." if private_setter else ""), ""]
            result += [table(name, entry["parameters"]), table(name, entry["fields"], properties=True)]
            result += [f"[Source](https://github.com/CrownByte0b/ByteInk/blob/master/{relative}#L{entry['line']})", ""]
    return re.sub(r"\n{3,}", "\n\n", "\n".join(result).rstrip()) + "\n", count


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail if generated reference differs from public sources")
    args = parser.parse_args()
    total, changed = 0, []
    for slug, (title, module, files) in GROUPS.items():
        content, count = render(title, module, files)
        total += count
        path = ROOT / "docs" / "reference" / f"{slug}.md"
        if args.check:
            if not path.exists() or path.read_text() != content:
                changed.append(str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
    if changed:
        raise SystemExit("Regenerate API reference: " + ", ".join(changed))
    print(f"{'Checked' if args.check else 'Generated'} {total} public declarations across {len(GROUPS)} reference pages")


if __name__ == "__main__":
    main()
