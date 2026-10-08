#!/usr/bin/env bash
# Investigation-only EGL control. Preserve the caller's driver choice; never start X or borrow JBR surfaces.
set -euo pipefail
unset WAYLAND_SOCKET DISPLAY WAYLAND_DISPLAY
task_root=$(cd "$(dirname "$0")/../../../.." && pwd)
output="${BYTEINK_EGL_TEST_OUTPUT:-$task_root/build/wayland-gpu/egl}"
hardware="${BYTEINK_EGL_TEST_HARDWARE:-false}"
promotion="${BYTEINK_EGL_TEST_PROMOTION_CHECK:-false}"
case "$hardware" in true|false) ;; *) echo 'BYTEINK_EGL_TEST_HARDWARE must be true or false.' >&2; exit 1 ;; esac
case "$promotion" in true|false) ;; *) echo 'BYTEINK_EGL_TEST_PROMOTION_CHECK must be true or false.' >&2; exit 1 ;; esac
mkdir -p "$output"
result_xml="$task_root/byteink-compose/build/test-results/meshEglTest/TEST-com.vivenotes.byteink.compose.InkMeshEglTest.xml"
cleanup() {
    if [[ -f $result_xml ]]; then cp "$result_xml" "$output/egl.xml"; fi
}
trap cleanup EXIT
cd "$task_root"
rm -f "$result_xml"
./gradlew --no-daemon :byteink-compose:meshEglTest "$@" "-PbyteinkEglTestHardware=$hardware" \
    "-PbyteinkEglPromotionCheck=$promotion" "-PbyteinkEglTestArtifacts=$output/frames"
python3 - "$result_xml" "$output/egl.xml" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
xml = Path(sys.argv[1])
root = ET.parse(xml).getroot()
if (int(root.get('tests', 0)) != 6 or
        any(int(root.get(key, 0)) for key in ['skipped', 'failures', 'errors'])):
    raise SystemExit('All six EGL controls must finish without errors or skips; software drivers are labeled explicitly.')
Path(sys.argv[2]).write_bytes(xml.read_bytes())
print('Surfaceless EGL: all six controls passed without skips. See XML stdout for the actual renderer and pixel metrics.')
PY
