#!/usr/bin/env python3
"""Exercise the compiled Kotlin/Wasm browser consumer with Chromium and actual pointer events."""
import argparse
import base64
import functools
import json
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from browser_contracts import pointer_contract


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--site", type=Path, default=Path("samples/wasm/build/dist/wasmJs/productionExecutable"))
    parser.add_argument("--output", type=Path, default=Path("build/wasm-browser"))
    parser.add_argument("--engine", choices=["chromium", "firefox", "webkit"], default="chromium")
    parser.add_argument("--browser", help="Optional executable for the selected browser engine")
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
            browser = getattr(playwright, args.engine).launch(executable_path=args.browser, headless=True)
            try:
                for width in [1280, 390]:
                    page = browser.new_page(viewport={"width": width, "height": 860})
                    errors = []
                    page.on("pageerror", lambda error: errors.append(str(error)))
                    page.on("pageerror", lambda error: page.evaluate("message => globalThis.byteinkFailure = message", str(error)))
                    page.add_init_script("window.addEventListener('pointerdown', event => globalThis.byteinkLastPointerId = event.pointerId, true)")
                    page.goto(f"http://127.0.0.1:{server.server_port}/?verify=1")
                    page.wait_for_function("globalThis.byteinkReady === true || globalThis.byteinkFailure")
                    if errors:
                        diagnostics = page.evaluate("globalThis.byteinkRenderDiagnostics || []")
                        for item in diagnostics:
                            (args.output / f"failed-{item['name']}.png").write_bytes(base64.b64decode(item.pop("png")))
                        (args.output / "render-failures.json").write_text(json.dumps(diagnostics, indent=2) + "\n")
                    assert not errors, errors
                    rendering = page.evaluate("byteinkRenderingChecks")
                    assert rendering["cases"] == 63, rendering
                    canvas = page.locator("canvas")

                    def pixels():
                        return page.evaluate("byteinkVisible()")

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
                    alpha = page.evaluate("byteinkPixel(200, 260) >>> 24")
                    assert alpha == 0x66, alpha
                    page.click("#reload")
                    page.locator("#status").filter(has_text="2 strokes reloaded").wait_for()
                    reloaded = pixels()
                    assert reloaded > marker_pixels + 1000
                    page.screenshot(path=str(args.output / f"drawing-{width}.png"), full_page=True)

                    def alpha_at(x, y):
                        return page.evaluate("point => byteinkPixel(point[0], point[1]) >>> 24", [x, y])

                    page.click("#move")
                    assert alpha_at(100, 260) == 0
                    assert alpha_at(200, 260) == 0x66
                    moved = pixels()
                    page.click("#reload")
                    assert pixels() == moved
                    assert "1 operations" in page.locator("#status").inner_text()
                    page.click("#undo")
                    assert pixels() == reloaded

                    page.select_option("#tool", "erase")
                    draw([(200, 220), (200, 300)])
                    assert alpha_at(200, 260) == 0
                    assert alpha_at(100, 260) == 0x66
                    cut = pixels()
                    page.click("#reload")
                    assert pixels() == cut
                    page.select_option("#tool", "object")
                    draw([(100, 260), (100, 260)])
                    assert alpha_at(100, 260) == 0
                    assert pixels() < cut
                    page.click("#undo")
                    assert pixels() == cut
                    page.click("#undo")
                    assert pixels() == reloaded
                    page.click("#reload")
                    assert pixels() == reloaded

                    # A cancelled pointer gesture must not become another saved stroke.
                    page.mouse.move(*position(500, 400))
                    page.mouse.down()
                    page.mouse.move(*position(700, 400), steps=3)
                    canvas.dispatch_event("pointercancel", {"pointerId": page.evaluate("byteinkLastPointerId")})
                    page.mouse.up()
                    page.click("#reload")
                    assert "2 strokes reloaded" in page.locator("#status").inner_text()
                    page.click("#clear")
                    assert pixels() == 0

                    page.select_option("#tool", "marker")
                    pointer_events = pointer_contract(page)
                    assert pixels() == 0
                    pen = {"count": None}
                    if args.engine == "chromium":
                        # Real browser pen/capture, then injected coalesced/predicted observations on
                        # that active pointer. Synthetic history makes fractional times deterministic.
                        page.select_option("#tool", "marker")
                        surface = page.locator("#ink-surface")
                        surface.evaluate("element => element.addEventListener('pointerdown', event => globalThis.byteinkDown = {id:event.pointerId, time:event.timeStamp}, {once:true})")
                        cdp = page.context.new_cdp_session(page)
                        x, y = position(240, 380)
                        cdp.send("Input.dispatchMouseEvent", {"type": "mouseMoved", "x": x, "y": y, "pointerType": "pen"})
                        cdp.send("Input.dispatchMouseEvent", {"type": "mousePressed", "x": x, "y": y, "button": "left", "buttons": 1,
                                                             "clickCount": 1, "pointerType": "pen", "force": .4, "tiltX": 20, "tiltY": 30})
                        assert page.evaluate("byteinkActive()") == 1
                        assert surface.evaluate("element => element.hasPointerCapture(byteinkDown.id)")
                        predicted_count = surface.evaluate("""element => {
                            const rect = element.getBoundingClientRect(), down = byteinkDown;
                            const sample = (x, time, pressure) => {
                                const event = new PointerEvent('pointermove', {pointerId:down.id, pointerType:'pen', buttons:1,
                                    clientX:rect.left + x / 960 * rect.width, clientY:rect.top + 380 / 500 * rect.height,
                                    pressure, tiltX:20, tiltY:30, bubbles:true});
                                Object.defineProperty(event, 'timeStamp', {value:down.time + time}); return event;
                            };
                            const real = [sample(280, .25, .65), sample(320, .5, .8)], prediction = sample(900, 8, 1);
                            const event = real[1];
                            Object.defineProperty(event, 'getCoalescedEvents', {value:() => real});
                            Object.defineProperty(event, 'getPredictedEvents', {value:() => [prediction]});
                            element.dispatchEvent(event); return byteinkPredicted();
                        }""")
                        assert predicted_count == 1
                        page.wait_for_function("byteinkPredicted() === 0")
                        x, y = position(340, 380)
                        cdp.send("Input.dispatchMouseEvent", {"type": "mouseReleased", "x": x, "y": y, "button": "left", "buttons": 0,
                                                             "clickCount": 1, "pointerType": "pen"})
                        pen = page.evaluate("byteinkLastInput")
                        assert pen["count"] == 4, pen
                        assert [point[2] for point in pen["samples"]][1:3] == [.25, .5], pen
                        assert all(point[0] < 400 for point in pen["samples"]), pen
                        assert abs(pen["samples"][1][3] - .65) < .0001, pen
                        assert pen["samples"][1][4] > 0 and pen["samples"][1][5] > 0, pen
                        assert page.evaluate("byteinkActive()") == 0
                        assert not surface.evaluate("element => element.hasPointerCapture(byteinkDown.id)")

                        # Two actual simultaneous browser touch contacts complete independently.
                        cdp.send("Emulation.setTouchEmulationEnabled", {"enabled": True, "maxTouchPoints": 10})
                        contacts = [{"id": index, "x": position(x, 430)[0], "y": position(x, 430)[1]} for index, x in [(11, 500), (12, 650)]]
                        cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": contacts})
                        assert page.evaluate("byteinkActive()") == 2
                        for contact in contacts:
                            contact["x"] += 20
                        cdp.send("Input.dispatchTouchEvent", {"type": "touchMove", "touchPoints": contacts})
                        cdp.send("Input.dispatchTouchEvent", {"type": "touchEnd", "touchPoints": []})
                        assert page.evaluate("byteinkActive()") == 0
                        assert "3 strokes" in page.locator("#status").inner_text()
                        assert page.evaluate("byteinkLastInput.samples.every(point => point[3] === null && point[4] === null && point[5] === null)")
                        cdp.send("Input.dispatchTouchEvent", {"type": "touchStart", "touchPoints": contacts})
                        assert page.evaluate("byteinkActive()") == 2
                        cdp.send("Input.dispatchTouchEvent", {"type": "touchCancel", "touchPoints": []})
                        assert page.evaluate("byteinkActive()") == 0
                        page.click("#reload")
                        assert "3 strokes reloaded" in page.locator("#status").inner_text()
                        cdp.detach()
                        page.click("#clear")
                        assert pixels() == 0
                    assert not errors, errors
                    results.append({"width": width, "rendering": rendering, "pointer_events": pointer_events, "marker_pixels": marker_pixels, "combined_pixels": visible,
                                    "reloaded_pixels": reloaded, "highlighter_alpha": alpha,
                                    "move_reload_undo": True, "partial_erase_reload_undo": True,
                                    "object_erase_undo": True,
                                    "trusted_pen_injection": "chromium-cdp" if args.engine == "chromium" else None,
                                    "pen_coalesced_fractional_axes_prediction_expiry": True if args.engine == "chromium" else None,
                                    "prediction_free_saved_inputs": pen["count"],
                                    "simultaneous_touch_finish_and_cancel": True if args.engine == "chromium" else None,
                                    "cancel_preserved_stroke_count": True, "clear_pixels": 0})
                    page.close()
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    report = {"engine": args.engine, "version": browser.version, "browser_cases": results}
    (args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
