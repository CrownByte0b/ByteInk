#!/usr/bin/env bash
# Unshipped independent-engine validation profile. Compare Google's Linux native
# with the pinned Ink sources using platform math; shipped natives retain the
# Android arithmetic and are checked against Android's mandatory fidelity matrix.
#
# Usage: native/build-oracle-baseline.sh [linux|windows] [google/ink commit]
# Uses a separate checkout and output directory for each OS. Only math patches
# 0003/0004 are omitted; all other production patches and compiler flags remain.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

target_os=${1:-linux}
[[ $target_os == linux || $target_os == windows ]] || {
  echo "Expected linux or windows" >&2
  exit 1
}
commit=${2:-$(pin google.ink.commit)}
src="$native/build/google-ink-oracle-$target_os"
out="$native/build/oracle-$target_os-x64"
angle_patch=0003-use-android-float-angle-arithmetic.patch
magnitude_patch=0004-use-android-float-magnitude-arithmetic.patch

install_bazelisk
# Seed immutable Git objects locally when the production clone already exists;
# refs, index, working tree and Bazel output base remain independent.
if [[ ! -d $src/.git && -d $native/build/google-ink/.git ]]; then
  git clone --quiet --no-checkout --no-hardlinks "$native/build/google-ink" "$src"
fi
checkout "$commit" "$angle_patch" "$magnitude_patch"

if [[ $target_os == linux ]]; then
  bazel build "${flags[@]}" --stripopt=--strip-unneeded //ink/jni:libink.so //ink/jni:libink.so.stripped
  bin=$(bazel info "${flags[@]}" bazel-bin)
  mkdir -p "$out"
  install -m 644 "$bin/ink/jni/libink.so.stripped" "$out/libink.so"
  install -m 644 "$bin/ink/jni/libink.so" "$out/libink.so.debug"
  library="$out/libink.so"
  checksum_key=libink.so.sha256
else
  windows=(--platforms=@zig_sdk//platform:windows_amd64 --extra_toolchains=@zig_sdk//toolchain:windows_amd64
    --linkopt=-Wl,--strip-debug)
  bazel build "${flags[@]}" "${windows[@]}" //ink/jni:libink.so
  dll=$(bazel cquery "${flags[@]}" "${windows[@]}" --output=files //ink/jni:libink.so 2>/dev/null)
  mkdir -p "$out"
  install -m 644 "$src/$dll" "$out/ink.dll"
  library="$out/ink.dll"
  checksum_key=ink.dll.sha256
fi

{
  echo "google.ink.commit=$commit"
  echo "validation.only=true"
  echo "float.angle.math=platform"
  echo "float.magnitude.math=platform"
  echo "bazel.version=$bazel_version"
  [[ $target_os != windows ]] || echo "pe.timestamp=content-hash"
  [[ $target_os != windows ]] || echo "pe.debug=stripped"
  [[ -z ${BYTEINK_BAZEL_FLAGS:-} ]] || echo "experiment.flags=$BYTEINK_BAZEL_FLAGS"
  for patch in "${patches[@]}"; do
    patch_name=$(basename "$patch")
    if [[ $patch_name == "$angle_patch" || $patch_name == "$magnitude_patch" ]]; then
      echo "omitted.patch.$patch_name=$(sha256sum "$patch" | cut -d' ' -f1)"
    else
      echo "patch.$patch_name=$(sha256sum "$patch" | cut -d' ' -f1)"
    fi
  done
  echo "$checksum_key=$(sha256sum "$library" | cut -d' ' -f1)"
} > "$out/build.properties"
echo "Built unshipped validation native $library"
