#!/usr/bin/env python3
"""Export the public Android notebook and captures for the same JVM/Wasm contract tests."""
import argparse
import base64
import csv
from contextlib import closing
import json
import sqlite3
import tempfile
import zipfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--matrix", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="byteink-kit-fixture-") as directory:
        database = Path(directory) / "notebook.sqlite"
        with zipfile.ZipFile(args.matrix / "synthetic.vive") as archive:
            database.write_bytes(archive.read("notebook.sqlite"))
        with closing(sqlite3.connect(database.as_uri() + "?mode=ro", uri=True)) as connection:
            connection.row_factory = sqlite3.Row

            def rows(table, page_id):
                result = []
                for item in connection.execute(f"SELECT * FROM {table} WHERE pageId=? ORDER BY id", (page_id,)):
                    row = dict(item)
                    row["points"] = base64.b64encode(row["points"]).decode()
                    if table != "ink_strokes":
                        kind = "erase" if table == "ink_erases" else "move"
                        row["targetIds"] = [target[0] for target in connection.execute(
                            f"SELECT strokeId FROM ink_{kind}_targets WHERE {kind}Id=? ORDER BY strokeId", (row["id"],))]
                    result.append(row)
                return result

            cases = []
            with (args.matrix / "cases.tsv").open() as source:
                for case in csv.DictReader(source, delimiter="\t"):
                    folder = args.matrix / "cases" / case["id"]
                    case["strokes"] = rows("ink_strokes", case["pageId"])
                    case["erases"] = rows("ink_erases", case["pageId"])
                    case["moves"] = rows("ink_moves", case["pageId"])
                    case["familyProto"] = base64.b64encode((folder / "family.pb").read_bytes()).decode()
                    case["geometry"] = json.loads((folder / "geometry.json").read_text())
                    del case["geometry"]["raw"]
                    for projection in case["geometry"]["replayed"]:
                        projection.pop("inputs", None)
                    case["reencoded"] = {row["id"]: base64.b64encode((folder / f'{row["id"]}.roundtrip.pb.gz').read_bytes()).decode()
                                         for row in case["strokes"]}
                    cases.append(case)
    assert len(cases) == 280
    assert sum(len(case["strokes"]) for case in cases) == 840
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"cases": cases}, separators=(",", ":")) + "\n")
    print(f"Exported {len(cases)} Android cases to {args.output}")


if __name__ == "__main__":
    main()
