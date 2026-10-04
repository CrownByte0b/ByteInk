# Build this wiki

Run these commands from the ByteInk repository root. Python 3.10+ is suitable for the pinned MkDocs tools; the wiki was verified with Python 3.14.

```sh
python3 -m venv build/docs-venv
build/docs-venv/bin/python -m pip install -r docs/requirements.txt
build/docs-venv/bin/python -m mkdocs serve
```

Open **http://127.0.0.1:8000/**. Markdown edits reload automatically. On Windows, use `python` and `build/docs-venv/Scripts/python.exe` in place of the Linux commands.

## Build and validate

```sh
python3 docs/tools/api_reference.py --check
build/docs-venv/bin/python -m mkdocs build --strict
```

The static output is `build/wiki/`. Serve it over HTTP so search workers can load their index:

```sh
python3 -m http.server 8000 --directory build/wiki
```

Optional desktop/mobile browser verification uses an installed Chromium:

```sh
build/docs-venv/bin/python -m pip install playwright
build/docs-venv/bin/python docs/tools/check_site.py --browser /path/to/chromium
```

[Material's client-side search](https://squidfunk.github.io/mkdocs-material/setup/setting-up-site-search/) supplies the header search bar, suggestions and result highlighting. Fonts and documentation assets are served locally. [MkDocs configuration](https://www.mkdocs.org/user-guide/configuration/) controls the navigation and output directory.

## Publish with GitHub Pages

1. Open the repository's **Settings → Pages** and set **Build and deployment → Source** to **GitHub Actions**.
2. Commit and push the wiki, `mkdocs.yml`, and `.github/workflows/docs.yml` to `master`.
3. Watch **Actions → Documentation**. Its deployment job reports the published URL, normally **https://crownbyte0b.github.io/ByteInk/**.

The workflow checks generated references, builds the wiki with strict validation, and deploys documentation/API source updates pushed to `master`. Pull requests run the build checks. To republish manually, select **Actions → Documentation → Run workflow** and choose `master`.

Deployment uses GitHub's built-in token and the `github-pages` environment. No personal token or extra secret is needed. The build sets `MKDOCS_SITE_URL` from Pages metadata so canonical links include the repository path or configured custom domain; local previews keep their usual URL. No native or Gradle build is needed to publish the wiki.

[GitHub's custom Pages workflow instructions](https://docs.github.com/en/pages/getting-started-with-github-pages/using-custom-workflows-with-github-pages).

## Verify the examples

The wiki includes the **actual Kotlin source files** from `docs/examples/src/main/kotlin/wiki/`; prose snippets remain short variations on those examples.

```sh
# Requires the normal ByteInk build toolchain and prepared native outputs.
./gradlew -p docs/examples check run --max-workers=1
```

This standalone consumer compiles all examples, then checks authoring, stored round trips, partial erase/replay, selection, geometry, and offscreen rendering. It uses `includeBuild` to consume the current checkout. It needs no personal notebook.

## Update API pages

```sh
python3 docs/tools/api_reference.py
```

The generator reads the public Kotlin declarations and reviewed parameter descriptions in `docs/tools/api_reference.py`. Add descriptions for new parameter names; review behavior/constraints against implementations and tests. Generation fails for an undescribed parameter; `--check` detects reference drift. Update guides and compile examples when semantics change.
