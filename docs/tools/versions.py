"""MkDocs hook that fills the wiki's version from byteinkReleaseVersion in gradle.properties.

Pages write {{ byteink_version }} for the release and {{ byteink_snapshot }} for what a checkout
builds. README.md is plain GitHub Markdown and cannot read the property, so the build stops while
its coordinates or badge name another version.
"""

import re
from pathlib import Path

from mkdocs.exceptions import PluginError

ROOT = Path(__file__).resolve().parents[2]
PLACEHOLDER = re.compile(r"\{\{\s*byteink_(\w+)\s*\}\}")
values: dict[str, str] = {}


def release_version(root: Path) -> str:
    properties = (root / "gradle.properties").read_text(encoding="utf-8")
    found = re.findall(r"^byteinkReleaseVersion\s*[=:]\s*(\S+)\s*$", properties, re.MULTILINE)
    if len(found) != 1:
        raise PluginError("gradle.properties must set byteinkReleaseVersion exactly once")
    return found[0]


def readme_versions(root: Path) -> set[str]:
    readme = (root / "README.md").read_text(encoding="utf-8")
    named = set(re.findall(r"com\.vivenotes\.byteink:[\w-]+:([^\"'`\s)]+)", readme))
    # Shields.io escapes a dash in badge text as "--".
    badges = re.findall(r"GitHub%20Packages-((?:[^-]|--)+)-", readme)
    return named | {badge.replace("--", "-").replace("__", "_") for badge in badges}


def on_config(config, root: Path = ROOT):
    release = release_version(root)
    stale = sorted(readme_versions(root) - {release})
    if stale:
        raise PluginError(
            f"README.md names {', '.join(stale)}, but byteinkReleaseVersion in gradle.properties is "
            f"{release}: update its badge and dependency coordinates"
        )
    values.update(version=release, snapshot=f"{release}-SNAPSHOT")
    return config


def on_page_markdown(markdown, page, **kwargs):
    def fill(match):
        if match[1] not in values:
            raise PluginError(f"{page.file.src_path}: unknown placeholder {match[0]}")
        return values[match[1]]

    return PLACEHOLDER.sub(fill, markdown)
