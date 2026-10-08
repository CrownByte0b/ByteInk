#!/usr/bin/env python3
"""Run isolated Part 5 CPU preparation experiments against a frozen production runtime.

Requires JDK 25 and a resolved classpath; never builds/edits the library. The Java
agent keeps the original loop as a reference and substitutes only pure preparation
for candidates. Vector API/module flags apply exclusively to benchmark processes.
Each fresh serial JVM rotates candidate order and verifies raw arrays and complete
software Swing pixels. Raw samples, sources, hashes, commands and gates are saved.
"""

import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

from run_wet_mesh import ROOT, binaries, distribution, execute, percentile, sha, sources
import statistics

HARNESS = [ROOT / "benchmarks/pen" / name for name in (
    "WetMeshBenchmark.java", "PreparationAgent.java", "MeshPreparation.java",
    "VectorPreparation.java", "CpuPreparationBenchmark.java")]


def summarize(forks):
    first = forks[0]
    def control_identity(fork):
        controls = dict(fork["controls"])
        # Synthetic raw NaN results are diagnostic evidence, not an acceptance tolerance.
        # Keep native finite output identity strict; merge every diagnostic rejection below.
        controls["arrays"] = {k: v for k, v in controls["arrays"].items()
                              if k not in ("candidate_fidelity", "expected_output_sha256")}
        return controls
    for fork in forks:
        if fork["status"] != "completed" or not fork["all_workers_terminated"]:
            raise ValueError("Failed controls or leaked workers")
        if control_identity(fork) != control_identity(first) or fork["cases"].keys() != first["cases"].keys():
            raise ValueError("Raw array/mesh/pixel controls or case inventory changed between forks")
        if {k: v for k, v in fork["runtime"].items() if k != "candidate_order"} != {
                k: v for k, v in first["runtime"].items() if k != "candidate_order"}:
            raise ValueError("Runtime or timing boundary changed between forks")
    fidelity = {mode: {"exact_in_all_forks": all(f["controls"]["arrays"]["candidate_fidelity"][mode]["exact"] for f in forks),
                       "fork_results": [f["controls"]["arrays"]["candidate_fidelity"][mode] for f in forks]}
                for mode in ("scalar", "vector", "workers")}
    result = {"forks": len(forks), "runtime": first["runtime"], "controls": control_identity(first),
              "synthetic_reference_raw_hashes": [f["controls"]["arrays"]["expected_output_sha256"] for f in forks],
              "candidate_numeric_fidelity": fidelity,
              "candidate_orders": [f["runtime"]["candidate_order"] for f in forks], "cases": {}}
    for name, case in first["cases"].items():
        structure = {k: v for k, v in case.items() if k != "variants"}
        if any({k: v for k, v in fork["cases"][name].items() if k != "variants"} != structure for fork in forks):
            raise ValueError(f"Structural workload changed: {name}")
        result["cases"][name] = dict(structure, variants={})
        for mode, variant in case["variants"].items():
            phase_samples = {"prepare": variant["samples"]} if case["kind"] == "pure_loop" else variant["phases"]
            phases = {}
            for phase, measurements in phase_samples.items():
                phases[phase] = {}
                for measure in measurements:
                    samples = [(f["cases"][name]["variants"][mode]["samples"] if case["kind"] == "pure_loop"
                                else f["cases"][name]["variants"][mode]["phases"][phase])[measure] for f in forks]
                    if any(not values or min(values) < 0 for values in samples):
                        raise ValueError(f"Missing/negative counter: {name}/{mode}/{phase}/{measure}")
                    phases[phase][measure] = {
                        "fork_medians": distribution([statistics.median(values) for values in samples]),
                        "fork_p95": distribution([percentile(values) for values in samples]),
                        "samples_per_fork": [len(values) for values in samples],
                    }
            retained = {key: [f["cases"][name]["variants"][mode][key] for f in forks]
                        for key in variant if key not in ("samples", "phases")}
            result["cases"][name]["variants"][mode] = {"phases": phases, "costs_per_fork": retained}
    return result


