# arm64 builds

byteink ships Ink's native library for `linux-arm64` and `windows-arm64` beside the two x86_64
platforms. Both arm64 libraries are cross-built on Linux x86_64, like the Windows x86_64 DLL, and
tested on arm64 machines (GitHub's `ubuntu-24.04-arm` and `windows-11-arm` runners in CI).

```sh
native/build-linux-arm64.sh          # native/build/out/<commit>/linux-arm64/libink.so (+ .debug)
native/build-windows.sh arm64        # native/build/out/<commit>/windows-arm64/ink.dll
native/check-arm64-fma.sh            # no fused multiply-add in code byteink compiles
native/build-linux-arm64-tests.sh    # native/build/linux-arm64-tests/: upstream gtest subset
native/build-windows-tests.sh arm64  # native/build/windows-arm64-tests/: the same subset
./gradlew :upstream:checkLinuxArm64Library :upstream:checkWindowsArm64Library
```

The loader jar bundles all four libraries by default, so a full local build needs both arm64
libraries too. `-PbyteinkBundledPlatforms=linux-x86_64,windows-x86_64` (or any subset of
`linux-x86_64`, `linux-arm64`, `windows-x86_64`, `windows-arm64`) builds a jar without the others,
and `-PbyteinkLinuxArm64Library=` / `-PbyteinkWindowsArm64Library=` name other builds.

## Toolchain

Both use hermetic_cc_toolchain 4.2.0 (Zig 0.14.0, LLVM 19) from patch 0002: static libc++,
libc++abi and libunwind, as in every other byteink binary. Linux targets `aarch64-linux-gnu.2.28`,
so the library needs nothing beyond glibc and no symbol newer than 2.28; the build at the current
pin needs only GLIBC_2.17/2.18 symbols. Windows targets `aarch64-windows-gnu` (MinGW-w64/UCRT) and
imports exactly the x86_64 DLL's Windows and UCRT libraries.

Upstream's LLVM toolchain (toolchains_llvm 1.5.0), which builds the Linux x86_64 library, is not
used for arm64: whenever it cross-compiles it replaces its bundled libc++ with the sysroot's
libstdc++ (`cc_toolchain_config.bzl`: `if stdlib == "builtin-libc++" and is_xcompile: stdlib =
"stdc++"`). A native arm64 build host would keep libc++, but the x86_64 development machine and CI
build hosts could not build it.

The Linux library is linked with `-Wl,--build-id=md5`, as toolchains_llvm links the x86_64 one,
and stripped with the LLVM distribution's `llvm-strip --strip-unneeded`, since Zig's toolchain has
no strip. Its unstripped copy ships separately as the `linux-arm64-debug-symbols` classifier.

lld's md5 build ID hashes the unstripped output, and Zig's compiler-rt records the absolute path
of the Bazel sandbox that compiled it in its debug information. Builds on different machines were
therefore identical except for those 16 bytes (CI run 41362260608 against a local build).
`set-content-build-id.py` rederives the ID as the MD5 of the stripped library with the ID zeroed,
and writes it into both files, so the library is reproducible across machines; only the debug copy
still carries build-host paths. The Windows DLLs strip debug information at link time and were
already byte-identical between CI and local builds.

## Floating point

AArch64 has fused multiply-add in its base instruction set, and clang fuses `a*b+c` within one
expression by default (`-ffp-contract=on`); Zig 0.14.0's `cc -O2` fuses it for both arm64 targets.
x86_64's baseline has no FMA, so the x86_64 libraries never fuse. A fused result rounds once
instead of twice, so arm64 geometry would differ from x86_64's in the last bits, and the Android
fidelity captures that every strict check compares with come from an x86_64 emulator.

Every arm64 compile therefore passes `-ffp-contract=off` (`arm64_flags` in `common.sh`; the CMake
build passes it on every architecture, where it is a no-op on x86_64). `check-arm64-fma.sh`
disassembles both libraries: the Linux library has no fused instruction at all. The Windows DLL
has one, in MinGW-w64's statically linked `strtod` (gdtoa's `tens[k - 9] * rv + z` step), which
Zig compiles with its own flags; strtod's exact fast path is unaffected by fusing and its
correction loop decides longer inputs. The check allows that one and fails on any other.

Ink already runs on Android arm64 devices, so arm64's 128-bit `long double` and unsigned `char`
(on Linux) are not new to its code.

Math functions are the remaining difference, as they already are between Linux and Windows x86_64.
Patches 0003/0004 compile Android's float angle and magnitude functions into every build. Beyond
those, the Linux x86_64 library calls the host's glibc for `sinf`, `cosf`, `tanf`, `expf`,
`log2`, `fmodf` and the rest. The Zig-built libraries link Zig's compiler-rt versions of
`sinf`, `cosf`, `tanf`, `expf`, `log2`, `fmodf` and `sqrtf` statically (local symbols in
`linux-arm64/libink.so`), and import only `acosf`, `atanf` and `pow` from glibc. Their last bits
can differ from glibc's. The strict comparisons below decide whether that matters.

## Verification

- `:upstream:checkLinuxArm64Library` / `checkWindowsArm64Library`: ELF machine AArch64 (183) or PE
  machine ARM64 (0xaa64), exactly the pinned JNI surface plus ByteInk's extensions, glibc only (and
  `ld-linux-aarch64.so.1`) or Windows-provided DLLs only.
- CI `native-arm64` builds, audits and checks both, and uploads the libraries, Linux symbols and
  both C++ test subsets.
- CI `jvm-linux-arm64` / `jvm-windows-arm64` run on arm64 runners for Zulu 25, Zulu 27 and JBR 25:
  the upstream C++ subset, the full build (every JVM suite, consumer smoke test, 20,000 fuzz
  iterations), the Android fidelity matrix, notebook/image parity against the x86_64 Linux
  reference, and a strict engine comparison of `oracleProduction.tsv` against the x86_64 Linux
  dump. Linux also runs the X11/EGL mesh and XInput2 pen tests and, on JBR 25, native Wayland
  tablet/presentation at 1x and 2x; Windows runs the native WM_POINTER pen test.
- Google ships no arm64 Linux binary, so the Google-binary oracle and conformance runs stay on
  Linux x86_64 (`runsGoogleLinuxBinary`).

To run the suites on an arm64 machine, build or download the four libraries into
`native/build/out/<commit>/` and run the same Gradle commands as the CI job. When the Gradle JVM
and the test JVM differ in architecture (for example an arm64 JDK under emulation),
`-PbyteinkHostPlatform=linux-arm64` names the platform whose library the suites must load.

## Status

2026-11-01: both libraries build and pass the static checks and the FMA audit on x86_64 Linux.
Running them on arm64 (CI runners, or locally under emulation) is pending; see
`memory/arm64.md` for the current state.
