# ByteInk — AndroidX Ink for Kotlin Desktop

ByteInk is a reusable Kotlin/JVM library that brings the real **AndroidX Ink / `google/ink` stroke engine** to **Linux x86_64 and Windows x86_64**,
primarily for ViveNotes’ Kotlin Multiplatform desktop app.

The goal is to generate and render strokes using the same engine and brush definitions as the Android app, preserving stroke geometry, stabilization
behaviour and stored ink compatibility across platforms.

## Technical approach

ByteInk uses AndroidX Ink’s published JVM APIs, backed by native C++ code through JNI. It builds and packages `libink.so` for Linux and `ink.dll`
for Windows, with a narrowly scoped native-loader fork handling platform detection, extraction and loading.

Rendering is implemented through **Compose Desktop and Skia**, using engine-generated outlines and triangle geometry. The renderer supports finished
and in-progress strokes, affine transforms, viewport culling and bounded geometry caching.

## Intended capabilities

- **Drawing:** pens, highlighters, pressure pens, dashed strokes and ViveNotes’ custom calligraphy brushes, including stabilization.
- **Editing and selection:** hit testing, lasso selection, moves, resizes, whole-stroke erasing and replay of existing partial erases.
- **Persistence:** Android-compatible stroke encoding and decoding, preserving existing rows and unknown data during notebook round trips.
- **Distribution:** reusable Gradle dependencies, with packaged native binaries and isolated upstream modifications.
- **Verification:** native/JVM tests, cross-platform geometry comparisons, Android rendering comparisons and save/load tests in both directions.

# Local builds

## Gui demo

```bash
./gradlew :samples:viewer:run

```

## Publish local package

```bash
./gradlew publishToMavenLocal
```

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
