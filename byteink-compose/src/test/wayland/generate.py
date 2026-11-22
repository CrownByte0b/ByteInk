#!/usr/bin/env python3
"""Regenerate only Wayland ABI metadata; the pinned XML is shared with the independent C server."""
import hashlib
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

fixture = Path(__file__).resolve().parent
# Pinned XML, its SHA-256 and the generated Java array. Review upstream changes before a new pin.
protocols = [
    ("tablet-v2.xml", "ac1128b26c779cf90b9ed71182ba5e34a7262826789adae206d825a4d45908b4", "TABLET"),
    ("cursor-shape-v1.xml", "bb57d91e53a79dadab7c612dab87c233393cee73673feefa7442cfbfdd9aed2f", "CURSOR_SHAPE"),
]
java = fixture.parents[2] / "src/main/java/com/vivenotes/byteink/compose/WaylandProtocol.java"
letters = {"int": "i", "uint": "u", "fixed": "f", "string": "s", "object": "o", "new_id": "n", "array": "a", "fd": "h"}


def metadata(xml, array):
    lines = [f"    static final Interface[] {array} = {{"]
    for interface in ET.parse(xml).getroot().findall("interface"):
        lines.append(f'        new Interface("{interface.get("name")}", {interface.get("version")}, new Message[]{{')
        for kind in ["request", "event"]:
            if kind == "event":
                lines.append("        }, new Message[]{")
            for message in interface.findall(kind):
                args = message.findall("arg")
                since = message.get("since", "1")
                signature = (since if since != "1" else "") + "".join(
                    ("?" if arg.get("allow-null") == "true" else "") + letters[arg.get("type")] for arg in args)
                types = [f'"{arg.get("interface")}"' if arg.get("interface") else "null" for arg in args]
                if types == ["null"]:
                    types = ["(String) null"]
                suffix = ", " + ", ".join(types) if types else ""
                lines.append(f'            new Message("{message.get("name")}", "{signature}"{suffix}),')
        lines.append("        }),")
    lines.append("    };")
    return "\n".join(lines)


source = java.read_text()
updated = source
for name, expected, array in protocols:
    xml = fixture / name
    if hashlib.sha256(xml.read_bytes()).hexdigest() != expected:
        raise SystemExit(f"{name} changed: review the protocol and update its pin before generating")
    start = updated.index(f"    static final Interface[] {array} = {{")
    end = updated.index("    };", start) + len("    };")
    updated = updated[:start] + metadata(xml, array) + updated[end:]
if "--check" in sys.argv:
    if source != updated:
        raise SystemExit("Wayland metadata is stale: run src/test/wayland/generate.py")
else:
    java.write_text(updated)
