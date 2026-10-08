#!/usr/bin/env python3
"""Profile real large wet meshes and complete raster draws in serial fresh JBR forks.

This runner never builds the library. Supply a resolved runtime classpath and
serialize against Gradle/native work. Freeze the baseline classpath first. Use
--source-root baseline/source-snapshots to associate frozen runtime binaries with
their corresponding production sources while implementation proceeds elsewhere.
All exact mesh/prepared/pixel hashes and complete-draw samples remain in raw forks.
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
HARNESS = ROOT / "benchmarks/pen/WetMeshBenchmark.java"
MEASURES = ("wall_ns", "thread_cpu_ns", "jvm_allocated_bytes")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def binaries(classpath):
    result = {}
    for entry in classpath.split(os.pathsep):
        path = Path(entry)
        if path.is_file():
            result[str(path.resolve())] = sha(path)
        elif path.is_dir():
            for child in sorted(path.rglob("*")):
                if child.is_file():
                    result[str(child.resolve())] = sha(child)
        else:
            raise ValueError(f"Missing classpath entry: {path}")
    return result


def sources(source_root):
    result = {}
    for module in ("byteink-compose", "byteink-core", "byteink-kit", "ink-nativeloader"):
        for path in sorted((source_root / module / "src/main").rglob("*")):
            if path.is_file():
                result[str(path.relative_to(source_root))] = sha(path)
    if not result:
        raise ValueError(f"No production sources found under {source_root}")
    return result


def execute(command, log):
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {log}\n{log.read_text()[-6000:]}")


def percentile(samples):
    return sorted(samples)[max(0, math.ceil(len(samples) * .95) - 1)]


def distribution(samples):
    return {"median": statistics.median(samples), "min": min(samples), "max": max(samples), "fork_values": samples}


def summarize(forks):
    first = forks[0]
    for fork in forks:
        if fork["status"] != "passed" or fork["runtime"] != first["runtime"] or fork["cases"].keys() != first["cases"].keys():
            raise ValueError("Runtime/status/case inventory changed across forks")
        if fork["correctness"] != first["correctness"]:
            raise ValueError("Native geometry, complete prepared data, input or exact pixel hashes changed across forks")
    result = {"forks": len(forks), "runtime": first["runtime"], "correctness": first["correctness"], "cases": {}}
    for name, initial in first["cases"].items():
        structure = {key: value for key, value in initial.items() if key != "samples"}
        for fork in forks:
            if {key: value for key, value in fork["cases"][name].items() if key != "samples"} != structure:
                raise ValueError(f"Structural counts or retained resources changed across forks: {name}")
        metrics = {}
        for phase in initial["samples"]:
            metrics[phase] = {}
            for measure in MEASURES:
                samples = [fork["cases"][name]["samples"][phase][measure] for fork in forks]
                metrics[phase][measure] = {"per_draw_median": distribution([statistics.median(x) for x in samples]),
                                           "per_draw_p95": distribution([percentile(x) for x in samples]),
                                           "all_sample_min": min(min(x) for x in samples), "all_sample_max": max(max(x) for x in samples)}
        result["cases"][name] = dict(structure, metrics=metrics)
    return result


def compare(before, after):
    result = {"runtime_equal": before["runtime"] == after["runtime"],
              "correctness_equal": before["correctness"] == after["correctness"], "cases": {}}
    for name in before["cases"].keys() & after["cases"].keys():
        old, new = before["cases"][name], after["cases"][name]
        metrics = {}
        for phase in old["metrics"].keys() & new["metrics"].keys():
            metrics[phase] = {}
            for measure in MEASURES:
                metrics[phase][measure] = {}
                for statistic in ("per_draw_median", "per_draw_p95"):
                    b, a = old["metrics"][phase][measure][statistic], new["metrics"][phase][measure][statistic]
                    metrics[phase][measure][statistic] = {"before": b["median"], "after": a["median"],
                        "after_over_before": a["median"] / b["median"] if b["median"] else None,
                        "fork_ranges_overlap": not (a["max"] < b["min"] or b["max"] < a["min"]),
                        "before_range": [b["min"], b["max"]], "after_range": [a["min"], a["max"]]}
        result["cases"][name] = {"metrics": metrics,
            "retained_cost_before": {key: value for key, value in old.items() if "retained" in key},
            "retained_cost_after": {key: value for key, value in new.items() if "retained" in key}}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--classpath", type=Path, required=True)
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=30)
    parser.add_argument("--measured", type=int, default=15)
    parser.add_argument("--warmup-cycles", type=int, default=6)
    parser.add_argument("--measured-cycles", type=int, default=3)
    parser.add_argument("--compare", type=Path)
    parser.add_argument("--profile", action="store_true", help="Additional untimed JFR fork")
    args = parser.parse_args()
    if min(args.forks, args.measured, args.measured_cycles) < 1 or min(args.warmup, args.warmup_cycles) < 0:
        parser.error("Positive fork/measured counts and nonnegative warmup counts required")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Output must be fresh")
    output.mkdir(parents=True, exist_ok=True)
    lock = ROOT / "build/performance-benchmark.lock"
    lock.parent.mkdir(exist_ok=True)
    try:
        lock.mkdir()
    except FileExistsError:
        parser.error(f"Another workload owns {lock}; serialize timing and build jobs")
    try:
        cp = args.classpath.resolve().read_text().strip()
        binary_before = binaries(cp)
        source_root = args.source_root.resolve()
        source_before = sources(source_root)
        harness_before = {str(path.relative_to(ROOT)): sha(path) for path in (HARNESS, Path(__file__).resolve())}
        source_copy = output / "source-snapshots"
        import shutil
        for name in source_before:
            target = source_copy / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_root / name, target)
        for path in (HARNESS, Path(__file__).resolve()):
            target = source_copy / path.relative_to(ROOT)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)
        java_home = args.java_home.resolve()
        java, javac = java_home / "bin/java", java_home / "bin/javac"
        classes = output / "harness-classes"
        classes.mkdir()
        (output / "runtime-classpath.txt").write_text(cp + "\n")
        execute([str(javac), "-nowarn", "-cp", cp, "-d", str(classes), str(HARNESS)], output / "javac.log")
        command = [str(java), "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true", "-Xms512m", "-Xmx2g", "-XX:+UseG1GC",
            f"-Dskiko.data.path={output / 'skiko-cache'}", f"-Dbyteink.wet.warmup={args.warmup}", f"-Dbyteink.wet.measured={args.measured}",
            f"-Dbyteink.wet.warmupCycles={args.warmup_cycles}", f"-Dbyteink.wet.measuredCycles={args.measured_cycles}",
            "-cp", str(classes) + os.pathsep + cp, "WetMeshBenchmark"]
        provenance = {"classpath_source": str(args.classpath.resolve()), "source_root": str(source_root), "command": command,
            "binary_files": binary_before, "production_source_files": source_before, "harness_source_files": harness_before,
            "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}
        (output / "start-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        forks = []
        commands = []
        for fork in range(1, args.forks + 1):
            print(f"Running wet mesh fork {fork}/{args.forks}", flush=True)
            report = output / f"fork-{fork}.json"
            call = command.copy()
            call.insert(1, f"-Dbyteink.ink.cache={output / f'native-cache-{fork}'}")
            call.append(str(report))
            commands.append(call)
            execute(call, output / f"fork-{fork}.log")
            forks.append(json.loads(report.read_text()))
        if args.profile:
            call = command.copy()
            call.insert(1, f"-XX:StartFlightRecording=settings=profile,filename={output / 'profile.jfr'},dumponexit=true")
            call.insert(1, f"-Dbyteink.ink.cache={output / 'native-cache-profile'}")
            call.append(str(output / "profile.json"))
            commands.append(call)
            execute(call, output / "profile.log")
        if binaries(cp) != binary_before or sources(source_root) != source_before or any(sha(ROOT / name) != value for name, value in harness_before.items()):
            raise RuntimeError("Selected runtime/production source snapshot/harness changed during timing; discard run")
        summary = summarize(forks)
        summary["provenance"] = dict(provenance, created_utc=datetime.now(timezone.utc).isoformat(), java_home=str(java_home),
            extracted_native_files={str(p.relative_to(output)): sha(p) for p in output.rglob("*.so")}, source_binary_guards_passed=True)
        (output / "commands.json").write_text(json.dumps(commands, indent=2) + "\n")
        (output / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
        if args.compare:
            comparison = compare(json.loads(args.compare.read_text()), summary)
            (output / "comparison.json").write_text(json.dumps(comparison, indent=2) + "\n")
            if not comparison["runtime_equal"] or not comparison["correctness_equal"]:
                raise RuntimeError("Runtime or exact before/after geometry/prepared/pixel controls changed")
        print(f"Saved {output / 'summary.json'}", flush=True)
    finally:
        lock.rmdir()


if __name__ == "__main__":
    main()
