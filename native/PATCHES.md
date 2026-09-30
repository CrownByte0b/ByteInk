# Native patch series

All patches apply to the `google.ink.commit` in `upstream/pins.properties`, in filename order.
The Windows build uses Bazel 8.7.0 and hermetic_cc_toolchain 4.2.0 (Zig 0.14.0 / LLVM 19),
MinGW-w64/UCRT, baseline x86-64, optimized code and static libc++/libunwind.

| Patch | Purpose | Upstream status |
|---|---|---|
| 0001 | Link Linux against the pinned Chromium bullseye sysroot, preserving the glibc baseline. | Local packaging policy; no upstream PR submitted. |
| 0002 | Add the Zig Windows toolchain, a PE linker branch, and a portable zlib Bazel definition. | Local portability patch; no upstream PR submitted. |
| 0003 | Use the pinned Android float-angle arithmetic for geometry and subtraction interpolation. | Local Android fidelity patch; no upstream PR submitted. See [ANGLE-MATH.md](ANGLE-MATH.md) for source hashes and licenses. |
| 0004 | Use the pinned Android float-magnitude arithmetic for vector lengths, polyline closure and subtraction interpolation. | Local Android fidelity patch; no upstream PR submitted. See [ANGLE-MATH.md](ANGLE-MATH.md). |

The Windows branch relies on `JNIEXPORT` for the 341 JNI exports; static libunwind additionally
exports its own small API. `:upstream:checkWindowsLibrary` checks the complete allowed surface,
system-only imports and absence of path-dependent debug entries. The portable upstream C++ subset
also links Windows' `dbghelp` for Abseil's symbolizer.

`build-windows.sh` strips debug information at link time. Zig does not accept lld's
`--no-insert-timestamp`; removing the path-dependent PDB leaves a deterministic image hash and
the path-free PE REPRO marker. `check-windows-reproducibility.sh` compares DLLs from two fresh
workspaces with Bazel action caching disabled. Download caches are still shared. The output hash
is recorded in `native/build/reproducibility/windows-sha256.txt`.

`build-oracle-baseline.sh` creates a separate unshipped validation build with only math patches
0003/0004 omitted. The strict Google engine/pin oracle uses matching platform arithmetic; the
shipped libraries use every patch and must pass the Android matrix and cross-OS comparison.

When updating upstream, apply each patch to the new pin, drop any upstreamed changes, and run both
native contracts, the full JVM runtime matrix, the C++ tests and the geometry/raster comparisons.
Do not change the native pin independently of the AndroidX release and Android app.

References: [Zig 0.14 driver](https://github.com/ziglang/zig/blob/0.14.0/src/main.zig),
[LLVM deterministic builds](https://blog.llvm.org/2019/11/deterministic-builds-with-clang-and-lld.html),
[Windows verification](WINDOWS.md).
