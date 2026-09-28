# Shared by the native/build-*.sh and test-*.sh scripts, which source it: the pins, the bazelisk
# launcher, and a google/ink checkout at a given commit with the native/patches series applied.
#
# BYTEINK_CACHE (default ~/.cache/byteink) holds the Bazel repository and disk caches, which builds
# of different commits share. For experiments only, BYTEINK_BAZEL_FLAGS adds flags to every Bazel
# build and test (for example --copt=-mavx); shipped binaries never use it.

native=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
root=$(dirname "$native")
pins="$root/upstream/pins.properties"
src="$native/build/google-ink"
patches=("$native"/patches/*.patch)

pin() {
  local value
  value=$(sed -n "s/^$1=//p" "$pins")
  [[ -n $value ]] || { echo "$pins has no $1" >&2; exit 1; }
  echo "$value"
}

bazel_version=$(pin bazel.version)
bazelisk_version=$(pin bazelisk.version)
bazelisk="$native/build/tools/bazelisk-$bazelisk_version-linux-amd64"
cache=${BYTEINK_CACHE:-$HOME/.cache/byteink}/bazel

read -r -a experiment_flags <<< "${BYTEINK_BAZEL_FLAGS:-}"
# Every build and test: the shared caches, the optimized configuration byteink ships with debug
# information (stripped separately), and any experiment flags.
flags=(--repository_cache="$cache/repository" --disk_cache="$cache/disk" -c opt --copt=-g "${experiment_flags[@]}")

# The user's git hooks and signing settings have no business in a throwaway checkout.
git() { command git -c core.hooksPath=/dev/null -c advice.detachedHead=false "$@"; }

# Downloads the pinned bazelisk once, checking its sha256.
install_bazelisk() {
  [[ -x $bazelisk ]] && return
  mkdir -p "$(dirname "$bazelisk")"
  curl -fsSL -o "$bazelisk.part" \
    "https://github.com/bazelbuild/bazelisk/releases/download/v$bazelisk_version/bazelisk-linux-amd64"
  echo "$(pin bazelisk.linux-amd64.sha256)  $bazelisk.part" | sha256sum --check --quiet -
  chmod +x "$bazelisk.part"
  mv "$bazelisk.part" "$bazelisk"
}

# Checks google/ink out at commit $1 in $src, clean, with the patch series applied in order except
# for any patch named in the remaining arguments.
checkout() {
  local commit=$1
  shift
  if [[ ! -d $src/.git ]]; then
    git clone --quiet "$(pin google.ink.repository)" "$src"
  fi
  if ! git -C "$src" cat-file -e "$commit^{commit}" 2>/dev/null; then
    git -C "$src" fetch --quiet origin
  fi
  git -C "$src" checkout --quiet --force "$commit"
  git -C "$src" clean -fdxq
  local patch
  for patch in "${patches[@]}"; do
    [[ " $* " == *" $(basename "$patch") "* ]] && continue
    git -C "$src" apply "$patch"
  done
}

# Runs the pinned Bazel in the checkout.
bazel() {
  (cd "$src" && USE_BAZEL_VERSION="$bazel_version" "$bazelisk" "$@")
}
