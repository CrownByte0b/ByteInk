# Wasm port: plan and implementation status

The 2026-11-05 request is to plan WebAssembly support and start implementing it. The engine
milestone, shared API/storage, rendering/browser-authoring and final local acceptance are implemented. The browser preview is opt-in with
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
  `build.properties` and `licenses/`. The manifest records ABI 4, engine/toolchain pins,
  bridge-source and patch hashes and both output hashes. Gradle checks them before testing
  and packaging. Runtime loading also checks ABI 4, engine pin and `bionic-acosf-v1` math profile.
  Replace both assets when upgrading the preview.
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
      Initial releases excluded the experimental module; milestone 6 adds gated publication.
      GitHub execution
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
- [x] **5 — Library rendering / browser authoring.** Opt-in `byteink-compose-wasm` supplies path
      and full-attribute mesh renderers, sharing the desktop vertex/preparation/fragment source.
      Owned snapshots (introduced in ABI 3, now ABI 4) expose paints, all canonical attributes, texture metadata and origins.
      Bounded finished/live geometry and texture caches have explicit retirement/close. Tiling,
      stamping, blends, vertex opacity/HSL, prediction fade, timed effects and atlas animation are
      checked against 63 synthetic desktop software controls. Atlas metadata is supplied separately:
      the pinned upstream protobuf drops atlas fields and cannot serialize animation-offset targets.
      `InkTextureAnimations` carries application texture definitions; direct snapshot drawing tests
      nonzero per-particle offsets without patching stored protobufs. Development family decoding
      is an explicit per-brush opt-in; its default version gate remains unchanged.
      `byteink-wasm` provides UI-independent browser normalization and multi-pointer authoring
      sessions: coalesced history, replacement/expiring predictions, pressure/shaft axes, inverse
      transforms, calibrated physical units and fractional time, capture and lifecycle cancellation.
      The Compose sample uses these library APIs rather than its previous single-pointer adapter.
      Chromium checks actual pen capture/axes/fractional history and simultaneous touch finish/cancel
      at 1280/390 widths; the independent Maven-only Compose consumer renders and disposes cleanly.
- [x] **6 — Local parity acceptance / release wiring.** All 840 Android buffers now pass direct
      order checks by default. Chromium/Firefox/WebKit pass software controls, native desktop-vs-Wasm
      geometry on actual Compose/WebGL canvases and input/lifetime checks. JVM→Wasm→JVM file exchange
      passes 29 rows, six erases, seven moves and 13 replay/undo states. Independent versioned Maven
      Node/browser consumers and the complete local publication task graph pass. CPU/software timing
      baselines are recorded separately from GPU and physical latency. `wasm.yml` gates release
      publication, receives the exact release version and supplies checked engine assets; publishing
      includes portable/engine/Compose Wasm variants. Hosted execution and actual release await the
      user's commit/push. No remote publication, commit, push or README edit was performed.
      This accepts the documented shared/Wasm surface, not every AndroidX/JVM UI API or Android pixel
      identity. WASI is a separate, unimplemented target.

## Reproduce

Linux build prerequisites: JDK (`JAVA_HOME` set), CMake >=3.25, Ninja, Python, git and curl.
The SDK and CMake dependencies are downloaded once into ignored directories. Node 22+ is needed
for Kotlin/WasmGC; the pinned SDK's Node works (`source native/build/emsdk/emsdk_env.sh`).

```sh
native/setup-wasm.sh
BYTEINK_JOBS=8 native/build-wasm.sh
source native/build/emsdk/emsdk_env.sh
node native/wasm/engine.test.mjs
bash native/wasm/test-math.sh
./gradlew -PbyteinkWasm=true :byteink-model:wasmJsNodeTest \
  :byteink-wasm:wasmJsNodeTest :byteink-portable:wasmJsNodeTest \
  :byteink-model:publishAllPublicationsToBuildRepository \
  :byteink-portable:publishKotlinMultiplatformPublicationToBuildRepository \
  :byteink-portable:publishWasmJsPublicationToBuildRepository \
  :byteink-wasm:publishAllPublicationsToBuildRepository :samples:wasm:wasmJsBrowserDistribution
python -m pip install playwright==1.63.0 pillow==12.3.0
python -m playwright install --with-deps chromium firefox webkit
for engine in chromium firefox webkit; do
  python native/wasm/browser-test.py --engine "$engine" --output "build/wasm-browser/$engine"
  python native/wasm/gpu-browser-test.py --engine "$engine" --output "build/wasm-gpu-browser/$engine"
  python native/wasm/benchmark-browser.py --engine "$engine" --output "build/wasm-benchmark/$engine"
done
```

Milestone 5 additionally stages `:byteink-compose-wasm:publishAllPublicationsToBuildRepository`.
Its standalone browser consumer uses the engine ZIP and Kotlin artifacts solely from Maven:

```sh
./gradlew -p consumer-tests/wasm-compose-fixture wasmJsBrowserDistribution
python native/wasm/consumer-browser-test.py
```

The committed 63-case synthetic control can be regenerated with
`:byteink-compose:exportWasmRenderingFixtures` (desktop native required only for regeneration).
The exporter writes `byteink-compose/build/fixtures/wasm-rendering.json`; compare that file with
`native/wasm/fixtures/rendering.json`. Browser CI consumes the committed fixture and needs no
desktop native. `?verify=1` enables software raster/ownership checks; `?gpu=1` renders the 63-case
actual Compose/WebGL canvas matrix; `?gpu=1&reference=1` uses independently exported desktop-native
canonical geometry, checking all attributes and index order before substitution. PNG software
controls remain byte-identical. The software gate remains 2/255 per premultiplied channel. GPU
screenshots also use 2/255 against the same-backend desktop geometry control (locally max 0).
Software-vs-GPU variation is measured separately, not used to widen either parity gate. WebGL
screenshots preserve the drawing buffer through presentation in test contexts only. This is not
a physical-GPU test; Chromium used SwiftShader, and renderer strings can be privacy-masked.
Playwright Linux WebKit acceptance is not Safari/macOS/iOS acceptance.

