# Wasm port: plan and implementation status

The 2026-11-05 request is to plan WebAssembly support and start implementing it. The engine
milestone and shared API/storage milestone are implemented. The browser preview is opt-in with
`-PbyteinkWasm=true`; existing desktop build commands and publication coordinates stay intact.
It is not part of the published 0.1.0-alpha.1 release.

## Architecture

AndroidX Ink's JVM jars use JNI and cannot be linked into Kotlin/Wasm. `byteink-wasm` therefore
calls a separate Emscripten-built instance of the **same** patched google/ink engine. Kotlin's
WasmGC heap and the engine's linear memory are separate. The small ES module boundary uses
typed arrays and owned C++ objects; it does not approximate or reimplement stroke modelling.

- Engine/native pin and all eight desktop patches are unchanged. CMake shares the exact
  160-entry native source inventory, filtering JNI and Android/Skia renderer adapters for Wasm.
- Emscripten 6.0.13 / emsdk commit `35659964bc2762fffec0d257997fb61475f765b7`; C++20, `-O2`,
  no fast math, `-ffp-contract=off`, native Wasm exceptions, 1 MiB stack and a growing heap
  capped at 512 MiB. No pthreads, filesystem, JNI, JVM, Skia or cross-origin isolation requirement.
- Source checkout `native/build/google-ink-wasm`, host protoc `native/build/wasm-host`, cross
  build `native/build/wasm`; no reuse/reset of the desktop source checkout. Both protoc and
  linked protobuf come from the existing protobuf 33.5 pin.
- Outputs: `native/build/out/<google.ink.commit>/wasm/byteink-engine.{mjs,wasm}`,
  `build.properties` and `licenses/`. The manifest records ABI 2, engine/toolchain pins,
  bridge-source and patch hashes and both output hashes. Gradle checks them before testing
  and packaging. Runtime loading also checks the ABI and engine pin.
- Maven preview coordinates `com.vivenotes.byteink:byteink-wasm:0.1.0-SNAPSHOT`; engine static
  assets in the root publication's `engine@zip` classifier. No local npm dependency paths
  enter published metadata. Kotlin-only compilation needs no C++ toolchain.
- Installed Node/npm are used for the opt-in build. Kotlin's Binaryen distribution is resolved
  from its narrowly scoped settings Ivy repository. Only the opt-in build switches to
  PREFER_SETTINGS, ignoring Kotlin's redundant project repository; desktop retains
  FAIL_ON_PROJECT_REPOS. `kotlin-js-store/wasm/package-lock.json` is tracked.

## Milestones

- [x] **1 — Real engine in Wasm.** Stock brushes (version 1 marker, pressure pen, highlighter
      with DISCARD, dashed line); arbitrary serialized brush families; finished and live strokes;
      copied position/triangle/outlines; native point queries; raw upstream protobuf inputs;
      Android-compatible input gzip with the same private phase field and pinned zlib.
- [x] **2 — Kotlin/Wasm API and packaging.** Async ES module initialization, explicit resource
      ownership/idempotent close, copied snapshots, validation, prediction replacement and
      separate engine ZIP with licenses. Finishing strips prediction and rebuilds a finished
      Stroke from real inputs so a fully consumed last frame cannot leave smoothing unflushed.
      This complete rebuild is an intentional initial cost, not a performance claim.
- [x] **3 — Browser path.** Kotlin/Wasm production consumer, pen/highlighter Canvas2D outline
      drawing, save/reload, cancel and clear; native-backed Kotlin/Node tests; all 840 Android
      input fixtures and spatial geometry checks; real Chromium checks at 1280px/390px.
- [x] **4a — Repeatable preview CI.** `wasm.yml`, callable/dispatchable and called by `ci.yml`,
      builds and checks the engine, Kotlin tests, local publications and actual browser consumer.
      Official release publishing still excludes the experimental module. GitHub execution
      awaits the user's push; local runs do not claim remote acceptance.
- [x] **4b — Shared API / ViveNotes kit.** `byteink-model` shares the existing settings/row types
      with unchanged JVM names. `byteink-kit` re-exports it; existing AndroidX entry points remain.
      `byteink-portable` provides common engine/style/stroke/row/replay APIs with JVM and Wasm
      adapters. Named calligraphy/stabilization families match the Android protobufs exactly.
      Masks, subtraction, splitting, transformed hit tests, lasso moves/resizes, operation replay,
      tombstones and unknown-data retention run through a single common implementation. Target
      buckets bound replay work; intermediate owners are closed while original source handles
      and final projections survive until page close. The same contract checks all 280 Android
      pages / 840 input encodings / 891 replayed projections / 3,564 native coverage probes on both
      backends. The browser sample
      now persists rows and operations, with reload/undo checks for partial/Object erase and move.
