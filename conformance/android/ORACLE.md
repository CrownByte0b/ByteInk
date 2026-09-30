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
ten generated brush-family pages, each with opaque and translucent pressure loops. The public
synthetic matrix below provides the stabilization/tool/size/colour and operation coverage.
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

## Native magnitude correction

The public matrix found 115 geometry failures from a one-ulp Bionic `hypotf` rounding
difference. Scoped production patch 0004 preserves Android magnitude arithmetic at Ink
call sites, without a global math override. All 280 full geometry JSONs and accepted
software images then match exactly. The previous 51-page notebook capture remains a
passing regression check: all 512,800 geometry values are exact and software channel
differences remain at most two levels.

Google's Linux binary uses platform math and consequently differs in 209 geometry/topology
values after this correction. The independent pin/engine oracle uses a separate unshipped
platform-math validation build, retaining strict comparison rules. Production correctness
uses Android goldens and strict cross-OS comparisons. See
[native/ANGLE-MATH.md](../../native/ANGLE-MATH.md) for the source and update procedure.

## Reference environment

API 35 x86_64 Pixel_Tablet is the current local reference. The installed emulator 37.1.11 and
isolated 36.6.11 both crashed in SwiftShader's generated shader code: strace recorded EACCES
for executable heap mappings. Camera/Vulkan disable and software CPU retries did not fix it.
Using `-gpu host` boots successfully without changing system permissions, the SDK installation,
or the user's AVD settings. The captured host GPU is NVIDIA RTX 4080 SUPER, driver 615.71.09.
A fixed API 36/SwiftShader reference is used for the public synthetic matrix below. The
earlier notebook captures retain their original API 35/host-GPU provenance.

## Public synthetic matrix

The reference generator runs the pinned app's brush catalog, codec, operation replay and
`NotebookTransferManager` export inside the isolated oracle package. It uses deterministic
generated coordinates and UUIDs; it reads no user notebooks. The resulting `synthetic.vive`
and Android geometry/encodings/PNGs are stored in `fixtures/matrix/` with a complete SHA-256
inventory. All desktop builds verify these public references without requiring an emulator.

The three original `CanvasStrokeRenderer` modes are preserved unchanged. Android's pinned
path renderer omits split pieces when their mesh has no outlines, while desktop fills their
real engine triangles. `images/softwareTriangles/` supplies an independent Android software
Canvas reference that fills all triangles together with consistent winding. The two-level
software pixel gate uses this reference only for detected zero-outline groups containing
triangles; original software/forced-path omission metrics remain in the report. Default
hardware comparison still covers every visible piece. The desktop renderer keeps erased
pieces visible rather than copying the Android path omission.

The matrix has 240 brush cases (ten families × six stabilization levels × four tools) and
40 operation cases (ten families × four tools at stabilization level three). Each page
contains sizes 2, 8 and 20 dp, an opaque colour, a translucent colour and automatic theme
colour. Operation pages replay Normal and Object erases, a move and a nonuniform resize.
The fixed viewport is 480×640 pixels at two pixels per page unit.

Generate fresh references using an API 36 x86_64 AVD with SwiftShader and enough free
data space to install both APKs. The recorded local capture used an isolated 8 GiB AVD under
ignored `build/avd/`; the original AVD configuration stayed unchanged. The example uses an
AVD named `Api36`:

```sh
# Use the selected API 36 x86_64 AVD read-only.
"$ANDROID_HOME/emulator/emulator" -avd Api36 -read-only -no-window -no-snapshot \
  -no-audio -gpu swangle
python3 conformance/android/run.py --matrix --capture-only \
  --android-project /path/to/viveNotes --device emulator-5554 \
  --results /absolute/path/to/fresh-results
```

[Android documents `swangle`](https://developer.android.com/studio/run/emulator-acceleration#accel-graphics)
as SwiftShader with an ANGLE backend. The installed emulator 37.1.11 boots this
backend successfully; its legacy direct GLES `-gpu swiftshader` backend exits 139 on this
host. The runner checks API 36, x86_64 and the SurfaceFlinger SwiftShader identity before
installing. Capture metadata records the device build, renderer, emulator and source hashes.
`--matrix` cannot be combined with private notebook capture or stroke diagnostics. Omit
`--capture-only` to compare the new references immediately after retrieval.

Run the committed references on either desktop OS:

```sh
./gradlew :byteink-testing:androidFidelityMatrix --offline
# Select an installed JVM explicitly when verifying multiple runtimes:
./gradlew build :conformance:oracle:oracleProduction --offline \
  -PbyteinkTestJavaHome=/path/to/jdk
# Linux-only independent engine/pin proof:
native/build-oracle-baseline.sh
./gradlew :conformance:oracle:oracleCompare --offline
```

The matrix report is under `byteink-testing/build/reports/android-matrix/`. Optional
`-PbyteinkMatrixDirectory` and `-PbyteinkMatrixOutput` select reference and report directories.
Use `gradlew.bat` on Windows. Counts, topology, identities and brush protobuf encodings are exact;
coordinates and coverage use the unchanged native geometry tolerance. Every software path
pixel remains subject to the two-channel-level gate. The hardware comparison separately
constrains the accepted AA and translucent ANY differences and records per-case metrics and
failure diagnostics. A missing/corrupt reference or geometry mismatch fails the check.

Gzip transport bytes depend on Android/JVM zlib and are recorded separately from the
byte-identical brush protobuf payload. Re-encoding decoded inputs also drops Google's
private animation-phase field 10, which the public native source reserves. Every remaining
protobuf byte must match, and every original stored input blob is preserved byte for byte.
These measured encoding differences do not permit changes to input values or brush meaning.

Measured per-family results and final verification evidence are summarized in
[FIDELITY.md](FIDELITY.md).
