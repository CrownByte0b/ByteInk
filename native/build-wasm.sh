#!/usr/bin/env bash
# Browser/Node ES module + Wasm of the pinned engine, without JNI. Run setup-wasm.sh once first.
set -euo pipefail
export BYTEINK_SOURCE=${BYTEINK_SOURCE:-"$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/build/google-ink-wasm"}
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
source "$native/cmake/dependencies.sh"

sdk="$native/build/emsdk"
emcmake="$sdk/upstream/emscripten/emcmake"
[[ -x $emcmake ]] || { echo "Run native/setup-wasm.sh first" >&2; exit 1; }
[[ $(git -C "$sdk" rev-parse HEAD) == "$(pin wasm.emsdk.commit)" ]] || {
  echo "emsdk checkout does not match upstream/pins.properties" >&2; exit 1;
}
"$sdk/upstream/emscripten/emcc" --version | head -1 | grep -F "$(pin wasm.emscripten.version)" >/dev/null
prepare_cmake_dependencies
commit=$(pin google.ink.commit)
checkout "$commit"
work="$native/build/wasm"
out=${BYTEINK_OUT:-"$native/build/out/$commit/wasm"}
jobs=${BYTEINK_JOBS:-$(getconf _NPROCESSORS_ONLN)}
deps_args=("-DABSEIL_SOURCE_DIR=$abseil" "-DPROTOBUF_SOURCE_DIR=$protobuf"
  "-DZLIB_SOURCE_DIR=$zlib" "-DLIBTESS2_SOURCE_DIR=$libtess2" "-DINK_SOURCE_DIR=$src")

# Host protoc is deliberately built outside the cross-compilation tree.
host="$native/build/wasm-host"
cmake -S "$native/cmake" -B "$host" -G Ninja -DCMAKE_BUILD_TYPE=Release "${deps_args[@]}"
cmake --build "$host" --target protoc --parallel "$jobs"
"$emcmake" cmake -S "$native/cmake" -B "$work" -G Ninja -DCMAKE_BUILD_TYPE=Release \
  "${deps_args[@]}" "-DWITH_PROTOC=$host/protobuf/protoc" "-DBYTEINK_ENGINE_COMMIT=$commit"
cmake --build "$work" --target ink --parallel "$jobs"

mkdir -p "$out"
install -m 644 "$work/byteink-engine.mjs" "$work/byteink-engine.wasm" "$out/"
mkdir -p "$out/licenses"
install -m 644 "$src/LICENSE" "$out/licenses/google-ink.txt"
install -m 644 "$abseil/LICENSE" "$out/licenses/abseil.txt"
install -m 644 "$protobuf/LICENSE" "$out/licenses/protobuf.txt"
install -m 644 "$protobuf/third_party/utf8_range/LICENSE" "$out/licenses/utf8-range.txt"
install -m 644 "$zlib/LICENSE" "$out/licenses/zlib.txt"
install -m 644 "$libtess2/LICENSE.txt" "$out/licenses/libtess2.txt"
install -m 644 "$sdk/upstream/emscripten/LICENSE" "$out/licenses/emscripten.txt"
install -m 644 "$sdk/upstream/emscripten/system/lib/libc/musl/COPYRIGHT" "$out/licenses/musl.txt"
for library in libcxx libcxxabi libunwind compiler-rt; do
  install -m 644 "$sdk/upstream/emscripten/system/lib/$library/LICENSE.TXT" "$out/licenses/$library.txt"
done
install -m 644 "$root/ink-nativeloader/src/jvmMain/resources/com/vivenotes/byteink/nativeloader/ANDROID-MATH-LICENSE.txt" \
  "$out/licenses/android-math.txt"
{
  echo "google.ink.commit=$commit"
  echo "emscripten.version=$(pin wasm.emscripten.version)"
  echo "emsdk.commit=$(pin wasm.emsdk.commit)"
  echo "bridge.abi=2"
  echo "bridge.source.sha256=$(sha256sum "$native/wasm/bridge.cc" | cut -d' ' -f1)"
  for patch in "${patches[@]}"; do
    echo "patch.$(basename "$patch")=$(sha256sum "$patch" | cut -d' ' -f1)"
  done
  for file in byteink-engine.mjs byteink-engine.wasm; do
    echo "$file.sha256=$(sha256sum "$out/$file" | cut -d' ' -f1)"
  done
} > "$out/build.properties"
echo "Built $out/byteink-engine.mjs and byteink-engine.wasm"
