#!/usr/bin/env bash
set -euo pipefail
unset WAYLAND_SOCKET
task_root=$(cd "$(dirname "$0")/../../../.." && pwd)
fixture="$task_root/byteink-compose/src/test/wayland"
suite="${BYTEINK_WAYLAND_TEST_SUITE:-pen}"
vulkan="${BYTEINK_WAYLAND_TEST_VULKAN:-false}"
renderer="${BYTEINK_WAYLAND_TEST_RENDERER:-pixman}"
scale="${BYTEINK_WAYLAND_TEST_SCALE:-1}"
case "$vulkan" in true|false) ;; *) echo 'BYTEINK_WAYLAND_TEST_VULKAN must be true or false.' >&2; exit 1 ;; esac
case "$renderer" in pixman|gl|vulkan) ;; *) echo 'BYTEINK_WAYLAND_TEST_RENDERER must be pixman, gl or vulkan.' >&2; exit 1 ;; esac
case "$suite" in
    pen) test_task=waylandPenTest; test_class=WaylandPenIntegrationTest ;;
    presentation) test_task=waylandPresentationTest; test_class=WaylandPresentationIntegrationTest ;;
    *) echo 'BYTEINK_WAYLAND_TEST_SUITE must be pen or presentation.' >&2; exit 1 ;;
esac
backend="${BYTEINK_WAYLAND_TEST_BACKEND:-shm}"
if [[ $vulkan == true && -z ${BYTEINK_WAYLAND_TEST_BACKEND:-} ]]; then backend=vulkan; fi
case "$backend" in shm|vulkan) ;; *) echo 'BYTEINK_WAYLAND_TEST_BACKEND must be shm or vulkan.' >&2; exit 1 ;; esac
output="${BYTEINK_WAYLAND_TEST_OUTPUT:-$task_root/build/wayland-$suite/fixture}"
mkdir -p "$output"
result_xml="$task_root/byteink-compose/build/test-results/$test_task/TEST-com.vivenotes.byteink.compose.$test_class.xml"
if [[ $suite == pen ]]; then evidence_xml="$output/wayland-scale-$scale.xml"
else evidence_xml="$output/wayland-presentation-$backend-scale-$scale.xml"; fi
# A build/compositor startup failure must not retain XML from an older successful invocation.
rm -f "$result_xml"
python3 "$fixture/generate.py" --check
wayland-scanner server-header "$fixture/tablet-v2.xml" "$output/tablet-server.h"
wayland-scanner private-code "$fixture/tablet-v2.xml" "$output/tablet-protocol.c"
wayland-scanner server-header "$fixture/byteink-test.xml" "$output/byteink-test-server.h"
wayland-scanner private-code "$fixture/byteink-test.xml" "$output/byteink-test-protocol.c"
if [[ -n ${BYTEINK_WESTON_INCLUDE:-} ]]; then
    weston_flags=("-I$BYTEINK_WESTON_INCLUDE")
else
    weston_package=$(pkg-config --list-all | awk '$1 ~ /^libweston-[0-9]+$/ { print $1 }' | sort -V | tail -1)
    [[ -n $weston_package ]] || { echo 'Install the development headers matching Weston.' >&2; exit 1; }
    read -r -a weston_flags <<< "$(pkg-config --cflags "$weston_package")"
fi
read -r -a dependency_flags <<< "$(pkg-config --cflags --libs wayland-server pixman-1 xkbcommon)"
cc -shared -fPIC -Wall -Wextra -Wno-unused-parameter -Wl,-Bsymbolic \
    "${weston_flags[@]}" -I"$output" "$fixture/tablet-fixture.c" \
    "$output/tablet-protocol.c" "$output/byteink-test-protocol.c" "${dependency_flags[@]}" \
    -o "$output/tablet-fixture.so"
# Keep the socket path short even when the evidence directory has a long name.
# Unix-domain socket addresses (including Wayland's) have a 108-byte limit.
runtime=$(mktemp -d "${TMPDIR:-/tmp}/byteink-wayland.XXXXXX")
chmod 700 "$runtime"
weston_pid=
cleanup() {
    status=$?
    if [[ -f $result_xml ]]; then cp "$result_xml" "$evidence_xml" || status=1; fi
    if [[ -n $weston_pid ]]; then kill "$weston_pid" 2>/dev/null || true; wait "$weston_pid" 2>/dev/null || true; fi
    rm -rf "$runtime"
    return "$status"
}
trap cleanup EXIT
XDG_RUNTIME_DIR="$runtime" weston --backend=headless-backend.so --renderer="$renderer" \
    --scale="$scale" \
    --no-config --idle-time=0 --socket=byteink-test --modules="$output/tablet-fixture.so" \
    --log="$output/weston.log" >"$output/weston-output.log" 2>&1 &
weston_pid=$!
for ((attempt=0; attempt<100; attempt++)); do
    [[ -S $runtime/byteink-test ]] && break
    kill -0 "$weston_pid" 2>/dev/null || { cat "$output/weston.log" >&2; exit 1; }
    sleep .05
done
[[ -S $runtime/byteink-test ]] || { echo 'Private Weston did not start.' >&2; exit 1; }
cd "$task_root"
XDG_RUNTIME_DIR="$runtime" WAYLAND_DISPLAY=byteink-test DISPLAY= XDG_SESSION_TYPE=wayland \
    ./gradlew --no-daemon ":byteink-compose:$test_task" "$@" \
    "-PbyteinkWaylandTestVulkan=$vulkan" "-PbyteinkWaylandTestBackend=$backend" \
    "-PbyteinkWaylandTestScale=$scale"
kill -0 "$weston_pid" 2>/dev/null || { echo 'Test compositor exited before verification completed.' >&2; exit 1; }
python3 - "$result_xml" "$evidence_xml" "$scale" "$suite" "$backend" <<'PY'
from pathlib import Path
import sys
import xml.etree.ElementTree as ET
result, evidence, scale, suite, backend = sys.argv[1:]
xml = Path(result)
root = ET.parse(xml).getroot()
required = ({'nativeDirtyFramesRetainFinishedContentAndMatchFullPainterThroughEraseAndReconnect'} if suite == 'pen' else {
    'realNativeDestinationPresentsWetAlphaAndFinishedHandoff',
    'nativeHideResizeReconfigureAndDisabledFallbackPreserveFinishedPixels',
})
if (int(root.get('tests', 0)) < (10 if suite == 'pen' else 2) or
        not required.issubset({case.get('name') for case in root.findall('testcase')}) or
        any(int(root.get(key, 0)) for key in ['skipped', 'failures', 'errors'])):
    raise SystemExit('All selected native Wayland cases must finish without errors or skips (toolkit termination is not a pass).')
Path(evidence).write_bytes(xml.read_bytes())
print(f'Native Wayland {suite}: {root.get("tests")} cases passed at {scale}x scaling, expected backend={backend}, no skips.')
PY
