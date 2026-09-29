#!/usr/bin/env python3
"""Local notebook fidelity check using an isolated, pinned Android app checkout."""

import argparse
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import re
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
    parser.add_argument("--notebooks", type=Path, required=True, help="Directory of local .vive fixtures")
    parser.add_argument("--device", required=True, help="ADB serial of an already running emulator")
    parser.add_argument("--results", type=Path, help="Fresh output directory, or existing prepared references")
    parser.add_argument("--diagnose", action="store_true", help="Dump strokes listed in results/diagnostics.tsv instead of rendering pages")
    args = parser.parse_args()
    source = args.android_project.resolve()
    results = (args.results or ROOT / "conformance/android/build" /
               datetime.now(timezone.utc).strftime("results-%Y%m%d-%H%M%S")).resolve()
    checkout = ROOT / "conformance/android/build/app"
    marker = checkout / "byteink-reference-commit.txt"
    if not checkout.exists():
        archive = run(["git", "archive", REFERENCE_COMMIT], cwd=source, capture_output=True).stdout
        checkout.mkdir(parents=True)
        with tarfile.open(fileobj=io.BytesIO(archive)) as package:
            package.extractall(checkout, filter="data")
        marker.write_text(REFERENCE_COMMIT + "\n")
    if not marker.is_file() or marker.read_text().strip() != REFERENCE_COMMIT:
        raise RuntimeError(f"Unverified reference checkout: {checkout}. Move it aside before running.")
    if not (results / "pages.tsv").exists():
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
    adb = ["adb", "-s", args.device]
    staging = "/data/local/tmp/byteink-oracle"
    private = "files/byteink-oracle"
    run(adb + ["shell", "mkdir", "-p", staging])
    run(adb + ["shell", "run-as", PACKAGE, "mkdir", "-p", private])
    files = [results / "pages.tsv", *sorted(results.glob("notebook-*.sqlite"))]
    if args.diagnose:
        files.append(results / "diagnostics.tsv")
    for file in files:
        run(adb + ["push", file, staging + "/"])
        run(adb + ["shell", "run-as", PACKAGE, "cp", f"{staging}/{file.name}", private + "/"])
    method = "diagnoseStrokes" if args.diagnose else "rebuildAndRender"
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
    archive = run(adb + ["exec-out", "run-as", PACKAGE, "tar", "-C", private, "-cf", "-", "android"],
                  capture_output=True).stdout
    with tarfile.open(fileobj=io.BytesIO(archive)) as package:
        package.extractall(results, filter="data")
    (results / "reference.txt").write_text(f"android_app_commit={REFERENCE_COMMIT}\n")
    if not args.diagnose:
        run([ROOT / "gradlew", ":byteink-testing:compareAndroidOracle", "--offline",
             f"-PbyteinkFidelityDirectory={results}"])


if __name__ == "__main__":
    main()