- [ ] **5 — Library rendering / browser authoring.** Compose/Wasm path rendering, complete mesh
      attributes/shaders/textures and animations, retained geometry/cache lifetime; browser
      coalesced/predicted samples, pressure/tilt/orientation/physical-unit normalization, time
      precision, pointer capture/cancel and multiple contacts. The sample adapter is deliberately
      a small single-pointer consumer, not this production input layer.
- [ ] **6 — Full parity / release.** Add operation round trips in both directions, browser
      engine/renderer matrices (Chromium/Firefox/WebKit), independent packaged consumers,
      compatibility/performance measurements, and publication wiring only after acceptance.
      Investigate the strict buffer-order differences below; do not call the first milestone
      full desktop/Android renderer or API parity. WASI is a separate target, not implemented.

## Reproduce

Linux build prerequisites: JDK (`JAVA_HOME` set), CMake >=3.25, Ninja, Python, git and curl.
The SDK and CMake dependencies are downloaded once into ignored directories. Node 22+ is needed
for Kotlin/WasmGC; the pinned SDK's Node works (`source native/build/emsdk/emsdk_env.sh`).

```sh
native/setup-wasm.sh
BYTEINK_JOBS=8 native/build-wasm.sh
source native/build/emsdk/emsdk_env.sh
node native/wasm/engine.test.mjs
./gradlew -PbyteinkWasm=true :byteink-model:wasmJsNodeTest \
  :byteink-wasm:wasmJsNodeTest :byteink-portable:wasmJsNodeTest \
  :byteink-model:publishAllPublicationsToBuildRepository \
  :byteink-portable:publishKotlinMultiplatformPublicationToBuildRepository \
  :byteink-portable:publishWasmJsPublicationToBuildRepository \
  :byteink-wasm:publishAllPublicationsToBuildRepository :samples:wasm:wasmJsBrowserDistribution
python -m pip install playwright==1.63.0
python -m playwright install chromium
python native/wasm/browser-test.py
```

After staging the preview, `consumer-tests/wasm-fixture` is a standalone Node application using
only Maven coordinates. Set `BYTEINK_WASM_ENGINE` to the absolute engine `.mjs` file URL and run
`./gradlew -p consumer-tests/wasm-fixture wasmJsNodeDevelopmentRun`. It checks row/operation
replay, unknown-data retention, independent ownership, native coverage and tombstone undo.

`browser-test.py --browser /absolute/path/to/chromium` reuses an installed executable. Its local
HTTP server/browser are always closed and its reports/screenshots go to `build/wasm-browser/`.
The API guide is [docs/guides/wasm.md](../docs/guides/wasm.md).

`-PbyteinkPortable=true` includes the facade's JVM target without Node/Emscripten; JVM CI runs
its contract and published/composite consumer smoke checks with that flag. The Wasm flag enables
both targets and the browser sample. `byteink-model` is always included because the existing
JVM kit depends on it. Release publishing includes that transitive model artifact, while the
portable/engine preview remains excluded. Wasm-only CI stages the facade root and Wasm
publications explicitly so it needs no desktop native bundle.

## Geometry acceptance and remaining limits

Every one of the 840 Android matrix input blobs matches the committed Android re-encoding gzip
exactly. Native decoder/brush construction runs on all of them. Oriented closed boundaries,
vertex counts/positions and native coverage probes match within the existing oracle-style
float tolerance; largest observed float gap `0.00002224121092808673` stroke units.

812 input strokes also match triangle-index and outline start ordering directly. The other
28 are size-20 round markers: cap vertex/triangle ordering differs and the closed outline starts
at a different vertex. The tests explicitly normalize only contour rotation and match vertex
positions bijectively, check counts/index bounds and compare native coverage. They report
`reorderedMeshes=28`, `rotatedOutlines=28`. The exact cause of representation ordering has not
been established; the native engine/source pin and algorithm are unchanged. This is not an
allowance for a changed boundary or a wider coordinate tolerance.

Run `BYTEINK_WASM_STRICT_TOPOLOGY=1 node native/wasm/engine.test.mjs` to require the original
buffer ordering as well; it currently fails at `marker-s0-mouse-brush`. That stricter parity
gate remains open and is not presented as passing CI. No Android pixel/shader fidelity,
physical-pen or pen-to-photon measurement is part of this milestone.

The C++ bridge exports only positions, indices and outlines. Arbitrary family geometry can be
built, but textures, vertex opacity, prediction fade and other effects need a renderer. Android
input blobs and complete note/operation rows are supported; databases and transaction/undo
integration remain application-owned. Reading never authorizes rewriting an original stored blob
or dropping unknown fields. `InkPage` retains original rows separately from derived geometry.

## References

- https://kotlinlang.org/docs/wasm-js-interop.html
- https://kotlinlang.org/docs/wasm-configuration.html
- https://kotlinlang.org/docs/js-project-setup.html
- https://emscripten.org/docs/porting/connecting_cpp_and_javascript/embind.html
- https://emscripten.org/docs/compiling/Modularized-Output.html
- Pinned engine headers and implementation; existing native/ANGLE-MATH.md and eight patches.
