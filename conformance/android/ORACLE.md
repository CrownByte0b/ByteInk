# Android notebook oracle

This optional local check uses ViveNotes at
`af05908e57f69593e32107b97bcdf19250637fa3`, with AndroidX Ink `1.1.0-alpha06`.
It extracts the committed app into `build/app`; the original checkout's dirty work is excluded.
Only the oracle's test sources and application ID are injected. The installed packages are
`com.vivenotes.byteinkoracle` and `.test`.

The test calls the app's actual codec, brush definitions, projection operations and renderer.
Its ordered replay fold follows `InkPageLoader`, without loading through the repository or
changing erased rows. HardwareRenderer/RenderNode/ImageReader capture the default hardware
CanvasStrokeRenderer, including Canvas.drawMesh, and a forced hardware path reference.
A software Bitmap Canvas captures the same path renderer independently of GPU antialiasing.
The capture follows [Android's HardwareRenderer example](https://developer.android.com/guide/topics/renderscript/migrate).
Images contain ink on white paper, with automatic ink resolved to black; page backgrounds,
paper templates and the editor UI are excluded.

## Run locally

Requirements: Python 3.12+, `android` CLI, `adb`, a booted API 35+ emulator, the pinned app
commit in the supplied Git checkout, Android SDK and cached app/byteink Gradle dependencies.
The reference build uses that commit's Gradle/JDK requirements.

```sh
python3 conformance/android/run.py \
  --android-project /path/to/viveNotes \
  --notebooks /path/to/local-vive-fixtures \
  --device emulator-5554
```

Each run defaults to a fresh directory under `conformance/android/build/`. `--results` can name
an existing prepared directory to repeat the Android capture/comparison. Preparation also adds
ten generated brush-family pages, each with opaque and translucent pressure loops. The full
stabilization/tool/size/colour matrix and Windows raster comparisons are separate work.
Fixtures, database copies, images and reports are ignored by Git. Private app storage is
transferred with run-as. Application IDs are verified before installing; instrumentation
output is checked because am instrument can exit zero after a failed test.

The pieces can also run separately. Use absolute result paths:

```sh
./gradlew :byteink-testing:prepareAndroidOracle --offline \
  -PbyteinkNotebooks=/path/to/fixtures -PbyteinkFidelityDirectory=/path/to/results
# Build/install the injected test, transfer its inputs and retrieve its android/ output.
./gradlew :byteink-testing:compareAndroidOracle --offline \
  -PbyteinkFidelityDirectory=/path/to/results
```

`-PbyteinkFidelityNative=/absolute/path/to/libink.so` overrides the library during preparation
or diagnostics, useful for comparing Google's Linux binary. Preparation refuses an existing
pages.tsv to prevent stale desktop references. To diagnose individual stored rows, create a
local diagnostics.tsv (`database filename<TAB>row id`), run diagnoseAndroidOracle with the
same result property, then run run.py with --diagnose. Both platforms use the same test-only
writer for decoded inputs, brush protobufs, live vertices, packed meshes and input prefixes.
These dumps can contain private notebook coordinates and remain under ignored build/.

## Acceptance and reports

Counts and identities must match. Geometry uses the native oracle's unchanged
`1e-4 + 1e-5 · max(|a|, |b|)` coordinate tolerance. Every software path pixel must differ by
at most two 8-bit RGB channel levels; this admits rasterizer rounding while rejecting missing
ink, changed colour and changed overlap coverage. Both checks fail the comparison command.

Default hardware mesh and forced hardware path outputs are measured separately with RGB MAE,
maximum delta, differing-ink percentage and 8×8 luminance SSIM. GPU edge antialiasing need not
match software path pixels. ANY brushes can also use different overlap behavior between the
mesh and path renderers; DISCARD highlighters use the path behavior. Uniform paths omit
per-vertex colour/opacity and prediction fade, and refuse textures/ACCUMULATE paints.
Full mesh-renderer equivalence is not claimed by this path-port acceptance check.

The report and pairs/amplified differences are local; desktop is left in the pairs. Source
notebooks are read only. The sample renderer's separate copy tests append and reread strokes
while preserving original rows, operations, attachments and checksums.

## Native angle correction

The initial run found one marker U-turn whose minimum x differed by 0.04084 dp. Input attributes
and brush protobufs were identical; byteink also matched Google's Linux library exactly.
Replacing only float atan2 and its float atan helper reproduced Android's live and packed
geometry. Production patch 0003 preserves that Android arithmetic within Ink, without a global
libm override. Source pins, attribution and updating instructions are in
[native/ANGLE-MATH.md](../../native/ANGLE-MATH.md).

All 512,560 real-notebook bounds/projection values match Android exactly after this correction.
All 41 real pages match Android software paths within two RGB levels, MAE at most 0.000515079.
The final 51-page run has 42,760 decoded rows and 42,720 projections: all 512,800
bounds/transform values match exactly. All ten generated brush pages are pixel identical to
Android software paths. Default hardware SSIM spans 0.965165–0.998544; forced hardware path
SSIM spans 0.992045–0.999411. The largest synthetic differences are translucent ANY self-overlaps,
which the mesh renderer accumulates and the path renderer discards. This is an accepted path
renderer deviation, not a claim of mesh-shader parity. The report is under ignored
`build/results/comparison.md`, with paired/difference PNGs. Instrumentation passed in 35.106 s;
the complete Gradle build/native oracle also passed.

## Reference environment

API 35 x86_64 Pixel_Tablet is the current local reference. The installed emulator 37.1.11 and
isolated 36.6.11 both crashed in SwiftShader's generated shader code: strace recorded EACCES
for executable heap mappings. Camera/Vulkan disable and software CPU retries did not fix it.
Using `-gpu host` boots successfully without changing system permissions, the SDK installation,
or the user's AVD settings. The captured host GPU is NVIDIA RTX 4080 SUPER, driver 615.71.09.
A fixed API 36/SwiftShader reference is still needed for the complete cross-platform matrix.
