#!/usr/bin/env python3
"""Measure session bursts, mesh preparation and software paint in serial fresh JVMs."""

import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "benchmarks/pen"
MEASUREMENTS = ("wall_ns", "thread_cpu_ns", "jvm_allocated_bytes")


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def sources():
    result = {}
    for module in ("byteink-compose", "byteink-core", "byteink-kit", "ink-nativeloader"):
        for path in (ROOT / module / "src").rglob("*"):
            if path.is_file() and not any("test" in part.lower() for part in path.relative_to(ROOT).parts):
                result[str(path.relative_to(ROOT))] = sha(path)
    for path in [Path(__file__).resolve(), *HARNESS.glob("*.java")]:
        result[str(path.relative_to(ROOT))] = sha(path)
    return dict(sorted(result.items()))


def binaries(classpath):
    result = {}
    for value in classpath.split(os.pathsep):
        path = Path(value)
        if path.is_file():
            result[str(path.resolve())] = sha(path)
        elif path.is_dir():
            for file in path.rglob("*"):
                if file.is_file():
                    result[str(file.resolve())] = sha(file)
    return dict(sorted(result.items()))


def execute(command, log):
    with log.open("w") as stream:
        result = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
    if result.returncode:
        raise SystemExit(f"Command failed ({result.returncode}); see {log}\n{log.read_text()[-6000:]}")


def runtime_classpath(output, args):
    if args.classpath:
        return args.classpath.resolve().read_text().strip()
    # The temporary task uses Gradle's resolved test classpath, including project outputs/native jars.
    # It does not change a build script or require a separately maintained dependency inventory.
    init = output / "classpath.gradle"
    target = output / "runtime-classpath.txt"
    init.write_text("""gradle.projectsEvaluated {
    def inkProject = gradle.rootProject.findProject(':byteink-compose')
    if (inkProject == null) return
    inkProject.tasks.register('penBenchmarkClasspath') {
        dependsOn inkProject.tasks.named('testClasses')
        doLast {
            def output = new File(inkProject.findProperty('byteinkPenClasspathOutput'))
            output.text = inkProject.extensions.getByName('sourceSets').getByName('test').runtimeClasspath.asPath
        }
    }
}
""")
    command = [str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")), "--no-daemon", "--max-workers=1",
               "--console=plain", "--no-configuration-cache", "--init-script", str(init)]
    if not args.online:
        command.append("--offline")
    command.extend([":byteink-compose:penBenchmarkClasspath", f"-PbyteinkPenClasspathOutput={target}"])
    execute(command, output / "classpath.log")
    return target.read_text().strip()


def distribution(values):
    return {"median": statistics.median(values), "min": min(values), "max": max(values), "fork_values": values}


def summarize(forks):
    first = forks[0]
    names = set(first["cases"])
    for fork in forks:
        if fork["status"] != "passed" or set(fork["cases"]) != names:
            raise ValueError("Workload failed or case inventory changed across forks")
        if any(fork[key] != first[key] for key in ("java", "vendor", "timing_scope", "allocation_scope")):
            raise ValueError("Runtime or measurement scope changed across forks")
    merged = {}
    for name in sorted(names):
        template = first["cases"][name]
        phases = [key for key, value in template.items() if isinstance(value, dict)]
        structure = {key: value for key, value in template.items() if key not in phases}
        if any({key: value for key, value in fork["cases"][name].items() if key not in phases} != structure for fork in forks):
            raise ValueError(f"Structural count or prepared-data hash changed between forks: {name}")
        merged[name] = structure
        for phase in phases:
            merged[name][phase] = {measure: distribution([
                statistics.median(fork["cases"][name][phase][measure]) for fork in forks
            ]) for measure in MEASUREMENTS}
    return {"fork_count": len(forks), "runtime": {key: first[key] for key in
            ("java", "vendor", "timing_scope", "allocation_scope")}, "cases": merged}


