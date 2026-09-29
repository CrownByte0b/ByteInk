# getting android-ink from google

AndroidX Ink's `ink/` sources at the pinned release, 1.1.0-alpha06: `frameworks/support` commit
`61ee8cd421d0` (see `upstream/pins.properties`), from AndroidX's GitHub mirror.

```bash
git init androidx-ink
cd androidx-ink
git remote add origin https://github.com/androidx/androidx.git
git sparse-checkout set --cone ink
git fetch --depth 1 --filter=blob:none origin 61ee8cd421d0c0252d8db0253b739de537999371
git checkout --detach FETCH_HEAD
```

# Using byteink

byteink is not published to a remote repository yet. Publish it into `build/repo` with:

```bash
./gradlew :ink-nativeloader:publishAllPublicationsToBuildRepository \
  :byteink-core:publishAllPublicationsToBuildRepository \
  :byteink-compose:publishAllPublicationsToBuildRepository \
  :byteink-vive:publishAllPublicationsToBuildRepository \
  :byteink-testing:publishAllPublicationsToBuildRepository
```

| Module | Coordinates |
|---|---|
| ViveNotes ink (brings the core) | `com.vivenotes.byteink:byteink-vive:0.1.0-SNAPSHOT` |
| Core | `com.vivenotes.byteink:byteink-core:0.1.0-SNAPSHOT` |
| Compose Desktop renderer | `com.vivenotes.byteink:byteink-compose:0.1.0-SNAPSHOT` |
| Native loader (a dependency of the core) | `com.vivenotes.byteink:ink-nativeloader:1.1.0-alpha06-byteink.1` |
| Test support: `.vive` notebooks in tests | `com.vivenotes.byteink:byteink-testing:0.1.0-SNAPSHOT` |

`byteink-core` brings Google's AndroidX Ink JVM modules (`ink-brush`, `ink-geometry`,
`ink-storage`, `ink-strokes`, 1.1.0-alpha06) unchanged. The one difference is the native loader:
Google's is replaced by byteink's fork of it (`ink-nativeloader/PATCHES.md`), which bundles byteink's
native library for Linux x86_64 and Windows x86_64.

## The native library

- **When it loads:** on first use of Ink. `InkRuntime.load()` loads it explicitly, and fails fast if
  Google's loader has got onto the classpath.
- **Where it goes:** it is extracted once per user and reused from there:
  - Linux: `$XDG_CACHE_HOME/byteink/natives/<sha256>/libink.so`, or under `~/.cache`.
  - Windows: `%LOCALAPPDATA%\byteink\natives\<sha256>\ink.dll`.
  - If that cache cannot be written, a new private temporary directory is used.
- **`-Dbyteink.ink.library=<absolute path>`:** loads a copy installed with the application instead,
  for Flatpak or jpackage builds, for example.
- **`-Dbyteink.ink.cache=<directory>`:** extracts somewhere else.
- **`InkNativeLibrary.loaded`:** says which file was loaded, and its SHA-256.

## ViveNotes ink

`byteink-vive` reads, replays and writes ink the way the Android app stores it, on the same engine.

- **Loading a page:** pass the page's rows from `ink_strokes`, `ink_erases` and `ink_moves` to
  `ViveInkPage.load`, as `StoredInkStroke`, `StoredInkErase` and `StoredInkMove` with their target
  ids.
  - It returns the page's strokes in draw order. Each is a `PageStroke`: the stroke, where it sits
    on the page, and which row it came from.
  - Every partial erase, object erase, move and resize has been replayed as Android replays it.
  - Rows it cannot read are listed in `unreadable`; it does not throw.
- **Hit testing:** `InkPageIndex(strokes)` finds what an eraser mask touches (`targetsFor`,
  `touching`), what a lasso selects (`selectWithLasso`), and the ink at a point or along a segment.
  It answers exactly as scanning every stroke would.
- **Writing:** `ViveInkCodec` makes new rows:
  - Strokes: `encodeStroke`, `encodeHighlighter`, `encodeCopy`, with brushes from `ViveBrushes`.
  - Object erases: `encodeErase`, with targets from `targetsFor`.
  - Moves: `encodeMove` and `encodeResize`.
- **What it never does:** rewrite a stored row. Ids, `seq` and timestamps come from the caller's
  database.
- **Partial erases:** they replay. Writing new ones is not supported yet.

## Compose Desktop rendering

