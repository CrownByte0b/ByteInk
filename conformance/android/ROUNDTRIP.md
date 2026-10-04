# Android notebook round trips

This harness verifies desktop-authored ink through ViveNotes' real
`NotebookTransferManager` importer, Room installation, `InkCodec`, operation replay,
`CanvasStrokeRenderer` and exporter. The Android application is pinned to
`af05908e57f69593e32107b97bcdf19250637fa3` with AndroidX Ink `1.1.0-alpha06`.
The runner extracts that commit into ignored `build/app`, verifies its source bytes,
and injects test sources and isolated application IDs. Production Android source and
the supplied application's working tree remain untouched.

## Coverage and acceptance

Preparation starts with the committed Android synthetic matrix: 840 stroke rows and
existing Normal/Object erases, moves and resizes. It adds an unknown-brush sentinel,
a tombstone and 55 controller-authored strokes covering every supported family and
applicable stabilization level, with mouse/stylus inputs and varied colours. Three
new Object-erase masks remove a marker, calligraphy stroke and highlighter. The
supported notebook contains 897 stroke rows; 95 affected/operation pages have
independent desktop geometry and raster expectations.

The Android test imports the supported `.vive`, decodes every stroke including
tombstones, and checks new inputs, brush protobuf, colour, size, epsilon, theme flag,
stabilization and stored bounds. Rebuilt bounds, outlines, triangles, coverage and
replay transforms are checked against desktop geometry. Stored bounds are recorded
before input quantization and are not used as an exact rebuilt-geometry oracle.

Every column in `ink_strokes`, `ink_erases`, `ink_erase_targets`, `ink_moves` and
`ink_move_targets` must survive import/export and a second import/export. Comparisons
include SQLite storage types, exact REAL bits, nulls and complete BLOB bytes.
The desktop verifier also checks the pre-authoring rows and attachment entry
inventories/SHA-256 values. This synthetic fixture has no attachments; archive-copy
unit tests exercise populated attachments and future archive entries.

Integer geometry and topology are exact. Coordinates use the unchanged native
oracle tolerance `1e-4 + 1e-5 * max(abs(a), abs(b))`. Every accepted software pixel
must differ by at most two RGB channel levels. The independent Android triangle-union
reference includes Normal-erase pieces lacking outlines, which the pinned Android
path renderer omits. Original hardware and software-path images are also retained.

### Exact encoding

Fresh encoding is compared from identical canonical inputs and noise seeds before
quantization. All 55 new strokes, three erase masks and four encoding-only probes
must match Android in both the uncompressed protobuf and complete gzip bytes:
**62/62 comparisons**, with no transport or private-field exceptions. The independent
probes cover empty input, one sample with a nonzero seed, 8,192 stylus samples with
all attributes, and 40,000 mouse samples. The large stylus payload crosses compressor
window/block boundaries. These probes generate no notebook rows or meshes.

ByteInk keeps the upstream native input serializer, restores alpha06's default
private animation-phase field 10 when absent, and compresses through the bundled
native library's pinned classic zlib helper. The helper uses Android's level-6 gzip
parameters and header. JVM zlib/zlib-ng output is host-dependent; changing only the
JVM compression level did not match long Android batches. The owned extension is
isolated in [patch 0005](../../native/patches/0005-encode-with-android-gzip.patch).
Use native artifacts containing this extension when running these checks.

Android input decoding is lossy. On the long probes, Android's own decode/re-encode
changes the quantized protobuf and gzip bytes relative to the original encoding.
The harness records that separate result; it still requires exact fresh encoding
and preserves original stored BLOBs. Permanent targets in
[`byteink-kit` encoding fixtures](../../byteink-kit/src/test/resources/ink/androidx-ink-1.1.0-alpha06/encoding/)
capture both original Android encodings and Android's decoded re-encodings. The
codec tests compare against the corresponding target, including all 840 existing
matrix decoded-input re-encodings.

### Unknown encodings

The pinned Android importer rejects `enc != ink/androidx1`, including tombstones.
The test proves rejection changes no database rows on both an empty and occupied
isolated database. Unknown brush IDs remain accepted and retain the app's
pressure-pen fallback.

The opposite transfer direction is also exercised: the test inserts an opaque
`ink/future-v17` row through the app's DAO and exports it with the real manager.
Desktop opens that Android export, skips its unsupported decoding, makes a notebook
copy and verifies every stored ink value/BLOB and attachment inventory survives.
The importer remains unchanged; unsupported encoding acceptance is not claimed.

## Run on Linux

