#!/usr/bin/env python3
"""Compare full versus retained software authoring in serial, fresh JVM forks.

Supply a classpath produced by run_pen.py. This runner never invokes Gradle. To
measure the reviewed baseline, use its frozen classpath and --modes full. A final
retained build uses --modes full,retained and compares every output frame against
a separate full-render oracle outside timing. Offscreen software Swing painting
includes full-window transfer but excludes AWT/compositor/physical pen latency.
"""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "benchmarks/pen/PenRetainedAuthoringBenchmark.java"


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inventory(classpath):
    result = {}
    for item in classpath.split(os.pathsep):
        path = Path(item)
        if path.is_file():
            result[str(path)] = digest(path)
        elif path.is_dir():
            for child in sorted(path.rglob("*")):
                if child.is_file():
                    result[str(child)] = digest(child)
    return result


def source_inventory():
    result = {str(HARNESS.relative_to(ROOT)): digest(HARNESS), str(Path(__file__).relative_to(ROOT)): digest(Path(__file__))}
    for module in ("byteink-compose", "byteink-core", "byteink-kit", "ink-nativeloader"):
        for path in (ROOT / module / "src/main").rglob("*"):
            if path.is_file():
                result[str(path.relative_to(ROOT))] = digest(path)
    return dict(sorted(result.items()))


def execute(command, log):
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {log}\n{log.read_text()[-6000:]}")


def percentile(values, fraction=.95):
    return sorted(values)[max(0, math.ceil(len(values) * fraction) - 1)]


def across_forks(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "fork_values": values}


def summarize(forks):
    first = forks[0]
    for report in forks:
        if report["status"] != "passed" or report["cases"].keys() != first["cases"].keys():
            raise ValueError("Workload failed or case inventory changed across forks")
        if report["runtime"] != first["runtime"]:
            raise ValueError("Runtime changed across forks")
        if report["correctness"] != first["correctness"]:
            raise ValueError("Output fingerprint changed across forks")
    result = {"forks": len(forks), "runtime": first["runtime"], "correctness": first["correctness"], "cases": {}}
    for name, initial in first["cases"].items():
        structural = {key: value for key, value in initial.items() if key not in ("samples", "initial_draw_samples")}
        if any({key: value for key, value in report["cases"][name].items() if key not in ("samples", "initial_draw_samples")} != structural for report in forks):
            raise ValueError(f"Structural counts changed between forks: {name}")
        metrics = {}
        for measure in initial["samples"]:
            samples = [report["cases"][name]["samples"][measure] for report in forks]
            metrics[measure] = {"per_frame_median": across_forks([statistics.median(x) for x in samples]),
                                "per_frame_p95": across_forks([percentile(x) for x in samples])}
        initial_metrics = {}
        for measure in initial.get("initial_draw_samples", {}):
            initial_metrics[measure] = across_forks([report["cases"][name]["initial_draw_samples"][measure][0] for report in forks])
        result["cases"][name] = dict(structural, metrics=metrics, initial_draw_metrics=initial_metrics)
    return result


