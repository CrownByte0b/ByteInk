#!/usr/bin/env bash
# Builds google/ink's JNI library for Linux x86_64 as byteink ships it: upstream's own Bazel build
# and LLVM toolchain at the pinned commit, plus the patches in native/patches/linux (which link
# against a Debian bullseye sysroot instead of the host's glibc). Keeps the stripped library and a
# separate debug copy, as Google's artifacts do.
#
# Usage: native/build-linux.sh [google/ink commit]
#
# The commit defaults to google.ink.commit in upstream/pins.properties. Results land in
# native/build/out/<commit>/linux-x86_64/. BYTEINK_CACHE (default ~/.cache/byteink) holds the
# Bazel repository and disk caches, which builds of different commits share.
#
# For experiments only: BYTEINK_BAZEL_FLAGS adds flags to the build (for example --copt=-mavx), and
# BYTEINK_OUT puts the results somewhere else. Shipped binaries use neither.
set -euo pipefail

native=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(dirname "$native")
pins="$root/upstream/pins.properties"

pin() {
  local value
  value=$(sed -n "s/^$1=//p" "$pins")
  [[ -n $value ]] || { echo "$pins has no $1" >&2; exit 1; }
  echo "$value"
}

commit=${1:-$(pin google.ink.commit)}
repository=$(pin google.ink.repository)
bazel_version=$(pin bazel.version)
bazelisk_version=$(pin bazelisk.version)
bazelisk_sha256=$(pin bazelisk.linux-amd64.sha256)
cache=${BYTEINK_CACHE:-$HOME/.cache/byteink}/bazel

# The user's git hooks and signing settings have no business in a throwaway checkout.
git() { command git -c core.hooksPath=/dev/null -c advice.detachedHead=false "$@"; }

bazelisk="$native/build/tools/bazelisk-$bazelisk_version-linux-amd64"
if [[ ! -x $bazelisk ]]; then
  mkdir -p "$(dirname "$bazelisk")"
  curl -fsSL -o "$bazelisk.part" \
    "https://github.com/bazelbuild/bazelisk/releases/download/v$bazelisk_version/bazelisk-linux-amd64"
  echo "$bazelisk_sha256  $bazelisk.part" | sha256sum --check --quiet -
  chmod +x "$bazelisk.part"
  mv "$bazelisk.part" "$bazelisk"
fi

src="$native/build/google-ink"
if [[ ! -d $src/.git ]]; then
  git clone --quiet "$repository" "$src"
fi
if ! git -C "$src" cat-file -e "$commit^{commit}" 2>/dev/null; then
  git -C "$src" fetch --quiet origin
fi
git -C "$src" checkout --quiet --force "$commit"
git -C "$src" clean -fdxq
patches=("$native"/patches/linux/*.patch)
for patch in "${patches[@]}"; do
  git -C "$src" apply "$patch"
done

bazel() {
  (cd "$src" && USE_BAZEL_VERSION="$bazel_version" "$bazelisk" "$@")
}
read -r -a extra_flags <<< "${BYTEINK_BAZEL_FLAGS:-}"
flags=(--repository_cache="$cache/repository" --disk_cache="$cache/disk" -c opt "${extra_flags[@]}")
bazel build "${flags[@]}" --copt=-g --stripopt=--strip-unneeded \
  //ink/jni:libink.so //ink/jni:libink.so.stripped

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
