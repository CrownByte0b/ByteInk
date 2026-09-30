# Stage 7 Android fidelity results

The committed Android reference has 280 synthetic pages and 840 original stroke rows:
ten families × six stabilization levels × four tools, plus 40 operation pages. Each page
contains 2/8/20 dp strokes with opaque, translucent and theme-resolved colours. The operation
pages verify effective Normal and Object erases, moves and nonuniform resizes. The real
Android app creates the database, encodes rows, replays operations and exports `synthetic.vive`.
No personal notebook data is included.

Reference: AndroidX Ink 1.1.0-alpha06, ViveNotes commit
`af05908e57f69593e32107b97bcdf19250637fa3`, API 36 x86_64, emulator 37.1.11,
ANGLE over SwiftShader Vulkan (`-gpu swangle`). The 480×640 viewport uses two pixels per dp.
The [complete artifact manifest](fixtures/matrix/manifest.sha256) has SHA-256
`91fd1f7a29b71d4f89c47f977ac59cab9db12774fdfdc82806b48a8438097a5c`.
Two final Android captures passed in 21.539/21.674 seconds; all 3,642 engine, encoding,
image and environment payloads were byte-identical. Archive metadata and capture timestamps
are recorded separately.

## Geometry and encoding

Linux and Windows production match all 859,366 integer values and 1,740,077 float
geometry/coverage values exactly. Counts, identities, outlines, mesh positions and indices,
transforms and coverage booleans are checked; the coordinate gate remains
`1e-4 + 1e-5 * max(abs(a), abs(b))`. All 280 accepted software references are pixel-identical.

All 280 brush-family protobufs are byte-identical. Android/JVM gzip streams differ, so transport
bytes are measured separately. All 840 original stored input blobs are preserved exactly.
Re-encoding drops only Google's private animation-phase field 10, reserved by the public
native source; every remaining protobuf byte must match. All 2,240 encoding checks pass.

The matrix exposed Bionic `hypotf` rounding that changed topology in 115 cases. Scoped native
patch 0004 fixes magnitude arithmetic, joining the existing angle correction. Both production
natives retain the pinned Ink engine. The independent Google engine/pin proof uses a separate
unshipped platform-math validation binary with patches 0003/0004 omitted. Production geometry
uses Android goldens and strict cross-OS comparisons. See
[the math source and update procedure](../../native/ANGLE-MATH.md).

## Raster measurements

| Family | Cases | Minimum hardware SSIM | Maximum hardware RGB MAE | Maximum hardware channel delta | Software channel delta |
|---|---:|---:|---:|---:|---:|
| calligraphy-v1-p0 | 28 | 0.992408 | 0.991768 | 255 | 0 |
| calligraphy-v1-p1 | 28 | 0.991928 | 0.939505 | 255 | 0 |
| calligraphy-v1-p2 | 28 | 0.992088 | 0.914788 | 255 | 0 |
| calligraphy-v1-p3 | 28 | 0.992238 | 0.895154 | 255 | 0 |
| calligraphy-v1-p4 | 28 | 0.991213 | 0.869786 | 255 | 0 |
| calligraphy-v1-p5 | 28 | 0.990735 | 0.881086 | 255 | 0 |
| dashed-line | 28 | 0.987057 | 0.976062 | 248 | 0 |
| highlighter | 28 | 0.998655 | 0.206793 | 94 | 0 |
| marker | 28 | 0.992203 | 1.182037 | 255 | 0 |
| pressure-pen | 28 | 0.992478 | 1.154840 | 255 | 0 |

These measurements use the Linux Zulu 27 production run; all Windows runtime images are
RGB-pixel identical, so the same family measurements apply. Hardware acceptance requires
SSIM ≥0.95, RGB MAE ≤5, ink-area ratio 0.85–1.15 and zero unexplained interior pixels above
a 16-level channel delta. Software acceptance requires every RGB channel difference ≤2.
The minimum default hardware SSIM is 0.987057; maximum MAE is 1.182037. All cases pass.

Large maximum hardware deltas occur at antialiased edges and translucent ANY self-overlaps.
The gate permits a two-pixel edge band and constrained same-hue alpha accumulation only
at the known translucent stroke colour. Opaque darkening, wrong hue, missing ink and geometry
changes still fail. Highlighter uses DISCARD overlap and gets no ANY exception: its software
rendering is exact, with hardware edge differences measured above.