Requirements are Python 3.12+, `android` CLI, `adb`, cached Gradle dependencies,
the pinned Android commit, matching production native artifacts, and a booted
API 36 x86_64 emulator using SwiftShader (`-gpu swangle`). The runner verifies the
device API, ABI and SurfaceFlinger renderer. See [ORACLE.md](ORACLE.md) for emulator
and isolated-app setup.

```sh
python3 conformance/android/run.py --roundtrip \
  --android-project /path/to/viveNotes \
  --device emulator-5554 \
  --results /absolute/path/to/fresh-linux-results
```

This prepares the fixture when `expectations.json` is absent, captures Android,
then runs desktop verification. Preparation refuses an existing prepared fixture;
use a fresh result directory after changing authoring or codec code. For a capture
retry, an existing prepared directory can be passed to `--results`.

## Prepare and verify on Windows

Run preparation using the intended Windows JVM/native build:

```powershell
.\gradlew.bat :byteink-testing:prepareAndroidRoundTrip --offline `
  -PbyteinkRoundTripDirectory=C:/byteink-results/windows-zulu27
```

Copy the entire prepared directory to the Linux Android host, preserving relative
paths. Capture those Windows-generated expectations without preparing or verifying
on Linux:

```sh
python3 conformance/android/run.py --roundtrip --capture-only \
  --android-project /path/to/viveNotes --device emulator-5554 \
  --results /absolute/path/to/copied-windows-results
```

Copy the complete directory, including `android/`, back to Windows and verify it
there using the same checkout/native build:

```powershell
.\gradlew.bat :byteink-testing:verifyAndroidRoundTrip --offline `
  -PbyteinkRoundTripDirectory=C:/byteink-results/windows-zulu27
```

The `prepareAndroidRoundTrip` and `verifyAndroidRoundTrip` tasks also work separately
on Linux with `./gradlew` and the same absolute-directory property. The prepared
`desktop-runtime.json` records OS, Java version and native hashes; the capture
metadata records the pinned app, device/renderer, APK/source hashes and input hashes.

## Artifacts and verified results

Results stay under ignored build directories. `roundtrip-report.json` reports
preservation, exact encoding, geometry and per-page pixels. Android produces
`reexport.vive`, `reimport-reexport.vive`, `unknown-android.vive`, `observed.json`,
page geometry and three PNG modes under `android/`. For each authored item/probe,
`android/encoding/` contains `.desktop`, `.original` and `.reencoded` protobuf/gzip
pairs. The runner clears prior Android output, checks instrumentation's actual test
result, retrieves failure diagnostics and hashes the retrieved artifact inventory.
Standalone desktop verification also requires successful instrumentation, the pinned app/Ink
versions, matching prepared-input hashes and an intact captured-artifact manifest before
checking the notebook. Generated desktop copies stay outside the captured Android inventory.

The Linux Zulu 27 capture `build/stage8-linux-native-zulu27` passes: 897 rows
preserved, 62/62 exact original protobuf/gzip encodings, 95 pages, 716,452 integer
and 1,466,420 floating-point geometry comparisons with maximum gap zero, and
maximum accepted software RGB delta zero. Instrumentation took 12.053 seconds.
The long decoded re-encoding differences described above are observed on Android
itself and retained as permanent targets.

The actual Windows 11 Zulu 27 prepare/capture/Windows-verify run
`build/stage8-windows-zulu27` also passes: 897 rows preserved, 62/62 exact original
protobuf/gzip encodings, the same 95 pages and geometry comparison counts, maximum
geometry gap zero, and zero differing accepted software pixels. Instrumentation
took 13.011 seconds. Its downloaded `roundtrip-report.json` records the Windows JVM
and native hashes independently of the Linux result.

The independent cross-OS audit finds identical typed ink values across six compared
archives, identical expectations, all 372 encoding artifacts byte-identical, and all
190 geometry files and 190 PNGs byte-identical (95 desktop and 95 Android pages).
Final Linux full builds pass on Zulu 27 and pinned JBR 25. Actual Windows full builds
pass on Zulu 27, Zulu 25 and pinned JBR 25, with 1,246 cases per build and only
platform/optional-fixture skips. These runs include 20,000 fuzz iterations with seed 1,
native export checks, the consumer/loader suites and performance budgets.

After the final evidence guard was added, both Linux and actual Windows JBR 25
passed the updated testing-module suite (35 cases, three optional-fixture skips) and
reran the guarded verifier successfully. Reports distinguish the original authoring
`desktopRuntime` from the actual `verificationRuntime`.
