# Shared, checksum-checked source dependencies for the Linux and Wasm CMake builds.
# Source after native/common.sh, then call prepare_cmake_dependencies.
fetch_cmake_archive() {
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

prepare_cmake_dependencies() {
  deps="$native/build/cmake-deps"
  abseil=$(fetch_cmake_archive abseil)
  protobuf=$(fetch_cmake_archive protobuf)
  zlib=$(fetch_cmake_archive zlib)
  libtess2="$deps/libtess2"
  if [[ ! -d $libtess2/.git ]]; then
    git clone --quiet "$(pin cmake.libtess2.repository)" "$libtess2"
  fi
  git -C "$libtess2" checkout --quiet --force "$(pin cmake.libtess2.commit)"
}
