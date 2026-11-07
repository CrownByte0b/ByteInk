#!/usr/bin/env python3
"""Run the independent Maven-only Compose/Wasm consumer, including owned-resource disposal."""
import argparse
import functools
import json
import threading
import time
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class SkikoDelayingHandler(QuietHandler):
    """Serves Skiko's runtime late while `delayed` is set, as a slow network or a large runtime can."""
    skiko_file = ""
    delayed = threading.Event()

    def do_GET(self):
        if self.delayed.is_set() and self.path.endswith(self.skiko_file):
            time.sleep(2)
        super().do_GET()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site", type=Path, default=Path("consumer-tests/wasm-compose-fixture/build/dist/wasmJs/productionExecutable"))
    parser.add_argument("--output", type=Path, default=Path("build/wasm-published-compose-browser"))
    parser.add_argument("--engine", choices=["chromium", "firefox", "webkit"], default="chromium")
    parser.add_argument("--browser")
    args = parser.parse_args()
    assert (args.site / "index.html").is_file()
    from playwright.sync_api import TimeoutError as PageTimeout, sync_playwright

    args.output.mkdir(parents=True, exist_ok=True)
    # Besides the engine, the page's Wasm files are Skiko's runtime and the smaller application.
    SkikoDelayingHandler.skiko_file = max((file for file in args.site.glob("*.wasm") if file.name != "byteink-engine.wasm"),
                                          key=lambda file: file.stat().st_size).name
    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(SkikoDelayingHandler, directory=str(args.site.resolve())))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    results = []
    try:
        with sync_playwright() as playwright:
            browser = getattr(playwright, args.engine).launch(executable_path=args.browser, headless=True)
            try:
                # The engine usually loads after Skiko's runtime, but nothing makes it. Each page has its own
                # HTTP cache, so the last load always finishes the engine first: Skia used too early fails it.
                for width, late_skiko in [(1280, False), (390, False), (1280, True)]:
                    (SkikoDelayingHandler.delayed.set if late_skiko else SkikoDelayingHandler.delayed.clear)()
                    page = browser.new_page(viewport={"width": width, "height": 600})
                    errors = []
                    page.on("pageerror", lambda error: errors.append(str(error)))
                    page.goto(f"http://127.0.0.1:{server.server_port}/")
                    try:
                        page.wait_for_function("globalThis.publishedWasmComposePass === true")
                    except PageTimeout:
                        raise AssertionError(f"The consumer never painted at width {width}, late Skiko {late_skiko}: {errors}") from None
                    assert not errors, errors
                    page.screenshot(path=str(args.output / f"consumer-{width}.png"))
                    page.mouse.move(50, 90)
                    page.mouse.down()
                    page.mouse.move(100, 90)
                    page.evaluate("closePublishedWasmCompose()")
                    page.mouse.up()
                    page.wait_for_function("globalThis.publishedWasmComposeDisposed === true")
                    page.wait_for_timeout(100)
                    assert not errors, errors
                    results.append({"engine": args.engine, "version": browser.version, "width": width, "late_skiko": late_skiko,
                                    "rendered": True, "disposed_during_gesture": True})
                    page.close()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    (args.output / "results.json").write_text(json.dumps(results, indent=2) + "\n")
    print("PUBLISHED_COMPOSE_WASM_CONSUMER_PASS", json.dumps(results))


if __name__ == "__main__":
    main()