After staging the preview, `consumer-tests/wasm-fixture` is a standalone Node application using
only Maven coordinates. Set `BYTEINK_WASM_ENGINE` to the absolute engine `.mjs` file URL and run
`./gradlew -p consumer-tests/wasm-fixture wasmJsNodeDevelopmentRun`. It checks row/operation
replay, unknown-data retention, independent ownership, native coverage and tombstone undo.

`--engine chromium|firefox|webkit` selects a browser; `--browser /absolute/path/to/executable`
reuses an installed executable for that engine. The local
HTTP server/browser are always closed and its reports/screenshots go to `build/wasm-browser/`.
The API guide is [docs/guides/wasm.md](../docs/guides/wasm.md).

`-PbyteinkPortable=true` includes the facade's JVM target without Node/Emscripten; JVM CI runs
its contract and published/composite consumer smoke checks with that flag. The Wasm flag enables
both targets and the browser sample. `byteink-model` is always included because the existing
JVM kit depends on it. Release publishing now includes model, portable, engine and Compose/Wasm.
The Wasm CI compilation/consumer steps need no desktop natives. Its subsequent file exchange
downloads only the Linux native artifact and uses `-PbyteinkBundledPlatforms=linux-x86_64` for JVM
tests. Run that exchange locally (with the Linux native available):

```sh
./gradlew -PbyteinkWasm=true -PbyteinkBundledPlatforms=linux-x86_64 \
  -PbyteinkRoundTripPhase=seed :byteink-portable:jvmTest --tests '*JvmRoundTripTest'
./gradlew -PbyteinkWasm=true -PbyteinkRoundTripPhase=wasm :byteink-portable:wasmJsNodeTest
./gradlew -PbyteinkWasm=true -PbyteinkBundledPlatforms=linux-x86_64 \
  -PbyteinkRoundTripPhase=return :byteink-portable:jvmTest --tests '*JvmRoundTripTest'
```

`build/wasm-roundtrip/` retains exchanged files, accepted counts and JVM/Node timing samples.
The test envelope is verification tooling, not a new public storage format. Round trips retain
all DTO fields, original blobs, unknown encodings/families/operations, tombstones and undo states,
including native fractional timestamps and Long values above JavaScript's exact-integer range.

## Geometry acceptance and remaining limits

Every one of the 840 Android matrix input blobs matches the committed Android re-encoding gzip
exactly. Native decoder/brush construction runs on all of them. Oriented closed boundaries,
vertex counts/positions and native coverage probes match within the existing oracle-style
float tolerance; largest observed float gap `0.000015185546885732037` stroke units.

All 840 strokes match triangle-index and outline-start ordering directly. No vertex bijection or
contour rotation normalization remains in the native gate. The previous 28 marker cap differences
came from Emscripten musl's `acosf(0)` (0x3fc90fda) versus Android Bionic (0x3fc90fdb), which changes
the cap midpoint's side test. Wasm alone links the pinned Bionic acosf through `float_math.cc`;
desktop/global libm, native engine/dependency pins and existing patches remain unchanged. ABI 4
requires the math profile and stamps its source hash. The unchanged Bionic reference and license
live under `native/wasm/testdata`; 250,026 float comparisons (finite results bit-exact, NaNs by class)
plus the original-SDK negative
control prevent regression. An SDK upgrade that changes that negative control needs review.

Software controls pass all three browsers at <=2/255. Actual same-backend desktop/Wasm WebGL
controls have max 0. Across software-vs-GPU, max 44/255 occurs at DISCARD path edges and Chromium
reaches 255/255 at a nearest-sampled rotated red/green texture boundary; these are disclosed raster
backend differences, not Android pixel equality. Physical-pen and pen-to-photon measurements are
not part of acceptance. Three-fork browser CPU/software/asset baselines and one-fork matched JVM/
Node public-API baselines are under build/wasm-parity (CI: build/wasm-benchmark and wasm-roundtrip).
The public guide describes the workload, measured values and limits.

The C++ bridge exports lightweight geometry plus full owned rendering snapshots and paint
preferences. The Wasm Compose renderer handles their textures, vertex effects and prediction fade.
Arbitrary serialized families retain the pinned upstream codec's limits: atlas layout/timing is
application texture metadata, and authoring an animation-offset behavior through protobuf is not
supported. Default family decoding rejects development versions; explicit opt-in accepts only
what the same pinned upstream decoder actually supports. Android
input blobs and complete note/operation rows are supported; databases and transaction/undo
integration remain application-owned. Reading never authorizes rewriting an original stored blob
or dropping unknown fields. `InkPage` retains original rows separately from derived geometry.

## References

- https://kotlinlang.org/docs/wasm-js-interop.html
- https://kotlinlang.org/docs/wasm-configuration.html
- https://kotlinlang.org/docs/js-project-setup.html
- https://emscripten.org/docs/porting/connecting_cpp_and_javascript/embind.html
- https://emscripten.org/docs/compiling/Modularized-Output.html
- https://www.w3.org/TR/pointerevents3/
- Pinned engine headers and implementation; existing native/ANGLE-MATH.md and eight patches.
