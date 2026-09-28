#!/usr/bin/env bash
# Runs google/ink's own C++ tests at the pinned commit with upstream's toolchain and -c opt, as
# byteink builds the library. Skips the Skia- and Dawn-based renderer (ink/rendering/skia/native),
# which byteink neither builds nor ships.
#
# The tests link against the build host's glibc, as upstream's CI does, in a checkout of their own:
# fuzztest's riegeli dependency needs copy_file_range (glibc 2.27), beyond the sysroot the shipped
# library links against. The shipped library itself is tested by :conformance and the oracle.
#
# Usage: native/test-linux.sh [google/ink commit] [extra `bazel test` flags...]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/common.sh"

commit=${1:-$(pin google.ink.commit)}
(($# == 0)) || shift
src="$native/build/google-ink-tests"
install_bazelisk
checkout "$commit" 0001-link-linux-against-bullseye-sysroot.patch
bazel test "${flags[@]}" --test_output=errors --keep_going "$@" \
  -- //ink/... -//ink/rendering/skia/native/...
