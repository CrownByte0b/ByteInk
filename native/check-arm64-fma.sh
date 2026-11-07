#!/usr/bin/env bash
# Checks that byteink's arm64 libraries fuse no floating-point multiply-adds the source does not ask
# for (common.sh arm64_flags; native/ARM64.md). Disassembles them with the LLVM distribution the
# builds already downloaded, so run it after native/build-linux-arm64.sh and
# native/build-windows.sh arm64.
#
# The Linux library must contain no fused instruction at all. The Windows DLL also links MinGW-w64's
# C runtime, which zig compiles with its own flags: its strtod (gdtoa) fuses one multiply-add in a
# step whose result its own correction loop makes exact or redundant. A count above that means
# contraction reached code byteink compiles.
#
# Usage: native/check-arm64-fma.sh [google/ink commit]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

commit=${1:-$(pin google.ink.commit)}
out="$native/build/out/$commit"
install_bazelisk
objdump="$(bazel info output_base)/external/toolchains_llvm++llvm+llvm_toolchain_llvm/bin/llvm-objdump"
[[ -x $objdump ]] || { echo "No llvm-objdump at $objdump: build the arm64 libraries first" >&2; exit 1; }

# Scalar and vector fused multiply-add/subtract, in all their AArch64 spellings.
fused() {
  "$objdump" -d --no-show-raw-insn "$1" | grep -cE $'\t(fmadd|fmsub|fnmadd|fnmsub|fmla|fmls)\t' || true
}

status=0
check() {
  local file=$1 allowed=$2 count
  [[ -f $file ]] || { echo "Missing $file" >&2; exit 1; }
  count=$(fused "$file")
  echo "$file: $count fused multiply-add instructions (at most $allowed allowed)"
  if (( count > allowed )); then status=1; fi
}
check "$out/linux-arm64/libink.so" 0
check "$out/windows-arm64/ink.dll" 1
exit $status
