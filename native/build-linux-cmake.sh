#!/usr/bin/env bash
# Builds libink.so for Linux x86_64 with native/cmake instead of Bazel, from the pinned google/ink
# commit with the whole native/patches series and the cmake.* pins in upstream/pins.properties.
# This is the build Flatpak/Flathub packages make offline with their SDK's compiler; it lets the
# conformance oracle and the test suites check that build against the Bazel one byteink ships.
#
# Usage: native/build-linux-cmake.sh
#
# BYTEINK_FLATPAK_SDK (for example org.freedesktop.Sdk//26.08) compiles inside that Flatpak SDK, as
# Flathub does; otherwise the host's cc/c++ build. JAVA_HOME (or, in the SDK, BYTEINK_JNI_HOME) is
# a JDK whose include/ supplies jni.h. Results land in native/build/out-cmake/<commit>/linux-x86_64/,
# or in BYTEINK_OUT, laid out as native/build-linux.sh lays out its own.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

commit=$(pin google.ink.commit)
deps="$native/build/cmake-deps"
work="$native/build/cmake"
out=${BYTEINK_OUT:-"$native/build/out-cmake/$commit/linux-x86_64"}

# Downloads and unpacks the archive pinned as cmake.$1, checking its sha256; prints where it is.
fetch_archive() {
  local url sha archive dir
  url=$(pin "cmake.$1.url")
  sha=$(pin "cmake.$1.sha256")
  archive="$deps/$(basename "$url")"
  dir="$deps/$1-$sha"
  if [[ ! -d $dir ]]; then
    mkdir -p "$deps"
    [[ -f $archive ]] || curl -fsSL -o "$archive.part" "$url"
    [[ -f $archive ]] || mv "$archive.part" "$archive"
    echo "$sha  $archive" | sha256sum --check --quiet -
    mkdir -p "$dir.part"
    tar -xzf "$archive" -C "$dir.part" --strip-components=1
    mv "$dir.part" "$dir"
  fi
  echo "$dir"
}

abseil=$(fetch_archive abseil)
protobuf=$(fetch_archive protobuf)
zlib=$(fetch_archive zlib)
libtess2="$deps/libtess2"
if [[ ! -d $libtess2/.git ]]; then
  git clone --quiet "$(pin cmake.libtess2.repository)" "$libtess2"
fi
git -C "$libtess2" checkout --quiet --force "$(pin cmake.libtess2.commit)"
checkout "$commit"

configure_and_build=(
  cmake -S "$native/cmake" -B "$work" -G Ninja -DCMAKE_BUILD_TYPE=Release
  "-DINK_SOURCE_DIR=$src" "-DABSEIL_SOURCE_DIR=$abseil" "-DPROTOBUF_SOURCE_DIR=$protobuf"
  "-DZLIB_SOURCE_DIR=$zlib" "-DLIBTESS2_SOURCE_DIR=$libtess2"
)
rm -rf "$work"
if [[ -n ${BYTEINK_FLATPAK_SDK:-} ]]; then
  jni_home=${BYTEINK_JNI_HOME:-${JAVA_HOME:?Set JAVA_HOME or BYTEINK_JNI_HOME to a JDK for jni.h}}
  flatpak run --no-documents-portal --filesystem="$root" --filesystem="$jni_home:ro" \
    --env=JAVA_HOME="$jni_home" --command=bash "$BYTEINK_FLATPAK_SDK" -c \
    "$(printf '%q ' "${configure_and_build[@]}") && ninja -C $(printf '%q' "$work") ink && c++ --version | head -1 > $(printf '%q' "$work/compiler")"
else
  "${configure_and_build[@]}"
  ninja -C "$work" ink
  c++ --version | head -1 > "$work/compiler"
fi

mkdir -p "$out"
install -m 644 "$work/libink.so" "$out/libink.so.debug"
objcopy --strip-unneeded "$work/libink.so" "$out/libink.so"
{
  echo "google.ink.commit=$commit"
  echo "float.angle.math=android-bionic-$(pin bionic.math.commit)"
  echo "float.magnitude.math=android-bionic-$(pin bionic.math.commit)"
  echo "build.system=cmake"
  echo "compiler=$(cat "$work/compiler")"
  [[ -z ${BYTEINK_FLATPAK_SDK:-} ]] || echo "flatpak.sdk=$BYTEINK_FLATPAK_SDK"
  for patch in "${patches[@]}"; do
    echo "patch.$(basename "$patch")=$(sha256sum "$patch" | cut -d' ' -f1)"
  done
  echo "libink.so.sha256=$(sha256sum "$out/libink.so" | cut -d' ' -f1)"
  echo "libink.so.debug.sha256=$(sha256sum "$out/libink.so.debug" | cut -d' ' -f1)"
} > "$out/build.properties"
echo "Built $out/libink.so"
