#!/usr/bin/env bash
# The portable upstream gtest subset that native/build-windows-tests.sh builds, cross-built for Linux
# arm64 with the same zig compiler, glibc ceiling and flags as native/build-linux-arm64.sh. Upstream's
# full suite (native/test-linux.sh) runs on x86_64 with its own toolchain; these run on an arm64
# Linux host, where they also exercise the Android float arithmetic (android_math_test) on AArch64.
#
# Usage: native/build-linux-arm64-tests.sh [google/ink commit]
# Results land in native/build/linux-arm64-tests/.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
commit=${1:-$(pin google.ink.commit)}
src="$native/build/google-ink-linux-arm64-tests"
install_bazelisk
checkout "$commit"
arm64=(--platforms=@zig_sdk//platform:linux_arm64 --extra_toolchains=@zig_sdk//toolchain:linux_arm64_gnu.2.28
  "${arm64_flags[@]}" --dynamic_mode=off --linkopt=-Wl,--strip-debug)
targets=(//ink/geometry/internal:android_math_test //ink/geometry:distance_test
  //ink/geometry:mesh_test //ink/brush:brush_test //ink/storage:input_batch_test)
bazel build "${flags[@]}" "${arm64[@]}" "${targets[@]}"
out="$native/build/linux-arm64-tests"
mkdir -p "$out"
for target in "${targets[@]}"; do
  executable=$(bazel cquery "${flags[@]}" "${arm64[@]}" --output=files "$target" 2>/dev/null)
  install -m 755 "$src/$executable" "$out/${target##*:}"
done
printf '%s\n' "${targets[@]}" > "$out/targets.txt"
