# Upstream pins

byteink runs Google's AndroidX Ink stroke engine unchanged. This directory records exactly which
upstream code that is, and holds the tooling that proves it.

## What is pinned, and where

| Pin | Value | Where |
|---|---|---|
| AndroidX Ink release on Google Maven | 1.1.0-alpha06, the Android app's version | `gradle/libs.versions.toml` (`androidx-ink`) |
| Its artifacts | sha256 of every `androidx.ink` file Gradle downloads | `gradle/verification-metadata.xml` |
| AndroidX source of that release | `frameworks/support` `61ee8cd421d0` | `pins.properties` |
| `google/ink` source for the natives | `96e50239e1c8`, one of five candidates | `pins.properties` |
| Native toolchain at that commit | Bazel 8.7.0, LLVM 19.1.0 | `pins.properties` |
| What byteink adds to that build | a Linux sysroot, a Windows cross-toolchain | `native/patches/` |
| The native loader byteink forks | AndroidX's `ink-nativeloader` at the support commit, with one patch | `ink-nativeloader/` (`PATCHES.md`) |
| JNI surface of the release | natives the jars declare; functions Google's binary exports | `jni/` |

Dependency verification covers only the `androidx.ink` group; every other dependency is trusted by
the `<trusted-artifacts>` rule in the verification file. The Ink artifacts are singled out because
they carry Google's native binary and define the JNI surface byteink's own binaries must match.

The AndroidX Ink version must match the Android app's. Stored ink is brush + inputs, and the mesh
is rebuilt by the engine, so both apps must run the same engine for a stroke to mean the same on
both.

## How the `google/ink` commit was found

Google does not publish which `google/ink` commit a release's `libink.so` was built from; the
prebuilts repository that might, on android.googlesource.com, was unreachable when this was
pinned. The binary's JNI surface narrows it down: the binary exports one `Java_*` function per
`JNI_METHOD` definition in `google/ink`'s JNI glue, and those definitions change often. `scan`
compares every commit on `google/ink`'s first-parent history with the exports of Google's binary.

| AndroidX Ink | `google/ink` commits with exactly that surface |
|---|---|
| 1.1.0-alpha06 | `d38cbb3d5e8a` (2026-07-21) … `66b1db31d937` (2026-08-10), 21 commits |
| 1.1.0-alpha09 | `9dba346a4785` (2026-09-05) … `a01f9d956833` (2026-09-09), 6 commits |

The alpha06 AndroidX commit (2026-07-23) and release (2026-07-29) narrow that to the five
candidates in `pins.properties`. Those have three distinct behaviours of `Stroke.subtract`:

| Commit | What it changes |
|---|---|
| `d38cbb3d5e8a` | baseline |
| `480b45ff2390` | fixes colour attributes after subtraction |
| `96e50239e1c8` | computes outlines for subtracted meshes |

`ee5d4005f036` and `f6e53da6f452` match `96e50239e1c8`: one adds only `testonly` code, the other
removes an `#include`.

The differential oracle (`:conformance:oracle`, see its build file) chose among the three. It runs
about 13,000 engine operations through a build of each candidate and through Google's binary:

| Build | Values differing from Google's binary | Of which erase outlines |
|---|---:|---:|
| `d38cbb3d5e8a` | 935 | 258 |
| `480b45ff2390` | 935 (same as `d38cbb3d5e8a`) | 258 |
| `96e50239e1c8` | 677 | 0 |

Google's binary computes outlines for subtracted meshes, so the pin is `96e50239e1c8`. The Android
app's erase replay relies on that behaviour too.

The 677 values that no candidate reproduces are not about the commit.

- **Google does not build its binary from this source.** Its `.comment` section reads
  `google3 clang version 9999.0.0` and `LLD google3-trunk`: Google's internal build and compiler,
  with AVX instructions. Builds of the public source cannot be bit-identical to it.
- **462 of the values are input encodings.** Google's internal source writes a field the public
  source reserves: field 10 of `CodedStrokeInputBatch`, the stroke's base animation phase. It is
  always 0.0 for ViveNotes, and every other byte is identical. Each side decodes the other's blobs
  to the same inputs; the public proto says `reserved 10;` since `a7f3d71` (2026-06-03).
- **The rest are rounding differences.** In finished strokes, 10 quantization parameters differ by
  1 ULP, and the positions they decode to match exactly. Live, in-progress buffers differ in 0.44%
  of their floats, by at most 5.4e-5 dp. A build of the same commit with `-mavx` (Google's
  instruction set) gives results bit-identical to the plain x86-64 build. So the rounding comes
  from Google's internal compiler and libraries, not from the instruction set, and a public build
  cannot remove it.