Android's pinned software/forced-path renderer omits split pieces with real triangles but
no outlines. This occurs in 36 operation cases, comprising 127 render groups. An independent
Android software Canvas triangle-union reference certifies those pieces; original renderer
images and omission metrics remain intact. Every piece is still checked against default
hardware rendering. The 244 supported forced-hardware-path cases have minimum SSIM 0.995646
and maximum MAE 0.238112. No mesh renderer is required by these thresholds. This acceptance
covers ViveNotes' uniform texture-free path rendering; per-vertex paint/prediction fade and
other mesh-shader features remain outside that renderer's existing supported scope.

## Verification

Full builds, native contracts, mandatory matrix acceptance, 20,000-case deterministic fuzzing,
consumer tests, loader tests and performance regressions pass on the following actual runtimes.
Every selected Test task executed freshly; logs and XML timestamps were audited before switching.

| Platform / runtime | Full build time | JUnit cases | Skipped | Matrix cases passed | Software max delta |
|---|---:|---:|---:|---:|---:|
| Linux / Zulu 27 | 1m25s | 2,217 | 2 | 280 | 0 |
| Linux / JBR 25 | 1m16s | 2,217 | 2 | 280 | 0 |
| Windows 11 / Zulu 27 | 1m33s | 1,236 | 6 | 280 | 0 |
| Windows 11 / Zulu 25 | 1m27s | 1,236 | 6 | 280 | 0 |
| Windows 11 / JBR 25 | 1m28s | 1,236 | 5 | 280 | 0 |

Every run has zero failures/errors. Linux additionally executes 981 AndroidX cases against
Google's binary. Skips cover read-only-cache/OS/runtime branches and the three optional private
notebook tests on Windows; private inputs are exercised locally on Linux. The Windows JBR
Unicode native-loading test executes and passes. The public matrix never skips.

All 2,800 desktop engine/encoding/image artifacts are byte-identical across the two Linux JVMs.
An independent audit of downloaded Windows output confirms all 280 PNGs are RGB-pixel identical
and all 280 geometry JSONs byte-identical to Linux, with zero float gap. All 2,240 desktop encoding
protobufs match exactly after decompression on every Windows runtime; 1,120 transport files differ
only in gzip, while the other 1,120 files are byte-identical.

Strict Google baseline proof passes over 16,278 values, with zero mismatches and unchanged
geometry/topology rules. Strict Windows/Linux production comparison also passes on all three
Windows runtimes: 15,771 equal values, 257 within the existing tolerance and 250 existing
informational AA/buffer/encoding values; zero mismatches.

The corrected native passes all 115 upstream Linux C++ targets and 250,000 independent NDK
Bionic magnitude pairs. All five actual Windows C++ executables pass: 105 tests passed, with
three upstream optimized-build death-test skips. Two fresh independent Windows builds with
action caching disabled are byte-identical and match the shipped DLL SHA-256
`bb125abff55514729aabee77d0041f67ca43d95bb4d14d15671a18b21485908e`.
All five runtime performance checks pass: 6,608 native allocations equal cleanups, no held/live
background path or raster rebuilds, and cached pixels stay bounded at 1 MiB and return to zero.
These are regression/fidelity checks; timings are subject to JVM/host/guest load.

The earlier 51-page Android notebook capture also passes regression: all 512,800 geometry
values remain exact, with every software channel difference ≤2. Personal inputs/results
remain local ignored artifacts.

Run the public acceptance without Android tooling or private data:

```sh
./gradlew :byteink-testing:androidFidelityMatrix --offline
# The complete build also runs the matrix as a mandatory test.
./gradlew build --offline
# Linux-only independent engine/pin proof:
native/build-oracle-baseline.sh
./gradlew :conformance:oracle:oracleCompare :conformance:oracle:oracleProduction --offline
```

Use `gradlew.bat` on Windows. Full per-case metrics, provenance, original Android renderer
omission metrics, and failure images are generated under
`byteink-testing/build/reports/android-matrix/` (`comparison.md` and `comparison.json`).
The always-on test writes `android-matrix-tests/`. CI retains both report directories.
[Capture instructions](ORACLE.md) explain how to regenerate the Android reference.
