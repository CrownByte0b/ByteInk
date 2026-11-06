#!/usr/bin/env python3
"""Exercise the compiled Kotlin/Wasm browser consumer with Chromium and actual pointer events."""
import argparse
import functools
import json
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site", type=Path, default=Path("samples/wasm/build/dist/wasmJs/productionExecutable"))
    parser.add_argument("--output", type=Path, default=Path("build/wasm-browser"))
    parser.add_argument("--browser", help="Chromium executable, or use Playwright's installed browser")
    args = parser.parse_args()
    assert (args.site / "index.html").is_file(), "Build :samples:wasm:wasmJsBrowserDistribution first"
    from playwright.sync_api import sync_playwright

    args.output.mkdir(parents=True, exist_ok=True)
    handler = functools.partial(QuietHandler, directory=str(args.site.resolve()))
    server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    results = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(executable_path=args.browser, headless=True)
            try:
                for width in [1280, 390]:
                    page = browser.new_page(viewport={"width": width, "height": 860})
                    errors = []
                    page.on("pageerror", lambda error: errors.append(str(error)))
                    page.goto(f"http://127.0.0.1:{server.server_port}/")
                    page.wait_for_function("globalThis.byteinkReady === true")
                    canvas = page.locator("canvas")

                    def pixels():
                        return canvas.evaluate("""canvas => {
                            const pixels = canvas.getContext('2d').getImageData(0, 0, canvas.width, canvas.height).data;
                            let visible = 0;
                            for (let i = 3; i < pixels.length; i += 4) if (pixels[i]) visible++;
                            return visible;
                        }""")

                    def position(x, y):
                        box = canvas.bounding_box()
                        return box["x"] + x * box["width"] / 960, box["y"] + y * box["height"] / 500

                    def draw(points):
                        page.mouse.move(*position(*points[0]))
                        page.mouse.down()
                        for point in points[1:]:
                            page.mouse.move(*position(*point), steps=6)
                        page.mouse.up()

                    assert pixels() == 0
                    draw([(50, 80), (180, 160), (300, 100), (420, 180)])
                    page.locator("#status").filter(has_text="1 strokes").wait_for()
                    marker_pixels = pixels()
                    assert marker_pixels > 1000, marker_pixels
                    page.select_option("#tool", "highlighter")
                    draw([(80, 260), (350, 260), (80, 260)])
                    page.locator("#status").filter(has_text="2 strokes").wait_for()
                    visible = pixels()
                    assert visible > marker_pixels + 1000
                    # DISCARD self overlap: a doubled-back highlight is filled once.
                    alpha = canvas.evaluate("canvas => canvas.getContext('2d').getImageData(200, 260, 1, 1).data[3]")
                    assert alpha == 0x66, alpha
                    page.click("#reload")
                    page.locator("#status").filter(has_text="2 strokes reloaded").wait_for()
                    reloaded = pixels()
                    assert reloaded > marker_pixels + 1000
                    page.screenshot(path=str(args.output / f"drawing-{width}.png"), full_page=True)

                    # A cancelled pointer gesture must not become another saved stroke.
                    page.mouse.move(*position(500, 400))
                    page.mouse.down()
                    page.mouse.move(*position(700, 400), steps=3)
                    canvas.dispatch_event("pointercancel", {"pointerId": 1})
                    page.mouse.up()
                    page.click("#reload")
                    assert "2 strokes reloaded" in page.locator("#status").inner_text()
                    page.click("#clear")
                    assert pixels() == 0
                    assert not errors, errors
                    results.append({"width": width, "marker_pixels": marker_pixels, "combined_pixels": visible,
                                    "reloaded_pixels": reloaded, "highlighter_alpha": alpha,
                                    "cancel_preserved_stroke_count": True, "clear_pixels": 0})
                    page.close()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    report = {"browser_cases": results}
    (args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
