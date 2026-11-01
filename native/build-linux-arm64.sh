#!/usr/bin/env bash
# Cross-compiles google/ink's JNI library for Linux arm64 (aarch64) on Linux x86_64: upstream's own
# Bazel build at the pinned commit with the native/patches series, using zig's clang (LLVM 19, like
# the x86_64 builds) from hermetic_cc_toolchain, as the Windows builds do. libc++ is linked
# statically, and glibc symbols are no newer than 2.28. Keeps the stripped library and a separate
# debug copy, as native/build-linux.sh does.
#
# Upstream's LLVM toolchain is not used: toolchains_llvm links the sysroot's libstdc++ instead of
# its libc++ whenever it cross-compiles, and every other byteink binary uses libc++. The common
# arm64_flags turn off fused multiply-add contraction (see common.sh).
#
# Usage: native/build-linux-arm64.sh [google/ink commit]
#
# The commit defaults to google.ink.commit in upstream/pins.properties. Results land in
# native/build/out/<commit>/linux-arm64/, or in BYTEINK_OUT for experiments. See common.sh for
# the caches and experiment flags.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

glibc=2.28
commit=${1:-$(pin google.ink.commit)}
install_bazelisk
checkout "$commit"
# toolchains_llvm adds the build ID for x86_64; byteink's debug-symbol package requires one.
arm64=(--platforms=@zig_sdk//platform:linux_arm64 --extra_toolchains=@zig_sdk//toolchain:linux_arm64_gnu.$glibc
  "${arm64_flags[@]}" --linkopt=-Wl,--build-id=md5)
bazel build "${flags[@]}" "${arm64[@]}" //ink/jni:libink.so

out=${BYTEINK_OUT:-"$native/build/out/$commit/linux-arm64"}
mkdir -p "$out"
# `bazel info` cannot resolve @zig_sdk in --platforms, so ask cquery where the file is.
library=$(bazel cquery "${flags[@]}" "${arm64[@]}" --output=files //ink/jni:libink.so 2>/dev/null)
# zig's toolchain has no strip. The exec-platform LLVM distribution that upstream's toolchain
# already downloaded (it builds protoc) strips aarch64 ELF as Bazel's own --stripopt does for x86_64.
strip_tool="$(bazel info output_base)/external/toolchains_llvm++llvm+llvm_toolchain_llvm/bin/llvm-strip"
[[ -x $strip_tool ]] || { echo "No llvm-strip at $strip_tool" >&2; exit 1; }
install -m 644 "$src/$library" "$out/libink.so.debug"
"$strip_tool" --strip-unneeded -o "$out/libink.so" "$out/libink.so.debug"
chmod 644 "$out/libink.so"
{
  echo "google.ink.commit=$commit"
  echo "float.angle.math=android-bionic-$(pin bionic.math.commit)"
  echo "float.magnitude.math=android-bionic-$(pin bionic.math.commit)"
  echo "bazel.version=$bazel_version"
  echo "toolchain=zig-aarch64-linux-gnu.$glibc"
  echo "fp.contract=off"
  [[ -z ${BYTEINK_BAZEL_FLAGS:-} ]] || echo "experiment.flags=$BYTEINK_BAZEL_FLAGS"
  for patch in "${patches[@]}"; do
    echo "patch.$(basename "$patch")=$(sha256sum "$patch" | cut -d' ' -f1)"
  done
  echo "libink.so.sha256=$(sha256sum "$out/libink.so" | cut -d' ' -f1)"
  echo "libink.so.debug.sha256=$(sha256sum "$out/libink.so.debug" | cut -d' ' -f1)"
} > "$out/build.properties"
echo "Built $out/libink.so"