def compare(before, after):
    results = {"runtime_changes": [key for key in before["runtime"] if before["runtime"][key] != after["runtime"].get(key)],
               "added_cases": sorted(set(after["cases"]) - set(before["cases"])),
               "removed_cases": sorted(set(before["cases"]) - set(after["cases"])), "cases": {}}
    for name in sorted(set(before["cases"]) & set(after["cases"])):
        old, new = before["cases"][name], after["cases"][name]
        phases = [key for key, value in old.items() if isinstance(value, dict)]
        changed = [key for key, value in old.items() if key not in phases and new.get(key) != value]
        entry = {"structure_changes": changed}
        for phase in phases:
            if phase not in new:
                entry.setdefault("missing_phases", []).append(phase)
                continue
            entry[phase] = {}
            for measure in MEASUREMENTS:
                b, a = old[phase][measure]["median"], new[phase][measure]["median"]
                entry[phase][measure] = {"before": b, "after": a, "after_over_before": a / b if b else None,
                    "fork_ranges_overlap": not (old[phase][measure]["max"] < new[phase][measure]["min"] or
                                                new[phase][measure]["max"] < old[phase][measure]["min"])}
        results["cases"][name] = entry
    return results


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path, help="Fresh output directory")
    parser.add_argument("--java-home", required=True, type=Path, help="JDK used for harness javac and runtime; use pinned JBR25")
    parser.add_argument("--forks", default=3, type=int)
    parser.add_argument("--classpath", type=Path, help="Existing classpath text file; skips Gradle compilation")
    parser.add_argument("--suite", choices=("session", "render", "queue", "both", "all"), default="both")
    parser.add_argument("--session-warmup", default=10, type=int, help="Warmup gestures per session case (default: 10)")
    parser.add_argument("--session-measured", default=9, type=int, help="Measured gestures per session case (default: 9)")
    parser.add_argument("--compare", type=Path, help="summary.json from an earlier run of this runner")
    parser.add_argument("--profile", action="store_true", help="Extra JFR fork excluded from timing aggregates")
    parser.add_argument("--online", action="store_true", help="Allow uncached Gradle dependency resolution")
    args = parser.parse_args()
    if args.forks < 1:
        parser.error("--forks must be positive")
    if args.session_warmup < 0 or args.session_measured < 1:
        parser.error("Session warmup must be nonnegative and measured gestures positive")
    output = args.output.resolve()
    if output.exists() and any(output.iterdir()):
        parser.error("Output must be a fresh directory")
    output.mkdir(parents=True, exist_ok=True)
    lock = ROOT / "build/performance-benchmark.lock"
    lock.parent.mkdir(exist_ok=True)
    try:
        lock.mkdir()
    except FileExistsError:
        parser.error(f"Another benchmark owns {lock}; serialize benchmark and build workloads")
    try:
        java = args.java_home.resolve() / "bin" / ("java.exe" if os.name == "nt" else "java")
        javac = args.java_home.resolve() / "bin" / ("javac.exe" if os.name == "nt" else "javac")
        source_hashes = sources()
        classpath = runtime_classpath(output, args)
        binary_hashes = binaries(classpath)
        classes = output / "harness-classes"
        classes.mkdir()
        suites = ("session", "render") if args.suite == "both" else ("session", "render", "queue") if args.suite == "all" else (args.suite,)
        harness_files = [HARNESS / "PenQueueBenchmark.java"] if suites == ("queue",) else [HARNESS / "PenSessionBenchmark.java", HARNESS / "PenRenderBenchmark.java", HARNESS / "PenPixelFingerprint.java"]
        if "queue" in suites and suites != ("queue",):
            harness_files.append(HARNESS / "PenQueueBenchmark.java")
        execute([str(javac), "-nowarn", "-cp", classpath, "-d", str(classes),
                 *map(str, harness_files)], output / "javac.log")
        classpath = os.pathsep.join([str(classes), classpath])
        result = {"schema": 1, "created_utc": datetime.now(timezone.utc).isoformat(), "suites": {}}
        if "render" in suites:
            fingerprint = output / "pixel-fingerprint.json"
            execute([str(java), "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
                     f"-Dskiko.data.path={output / 'skiko-cache'}", f"-Dbyteink.ink.cache={output / 'native-cache-fingerprint'}",
                     "-cp", classpath, "PenPixelFingerprint", str(fingerprint)], output / "pixel-fingerprint.log")
            result["pixel_fingerprint"] = json.loads(fingerprint.read_text())
        for suite in suites:
            directory = output / suite
            directory.mkdir()
            main_class = {"session": "PenSessionBenchmark", "render": "PenRenderBenchmark",
                          "queue": "com.vivenotes.byteink.compose.PenQueueBenchmark"}[suite]
            command = [str(java), "--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true",
                       "-Xms512m", "-Xmx2g", "-XX:+UseG1GC", f"-Dskiko.data.path={output / 'skiko-cache'}"]
            if suite == "session":
                command.extend([f"-Dbyteink.pen.sessionWarmup={args.session_warmup}",
                                f"-Dbyteink.pen.sessionMeasured={args.session_measured}"])
            forks = []
            for fork in range(1, args.forks + 1):
                report = directory / f"fork-{fork}.json"
                print(f"Running {suite} fork {fork}/{args.forks}", flush=True)
                execute(command + [f"-Dbyteink.ink.cache={output / f'native-cache-{suite}-{fork}'}", "-cp", classpath,
                                   main_class, str(report)], directory / f"fork-{fork}.log")
                forks.append(json.loads(report.read_text()))
            summary = summarize(forks)
            (directory / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
            result["suites"][suite] = summary
            if args.profile:
                profile = directory / "profile.jfr"
                execute(command + [f"-XX:StartFlightRecording=settings=profile,filename={profile},dumponexit=true",
                                   f"-Dbyteink.ink.cache={output / f'native-cache-{suite}-profile'}", "-cp", classpath,
                                   main_class, str(directory / "profile.json")], directory / "profile.log")
        if sources() != source_hashes or binaries(classpath.split(os.pathsep, 1)[1]) != binary_hashes:
            raise SystemExit("Sources or runtime binaries changed during timing; discard this run")
        result["provenance"] = {"source_files": source_hashes, "binary_files": binary_hashes,
                                "java_home": str(args.java_home.resolve()),
                                "git_head": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
                                "git_dirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip())}
        (output / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
        if args.compare:
            before = json.loads(args.compare.read_text())
            comparisons = {suite: compare(before["suites"][suite], result["suites"][suite])
                           for suite in suites if suite in before["suites"]}
            if "pixel_fingerprint" in before and "pixel_fingerprint" in result:
                comparisons["pixel_fingerprint_equal"] = before["pixel_fingerprint"] == result["pixel_fingerprint"]
            (output / "comparison.json").write_text(json.dumps(comparisons, indent=2) + "\n")
        print(f"Saved {output / 'summary.json'}", flush=True)
    finally:
        lock.rmdir()


if __name__ == "__main__":
    main()
