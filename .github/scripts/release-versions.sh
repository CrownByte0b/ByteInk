#!/usr/bin/env bash
# Emit Gradle project properties for both testing and publishing the exact tag coordinates.
set -euo pipefail
tag=${1:?Usage: release-versions.sh v<major.minor.patch[-prerelease]>}
if [[ ! $tag =~ ^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?$ ]]; then
  echo "Release tag must be v<major.minor.patch[-prerelease]>: $tag" >&2
  exit 1
fi
version=${tag#v}
if [[ ${version^^} == *SNAPSHOT* ]]; then
  echo 'A release tag cannot publish a SNAPSHOT version' >&2
  exit 1
fi
ink_version=$(sed -n 's/^androidx-ink = "\([^"]*\)"/\1/p' gradle/libs.versions.toml)
[[ -n $ink_version ]] || { echo 'Missing pinned AndroidX Ink version' >&2; exit 1; }
printf 'ORG_GRADLE_PROJECT_byteinkVersion=%s\n' "$version"
printf 'ORG_GRADLE_PROJECT_byteinkNativeLoaderVersion=%s-byteink.%s\n' "$ink_version" "$version"
