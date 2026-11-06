#!/usr/bin/env bash
# Install the pinned official SDK in the ignored build directory, without changing shell profiles.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
sdk="$native/build/emsdk"
version=$(pin wasm.emscripten.version)
if [[ ! -d $sdk/.git ]]; then
  git clone --quiet --depth 1 --branch "$version" https://github.com/emscripten-core/emsdk.git "$sdk"
fi
[[ $(git -C "$sdk" rev-parse HEAD) == "$(pin wasm.emsdk.commit)" ]] || {
  echo "Unexpected emsdk checkout at $sdk; expected $(pin wasm.emsdk.commit)" >&2
  exit 1
}
"$sdk/emsdk" install "$version"
"$sdk/emsdk" activate "$version"
