#!/usr/bin/env python3
"""Check built pages/search assets and exercise API searches in desktop/mobile Chromium.

Optional browser validation dependency: pip install playwright.
Use --browser /path/to/chromium when a system browser is already available.
"""
from __future__ import annotations

import argparse
import functools
import json
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, format, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--browser", help="System Chromium executable, or use Playwright's installed browser")
    parser.add_argument("--site", type=Path, default=Path("build/wiki"))
    args = parser.parse_args()
    site = args.site.resolve()
    search_index = json.loads((site / "search/search_index.json").read_text())
    indexed = {entry["location"].split("#")[0] for entry in search_index["docs"]}
    pages = ["", "getting-started/", "guides/authoring/", "guides/rendering/",
             "guides/storage/", "guides/operations/", "guides/notebooks/", "reference/",
             "reference/core/", "reference/authoring/", "reference/rendering/", "reference/brushes/",
             "reference/storage/", "reference/operations/", "reference/loader/", "reference/testing/",
             "troubleshooting/", "guides/publishing/"]
    for page in pages:
        assert page in indexed, f"Missing indexed page {page}"
        assert (site / page / "index.html").is_file(), f"Missing HTML page {page}"
    assert not (site / "examples/build").exists(), "Generated Gradle artifacts leaked into the wiki"

    from playwright.sync_api import sync_playwright

    handler = functools.partial(QuietHandler, directory=str(site))
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    results = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(
                executable_path=args.browser,
                headless=True,
                args=["--no-sandbox", "--disable-dev-shm-usage"],
            )
            try:
                for width, height in [(1280, 900), (390, 844)]:
                    page = browser.new_page(viewport={"width": width, "height": height})
                    errors = []
                    page.on("pageerror", lambda error: errors.append(str(error)))
                    url = f"http://127.0.0.1:{server.server_port}/"
                    for query, expected in [("encodeErase", "reference/storage/"),
                                            ("pixelBudgetBytes", "reference/rendering/"),
                                            ("colorFollowsTheme", "reference/"),
                                            ("onPartial", "reference/storage/"),
                                            ("InkMeshRenderer", "reference/rendering/"),
                                            ("animationTimeMillis", "reference/rendering/"),
                                            ("ViveInkPage.replay", "reference/storage/"),
                                            ("InkLowLatencySurface", "reference/authoring/"),
                                            ("NativeInkInputSource", "reference/authoring/"),
                                            ("InkAuthoringSession", "reference/authoring/"),
                                            ("releaseLiveStroke", "reference/rendering/"),
                                            ("tiltRadians", "reference/authoring/")]:
                        page.goto(url, wait_until="networkidle")
                        if width < 600 and not page.locator("#__search").is_checked():
                            page.locator('label.md-header__button[for="__search"]').click()
                        field = page.locator("input.md-search__input")
                        # Material observes keyup/focus/worker readiness, not input alone.
                        # Real typing also works when the mobile menu already focused the field.
                        field.fill("")
                        field.press_sequentially(query)
                        assert field.input_value() == query, (query, field.input_value())
                        target = page.locator(f'.md-search-result__link:visible[href*="{expected}"]').first
                        try:
                            target.wait_for(state="visible")
                        except Exception as error:
                            state = {
                                "url": page.url,
                                "input_value": field.input_value(),
                                "input_focused": field.evaluate("node => node === document.activeElement"),
                                "search_open": page.locator("#__search").is_checked(),
                                "links": page.locator(".md-search-result__link").count(),
                                "message": page.locator(".md-search-result__meta").inner_text(),
                                "workers": [worker.url for worker in page.workers],
                                "page_errors": errors,
                            }
                            raise AssertionError(f"No visible search result for {query!r} at {width}px: {state}") from error
                        links = page.locator(".md-search-result__link").evaluate_all("nodes => nodes.map(n => n.href)")
                        assert any(expected in link for link in links), (query, links)
                        target.click()
                        page.wait_for_load_state("networkidle")
                        assert expected in page.url, (query, page.url)
                        assert page.locator("article h1").is_visible()
                        results.append({"width": width, "query": query, "results": len(links)})
                    assert not errors, errors
                    if width == 1280:
                        page.goto(url, wait_until="networkidle")
                        page.screenshot(path=str(site.parent / "wiki-desktop.png"), full_page=True)
                    page.close()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    print(json.dumps({"pages_indexed": len(pages), "search_entries": len(search_index["docs"]),
                      "browser_searches": results}, indent=2))


if __name__ == "__main__":
    main()