def comparison(before, after):
    result = {"runtime_equal": before["runtime"] == after["runtime"], "cases": {}, "fingerprint_equal": {}}
    for key in before["correctness"].keys() & after["correctness"].keys():
        before_frame = {k: v for k, v in before["correctness"][key].items() if k != "full_vs_retained_exact"}
        after_frame = {k: v for k, v in after["correctness"][key].items() if k != "full_vs_retained_exact"}
        result["fingerprint_equal"][key] = before_frame == after_frame
    for name in before["cases"].keys() & after["cases"].keys():
        old, new = before["cases"][name], after["cases"][name]
        metrics = {}
        for measure in old["metrics"].keys() & new["metrics"].keys():
            metrics[measure] = {}
            for statistic in ("per_frame_median", "per_frame_p95"):
                b, a = old["metrics"][measure][statistic], new["metrics"][measure][statistic]
                metrics[measure][statistic] = {"before": b["median"], "after": a["median"],
                    "after_over_before": a["median"] / b["median"] if b["median"] else None,
                    "fork_ranges_overlap": not (a["max"] < b["min"] or b["max"] < a["min"])}
        result["cases"][name] = metrics
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--classpath", required=True, type=Path, help="Classpath text file; no Gradle tasks are run")
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup-cycles", type=int, default=2)
    parser.add_argument("--measured-cycles", type=int, default=3)
    parser.add_argument("--scene-strokes", type=int, default=48, help="Number of genuine finished strokes in the static page; zero is allowed")
    parser.add_argument("--scene-inputs", type=int, default=128, help="Real inputs per finished page stroke")
    parser.add_argument("--live-inputs", type=int, default=8192, help="Real observations per live gesture; smaller counts follow a prefix of the 8192-observation path")
    parser.add_argument("--inputs-per-frame", type=int, default=128)
    parser.add_argument("--no-paper-grid", action="store_true", help="Omit ruled paper lines; with zero scene strokes the callback is truly empty")
    parser.add_argument("--workloads", choices=("all", "1080p", "4k", "2x", "1080p,4k"), default="all")
    parser.add_argument("--modes", choices=("full", "retained", "full,retained"), default="full,retained")
    parser.add_argument("--suites", choices=("raster", "swing", "raster,swing"), default="raster,swing")
    parser.add_argument("--compare", type=Path)
    args = parser.parse_args()
    if args.forks < 1 or args.warmup_cycles < 0 or args.measured_cycles < 1 or args.scene_strokes < 0 or args.scene_inputs < 1:
        parser.error("Positive fork/measured/scene-input counts and nonnegative warmup/scene-strokes are required")
    if args.live_inputs < 1 or args.inputs_per_frame < 1 or args.live_inputs % args.inputs_per_frame:
        parser.error("Positive live counts are required; live-inputs must be divisible by inputs-per-frame")
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
        binary_before = inventory(cp)
        source_before = source_inventory()
        java = args.java_home.resolve() / "bin/java"
        javac = args.java_home.resolve() / "bin/javac"
        classes = output / "harness-classes"
        classes.mkdir()
        (output / "runtime-classpath.txt").write_text(cp + "\n")
        execute([str(javac), "-nowarn", "-cp", cp, "-d", str(classes), str(HARNESS)], output / "javac.log")
        command = [str(java), "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Xms512m", "-Xmx2g", "-XX:+UseG1GC",
                   f"-Dskiko.data.path={output / 'skiko-cache'}", f"-Dbyteink.retained.warmupCycles={args.warmup_cycles}",
                   f"-Dbyteink.retained.measuredCycles={args.measured_cycles}", f"-Dbyteink.retained.sceneStrokes={args.scene_strokes}",
                   f"-Dbyteink.retained.sceneInputs={args.scene_inputs}", f"-Dbyteink.retained.liveInputs={args.live_inputs}",
                   f"-Dbyteink.retained.inputsPerFrame={args.inputs_per_frame}", f"-Dbyteink.retained.noPaperGrid={str(args.no_paper_grid).lower()}",
                   f"-Dbyteink.retained.workloads={args.workloads}", f"-Dbyteink.retained.modes={args.modes}",
                   f"-Dbyteink.retained.suites={args.suites}", "-cp", str(classes) + os.pathsep + cp,
                   "PenRetainedAuthoringBenchmark"]
        (output / "start-provenance.json").write_text(json.dumps({"command": command, "classpath_source": str(args.classpath.resolve()),
            "source_files_observed_during_run": source_before, "binary_files": binary_before,
            "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}, indent=2) + "\n")
        forks = []
        for fork in range(1, args.forks + 1):
            print(f"Running retained authoring fork {fork}/{args.forks}", flush=True)
            path = output / f"fork-{fork}.json"
            # The per-fork cache gives independent native extraction and records the shipped bytes.
            command_with_native = command.copy()
            command_with_native.insert(1, f"-Dbyteink.ink.cache={output / f'native-cache-{fork}'}")
            execute(command_with_native + [str(path)], output / f"fork-{fork}.log")
            forks.append(json.loads(path.read_text()))
        if inventory(cp) != binary_before or source_inventory() != source_before:
            raise RuntimeError("Production/harness sources or runtime binaries changed during timing; discard run")
        summary = summarize(forks)
        native_files = {str(path.relative_to(output)): digest(path) for path in output.rglob("*")
                        if path.is_file() and (path.name.endswith(".so") or path.name.endswith(".dll"))}
        summary["provenance"] = {"created_utc": datetime.now(timezone.utc).isoformat(),
            "command": command, "java_home": str(args.java_home.resolve()), "binary_files": binary_before,
            "source_files_observed_during_run": source_before, "extracted_native_files": native_files,
            "classpath_source": str(args.classpath.resolve()),
            "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
            "git_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())}
        frozen_inventory = args.classpath.resolve().parent / "inventory.json"
        if frozen_inventory.is_file():
            summary["provenance"]["frozen_classpath_inventory"] = json.loads(frozen_inventory.read_text())
            summary["provenance"]["frozen_classpath_inventory_sha256"] = digest(frozen_inventory)
        if args.modes == "full,retained":
            for key, case in summary["cases"].items():
                if key.endswith("/full"):
                    retained = summary["cases"][key[:-len("full")] + "retained"]
                    if case["final_pixel_sha256"] != retained["final_pixel_sha256"]:
                        raise ValueError(f"Final full/retained painter pixels differ: {key}")
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        if args.compare:
            before = json.loads(args.compare.read_text())
            (output / "comparison.json").write_text(json.dumps(comparison(before, summary), indent=2) + "\n")
        if args.modes == "full,retained":
            paired_before = dict(summary, cases={key: value for key, value in summary["cases"].items() if key.endswith("/full")})
            paired_after = dict(summary, cases={key[:-len("retained")] + "full": value for key, value in summary["cases"].items() if key.endswith("/retained")})
            (output / "paired-comparison.json").write_text(json.dumps(comparison(paired_before, paired_after), indent=2) + "\n")
        print(f"Saved {output / 'summary.json'}", flush=True)
    finally:
        lock.rmdir()


if __name__ == "__main__":
    main()
