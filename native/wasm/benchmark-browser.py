#!/usr/bin/env python3
"""Record repeatable browser CPU/software costs, separately from physical/display latency."""
import argparse
import functools
import json
import platform
import threading
from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class QuietHandler(SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine",choices=["chromium","firefox","webkit"],default="chromium")
    parser.add_argument("--browser")
    parser.add_argument("--forks",type=int,default=3)
    parser.add_argument("--site",type=Path,default=Path("samples/wasm/build/dist/wasmJs/productionExecutable"))
    parser.add_argument("--output",type=Path,default=Path("build/wasm-benchmark"))
    args=parser.parse_args()
    assert args.forks>0
    args.output.mkdir(parents=True,exist_ok=True)
    from playwright.sync_api import sync_playwright
    server=ThreadingHTTPServer(("127.0.0.1",0),functools.partial(QuietHandler,directory=str(args.site.resolve())))
    thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    forks=[]
    try:
        with sync_playwright() as p:
            for fork in range(args.forks):
                browser=getattr(p,args.engine).launch(executable_path=args.browser,headless=True)
                try:
                    page=browser.new_page()
                    page.set_default_timeout(120000)
                    errors=[];page.on("pageerror",lambda error:errors.append(str(error)))
                    page.goto(f"http://127.0.0.1:{server.server_port}/?benchmark=1")
                    page.wait_for_function("globalThis.byteinkBenchmark")
                    assert not errors,errors
                    report=page.evaluate("globalThis.byteinkBenchmark")
                    report["resources"]=page.evaluate("performance.getEntriesByType('resource').map(r=>({name:r.name.split('/').pop(),encodedBytes:r.encodedBodySize,decodedBytes:r.decodedBodySize,durationMillis:r.duration}))")
                    forks.append(report)
                    print(json.dumps({"engine":args.engine,"fork":fork,"engineLoadMillis":report["engineLoadMillis"]}),flush=True)
                    version=browser.version
                finally:
                    browser.close()
    finally:
        server.shutdown();server.server_close();thread.join()
    assets={path.name:path.stat().st_size for path in args.site.iterdir() if path.suffix in [".wasm",".mjs",".js"]}
    result={"engine":args.engine,"browserVersion":version,"host":platform.platform(),"machine":platform.machine(),
            "scope":"CPU and offscreen software/readback; no GPU/display/physical pen latency", "staticAssetBytes":assets,"forks":forks}
    (args.output/"results.json").write_text(json.dumps(result,indent=2)+"\n")


if __name__=="__main__":
    main()