`./gradlew :conformance:oracle:oracleCompare` enforces this:

- Floats pass within 1e-4 + 1e-5 · max(|a|, |b|).
- Input encodings must be identical once field 10 is removed. Brush families must be identical
  protos; their gzip bytes depend on the JVM's zlib and are only informational.
- Packed buffers are compared through what they decode to.
- An outline that starts at another vertex of the same loop is equivalent: same vertices, same
  order.
- Each dump records its platform. Across platforms, antialiasing derivatives beyond tolerance are
  informational (see [Windows](#windows)).
- Everything else must be identical.

For the pin this passes with 0 mismatches, both on the synthetic cases (16,278 values) and on real
notebooks (`-PoracleFixtures=<directory of .vive files>`, 59,653 values including their stored
partial erases). It fails for `d38cbb3d5e8a`, with 258 mismatches.

## Building the natives

`native/` builds the JNI library at `google.ink.commit` with upstream's own Bazel build and
toolchain, plus a patch series in `native/patches/`. The patches are applied in order and kept
small enough to offer upstream.

| Patch | What it does |
|---|---|
| `0001-link-linux-against-bullseye-sysroot.patch` | Links against Chromium's Debian bullseye sysroot instead of the host's glibc |
| `0002-cross-compile-windows-with-zig.patch` | Adds `hermetic_cc_toolchain` 4.2.0 (zig 0.14.0) for Windows, a Windows branch in `ink/jni`'s link options, and zlib 1.3.1.bcr.8, whose build stops passing MSVC flags to other compilers |

The scripts:

- `native/build-linux.sh` writes `native/build/out/<commit>/linux-x86_64/`: `libink.so`
  (stripped), `libink.so.debug`, and `build.properties` with the commit, Bazel version, patch
  hashes and library hash.
- `native/build-windows.sh` cross-compiles on Linux and writes `ink.dll` and `build.properties` to
  `native/build/out/<commit>/windows-x86_64/`. Patch 0002 leaves the Linux library bit-identical.
- `native/test-linux.sh` runs `google/ink`'s C++ tests (`//ink/...` except the Skia and Dawn
  renderer, which byteink does not build) at the pin: 114 of 114 pass. The tests use a checkout of
  their own without patch 0001, since fuzztest's riegeli needs a newer glibc than the sysroot has.

Bazel comes from bazelisk, pinned by version and sha256 in `pins.properties`. The Bazel repository
and disk caches live in `~/.cache/byteink/bazel` (or under `$BYTEINK_CACHE`). `BYTEINK_BAZEL_FLAGS`
and `BYTEINK_OUT` are for experiments, such as the `-mavx` build above, and `build.properties`
records any experiment flags.

Two Gradle tasks check what the scripts built, or the files named by `-PbyteinkLinuxLibrary` and
`-PbyteinkWindowsLibrary`:

- `./gradlew :upstream:checkLinuxLibrary`
  - It must export exactly the pinned `Java_*` surface.
  - It may need only glibc's own libraries, with no libstdc++.
  - No symbol may be newer than `GLIBC_2.28` (manylinux_2_28). The pin's build needs
    `GLIBC_2.18`; Google's needs `GLIBC_2.26`.
- `./gradlew :upstream:checkWindowsLibrary`
  - It must be an x86-64 DLL exporting exactly the pinned surface, plus LLVM's unwinder API.
  - It may import only libraries Windows 10 and later provide.

## Windows

Google publishes no Windows binary, so byteink builds `ink.dll` itself. It cross-compiles on Linux
with zig's clang, which is LLVM 19 like the Linux build. The target is MinGW-w64 on the Universal
C Runtime (UCRT), with libc++ and libunwind linked in statically.

This toolchain was chosen over clang-cl with Microsoft's SDK for three reasons:

- **Same C++ library on both OSes.** Both builds use libc++, so containers, sorting and hashing
  behave the same. clang-cl would use Microsoft's STL.
- **Built on Linux.** The build is hermetic and needs no Windows machine or Visual Studio, and no
  Microsoft SDK licence to accept.
- **No shared C++ ABI.** The JVM calls only `extern "C"` JNI functions, so the DLL's internal C++
  ABI concerns nobody else.

What the DLL needs and offers:

- **Imports:** only libraries Windows provides: `KERNEL32`, `ADVAPI32`, `dbghelp` (abseil's
  symbolizer), and the UCRT's `api-ms-win-crt-*`.
- **Exports:** the 341 JNI functions plus libunwind's API (`_Unwind_*`, `unw_*`). libunwind marks
  its API `dllexport`, and zig rejects the linker flag that would drop it. Windows resolves imports
  per DLL, so these exports clash with nothing.

### How Windows results differ

Both builds use the same compiler with the same baseline x86-64 instructions (no AVX, no FMA), so
their arithmetic is the same. Their C math libraries are not:

- **Linux** calls glibc's `libm`.
- **The DLL** calls the UCRT's `atan2f`, `acosf`, `atanf`, `pow` and `hypot`, and zig's
  compiler-rt (ported from musl) for `sinf`, `cosf`, `tanf`, `expf` and similar functions.

These disagree in last bits. The oracle measured the effect on a Windows JVM, against Google's
binary on Linux: 59,653 values, 0 mismatches.

| What | How Windows compares with Google's binary |
|---|---|
| Geometry of finished strokes, erases and lassos (bounds, mesh positions, triangles, coverage) | identical |
| Live-stroke positions and outlines | within 1.1e-4 dp |
| Brush families | identical protos, different gzip bytes |
| Outlines | the same loop, but in 2 of 2,530 notebook strokes an end vertex moves to the other side, so the outline starts one vertex later |
| Antialiasing derivatives | 3 values beyond tolerance (see below) |

The antialiasing derivatives are per-vertex attributes that only a mesh renderer reads; byteink
draws outlines. Ink averages them through atan2, sin and cos. Where a vertex's triangles point in
nearly opposite directions, last-bit differences swing that average. In the same 2 strokes this
moves the derivatives' ranges by up to 0.785, and in one live step a derivative differs by
1.4e-4.

## Tasks

- `./gradlew :upstream:verifyJniSurface` runs as part of `check`. It recomputes `jni/` from the
  verified jars and fails if the lists changed, or if Google's binary does not export a native the
  jars declare.
- `./gradlew :upstream:jniSurface` rewrites `jni/` after a version change.
- `./gradlew :upstream:scanGoogleInk` clones `google/ink` into `upstream/build/google-ink` (or uses
  `-PgoogleInkDir=<clone>`) and writes `upstream/build/reports/google-ink-jni-scan.md`. It fails
  unless `google.ink.commit` and every candidate match the pinned release exactly.
  `-PscanReferences=<label>=<file>,…` adds reference lists, such as another release's exports.
- `./gradlew :upstream:checkLinuxLibrary` and `:upstream:checkWindowsLibrary` check byteink's
  native builds (see [Building the natives](#building-the-natives)); `check` does not run them.
- `./gradlew :upstream:installDist` builds the tool as
  `upstream/build/install/upstream/bin/upstream`. Its commands are:
  - `surface`
  - `exports`: prints a binary's `Java_*` exports.
  - `source-symbols`: prints a `google/ink` revision's JNI functions.
  - `scan`
  - `check-linux-library` and `check-windows-library`: what the two tasks run.
  - `compare-abi`: what the loader fork's `verifyUpstreamAbi` runs against Google's jar.

## Moving to another release

1. Choose the release together with the Android app, never ahead of it.
2. Change `androidx-ink` in the catalog, run `./gradlew --write-verification-metadata sha256 help`,
   check that only `androidx.ink` entries changed, and delete the old version's entries.
3. Run `./gradlew :upstream:jniSurface` and review the change to `jni/`.
4. Set `androidx.support.commit` from the end of the release's commit range in the Ink release
   notes, and move the reference checkout (see the root README) to it.
5. Run `./gradlew :upstream:scanGoogleInk`. From the commits that match exactly and predate the
   release, set `google.ink.candidates` and `google.ink.commit`, then confirm the choice against
   Google's binary.
6. Run `./gradlew :ink-nativeloader:syncForkSources` to take the forked loader's sources from the
   new AndroidX commit, with its patch re-applied (`ink-nativeloader/PATCHES.md` covers a patch that
   no longer applies). Rebase `native/patches/` onto the new `google/ink` commit, rebuild both
   natives, and run the two library checks, `native/test-linux.sh` and the oracle.
7. Adapt the ViveNotes brush catalog to API renames without changing what stored ink means (the
   release notes list them; 1.1.0-alpha08 renamed `DampingNode`'s properties, for example). Then run
   every suite.
