#!/usr/bin/env bash
# Cross-compiles google/ink's JNI library for Windows x86_64 on Linux: upstream's own Bazel build at
# the pinned commit with the native/patches series, using zig's clang (LLVM 19, like the Linux
# build) and MinGW-w64 from hermetic_cc_toolchain, with libc++ linked statically. The DLL needs
# only libraries every Windows 10 or later has.
#
# Usage: native/build-windows.sh [google/ink commit]
#
# The commit defaults to google.ink.commit in upstream/pins.properties. Results land in
# native/build/out/<commit>/windows-x86_64/, or in BYTEINK_OUT for experiments. See common.sh for
# the caches and experiment flags.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

commit=${1:-$(pin google.ink.commit)}
install_bazelisk
checkout "$commit"
windows=(--platforms=@zig_sdk//platform:windows_amd64 --extra_toolchains=@zig_sdk//toolchain:windows_amd64)
# lld's default PE timestamp is a hash of the image and PDB; the PDB includes build-host paths.
# Keep optimized code and its compile-time debug info, but ship no path-dependent PDB reference
# so its content-derived timestamp is independent of checkout locations and hosts. Zig's driver
# does not accept lld's --no-insert-timestamp; removing the path-dependent PDB is sufficient.
windows+=(--linkopt=-Wl,--strip-debug)
bazel build "${flags[@]}" "${windows[@]}" //ink/jni:libink.so

out=${BYTEINK_OUT:-"$native/build/out/$commit/windows-x86_64"}
mkdir -p "$out"
# Bazel names the output after the target, whatever the platform: it is a PE DLL. (`bazel info`
# cannot resolve @zig_sdk in --platforms, so ask cquery where the file is.)
dll=$(bazel cquery "${flags[@]}" "${windows[@]}" --output=files //ink/jni:libink.so 2>/dev/null)
install -m 644 "$src/$dll" "$out/ink.dll"
{
  echo "google.ink.commit=$commit"
  echo "float.angle.math=android-bionic-$(pin bionic.math.commit)"
  echo "float.magnitude.math=android-bionic-$(pin bionic.math.commit)"
  echo "bazel.version=$bazel_version"
  echo "pe.timestamp=content-hash"
  echo "pe.debug=stripped"
  [[ -z ${BYTEINK_BAZEL_FLAGS:-} ]] || echo "experiment.flags=$BYTEINK_BAZEL_FLAGS"
  for patch in "${patches[@]}"; do
    echo "patch.$(basename "$patch")=$(sha256sum "$patch" | cut -d' ' -f1)"
  done
  echo "ink.dll.sha256=$(sha256sum "$out/ink.dll" | cut -d' ' -f1)"
} > "$out/build.properties"
echo "Built $out/ink.dll"
