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
- Input encodings must be identical once field 10 is removed.
- Packed buffers are compared through what they decode to.
- Everything else must be identical.

For the pin this passes with 0 mismatches, both on the synthetic cases and on real notebooks
(`-PoracleFixtures=<directory of .vive files>`, including their stored partial erases). It fails
for `d38cbb3d5e8a`, with 258 mismatches.

## Tasks

- `./gradlew :upstream:verifyJniSurface` runs as part of `check`. It recomputes `jni/` from the
  verified jars and fails if the lists changed, or if Google's binary does not export a native the
  jars declare.
- `./gradlew :upstream:jniSurface` rewrites `jni/` after a version change.
- `./gradlew :upstream:scanGoogleInk` clones `google/ink` into `upstream/build/google-ink` (or uses
  `-PgoogleInkDir=<clone>`) and writes `upstream/build/reports/google-ink-jni-scan.md`. It fails
  unless `google.ink.commit` and every candidate match the pinned release exactly.
  `-PscanReferences=<label>=<file>,…` adds reference lists, such as another release's exports.
- `./gradlew :upstream:installDist` builds the tool as
  `upstream/build/install/upstream/bin/upstream`. Its commands are `surface`, `exports` (print a
  binary's `Java_*` exports), `source-symbols` (print a `google/ink` revision's JNI functions) and
  `scan`.

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
6. Re-copy the forked `ink-nativeloader` sources from the new AndroidX commit, re-apply its one
   patch, and rebase any native build patches.
7. Adapt the ViveNotes brush catalog to API renames without changing what stored ink means (the
   release notes list them; 1.1.0-alpha08 renamed `DampingNode`'s properties, for example). Then run
   every suite.
