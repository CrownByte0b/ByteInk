#!/usr/bin/env python3
"""Regenerate only tablet ABI metadata; the pinned XML is shared with the independent C server."""
import hashlib
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

fixture = Path(__file__).resolve().parent
xml = fixture / "tablet-v2.xml"
expected = "ac1128b26c779cf90b9ed71182ba5e34a7262826789adae206d825a4d45908b4"
if hashlib.sha256(xml.read_bytes()).hexdigest() != expected:
    raise SystemExit("tablet-v2.xml changed: review the protocol and update its pin before generating")
java = fixture.parents[2] / "src/main/java/com/vivenotes/byteink/compose/WaylandProtocol.java"
letters = {"int": "i", "uint": "u", "fixed": "f", "string": "s", "object": "o", "new_id": "n", "array": "a", "fd": "h"}
lines = ["    static final Interface[] TABLET = {"]
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
source = java.read_text()
start = source.index("    static final Interface[] TABLET = {")
end = source.index("    };", start) + len("    };")
updated = source[:start] + "\n".join(lines) + source[end:]
if "--check" in sys.argv:
    if source != updated:
        raise SystemExit("Tablet metadata is stale: run src/test/wayland/generate.py")
else:
    java.write_text(updated)
