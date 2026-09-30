#!/usr/bin/env bash
# Installs the pinned SDK, verifies its official checksum, and names it for the test matrix.
set -euo pipefail
platform=$1
pins=upstream/pins.properties
pin() { sed -n "s/^$1=//p" "$pins"; }
archive="jbrsdk-$(pin jbr25.version)-$platform-b$(pin jbr25.build).tar.gz"
destination="$RUNNER_TEMP/byteink-jbr25"
mkdir -p "$destination"
curl --retry 3 -fsSL "https://cache-redirector.jetbrains.com/intellij-jbr/$archive" -o "$destination/archive.tar.gz"
printf '%s  %s\n' "$(pin "jbr25.$platform.sha512")" "$destination/archive.tar.gz" | sha512sum --check --quiet -
tar -xzf "$destination/archive.tar.gz" --strip-components=1 -C "$destination"
java_home=$destination
if [[ $platform == windows-* ]]; then java_home=$(cygpath -w "$destination"); fi
printf 'BYTEINK_TEST_JAVA_HOME=%s\n' "$java_home" >> "$GITHUB_ENV"
"$destination/bin/java" -version
