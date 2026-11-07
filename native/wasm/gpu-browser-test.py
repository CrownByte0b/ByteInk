#!/usr/bin/env python3
"""Gate desktop-vs-Wasm native geometry on WebGL; measure software-vs-GPU separately."""
import argparse
import base64
import functools
import io
import json
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from PIL import Image


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=["chromium", "firefox", "webkit"], default="chromium")
    parser.add_argument("--browser")
    parser.add_argument("--site", type=Path, default=Path("samples/wasm/build/dist/wasmJs/productionExecutable"))
    parser.add_argument("--output", type=Path, default=Path("build/wasm-gpu-browser"))
    args = parser.parse_args()
    from playwright.sync_api import sync_playwright

    args.output.mkdir(parents=True, exist_ok=True)
    fixtures = json.loads((args.site / "rendering.json").read_text())
    server = ThreadingHTTPServer(("127.0.0.1", 0), functools.partial(QuietHandler, directory=str(args.site.resolve())))
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    results = []
    try:
        with sync_playwright() as playwright:
            browser = getattr(playwright, args.engine).launch(executable_path=args.browser, headless=True)
            try:
                errors = []
                def capture(reference):
                    page = browser.new_page(viewport={"width": 1920, "height": 1280}, device_scale_factor=1)
                    try:
                        # Keep the actual WebGL drawing buffer through presentation/screenshot.
                        # Test-only: do not change production context options or claim these timings.
                        page.add_init_script("""const original = HTMLCanvasElement.prototype.getContext;
                            HTMLCanvasElement.prototype.getContext = function(type,options) {
                                return original.call(this,type,type.startsWith('webgl') ? {...options,preserveDrawingBuffer:true} : options);
                            };""")
                        page.on("pageerror", lambda error: errors.append(str(error)))
                        page.on("pageerror", lambda error: page.evaluate("message => globalThis.byteinkGpuFailure = message", str(error)))
                        page.goto(f"http://127.0.0.1:{server.server_port}/?gpu=1&reference={int(reference)}")
                        page.wait_for_function("globalThis.byteinkGpuReady || globalThis.byteinkGpuFailure")
                        assert not errors, errors
                        page.evaluate("() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))")
                        backend = page.locator("#gpu-surface canvas").evaluate("""canvas => {
                            const gl = canvas.getContext('webgl2') || canvas.getContext('webgl');
                            if (!gl) return null;
                            gl.finish();
                            const debug = gl.getExtension('WEBGL_debug_renderer_info');
                            return {version:gl.getParameter(gl.VERSION),renderer:debug ? gl.getParameter(debug.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER)};
                        }""")
                        assert backend, "Expected an actual Compose WebGL canvas"
                        png = page.locator("#gpu-surface canvas").screenshot()
                        (args.output / ("desktop-geometry.png" if reference else "matrix.png")).write_bytes(png)
                        pixels = Image.open(io.BytesIO(png)).convert("RGB")
                        assert pixels.size == (1792, 1152), pixels.size
                        assert pixels.getpixel((0,0)) == (0,0,0) and pixels.getpixel((128,0)) == (255,255,255), "Blank/missing WebGL presentation"
                        assert len(pixels.getcolors(pixels.width*pixels.height)) > 100, "No rendered controls"
                        return pixels,backend
                    finally:
                        page.close()
                actual,backend = capture(False)
                desktop_gpu,reference_backend = capture(True)
                assert backend == reference_backend
                for index, fixture in enumerate(fixtures["cases"]):
                    reference = Image.open(io.BytesIO(base64.b64decode(fixture["png"]))).convert("RGBA")
                    for background, color in enumerate(["black", "white"]):
                        software = Image.alpha_composite(Image.new("RGBA", (128, 128), color), reference).convert("RGB")
                        x, y = index % 7 * 256 + background * 128, index // 7 * 128
                        expected = desktop_gpu.crop((x, y, x+128, y+128))
                        tile = actual.crop((x, y, x + 128, y + 128))
                        gaps = [abs(a-e) for a,e in zip(tile.tobytes(),expected.tobytes())]
                        raster_gaps = [abs(a-e) for a,e in zip(tile.tobytes(),software.tobytes())]
                        gap = max(gaps)
                        if gap > 2:
                            tile.save(args.output / f"failed-{fixture['name']}-{color}.png")
                            expected.save(args.output / f"expected-{fixture['name']}-{color}.png")
                        results.append({"name": fixture["name"], "background": color, "maxChannelGap": gap,
                                        "failedChannels": sum(value > 2 for value in gaps),
                                        "softwareVsGpuMaxChannelGap":max(raster_gaps),
                                        "softwareVsGpuChannelsAbove2":sum(value > 2 for value in raster_gaps)})
                report = {"engine": args.engine, "version": browser.version, "backend": backend,
                          "reference":"desktop native canonical geometry on the same WebGL backend",
                          "cases": results, "limit": 2, "maxChannelGap": max(item["maxChannelGap"] for item in results)}
                (args.output / "results.json").write_text(json.dumps(report, indent=2) + "\n")
                assert not errors, errors
                failed = [case for case in results if case["failedChannels"]]
                assert not failed, failed
                print(json.dumps({key: value for key,value in report.items() if key != "cases"}, indent=2))
            finally:
                browser.close()
    finally:
        server.shutdown()
        server.server_close()
        thread.join()


if __name__ == "__main__":
    main()
