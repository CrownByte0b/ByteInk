#!/usr/bin/env bash
set -euo pipefail
native=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
work="$native/build/wasm-math-test"
mkdir -p "$work"
python3 - "$native/wasm/testdata/bionic-acosf.c" <<'PY'
import hashlib, sys
from pathlib import Path
assert hashlib.sha256(Path(sys.argv[1]).read_bytes()).hexdigest() == '8f85d8f17ef06dd2fcfe19b9b7f1d0891d37312b1fe299c8e79bc8466c96e468'
PY
"$native/build/emsdk/upstream/emscripten/em++" -std=c++20 -O2 -ffp-contract=off \
  "$native/wasm/float_math.cc" "$native/wasm/float_math.test.cc" \
  -I"$native/wasm/testdata" -sENVIRONMENT=node -sEXIT_RUNTIME=1 -o "$work/math-test.mjs"
source "$native/build/emsdk/emsdk_env.sh" >/dev/null 2>&1
run_module() {
  node --input-type=module - "$1" <<'JS'
import { pathToFileURL } from 'node:url';
const { default: initialize } = await import(pathToFileURL(process.argv[2]));
await initialize();
JS
}
run_module "$work/math-test.mjs"
# Prove the SDK's original implementation triggers this regression, rather than just testing
# the comparison loop against two copies of our compatibility implementation.
"$native/build/emsdk/upstream/emscripten/em++" -std=c++20 -O2 -ffp-contract=off \
  "$native/wasm/float_math.test.cc" -I"$native/wasm/testdata" \
  -sENVIRONMENT=node -sEXIT_RUNTIME=1 -o "$work/stock-math-test.mjs"
if run_module "$work/stock-math-test.mjs" > "$work/negative-control.log" 2>&1; then
  echo 'The original SDK math unexpectedly passed; review whether compatibility is still needed.' >&2
  exit 1
fi
grep -F 'acosf(00000000): 3fc90fda != Bionic 3fc90fdb' "$work/negative-control.log" >/dev/null
echo 'Original SDK negative control fails at acosf(0), as expected.'
