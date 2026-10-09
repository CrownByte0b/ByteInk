#!/usr/bin/env python3
"""Reproduce Linux CPU raster comparisons using real ByteInk, Qt6 and Electron.

Run --prepare first (builds are never concurrent with timings). --run performs
fresh serial, counterbalanced forks. All frame observations, process-tree memory
checkpoints, PNGs, version pins and source/binary hashes are retained. No window
presentation, GPU, physical pen, or equivalent-feature app claim is made.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import selectors
import signal
import statistics
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "benchmarks/toolkits"
TOOLING = ROOT / "build/toolkit-comparison/tooling/node_modules/electron"
CASES = [
    ("blank_1080p", 1920, 1080, 0, False, 1),
    ("notes_100", 1920, 1080, 100, False, 1),
    ("page_1000", 1920, 1080, 1000, False, 1),
    ("dense_10000", 1920, 1080, 10000, False, 1),
    ("page_4k", 3840, 2160, 1000, False, 1),
    ("alpha_1000", 1920, 1080, 1000, True, 1),
    ("zoom_1000", 1920, 1080, 1000, False, 2),
]
STACKS = ("byteink", "qt6", "electron")

def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def execute(command, log):
    with log.open("w") as output:
        status = subprocess.run([str(x) for x in command], cwd=ROOT, stdout=output, stderr=subprocess.STDOUT)
    if status.returncode:
        raise RuntimeError(f"Command failed; see {log}\n{log.read_text()[-5000:]}")

def source_hashes():
    paths = list(SOURCES.glob("*")) + [Path(__file__), ROOT / "benchmarks/run_pen.py", ROOT / "gradle/libs.versions.toml", ROOT / "upstream/pins.properties"]
    for module in ("byteink-core", "byteink-compose", "byteink-kit", "ink-nativeloader"):
        paths.extend((ROOT / module / "src/main").rglob("*"))
    return {str(p.relative_to(ROOT)): digest(p) for p in sorted(paths) if p.is_file()}

def binary_hashes(cp, prepared):
    paths = [prepared / "qt/qt_benchmark", TOOLING / "dist/electron", TOOLING / "package.json", prepared / "scene.bin"]
    paths.extend((prepared / "classes").rglob("*.class"))
    # Guard the dynamically linked Qt implementation, not just our small driver executable.
    for line in subprocess.check_output(["ldd", str(prepared / "qt/qt_benchmark")], text=True).splitlines():
        if "=> /" in line:
            library = Path(line.split("=>", 1)[1].strip().split()[0])
            if "Qt6" in library.name: paths.append(library)
    paths.extend(p for p in (TOOLING / "dist").glob("*") if p.is_file() and p.suffix in (".pak", ".bin", ".dat", ".so"))
    for item in cp.split(os.pathsep):
        p = Path(item)
        if p.is_file(): paths.append(p)
        elif p.is_dir(): paths.extend(x for x in p.rglob("*") if x.is_file())
        else: raise FileNotFoundError(p)
    return {str(p.resolve()): digest(p) for p in paths}

def java_command(java_home, cp, classes):
    # Small initial heap, normal ergonomic growth; no forced collection between cases.
    return [str(java_home / "bin/java"), "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
            "-Xms32m", "-Xmx1g", "-XX:+UseG1GC", "-cp", str(classes) + os.pathsep + cp, "ByteinkBenchmark"]

def prepare(args):
    target = args.prepared.resolve(); target.mkdir(parents=True, exist_ok=True)
    sys.path.insert(0, str(ROOT / "benchmarks"))
    from run_pen import runtime_classpath
    execute([ROOT / "gradlew", "--offline", "--no-daemon", "--max-workers=1", "--no-configuration-cache", "--console=plain",
             ":byteink-kit:jar", ":byteink-core:jar", ":byteink-compose:jar"], target / "production-build.log")
    cp = runtime_classpath(target, argparse.Namespace(classpath=None, online=False))
    (target / "runtime-classpath.txt").write_text(cp + "\n")
    classes = target / "classes"; classes.mkdir(exist_ok=True)
    execute([args.java_home / "bin/javac", "-nowarn", "-cp", cp, "-d", classes, SOURCES / "ByteinkBenchmark.java"], target / "javac.log")
    execute(["cmake", "-S", SOURCES, "-B", target / "qt", "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release"], target / "cmake.log")
    execute(["cmake", "--build", target / "qt", "--parallel", "2"], target / "qt-build.log")
    execute(java_command(args.java_home, cp, classes) + ["generate", str(target / "scene.bin")], target / "scene.log")
    (target / "prepared.json").write_text(json.dumps({"source_hashes": source_hashes(), "binary_hashes": binary_hashes(cp, target)}, indent=2) + "\n")
    print(f"Prepared {target}", flush=True)

def tree_pids(root):
    result, pending = [], [root]
    while pending:
        pid = pending.pop()
        if pid in result: continue
        try:
            children = Path(f"/proc/{pid}/task/{pid}/children").read_text().split()
            result.append(pid); pending.extend(map(int, children))
        except (FileNotFoundError, ProcessLookupError): pass
    return result

def checkpoint(root):
    processes = []
    for pid in tree_pids(root):
        try:
            stat = Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
            memory = {}
            for line in Path(f"/proc/{pid}/smaps_rollup").read_text().splitlines()[1:]:
                name, value, *_ = line.split(); memory[name.rstrip(":")] = int(value) * 1024
            processes.append({"pid": pid, "start_ticks": int(stat[19]), "cpu_ticks": int(stat[11]) + int(stat[12]),
                              "rss_bytes": memory["Rss"], "pss_bytes": memory["Pss"],
                              "uss_bytes": memory.get("Private_Clean", 0) + memory.get("Private_Dirty", 0) + memory.get("Private_Hugetlb", 0)})
        except (FileNotFoundError, ProcessLookupError): pass
    if not processes: raise RuntimeError("No processes at checkpoint")
    return {"processes": processes, **{key: sum(p[key] for p in processes) for key in ("rss_bytes", "pss_bytes", "uss_bytes", "cpu_ticks")}}

def receive(process, timeout=120):
    deadline = time.monotonic() + timeout
    selector = selectors.DefaultSelector(); selector.register(process.stdout, selectors.EVENT_READ)
    try:
        while time.monotonic() < deadline:
            if not selector.select(max(0, deadline - time.monotonic())): break
            line = process.stdout.readline()
            if not line: raise RuntimeError(f"Benchmark exited {process.poll()} before result")
            try: return json.loads(line)
            except json.JSONDecodeError: continue  # Runtime native-loader messages are retained in stderr.
        raise TimeoutError("Benchmark response timeout")
    finally: selector.close()

def run_one(stack, fork, args, cp, output, case_order):
    folder = output / f"fork-{fork}" / stack; folder.mkdir(parents=True)
    prepared = args.prepared.resolve()
    common = [str(prepared / "scene.bin"), str(folder), str(args.warmup), str(args.measured)]
    env = dict(os.environ)
    if stack == "byteink": command = java_command(args.java_home, cp, prepared / "classes") + common
    elif stack == "qt6": command = [str(prepared / "qt/qt_benchmark")] + common
    else:
        command = [str(TOOLING / "dist/electron"), "--no-sandbox", "--ozone-platform=x11", str(SOURCES / "electron.cjs")] + common
        env["BYTEINK_ELECTRON_PROFILE"] = str(folder / "profile")
    with (folder / "stderr.log").open("w") as errors:
        started = time.perf_counter_ns()
        process = subprocess.Popen(command, cwd=ROOT, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=errors, text=True, bufsize=1, start_new_session=True)
        try:
            ready = receive(process)
            ready_ns = time.perf_counter_ns() - started
            if ready["event"] != "ready" or ready["scene_sha256"] != digest(prepared / "scene.bin"): raise AssertionError("ready/hash")
            time.sleep(.2)
            idle = checkpoint(process.pid)
            results = {}
            for case in case_order:
                name = case[0]
                before = checkpoint(process.pid)
                process.stdin.write(",".join(str(x).lower() for x in case) + "\n"); process.stdin.flush()
                response = receive(process)
                if response["event"] != "case" or response["name"] != name or len(response["wall_ns"]) != args.measured: raise AssertionError(response)
                after = checkpoint(process.pid)
                response["loaded_checkpoint"] = after
                response["whole_case_cpu_ns"] = (after["cpu_ticks"] - before["cpu_ticks"]) * (1e9 / os.sysconf("SC_CLK_TCK"))
                results[name] = response
                process.stdin.write("ack\n"); process.stdin.flush()
            process.stdin.write("quit\n"); process.stdin.flush()
            if process.wait(timeout=20): raise RuntimeError(f"Benchmark failed, see {folder / 'stderr.log'}")
            report = {"stack": stack, "fork": fork, "command": command, "ready": ready, "fresh_process_ready_ns": ready_ns,
                      "idle_checkpoint": idle, "case_order": [x[0] for x in case_order], "cases": results}
            (folder / "raw.json").write_text(json.dumps(report, indent=2) + "\n")
            return report
        finally:
            if process.poll() is None: os.killpg(process.pid, signal.SIGKILL); process.wait()

def dist(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "fork_values": values}

def summarize(raw):
    result = {}
    for stack in STACKS:
        forks = [x for x in raw if x["stack"] == stack]
        result[stack] = {"runtime": forks[0]["ready"], "fresh_process_ready_ns": dist([x["fresh_process_ready_ns"] for x in forks]),
            "idle_memory": {m: dist([x["idle_checkpoint"][m] for x in forks]) for m in ("rss_bytes", "pss_bytes", "uss_bytes")}, "cases": {}}
        for name, *_ in CASES:
            cases = [x["cases"][name] for x in forks]
            result[stack]["cases"][name] = {
                "frame_median_ns": dist([statistics.median(x["wall_ns"]) for x in cases]),
                "frame_p95_ns": dist([sorted(x["wall_ns"])[math.ceil(.95 * len(x["wall_ns"])) - 1] for x in cases]),
                "loaded_memory": {m: dist([x["loaded_checkpoint"][m] for x in cases]) for m in ("rss_bytes", "pss_bytes", "uss_bytes")},
                "whole_case_cpu_ns": dist([x["whole_case_cpu_ns"] for x in cases])}
    return result

def validate_images(output, forks):
    from PIL import Image
    import numpy as np
    checks, image_hashes = {}, {}
    for name, width, height, count, *_ in CASES:
        images = {}
        for stack in STACKS:
            arrays = [np.array(Image.open(output / f"fork-{i}" / stack / f"{name}.png").convert("RGBA")) for i in range(1, forks + 1)]
            if any(x.shape != (height, width, 4) or not np.all(x[:, :, 3] == 255) for x in arrays): raise AssertionError(f"dimensions/opacity {name}/{stack}")
            if any(not np.array_equal(arrays[0], x) for x in arrays[1:]): raise AssertionError(f"non-deterministic raster {name}/{stack}")
            image = arrays[0][:, :, :3]
            coverage = 255 - image.mean(axis=2)
            if count and coverage.sum() == 0: raise AssertionError("empty ink output")
            if not count and np.any(image != 255): raise AssertionError("nonwhite blank output")
            images[stack] = image
        image_hashes[name] = {stack: hashlib.sha256(array.tobytes()).hexdigest() for stack, array in images.items()}
        checks[name] = {}
        for stack in STACKS[1:]:
            difference = np.abs(images["byteink"].astype(np.int16) - images[stack].astype(np.int16))
            checks[name][stack] = {"mean_absolute_channel_error_255": float(difference.mean()), "max_channel_error_255": int(difference.max()),
                "different_pixels": int(np.any(difference, axis=2).sum()),
                "coverage_sum_ratio_to_byteink": float((255 - images[stack].mean(axis=2)).sum() / (255 - images['byteink'].mean(axis=2)).sum()) if count else None}
    return {"same_scene_hash": True, "byteink_regenerated_outlines_equal": True, "within_stack_exact_pixels_across_forks": True,
            "cross_stack_exact_pixels_required": False, "rgb_pixel_sha256": image_hashes, "comparisons": checks}

def run(args):
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()): raise ValueError("Use a fresh output directory")
    output.mkdir(parents=True, exist_ok=True)
    prepared = args.prepared.resolve(); cp = (prepared / "runtime-classpath.txt").read_text().strip()
    guard = json.loads((prepared / "prepared.json").read_text())
    if source_hashes() != guard["source_hashes"] or binary_hashes(cp, prepared) != guard["binary_hashes"]: raise RuntimeError("Prepare again: sources or binaries changed")
    lock = ROOT / "build/performance-benchmark.lock"; lock.mkdir()
    xvfb = None
    try:
        # Dedicated display; all actual rendering is CPU raster into offscreen images.
        display_file = output / "xvfb-display.txt"
        with display_file.open("w") as display, (output / "xvfb.log").open("w") as log:
            xvfb = subprocess.Popen(["Xvfb", "-displayfd", str(display.fileno()), "-screen", "0", "1920x1080x24", "-nolisten", "tcp"], pass_fds=(display.fileno(),), stdout=log, stderr=log)
        for _ in range(100):
            if display_file.read_text().strip(): break
            if xvfb.poll() is not None: raise RuntimeError("Xvfb failed")
            time.sleep(.05)
        os.environ["DISPLAY"] = ':' + display_file.read_text().strip()
        raw, orders = [], []
        # All six baseline permutations, cycling if more forks are requested.
        import itertools
        permutations = list(itertools.permutations(STACKS))
        for fork in range(1, args.forks + 1):
            order = permutations[(fork - 1) % len(permutations)]
            rotated = CASES[(fork - 1) % len(CASES):] + CASES[:(fork - 1) % len(CASES)]
            orders.append(list(order))
            for stack in order:
                print(f"Fork {fork}/{args.forks}: {stack}", flush=True)
                raw.append(run_one(stack, fork, args, cp, output, rotated))
        if source_hashes() != guard["source_hashes"] or binary_hashes(cp, prepared) != guard["binary_hashes"]: raise RuntimeError("Source/binary changed during run")
        # Pixel auditing is outside all timed forks and includes all final outputs.
        validation = validate_images(output, args.forks)
        manifest = {"schema": 1, "created_utc": datetime.now(timezone.utc).isoformat(),
            "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "platform": subprocess.check_output(["uname", "-srmo"], text=True).strip(),
            "cpu": json.loads(subprocess.check_output(["lscpu", "-J"], text=True)),
            "compiler": subprocess.check_output(["c++", "--version"], text=True).splitlines()[0],
            "electron_fixture": "Trusted local HTML; Node enabled for monotonic hrtime; browser sandbox disabled for fixture only",
            "clocks": {"byteink": "System.nanoTime", "qt6": "QElapsedTimer.nsecsElapsed", "electron": "process.hrtime.bigint"},
            "ram_bytes": os.sysconf("SC_PHYS_PAGES") * os.sysconf("SC_PAGE_SIZE"),
            "power_profile": Path("/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor").read_text().strip(),
            "forks": args.forks, "warmup_frames_per_case": args.warmup, "measured_frames_per_case": args.measured,
            "case_definitions": CASES, "stack_orders": orders, "source_binary_guards_passed": True,
            "memory_scope": "Linux process tree smaps_rollup checkpoint; includes host/runtime/PNG allocations; not peak memory",
            "frame_scope": "CPU full white clear + cached uniform-color closed outline fills; Electron includes one-pixel synchronization readback",
            "unmeasured": ["Windows", "GPU drawing", "native input", "stroke modeling time", "compositor/display completion", "physical pen latency"],
            "provenance": guard}
        (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        (output / "raw.json").write_text(json.dumps(raw, indent=2) + "\n")
        (output / "validation.json").write_text(json.dumps(validation, indent=2) + "\n")
        (output / "summary.json").write_text(json.dumps(summarize(raw), indent=2) + "\n")
        print(f"Completed {output}", flush=True)
    finally:
        if xvfb is not None: xvfb.terminate(); xvfb.wait(timeout=10)
        lock.rmdir()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--prepared", type=Path, default=ROOT / "build/toolkit-comparison/prepared")
    parser.add_argument("--output", type=Path, default=ROOT / "build/toolkit-comparison/results")
    parser.add_argument("--prepare", action="store_true")
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--forks", type=int, default=6)
    parser.add_argument("--warmup", type=int, default=40)
    parser.add_argument("--measured", type=int, default=30)
    args = parser.parse_args()
    if args.forks < 1 or args.warmup < 0 or args.measured < 1: parser.error("Invalid sample counts")
    if not args.prepare and not args.run: parser.error("Select --prepare and/or --run")
    if args.prepare: prepare(args)
    if args.run: run(args)

if __name__ == "__main__": main()
