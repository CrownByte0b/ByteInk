#!/usr/bin/env bash
# Builds libink.so for this Linux host's architecture (x86_64 or arm64) with native/cmake instead of
# Bazel, from the pinned google/ink commit with the whole native/patches series and the cmake.* pins
# in upstream/pins.properties.
# This is the build Flatpak/Flathub packages make offline with their SDK's compiler; it lets the
# conformance oracle and the test suites check that build against the Bazel one byteink ships.
#
# Usage: native/build-linux-cmake.sh
#
# BYTEINK_FLATPAK_SDK (for example org.freedesktop.Sdk//26.08) compiles inside that Flatpak SDK, as
# Flathub does; otherwise the host's cc/c++ build. JAVA_HOME (or, in the SDK, BYTEINK_JNI_HOME) is
# a JDK whose include/ supplies jni.h. Results land in native/build/out-cmake/<commit>/linux-<arch>/
# (linux-x86_64 or linux-arm64), or in BYTEINK_OUT, laid out as native/build-linux.sh lays out its own.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

commit=$(pin google.ink.commit)
deps="$native/build/cmake-deps"
work="$native/build/cmake"
case $(uname -m) in
  x86_64) platform=linux-x86_64 ;;
  aarch64 | arm64) platform=linux-arm64 ;;
  *) echo "byteink builds Linux x86_64 and arm64, not $(uname -m)" >&2; exit 1 ;;
esac
out=${BYTEINK_OUT:-"$native/build/out-cmake/$commit/$platform"}

source "$native/cmake/dependencies.sh"
prepare_cmake_dependencies
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