Keep an `InkPathRenderer` for the canvas and call `drawInk` from its `DrawScope`, or
`renderer.draw` with a Compose `Canvas`. Both accept finished and in-progress Ink strokes.
The supplied `AffineTransform` maps stroke coordinates into the canvas's local pixel coordinates;
the renderer applies it on top of any transform already on the canvas. For a ViveNotes projection,
combine its offset and scale with the page's pan, zoom and density.

```kotlin
val renderer = remember { InkPathRenderer() }
Canvas(Modifier.fillMaxSize()) {
    drawInk(renderer, stroke, strokeToCanvas)
}
```

The renderer fills each coat once, with antialiasing and the paint's colour functions. It supports
texture-free `ANY` and `DISCARD` paints, including ViveNotes' highlighter. Shapes made by `split`
have no outlines at the pinned Ink release; their triangles form one winding path, keeping erased
gaps visible. Finished paths are cached by shape in a bounded cache; live paths update with the
stroke's mutation version. `clearCache()` releases the cached geometry when changing pages.

Uniform fills omit per-vertex colour/opacity changes and prediction fading. `ACCUMULATE` and
textured paints are refused; `canDraw` checks whether a brush is supported. Android screenshot
comparisons are still pending. The implementation follows the pinned AndroidX `CanvasPathRenderer`
and [Skia's path fill rules](https://skia.org/docs/user/api/skpath_overview/).

The sample viewer verifies archive checksums, replays the notebook's ink, and shows every page with
pan and zoom. Automatic ink resolves to black on its white paper; deliberate colours retain their
alpha. The preview shows ink only.

```bash
./gradlew :samples:viewer:run --args='/path/to/notebook.vive'
./gradlew :samples:viewer:run --args='--render-all /path/to/notebook.vive /path/to/pngs'
```

`byteink-testing` also provides `NotebookInkImages.writePng` and
`ViveNotebook.writeCopyWithStrokes`, which appends rows to a separate test copy and refreshes its
checksums and database metadata. This helper preserves existing rows and archive entries; the
application's export and sync integration comes later.

In tests, `byteink-testing` reads a `.vive` notebook's rows: `ViveNotebook.open(file).page(pageId)`.

## If your build also depends on Google's Ink

`byteink-core` claims the capability of Google's `androidx.ink:ink-nativeloader`. A build that also
pulls in Google's loader, through `androidx.ink:ink-strokes` for example, therefore fails to resolve
with "Both provide capability 'androidx.ink:ink-nativeloader…'". Put byteink's loader in its place:

```kotlin
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.ink:ink-nativeloader"))
            .using(module("com.vivenotes.byteink:ink-nativeloader:1.1.0-alpha06-byteink.1"))
    }
}
```

`consumer-tests` checks this snippet against the published metadata.

# Building byteink

The loader jar bundles both native libraries, so a build needs them first:

- Build them with `native/build-linux.sh` and `native/build-windows.sh`. These run on Linux with
  Bazel, which the scripts fetch; see `upstream/README.md`.
- Or use builds from elsewhere, such as CI's `libink-linux-x86_64` and `ink-windows-x86_64`
  artifacts: unpack them into `native/build/out/`, as CI's JVM job does, or name the files with
  `-PbyteinkLinuxLibrary=<file> -PbyteinkWindowsLibrary=<file>`.

Then `./gradlew build` runs everything:

- the loader's tests, on JDK 27 and on JDK 25;
- AndroidX's own Ink suites, on byteink's library and on Google's;
- the ViveNotes ink tests, including the Android app's own tests ported to the desktop;
- the consumer tests, and the checks that the fork is upstream's code plus `patches/`.

Two properties run more than the default:

- **`-PbyteinkNotebooks=<directory>`:** replays every `.vive` notebook in a directory and checks
  hit testing on its pages, renders every page to PNG, and appends new strokes to temporary copies
  for a save/load check. Reports go to `byteink-testing/build/reports/notebooks/`, with images under
  `rendering/`. `BYTEINK_NOTEBOOKS` is an alternative to the property. Notebooks are personal, so
  none are committed, and these tests skip when no directory is named. Saved-versus-rebuilt bounds
  are reported as diagnostics; fidelity requires comparison with geometry rebuilt on Android from
  the same saved inputs.
- **`-PbyteinkFuzzIterations=<count>` and `-PbyteinkFuzzSeed=<seed>`:** run the fuzzer longer
  (the default is 500 iterations of seed 1), for example
  `./gradlew :byteink-vive:test --tests '*FuzzTest*' -PbyteinkFuzzIterations=100000 -PbyteinkFuzzSeed=2`.
  It runs random ink through every operation in a JVM of its own, because a failing native check
  aborts the whole process. A failure names the seed and iteration that reproduce it.
