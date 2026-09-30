#!/usr/bin/env bash
# Portable upstream gtest subset, cross-built with the same Windows compiler as the shipped DLL.
# The full fuzztest-based suite remains on Linux; upstream fuzztest is not Windows-portable.
# Run these standalone, statically linked executables on Windows (or Wine for an additional check).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
commit=${1:-$(pin google.ink.commit)}
src="$native/build/google-ink-windows-tests"
install_bazelisk
checkout "$commit"
windows=(--platforms=@zig_sdk//platform:windows_amd64 --extra_toolchains=@zig_sdk//toolchain:windows_amd64
  --dynamic_mode=off --linkopt=-Wl,--strip-debug --linkopt=-ldbghelp)
targets=(//ink/geometry/internal:android_math_test //ink/geometry:distance_test
  //ink/geometry:mesh_test //ink/brush:brush_test //ink/storage:input_batch_test)
bazel build "${flags[@]}" "${windows[@]}" "${targets[@]}"
out="$native/build/windows-tests"
mkdir -p "$out"
for target in "${targets[@]}"; do
  executable=$(bazel cquery "${flags[@]}" "${windows[@]}" --output=files "$target" 2>/dev/null)
  install -m 644 "$src/$executable" "$out/${target##*:}.exe"
done
printf '%s\n' "${targets[@]}" > "$out/targets.txt"