def comparison(summary):
    result = {}
    for name, case in summary["cases"].items():
        original = case["variants"]["original"]["phases"]
        result[name] = {}
        for mode, variant in case["variants"].items():
            if mode == "original":
                continue
            result[name][mode] = {}
            for phase, measurements in variant["phases"].items():
                result[name][mode][phase] = {}
                for measure, statistics_ in measurements.items():
                    entry = {}
                    for stat in ("fork_medians", "fork_p95"):
                        before, after = original[phase][measure][stat], statistics_[stat]
                        entry[stat] = {"before": before["median"], "after": after["median"],
                                      "after_over_before": after["median"] / before["median"] if before["median"] else None,
                                      "before_range": [before["min"], before["max"]],
                                      "after_range": [after["min"], after["max"]],
                                      "candidate_faster_outside_fork_range": after["max"] < before["min"]}
                    result[name][mode][phase][measure] = entry
    return result


def gates(comparisons, summary):
    result = {"minimum_forks": 3, "loop_gain_required": .20, "complete_paint_gain_required": .05,
              "windows_runtime_verified": False, "production_algorithm_promoted": False, "candidates": {}}
    for mode in ("scalar", "vector", "workers"):
        loop = comparisons["full_6144"][mode]["prepare"]["wall_ns"]["fork_medians"]
        paints = {name: case[mode]["changed_paint"]["wall_ns"]["fork_medians"]
                  for name, case in comparisons.items() if "changed_paint" in case[mode]}
        loop_pass = loop["after_over_before"] <= .8 and loop["candidate_faster_outside_fork_range"]
        paint_pass = [name for name, values in paints.items() if values["after_over_before"] <= .95
                      and values["candidate_faster_outside_fork_range"]]
        result["candidates"][mode] = {"large_loop_gate_passed": loop_pass,
                                      "complete_paint_cases_passing_speed_gate": paint_pass,
                                      "speed_gates_passed": summary["forks"] >= 3 and loop_pass and bool(paint_pass),
                                      "exact_numeric_fidelity_passed": summary["candidate_numeric_fidelity"][mode]["exact_in_all_forks"],
                                      "promotion_requires": "All fidelity/lifecycle checks, no small-case regression, supported-platform fallback/runtime verification, matching real paint workload. Passing isolated speed gates alone is insufficient."}
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--classpath", required=True, type=Path)
    parser.add_argument("--java-home", required=True, type=Path)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--forks", type=int, default=3)
    parser.add_argument("--warmup", type=int, default=500)
    parser.add_argument("--measured", type=int, default=200)
    parser.add_argument("--warmup-cycles", type=int, default=4)
    parser.add_argument("--measured-cycles", type=int, default=8)
    parser.add_argument("--profile", action="store_true", help="Additional JFR fork, excluded from all timing aggregates")
    args = parser.parse_args()
    if min(args.forks, args.measured, args.measured_cycles) < 1 or min(args.warmup, args.warmup_cycles) < 0:
        parser.error("Positive forks/samples and nonnegative warmup required")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Output must be fresh")
    output.mkdir(parents=True, exist_ok=True)
    lock = ROOT / "build/performance-benchmark.lock"
    lock.parent.mkdir(exist_ok=True)
    try:
        lock.mkdir()
    except FileExistsError:
        parser.error(f"Another workload owns {lock}; serialize builds and measurements")
    try:
        cp = args.classpath.resolve().read_text().strip()
        java_home = args.java_home.resolve()
        java, javac = java_home / "bin/java", java_home / "bin/javac"
        classes = output / "harness-classes"
        classes.mkdir()
        source_root = args.source_root.resolve()
        production_sources, binary_hashes = sources(source_root), binaries(cp)
        tooling_hashes = {str(path.relative_to(ROOT)): sha(path) for path in HARNESS + [Path(__file__).resolve(), ROOT / "benchmarks/run_wet_mesh.py"]}
        runtime_hashes = {name: sha(java_home / name) for name in ("bin/java", "bin/javac", "lib/modules", "lib/server/libjvm.so")}
        for name in production_sources:
            target = output / "source-snapshots" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source_root / name, target)
        for name in tooling_hashes:
            target = output / "source-snapshots" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(ROOT / name, target)
        execute([str(javac), "--add-modules", "jdk.incubator.vector", "-cp", cp, "-d", str(classes), *map(str, HARNESS)], output / "javac.log")
        agent = output / "preparation-agent.jar"
        with zipfile.ZipFile(agent, "w") as archive:
            archive.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\nPremain-Class: PreparationAgent\n\n")
            for path in sorted(classes.glob("PreparationAgent*.class")):
                archive.write(path, path.name)
        common = [str(java), "--add-modules", "jdk.incubator.vector", "--enable-native-access=ALL-UNNAMED", f"-javaagent:{agent}",
                  "-Djava.awt.headless=true", "-Xms512m", "-Xmx2g", "-XX:+UseG1GC",
                  f"-Dskiko.data.path={output / 'skiko-cache'}", f"-Dbyteink.cpu.warmup={args.warmup}",
                  f"-Dbyteink.cpu.measured={args.measured}", f"-Dbyteink.cpu.warmupCycles={args.warmup_cycles}",
                  f"-Dbyteink.cpu.measuredCycles={args.measured_cycles}", "-cp", str(classes) + os.pathsep + cp, "CpuPreparationBenchmark"]
        provenance = {"created_utc": datetime.now(timezone.utc).isoformat(), "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                      "java_home": str(java_home), "classpath": cp, "source_root": str(source_root),
                      "production_sources": production_sources, "binaries": binary_hashes, "tooling_sources": tooling_hashes,
                      "jdk_files": runtime_hashes, "agent_sha256": sha(agent)}
        (output / "start-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
        forks, commands = [], []
        for index in range(args.forks):
            print(f"CPU preparation fork {index + 1}/{args.forks}", flush=True)
            report = output / f"fork-{index + 1}.json"
            command = common.copy()
            command[1:1] = [f"-Dbyteink.cpu.fork={index}", f"-Dbyteink.ink.cache={output / f'native-cache-{index + 1}'}"]
            command.append(str(report))
            commands.append(command)
            execute(command, output / f"fork-{index + 1}.log")
            forks.append(json.loads(report.read_text()))
        if args.profile:
            command = common.copy()
            command[1:1] = [f"-XX:StartFlightRecording=settings=profile,filename={output / 'profile.jfr'},dumponexit=true",
                            f"-Dbyteink.ink.cache={output / 'native-cache-profile'}"]
            command.append(str(output / "profile.json"))
            commands.append(command)
            execute(command, output / "profile.log")
        if binaries(cp) != binary_hashes or sources(source_root) != production_sources or any(sha(ROOT / n) != h for n, h in tooling_hashes.items()):
            raise RuntimeError("Source/binary/tooling changed during timing; discard run")
        if any(sha(java_home / n) != h for n, h in runtime_hashes.items()):
            raise RuntimeError("JDK changed during timing; discard run")
        summary = summarize(forks)
        comparisons = comparison(summary)
        gate_result = gates(comparisons, summary)
        summary["provenance"] = dict(provenance, guards_passed=True,
                                     native_files={str(p.relative_to(output)): sha(p) for p in output.rglob("*.so")})
        for name, value in (("summary", summary), ("comparison", comparisons), ("gates", gate_result), ("commands", commands)):
            (output / f"{name}.json").write_text(json.dumps(value, indent=2) + "\n")
        print(f"Saved {output / 'summary.json'}", flush=True)
    finally:
        lock.rmdir()


if __name__ == "__main__":
    main()
