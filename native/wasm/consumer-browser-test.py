#!/usr/bin/env python3
"""Run the independent Maven-only Compose/Wasm consumer, including owned-resource disposal."""
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
    parser.add_argument("--site", type=Path, default=Path("consumer-tests/wasm-compose-fixture/build/dist/wasmJs/productionExecutable"))
    parser.add_argument("--output", type=Path, default=Path("build/wasm-published-compose-browser"))
    parser.add_argument("--engine", choices=["chromium", "firefox", "webkit"], default="chromium")
    parser.add_argument("--browser")
    args = parser.parse_args()
    assert (args.site / "index.html").is_file()
    from playwright.sync_api import sync_playwright

    args.output.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(QuietHandler, directory=str(args.site.resolve())))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    results = []
    try:
        with sync_playwright() as playwright:
            browser = getattr(playwright, args.engine).launch(executable_path=args.browser, headless=True)
            try:
                for width in [1280, 390]:
                    page = browser.new_page(viewport={"width": width, "height": 600})
                    errors = []
                    page.on("pageerror", lambda error: errors.append(str(error)))
                    page.goto(f"http://127.0.0.1:{server.server_port}/")
                    page.wait_for_function("globalThis.publishedWasmComposePass === true")
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
                    results.append({"engine": args.engine, "version": browser.version, "width": width, "rendered": True, "disposed_during_gesture": True})
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
