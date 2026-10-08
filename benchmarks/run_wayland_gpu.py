#!/usr/bin/env python3
"""Measure Step 3 rendering/presentation on private native JBR Wayland windows.

No Gradle tasks run. Supply a resolved/frozen classpath and the pinned JBR Java
home. Fresh serial JVM forks compare the shipped software Swing painter with
an isolated owned EGL/Skia offscreen renderer followed by full readback and the
same Swing painter. JBR SHM and explicitly requested Vulkan destinations are
identified from the actual GraphicsConfiguration and native SurfaceData.
Toolkit.sync is a client toolkit operation; this does not measure scanout.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import shlex
import shutil
import statistics
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [ROOT / "benchmarks/pen/WaylandGpuBenchmark.java",
           ROOT / "benchmarks/pen/PenRetainedAuthoringBenchmark.java",
           ROOT / "byteink-compose/src/test/java/com/vivenotes/byteink/compose/HeadlessEglContext.java"]
FIXTURE = ROOT / "byteink-compose/src/test/wayland"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def binaries(classpath):
    result = {}
    for item in classpath.split(os.pathsep):
        path = Path(item)
        if path.is_file():
            result[str(path)] = sha(path)
        elif path.is_dir():
            for child in sorted(path.rglob("*")):
                if child.is_file():
                    result[str(child)] = sha(child)
        else:
            raise ValueError(f"Missing classpath entry: {path}")
    return result


def sources(source_root):
    result = {}
    for module in ("byteink-compose", "byteink-core", "byteink-kit", "ink-nativeloader"):
        for path in (source_root / module / "src/main").rglob("*"):
            if path.is_file():
                result[str(path.relative_to(source_root))] = sha(path)
    for path in SOURCES + [Path(__file__).resolve()] + sorted(FIXTURE.glob("*")):
        if path.is_file():
            result[str(path.relative_to(ROOT))] = sha(path)
    return dict(sorted(result.items()))


def native_runtime_files(java_home, vendor, icd):
    """Fingerprint the selected runtime and native driver/loader inputs outside timing."""
    paths = set()
    for relative in ("bin/java", "bin/javac", "lib/modules", "lib/server/libjvm.so", "lib/libawt_wl.so",
                     "lib/libawt.so", "lib/libawt_headless.so", "lib/libjava.so"):
        path = java_home / relative
        if path.is_file():
            paths.add(path.resolve())
    weston = shutil.which("weston")
    if weston:
        paths.add(Path(weston).resolve())
    cache = subprocess.check_output(["ldconfig", "-p"], text=True)
    libraries = {}
    for line in cache.splitlines():
        if "=>" in line and "x86-64" in line:
            name = line.strip().split()[0]
            libraries[name] = Path(line.split("=>", 1)[1].strip())
    selected = {"libEGL.so.1", "libGL.so.1", "libOpenGL.so.0", "libGLdispatch.so.0", "libvulkan.so.1",
                "libwayland-client.so.0", "libwayland-server.so.0", "libpixman-1.so.0", "libxkbcommon.so.0"}
    for path in (vendor, icd):
        name = json.loads(path.read_text())["ICD"]["library_path"]
        candidate = Path(name)
        if candidate.is_absolute() and candidate.is_file():
            paths.add(candidate.resolve())
        else:
            selected.add(name)
    if "nvidia" in vendor.name or "nvidia" in icd.name:
        selected.update(name for name in libraries if name.startswith(("libnvidia-glcore", "libnvidia-eglcore", "libnvidia-gpucomp")))
    selected.update(name for name in libraries if name.startswith(("libweston-", "libweston-desktop-")))
    for name in selected:
        if name in libraries and libraries[name].is_file():
            paths.add(libraries[name].resolve())
    return {str(path): sha(path) for path in sorted(paths)}


def execute(command, log, env=None, timeout=None):
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, env=env, stdout=stream,
                                stderr=subprocess.STDOUT, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {log}\n{log.read_text()[-6000:]}")


def percentile(values, fraction=.95):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def across(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "fork_values": values}


def summarize(forks):
    first = forks[0]
    for report in forks:
        if report["status"] != "passed" or report["cases"].keys() != first["cases"].keys():
            raise ValueError("A fork failed or workload inventory changed")
        if report["runtime_by_part"] != first["runtime_by_part"]:
            raise ValueError("Actual Java/toolkit/driver/destination identity changed between forks")
        if report["correctness"] != first["correctness"]:
            raise ValueError("Engine/pixel fingerprints or observed GPU differences changed between forks")
    result = {"forks": len(forks), "runtime_by_part": first["runtime_by_part"],
              "correctness": first["correctness"], "cases": {}}
    for name, initial in first["cases"].items():
        sample_keys = {"samples", "changed_samples", "cancel_samples", "initial_draw_samples", "native_counter_diagnostic"}
        structural = {key: value for key, value in initial.items() if key not in sample_keys}
        if any({key: value for key, value in fork["cases"][name].items() if key not in sample_keys} != structural for fork in forks):
            raise ValueError(f"Workload/output/resource counts changed between forks: {name}")
        scopes = {}
        for section in ("samples", "changed_samples", "cancel_samples"):
            metrics = {}
            for metric in initial[section]:
                samples = [fork["cases"][name][section][metric] for fork in forks]
                metrics[metric] = {"per_frame_median": across([statistics.median(values) for values in samples]),
                                   "per_frame_p95": across([percentile(values) for values in samples])}
            scopes[section.removesuffix("samples") + "metrics"] = metrics
        first_metrics = {metric: across([fork["cases"][name]["initial_draw_samples"][metric][0] for fork in forks])
                         for metric in initial["initial_draw_samples"]}
        result["cases"][name] = dict(structural, **scopes, initial_draw_metrics=first_metrics,
                                    native_counter_diagnostics_by_fork=[fork["cases"][name]["native_counter_diagnostic"] for fork in forks])
    return result


def fixture_library(args, output):
    directory = output / "fixture"
    directory.mkdir()
    if args.fixture_library:
        target = directory / "tablet-fixture.so"
        shutil.copy2(args.fixture_library.resolve(), target)
        return target, {"supplied_binary": str(args.fixture_library.resolve()), "sha256": sha(target)}
    execute(["python3", str(FIXTURE / "generate.py"), "--check"], directory / "generate-check.log")
    for protocol, prefix in (("tablet-v2.xml", "tablet"), ("byteink-test.xml", "byteink-test")):
        execute(["wayland-scanner", "server-header", str(FIXTURE / protocol), str(directory / f"{prefix}-server.h")], directory / f"{prefix}-header.log")
        execute(["wayland-scanner", "private-code", str(FIXTURE / protocol), str(directory / f"{prefix}-protocol.c")], directory / f"{prefix}-code.log")
    include = args.weston_include
    if include is None and os.environ.get("BYTEINK_WESTON_INCLUDE"):
        include = Path(os.environ["BYTEINK_WESTON_INCLUDE"])
    if include is None:
        packages = subprocess.check_output(["pkg-config", "--list-all"], text=True).splitlines()
        packages = [line.split()[0] for line in packages if line.startswith("libweston-")]
        if not packages:
            raise ValueError("Provide --weston-include for the headers matching installed Weston, or --fixture-library")
        package = max(packages, key=lambda name: int(name.rsplit("-", 1)[1]))
        weston_flags = shlex.split(subprocess.check_output(["pkg-config", "--cflags", package], text=True))
        header_info = {"pkg_config_package": package, "flags": weston_flags}
    else:
        include = include.resolve()
        if not include.is_dir():
            raise ValueError(f"Missing Weston include directory: {include}")
        weston_flags = [f"-I{include}"]
        header_info = {"include": str(include), "headers": {str(p.relative_to(include)): sha(p) for p in sorted(include.rglob("*.h"))}}
    deps = shlex.split(subprocess.check_output(["pkg-config", "--cflags", "--libs", "wayland-server", "pixman-1", "xkbcommon"], text=True))
    target = directory / "tablet-fixture.so"
    command = ["cc", "-shared", "-fPIC", "-Wall", "-Wextra", "-Wno-unused-parameter", "-Wl,-Bsymbolic", *weston_flags,
               f"-I{directory}", str(FIXTURE / "tablet-fixture.c"), str(directory / "tablet-protocol.c"),
               str(directory / "byteink-test-protocol.c"), *deps, "-o", str(target)]
    execute(command, directory / "compile.log")
    return target, {"command": command, "sha256": sha(target), "header_provenance": header_info}


def process_cpu(pid):
    # Linux /proc stat clocks are coarse; preserve a whole-fork diagnostic only.
    fields = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    return int((int(fields[11]) + int(fields[12])) * 1_000_000_000 / os.sysconf("SC_CLK_TCK"))


def native_part(args, output, fixture, classes, cp, fork, destination, scale, workload, driver_env):
    stem = f"fork-{fork}-{destination}-scale{scale}"
    report = output / f"{stem}.json"
    runtime = Path(tempfile.mkdtemp(prefix="byteink-gpu-", dir="/tmp"))
    os.chmod(runtime, 0o700)
    env = os.environ.copy()
    env.pop("WAYLAND_SOCKET", None)
    env.update(driver_env)
    env.update(XDG_RUNTIME_DIR=str(runtime), WAYLAND_DISPLAY="byteink-gpu", DISPLAY="", XDG_SESSION_TYPE="wayland")
    weston_command = ["weston", "--backend=headless-backend.so", "--use-pixman", f"--scale={scale}",
                      "--width=3840", "--height=2160", "--no-config", "--idle-time=0", "--socket=byteink-gpu",
                      f"--modules={fixture}", f"--log={output / (stem + '-weston.log')}"]
    java_home = args.java_home.resolve()
    command = [str(java_home / "bin/java"), "--enable-native-access=ALL-UNNAMED", "--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED",
               "--add-opens=java.desktop/sun.java2d=ALL-UNNAMED", "--add-opens=java.desktop/sun.java2d.wl=ALL-UNNAMED",
               "--add-opens=java.desktop/sun.java2d.vulkan=ALL-UNNAMED",
               "--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "-Dawt.toolkit.name=WLToolkit", "-Djava.awt.headless=false",
               "-Dsun.java2d.vulkan=True" if destination == "vulkan" else "-Dsun.java2d.vulkan=false",
               "-Xms512m", "-Xmx2g", "-XX:+UseG1GC", f"-Dskiko.data.path={output / (stem + '-skiko-cache')}",
               f"-Dbyteink.ink.cache={output / (stem + '-native-cache')}", f"-Dbyteink.gpu.destination={destination}",
               f"-Dbyteink.gpu.modes={args.modes}", f"-Dbyteink.gpu.warmupCycles={args.warmup_cycles}",
               f"-Dbyteink.gpu.measuredCycles={args.measured_cycles}", f"-Dbyteink.gpu.liveInputs={args.live_inputs}",
               f"-Dbyteink.gpu.inputsPerFrame={args.inputs_per_frame}", f"-Dbyteink.gpu.changedFrames={args.changed_frames}",
               f"-Dbyteink.gpu.driver={args.driver}", f"-Dbyteink.gpu.windowCounters={str(args.window_counters).lower()}",
               f"-Dbyteink.retained.sceneStrokes={args.scene_strokes}", f"-Dbyteink.retained.sceneInputs={args.scene_inputs}",
               f"-Dbyteink.retained.workloads={workload}", "-cp", str(classes) + os.pathsep + cp, "WaylandGpuBenchmark", str(report)]
    if args.window_counters:
        command.insert(1, "-Dawt.window.counters=byteink-benchmark-quiet")
    with (output / (stem + "-weston-output.log")).open("w") as stream:
        weston = subprocess.Popen(weston_command, cwd=ROOT, env=env, stdout=stream, stderr=subprocess.STDOUT)
    try:
        deadline = time.monotonic() + 10
        while not (runtime / "byteink-gpu").is_socket():
            if weston.poll() is not None or time.monotonic() >= deadline:
                raise RuntimeError(f"Private Weston failed to initialize; see {stem}-weston.log")
            time.sleep(.02)
        cpu_before = process_cpu(weston.pid)
        started = time.monotonic_ns()
        print(f"Running {stem}: {workload}, {args.driver} driver requested", flush=True)
        execute(command, output / (stem + ".log"), env, args.timeout_seconds)
        if weston.poll() is not None:
            raise RuntimeError("Compositor terminated before the measured fork completed")
        data = json.loads(report.read_text())
        diagnostic = {"wall_ns_including_setup_and_untimed_controls": time.monotonic_ns() - started,
                      "weston_cpu_ns_including_setup_and_untimed_controls": process_cpu(weston.pid) - cpu_before}
        return data, {"java": command, "weston": weston_command, "environment": {key: env[key] for key in driver_env},
                      "destination": destination, "scale": scale, "workloads": workload, "diagnostic": diagnostic}
    finally:
        if weston.poll() is None:
            weston.terminate()
            try:
                weston.wait(timeout=5)
            except subprocess.TimeoutExpired:
                weston.kill()
                weston.wait(timeout=5)
        shutil.rmtree(runtime)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--classpath", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup-cycles", type=int, default=4)
    parser.add_argument("--measured-cycles", type=int, default=3)
    parser.add_argument("--scene-strokes", type=int, default=8)
    parser.add_argument("--scene-inputs", type=int, default=32)
    parser.add_argument("--live-inputs", type=int, default=8192)
    parser.add_argument("--inputs-per-frame", type=int, default=128)
    parser.add_argument("--changed-frames", type=int, default=8)
    parser.add_argument("--workloads", choices=("all", "1080p", "4k", "2x", "1080p,4k"), default="all")
    parser.add_argument("--modes", default="software-full,software-retained,egl-readback")
    parser.add_argument("--destinations", choices=("software", "vulkan", "software,vulkan"), default="software,vulkan")
    parser.add_argument("--driver", choices=("software", "hardware"), default="software",
                        help="Explicit vendor/ICD defaults for this machine; actual renderer identities are always recorded")
    parser.add_argument("--egl-vendor-file", type=Path)
    parser.add_argument("--vulkan-icd-file", type=Path)
    parser.add_argument("--weston-include", type=Path)
    parser.add_argument("--fixture-library", type=Path, help="Use a previously compiled fixture with recorded hash instead of compiling one")
    parser.add_argument("--timeout-seconds", type=int, default=1800)
    parser.add_argument("--window-counters", action="store_true", help="Optional separate instrumented SHM frame/drop diagnostic; do not merge with uninstrumented timing")
    args = parser.parse_args()
    if min(args.forks, args.measured_cycles, args.inputs_per_frame, args.changed_frames, args.scene_inputs) < 1 or min(args.warmup_cycles, args.scene_strokes) < 0:
        parser.error("Positive measured/input/fork counts and nonnegative warmup/scene counts required")
    if args.live_inputs <= args.changed_frames * args.inputs_per_frame:
        parser.error("live-inputs must exceed changed-frames * inputs-per-frame (a genuine prefilling phase is required)")
    if not set(args.modes.split(",")).issubset({"software-full", "software-retained", "egl-readback"}):
        parser.error("Unknown mode")
    vendor = args.egl_vendor_file or Path("/usr/share/glvnd/egl_vendor.d/50_mesa.json" if args.driver == "software" else "/usr/share/glvnd/egl_vendor.d/10_nvidia.json")
    icd = args.vulkan_icd_file or Path("/usr/share/vulkan/icd.d/lvp_icd.x86_64.json" if args.driver == "software" else "/usr/share/vulkan/icd.d/nvidia_icd.x86_64.json")
    if not vendor.is_file() or not icd.is_file():
        parser.error("Explicit EGL vendor/Vulkan ICD files are required; provide paths supported on this host")
    driver_env = {"__EGL_VENDOR_LIBRARY_FILENAMES": str(vendor.resolve()), "VK_ICD_FILENAMES": str(icd.resolve()),
                  "VK_DRIVER_FILES": str(icd.resolve()),
                  "LIBGL_ALWAYS_SOFTWARE": "1" if args.driver == "software" else "0"}
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Output must be a fresh directory")
    output.mkdir(parents=True, exist_ok=True)
    lock = ROOT / "build/performance-benchmark.lock"
    lock.parent.mkdir(exist_ok=True)
    try:
        lock.mkdir()
    except FileExistsError:
        parser.error(f"Another workload owns {lock}; coordinate serial benchmark/build execution")
    try:
        cp = args.classpath.resolve().read_text().strip()
        binary_before = binaries(cp); source_before = sources(args.source_root.resolve())
        native_before = native_runtime_files(args.java_home.resolve(), vendor, icd)
        snapshots = output / "source-snapshots"
        for name in source_before:
            source = ROOT / name if name.startswith("benchmarks/") or name.startswith("byteink-compose/src/test/") else args.source_root.resolve() / name
            target = snapshots / name; target.parent.mkdir(parents=True, exist_ok=True); shutil.copy2(source, target)
        fixture, fixture_info = fixture_library(args, output)
        classes = output / "harness-classes"; classes.mkdir()
        javac = [str(args.java_home.resolve() / "bin/javac"), "-nowarn", "-cp", cp, "-d", str(classes), *map(str, SOURCES)]
        execute(javac, output / "javac.log")
        (output / "runtime-classpath.txt").write_text(cp + "\n")
        provenance = {"created_utc": datetime.now(timezone.utc).isoformat(), "classpath_source": str(args.classpath.resolve()),
                      "source_root": str(args.source_root.resolve()), "binary_files": binary_before, "source_files": source_before,
                      "fixture": fixture_info, "driver_requested": args.driver,
                      "driver_files": {str(vendor.resolve()): sha(vendor), str(icd.resolve()): sha(icd)},
                      "native_runtime_files": native_before, "window_counters": args.window_counters,
                      "javac": javac, "java_home": str(args.java_home.resolve()),
                      "weston_version": subprocess.check_output(["weston", "--version"], text=True).strip(),
                      "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}
        (output / "start-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        groups = []
        if args.workloads in ("all", "1080p,4k"):
            groups.append((1, "1080p,4k"))
        elif args.workloads in ("1080p", "4k"):
            groups.append((1, args.workloads))
        if args.workloads in ("all", "2x"):
            groups.append((2, "2x"))
        commands, forks = [], []
        for fork in range(1, args.forks + 1):
            combined = {"status": "passed", "runtime_by_part": {}, "correctness": {}, "cases": {}}
            for destination in args.destinations.split(","):
                for scale, workload in groups:
                    data, command = native_part(args, output, fixture, classes, cp, fork, destination, scale, workload, driver_env)
                    commands.append(command)
                    if data.get("status") != "passed":
                        raise RuntimeError("A measured part did not pass")
                    combined["runtime_by_part"][f"{destination}/scale{scale}"] = data["runtime"]
                    for section in ("correctness", "cases"):
                        for key, value in data[section].items():
                            combined[section][f"{destination}/{key}"] = value
            (output / f"fork-{fork}-combined.json").write_text(json.dumps(combined, indent=2) + "\n")
            forks.append(combined)
            (output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        if (binaries(cp) != binary_before or sources(args.source_root.resolve()) != source_before
                or sha(fixture) != fixture_info["sha256"]
                or native_runtime_files(args.java_home.resolve(), vendor, icd) != native_before):
            raise RuntimeError("Runtime/native/production/harness/fixture bytes changed during measurement; discard run")
        summary = summarize(forks)
        summary["provenance"] = dict(provenance, source_binary_guards_passed=True,
                                     extracted_native_files={str(p.relative_to(output)): sha(p) for p in output.rglob("*.so")})
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        print(f"Saved {output / 'summary.json'}", flush=True)
    finally:
        lock.rmdir()


if __name__ == "__main__":
    main()
