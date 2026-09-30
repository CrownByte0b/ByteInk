#!/usr/bin/env python3
"""Capture Android fidelity references using an isolated, pinned ViveNotes checkout."""

import argparse
from datetime import datetime, timezone
import io
import hashlib
import os
import json
from pathlib import Path
import re
import shutil
import subprocess
import tarfile

REFERENCE_COMMIT = "af05908e57f69593e32107b97bcdf19250637fa3"
PACKAGE = "com.vivenotes.byteinkoracle"
ROOT = Path(__file__).resolve().parents[2]


def run(arguments, cwd=ROOT, **options):
    return subprocess.run([str(argument) for argument in arguments], cwd=cwd, check=True, **options)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android-project", type=Path, required=True, help="ViveNotes Git checkout; dirty files are excluded")
    parser.add_argument("--notebooks", type=Path, help="Directory of local .vive fixtures")
    parser.add_argument("--matrix", action="store_true", help="Generate the public synthetic matrix on API 36 x86_64 / SwiftShader")
    parser.add_argument("--capture-only", action="store_true", help="Retrieve references without running the desktop comparison")
    parser.add_argument("--emulator", type=Path, help="Emulator executable, when it is outside PATH/the configured SDK")
    parser.add_argument("--device", required=True, help="ADB serial of an already running emulator")
    parser.add_argument("--results", type=Path, help="Fresh output directory, or existing prepared references")
    parser.add_argument("--diagnose", action="store_true", help="Dump strokes listed in results/diagnostics.tsv instead of rendering pages")
    args = parser.parse_args()
    if args.matrix and (args.notebooks or args.diagnose):
        parser.error("--matrix cannot be combined with --notebooks or --diagnose")
    if not args.matrix and not args.notebooks:
        parser.error("--notebooks is required unless --matrix is selected")
    source = args.android_project.resolve()
    results = (args.results or ROOT / "conformance/android/build" /
               datetime.now(timezone.utc).strftime("results-%Y%m%d-%H%M%S")).resolve()
    checkout = ROOT / "conformance/android/build/app"
    adb = ["adb", "-s", args.device]
    environment = {}
    if args.matrix:
        for property in ["ro.build.version.sdk", "ro.product.cpu.abi", "ro.build.fingerprint"]:
            environment[property] = run(adb + ["shell", "getprop", property], capture_output=True, text=True).stdout.strip()
        environment["graphics"] = run(adb + ["shell", "dumpsys", "SurfaceFlinger"], capture_output=True, text=True).stdout
        if environment["ro.build.version.sdk"] != "36" or environment["ro.product.cpu.abi"] != "x86_64":
            raise RuntimeError("The synthetic matrix requires the pinned API 36 x86_64 reference")
        graphics = next((line for line in environment["graphics"].splitlines() if line.startswith("GLES:")), "")
        if "SwiftShader" not in graphics:
            raise RuntimeError("The synthetic matrix requires SwiftShader; refusing a host-GPU reference")
        environment["graphics"] = graphics
        sdk = Path(os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or Path.home() / "Android/Sdk")
        emulator = args.emulator or shutil.which("emulator") or sdk / "emulator/emulator"
        environment["emulator"] = run([emulator, "-version"], capture_output=True, text=True).stdout.splitlines()[0]
        results.mkdir(parents=True, exist_ok=True)
        if (results / "android-matrix").exists():
            raise RuntimeError("Use a fresh result directory for synthetic references")
    marker = checkout / "byteink-reference-commit.txt"
    archive = run(["git", "archive", REFERENCE_COMMIT], cwd=source, capture_output=True).stdout
    if not checkout.exists():
        checkout.mkdir(parents=True)
        with tarfile.open(fileobj=io.BytesIO(archive)) as package:
            package.extractall(checkout, filter="data")
        marker.write_text(REFERENCE_COMMIT + "\n")
    if not marker.is_file() or marker.read_text().strip() != REFERENCE_COMMIT:
        raise RuntimeError(f"Unverified reference checkout: {checkout}. Move it aside before running.")
    # A marker alone cannot certify a reused checkout. Verify the archived source bytes too;
    # generated build outputs and the injected external test directory are outside this set.
    with tarfile.open(fileobj=io.BytesIO(archive)) as package:
        expected = set()
        for entry in package:
            if not entry.isfile():
                continue
            expected.add(entry.name)
            file = checkout / entry.name
            original = package.extractfile(entry).read()
            if not file.is_file() or file.read_bytes() != original:
                raise RuntimeError(f"Pinned reference source changed: {entry.name}. Move the isolated checkout aside before running.")
        for directory in ["app/src", "buildSrc/src", "build-logic/src"]:
            for file in (checkout / directory).rglob("*"):
                if file.is_file() and file.relative_to(checkout).as_posix() not in expected:
                    raise RuntimeError(f"Unexpected reference source: {file.relative_to(checkout)}")
    if not args.matrix and not (results / "pages.tsv").exists():
        run([ROOT / "gradlew", ":byteink-testing:prepareAndroidOracle", "--offline",
             f"-PbyteinkNotebooks={args.notebooks.resolve()}", f"-PbyteinkFidelityDirectory={results}"])
    run([checkout / "gradlew", ":app:assembleDebug", ":app:assembleDebugAndroidTest", "--offline",
         f"--init-script={ROOT / 'conformance/android/inject.gradle'}",
         f"-PbyteinkOracleSources={ROOT / 'conformance/android/src'}"], cwd=checkout)
    for directory, apk, application_id in [
        ("debug", "app-debug.apk", PACKAGE),
        ("androidTest/debug", "app-debug-androidTest.apk", PACKAGE + ".test"),
    ]:
        output = checkout / "app/build/outputs/apk" / directory
        if json.loads((output / "output-metadata.json").read_text())["applicationId"] != application_id:
            raise RuntimeError("Refusing to install an APK without the isolated reference application ID")
        run(["android", "install", f"--device={args.device}", f"--apks={output / apk}"])
    staging = "/data/local/tmp/byteink-oracle"
    private = "files/byteink-oracle"
    run(adb + ["shell", "mkdir", "-p", staging])
    run(adb + ["shell", "run-as", PACKAGE, "mkdir", "-p", private])
    files = [] if args.matrix else [results / "pages.tsv", *sorted(results.glob("notebook-*.sqlite"))]
    if args.diagnose:
        files.append(results / "diagnostics.tsv")
    for file in files:
        run(adb + ["push", file, staging + "/"])
        run(adb + ["shell", "run-as", PACKAGE, "cp", f"{staging}/{file.name}", private + "/"])
    method = "generateSyntheticMatrix" if args.matrix else "diagnoseStrokes" if args.diagnose else "rebuildAndRender"
    instrumentation = run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                                 "com.vivenotes.byteink.AndroidNotebookOracleTest#" + method,
                                 PACKAGE + ".test/androidx.test.runner.AndroidJUnitRunner"],
                          capture_output=True, text=True)
    log = instrumentation.stdout + instrumentation.stderr
    (results / "instrumentation.txt").write_text(log)
    print(log, end="", flush=True)
    # am instrument can exit zero even when a test fails.
    if not re.search(r"OK \(1 test\)", log) or "FAILURES!!!" in log:
        raise RuntimeError(f"Android reference test failed; see {results / 'instrumentation.txt'}")
    artifact = "android-matrix" if args.matrix else "android"
    archive = run(adb + ["exec-out", "run-as", PACKAGE, "tar", "-C", private, "-cf", "-", artifact],
                  capture_output=True).stdout
    with tarfile.open(fileobj=io.BytesIO(archive)) as package:
        package.extractall(results, filter="data")
    (results / "reference.txt").write_text(f"android_app_commit={REFERENCE_COMMIT}\n")
    if args.matrix:
        source_hashes = {str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
                         for directory in ["conformance/android/src", "conformance/android/shared"]
                         for path in sorted((ROOT / directory).rglob("*.kt"))}
        capture_metadata = json.dumps({
            "android_app_commit": REFERENCE_COMMIT, "androidx_ink": "1.1.0-alpha06",
            "environment": environment, "sources": source_hashes,
            "apk_sha256": {name: hashlib.sha256((checkout / "app/build/outputs/apk" / directory / name).read_bytes()).hexdigest()
                           for directory, name in [("debug", "app-debug.apk"), ("androidTest/debug", "app-debug-androidTest.apk")]},
        }, indent=2) + "\n"
        (results / "capture-environment.json").write_text(capture_metadata)
        public = results / artifact
        (public / "capture-environment.json").write_text(capture_metadata)
        manifest = public / "manifest.sha256"
        manifest.write_text("".join(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.relative_to(public).as_posix()}\n"
                                   for path in sorted(public.rglob("*")) if path.is_file() and path != manifest))
        if not args.capture_only:
            run([ROOT / "gradlew", ":byteink-testing:androidFidelityMatrix", "--offline",
                 f"-PbyteinkMatrixDirectory={results / artifact}", f"-PbyteinkMatrixOutput={results / 'comparison'}"])
    elif not args.diagnose and not args.capture_only:
        run([ROOT / "gradlew", ":byteink-testing:compareAndroidOracle", "--offline",
             f"-PbyteinkFidelityDirectory={results}"])


if __name__ == "__main__":
    main()
