#!/usr/bin/env bash
# Build in two independent checkouts/output bases, including a different path length, and compare
# every shipped byte. Downloads are shared, but action caches are disabled and workspaces are fresh
# each time, so cached link outputs cannot masquerade as an independent reproducibility proof.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"
commit=${1:-$(pin google.ink.commit)}
proof="$native/build/reproducibility"
mkdir -p "$proof"
workspaces=$(mktemp -d "$proof/workspaces.XXXXXX")
for name in first second-longer-checkout; do
  BYTEINK_SOURCE="$workspaces/$name" BYTEINK_OUT="$proof/$name-out" \
    BYTEINK_BAZEL_FLAGS="${BYTEINK_BAZEL_FLAGS:-} --disk_cache=" \
    "$native/build-windows.sh" "$commit"
done
cmp "$proof/first-out/ink.dll" "$proof/second-longer-checkout-out/ink.dll"
sha256sum "$proof/first-out/ink.dll" "$proof/second-longer-checkout-out/ink.dll" \
  | tee "$proof/windows-sha256.txt"
