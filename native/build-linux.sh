#!/usr/bin/env bash
# Builds google/ink's JNI library for Linux x86_64 as byteink ships it: upstream's own Bazel build
# and LLVM toolchain at the pinned commit, plus the native/patches series (of which the Linux one
# links against a Debian bullseye sysroot instead of the host's glibc). Keeps the stripped library
# and a separate debug copy, as Google's artifacts do.
#
# Usage: native/build-linux.sh [google/ink commit]
#
# The commit defaults to google.ink.commit in upstream/pins.properties. Results land in
# native/build/out/<commit>/linux-x86_64/, or in BYTEINK_OUT for experiments. See common.sh for
# the caches and experiment flags.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

commit=${1:-$(pin google.ink.commit)}
install_bazelisk
checkout "$commit"
bazel build "${flags[@]}" --stripopt=--strip-unneeded //ink/jni:libink.so //ink/jni:libink.so.stripped

out=${BYTEINK_OUT:-"$native/build/out/$commit/linux-x86_64"}
mkdir -p "$out"
bin=$(bazel info "${flags[@]}" bazel-bin)
install -m 644 "$bin/ink/jni/libink.so.stripped" "$out/libink.so"
install -m 644 "$bin/ink/jni/libink.so" "$out/libink.so.debug"
{
  echo "google.ink.commit=$commit"
  echo "bazel.version=$bazel_version"
  [[ -z ${BYTEINK_BAZEL_FLAGS:-} ]] || echo "experiment.flags=$BYTEINK_BAZEL_FLAGS"
  for patch in "${patches[@]}"; do
    echo "patch.$(basename "$patch")=$(sha256sum "$patch" | cut -d' ' -f1)"
  done
  echo "libink.so.sha256=$(sha256sum "$out/libink.so" | cut -d' ' -f1)"
} > "$out/build.properties"
echo "Built $out/libink.so"
