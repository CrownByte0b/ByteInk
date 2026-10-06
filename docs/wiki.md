# Build this wiki

Run these commands from the ByteInk repository root. Python 3.10+ is suitable for the pinned MkDocs tools; the wiki was verified with Python 3.14.

```sh
python3 -m venv build/docs-venv
build/docs-venv/bin/python -m pip install -r docs/requirements.txt
build/docs-venv/bin/python -m mkdocs serve
```

## Verify the examples

The wiki includes the **actual Kotlin source files** from `docs/examples/src/main/kotlin/wiki/`; prose snippets remain short variations on those examples.

```sh
# Requires the normal ByteInk build toolchain and prepared native outputs.
./gradlew -p docs/examples check run --max-workers=1
```

This standalone consumer compiles all examples, including the textured Compose surface, then checks authoring, stored round trips, partial erase/decoded replay/undo, selection, rendering attributes and offscreen mesh rendering. It uses `includeBuild` to consume the current checkout. It needs no personal notebook.

## Update API pages

```sh
python3 docs/tools/api_reference.py
```

The generator reads the public Kotlin declarations and reviewed parameter descriptions in `docs/tools/api_reference.py`. Add descriptions for new parameter names; review behavior/constraints against implementations and tests. Generation fails for an undescribed parameter; `--check` detects reference drift. Update guides and compile examples when semantics change.
