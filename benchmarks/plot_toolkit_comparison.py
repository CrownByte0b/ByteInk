#!/usr/bin/env python3
"""Generate the wiki's charts, tables and public evidence from unchanged raw runs."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
os.environ.setdefault("MPLCONFIGDIR", str(ROOT / "build/toolkit-comparison/matplotlib-cache"))
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

STACKS = ("byteink", "qt6", "electron")
LABELS = ("ByteInk", "Qt 6 / C++", "Electron")
COLORS = ("#7c3aed", "#059669", "#ea580c")
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10, "svg.fonttype": "none", "svg.hashsalt": "byteink-toolkit-comparison"})

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def bars(ax, values, unit):
    median = np.array([v["median"] for v in values])
    lower = median - np.array([v["min"] for v in values])
    upper = np.array([v["max"] for v in values]) - median
    ax.set_facecolor("#ffffff")
    ax.barh(range(3), median, color=COLORS, height=.55, xerr=np.array([lower, upper]), capsize=4,
            error_kw={"elinewidth": 1.2, "ecolor": "#334155"}, zorder=3)
    ax.set_yticks(range(3), LABELS); ax.invert_yaxis()
    limit = max(v["max"] for v in values)
    ax.set_xlim(0, limit * 1.34)
    for i, value in enumerate(median):
        ax.text(values[i]["max"] + limit * .025, i, f"{value:,.2f}", va="center", fontweight="bold", color="#0f172a")
    ax.set_xlabel(unit, color="#475569")
    ax.xaxis.grid(True, color="#e2e8f0", zorder=0)
    ax.tick_params(length=0, colors="#475569")
    for spine in ax.spines.values(): spine.set_visible(False)

def scaled(value, divisor):
    return {k: v / divisor for k, v in value.items() if k in ("median", "min", "max")}

def save(fig, assets, name):
    fig.savefig(assets / (name + ".svg"), bbox_inches="tight", metadata={"Date": None})
    fig.savefig(assets / (name + ".png"), bbox_inches="tight", dpi=180)
    plt.close(fig)

def sanitize(value):
    if isinstance(value, dict): return {sanitize(k): sanitize(v) for k, v in value.items()}
    if isinstance(value, list): return [sanitize(v) for v in value]
    if isinstance(value, str):
        return value.replace(str(ROOT), "$REPO").replace(str(Path.home() / ".gradle"), "$GRADLE_USER_HOME").replace(str(Path.home()), "$USER_HOME")
    return value

def tables(summary):
    names = {"blank_1080p": "Blank · 1080p", "notes_100": "100 placements · 1080p", "page_1000": "1,000 placements · 1080p",
             "dense_10000": "10,000 placements · 1080p", "page_4k": "1,000 placements · 4K", "alpha_1000": "1,000 translucent · 1080p", "zoom_1000": "1,000 at 2× zoom · 1080p"}
    lines = ["| CPU raster workload | ByteInk | Qt 6 / C++ | Electron |", "| --- | ---: | ---: | ---: |"]
    for name, label in names.items():
        cells = []
        for stack in STACKS:
            case = summary[stack]["cases"][name]
            cells.append(f"{case['frame_median_ns']['median']/1e6:.3f} ({case['frame_p95_ns']['median']/1e6:.3f})")
        lines.append("| " + " | ".join([label] + cells) + " |")
    lines += ["", "Times are **median (P95), milliseconds; lower is better**. Each entry is the",
              "median of six process statistics, with 30 measured frames per process.", "",
              "| Host checkpoint | ByteInk | Qt 6 / C++ | Electron |", "| --- | ---: | ---: | ---: |"]
    for label, values, divisor in [
        ("Ready PSS · MiB", [summary[s]["idle_memory"]["pss_bytes"]["median"] for s in STACKS], 2**20),
        ("10,000-placement checkpoint PSS · MiB", [summary[s]["cases"]["dense_10000"]["loaded_memory"]["pss_bytes"]["median"] for s in STACKS], 2**20),
        ("Fresh process → ready · ms", [summary[s]["fresh_process_ready_ns"]["median"] for s in STACKS], 1e6)]:
        lines.append("| " + " | ".join([label] + [f"{v/divisor:.2f}" for v in values]) + " |")
    return "\n".join(lines) + "\n"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--prepared", type=Path, default=ROOT / "build/toolkit-comparison/prepared")
    parser.add_argument("--assets", type=Path, default=ROOT / "docs/assets/toolkit-comparison")
    parser.add_argument("--page", type=Path, default=ROOT / "docs/guides/toolkit-comparison.md")
    args = parser.parse_args()
    assets = args.assets; assets.mkdir(parents=True, exist_ok=True)
    summary = json.loads((args.input / "summary.json").read_text())
    manifest = json.loads((args.input / "manifest.json").read_text())
    if manifest["forks"] != 6 or manifest["measured_frames_per_case"] != 30: raise ValueError("Published table contract requires six forks / 30 measured frames")
    fig, axes = plt.subplots(2, 2, figsize=(12, 7), layout="constrained", facecolor="#f8fafc")
    fig.suptitle("Same ink outlines · CPU raster time", fontsize=19, fontweight="bold", color="#0f172a")
    for ax, (case, label) in zip(axes.flat, [("notes_100", "100 placements · 1080p"), ("page_1000", "1,000 placements · 1080p"),
                                           ("dense_10000", "10,000 placements · 1080p"), ("page_4k", "1,000 placements · 4K")]):
        bars(ax, [scaled(summary[s]["cases"][case]["frame_median_ns"], 1e6) for s in STACKS], "Milliseconds / full redraw · lower is better")
        ax.set_title(label, loc="left", pad=14, fontweight="bold", color="#0f172a")
    fig.supxlabel("Linux / Ryzen 9 7900X · 6 fresh processes per stack · whiskers: range of process medians", fontsize=10, color="#475569")
    save(fig, assets, "raster-times")

    fig, axes = plt.subplots(1, 3, figsize=(14, 4.5), layout="constrained", facecolor="#f8fafc")
    fig.suptitle("Host resources · measured checkpoints", fontsize=19, fontweight="bold", color="#0f172a")
    values = [([summary[s]["idle_memory"]["pss_bytes"] for s in STACKS], 2**20, "Ready memory", "Process-tree PSS / MiB"),
              ([summary[s]["cases"]["dense_10000"]["loaded_memory"]["pss_bytes"] for s in STACKS], 2**20, "After 10,000 placements", "Process-tree PSS / MiB"),
              ([summary[s]["fresh_process_ready_ns"] for s in STACKS], 1e6, "Fresh process → ready", "Milliseconds / warm filesystem cache")]
    for ax, (series, divisor, title, unit) in zip(axes, values):
        bars(ax, [scaled(v, divisor) for v in series], unit)
        ax.set_title(title, loc="left", pad=14, fontweight="bold", color="#0f172a")
    fig.supxlabel("Minimal raster hosts, different supplied features · not peak memory or complete note-taking apps · whiskers: 6-run range", fontsize=10, color="#475569")
    save(fig, assets, "host-resources")

    from PIL import Image
    fig, axes = plt.subplots(1, 3, figsize=(12, 3.5), layout="constrained", facecolor="#f8fafc")
    fig.suptitle("Matched output · enlarged identical crop", fontsize=17, fontweight="bold", color="#0f172a")
    for ax, stack, label, color in zip(axes, STACKS, LABELS, COLORS):
        image = Image.open(args.input / "fork-1" / stack / "page_1000.png").convert("RGB")
        ax.imshow(image.crop((0, 0, 100, 70)), interpolation="nearest")
        ax.set_title(label, color=color, fontweight="bold"); ax.axis("off")
    fig.supxlabel("ByteInk and Electron: exact pixels · Qt: same outlines, different antialiasing · no stroke-quality ranking", fontsize=10, color="#475569")
    save(fig, assets, "output-crop")

    # Retain every observation; remove local absolute usernames from public provenance.
    evidence = assets / "evidence"; evidence.mkdir(exist_ok=True)
    for name in ("raw.json", "summary.json", "manifest.json", "validation.json"):
        data = sanitize(json.loads((args.input / name).read_text()))
        (evidence / name).write_text(json.dumps(data, indent=2) + "\n")
    import PIL
    import sys
    (evidence / "chart-provenance.json").write_text(json.dumps({"plotter_sha256": sha(Path(__file__)),
        "python": sys.version, "matplotlib": matplotlib.__version__, "numpy": np.__version__, "pillow": PIL.__version__}, indent=2) + "\n")
    shutil.copy2(args.prepared / "scene.bin", evidence / "scene.bin")
    with zipfile.ZipFile(assets / "evidence.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(evidence.glob("*")): archive.write(path, "evidence/" + path.name)
        for path in sorted((ROOT / "benchmarks/toolkits").glob("*")):
            if path.is_file(): archive.write(path, "benchmarks/toolkits/" + path.name)
        for name in ("run_toolkit_comparison.py", "plot_toolkit_comparison.py", "run_pen.py"):
            archive.write(ROOT / "benchmarks" / name, "benchmarks/" + name)
    (assets / "checksums.json").write_text(json.dumps({str(p.relative_to(assets)): sha(p) for p in sorted(assets.rglob("*")) if p.is_file() and p.name != "checksums.json"}, indent=2) + "\n")
    fragment = tables(summary)
    (args.input / "wiki-tables.md").write_text(fragment)
    page = args.page.read_text(); start, end = "<!-- toolkit-results:start -->", "<!-- toolkit-results:end -->"
    if page.count(start) != 1 or page.count(end) != 1: raise ValueError("Missing generated-table markers")
    before, rest = page.split(start); _, after = rest.split(end)
    args.page.write_text(before + start + "\n\n" + fragment + "\n" + end + after)
    print(f"Generated charts, evidence and tables in {assets}")

if __name__ == "__main__": main()
