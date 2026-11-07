#!/usr/bin/env python3
"""Runs and drives byteink's two sample apps on a private display. See SKILL.md for usage.

  viewer-start [NOTEBOOK.vive]   Launch :samples:viewer:run on a private Xvfb and wait for its window.
  viewer COMMAND...              Real mouse input: control NAME, layout, click X Y, drag X1 Y1 X2 Y2 [STEPS],
                                 move X Y [STEPS], down, up, sleep MS, shot NAME. Coordinates are screenshot pixels.
  viewer-stop

  wasm-setup                     Install Playwright in build/run-byteink/venv, and its Chromium if none is installed.
  wasm-start [--no-build]        Build :samples:wasm, serve it on 127.0.0.1 and open it in headless Chromium.
  wasm COMMAND...                tool NAME, button NAME, drag X1 Y1 X2 Y2 [STEPS], move X Y [STEPS], down, up,
                                 click X Y, sleep MS, shot NAME, status, eval JS, errors, open [QUERY].
                                 Coordinates are CSS pixels inside the ink surface.
  wasm-stop
  wasm-test                      The repo's browser acceptance test (native/wasm/browser-test.py) against the built sample.

  gradle ARGS...                 ./gradlew with no desktop display, Node from the Emscripten SDK if there is
                                 no other, and only the native libraries this checkout has.
  status                         What is running, and where its logs and screenshots are.
  stop                           Stop everything this driver started.
"""
import json
import os
import pathlib
import shutil
import signal
import socket
import subprocess
import sys
import time

ROOT = pathlib.Path(__file__).resolve().parents[3]
HERE = pathlib.Path(__file__).resolve().parent
WORK = ROOT / "build" / "run-byteink"
SHOTS = WORK / "shots"
STATE = WORK / "state.json"
SOCKET = WORK / "wasm.sock"
VENV_PYTHON = WORK / "venv" / "bin" / "python"
SITE = ROOT / "samples" / "wasm" / "build" / "dist" / "wasmJs" / "productionExecutable"
VIEWER_MAIN = "com.vivenotes.byteink.viewer.MainKt"
PLAYWRIGHT = "playwright==1.63.0"  # The version .github/workflows/wasm.yml installs.


def load_state():
    return json.loads(STATE.read_text()) if STATE.exists() else {}


def save_state(state):
    WORK.mkdir(parents=True, exist_ok=True)
    STATE.write_text(json.dumps(state, indent=2) + "\n")


def process_start(pid):
    """When this PID started, or None once it is gone or a zombie (this driver doesn't reap its children)."""
    try:
        fields = pathlib.Path(f"/proc/{pid}/stat").read_text().rsplit(")", 1)[1].split()
    except (OSError, IndexError):
        return None
    return None if fields[0] == "Z" else fields[19]


def handle(pid):
    """A PID with its start time: state.json can outlive a reboot, and PIDs get reused."""
    return f"{pid}:{process_start(pid)}"


def pid_of(process):
    return int(process.split(":")[0])


def alive(process):
    """Whether this exact process still runs. Everything that signals a process checks this first."""
    if not process:
        return False
    pid, start = process.split(":")
    return process_start(pid) == start


def stop_process(process, seconds=10):
    if not alive(process):
        return
    os.kill(pid_of(process), signal.SIGTERM)
    deadline = time.time() + seconds
    while alive(process) and time.time() < deadline:
        time.sleep(0.1)
    if alive(process):
        os.kill(pid_of(process), signal.SIGKILL)


def die(message, log=None):
    print(f"ERROR: {message}", file=sys.stderr)
    if log and pathlib.Path(log).exists():
        lines = pathlib.Path(log).read_text(errors="replace").splitlines()
        # A cause sits above a long stack trace or Gradle's advice, so a plain tail would miss it.
        causes = [line for line in lines if "Exception" in line or "Error:" in line or "error:" in line]
        if "* What went wrong:" in lines:
            start = lines.index("* What went wrong:")
            causes += lines[start:start + 6]
        print(f"--- from {log}", file=sys.stderr)
        print("\n".join(causes[:12] + ["..."] + lines[-5:]), file=sys.stderr)
    sys.exit(1)


def background(command, log, env=None, cwd=ROOT, **kwargs):
    """Starts a process that outlives this driver, in its own session so nothing of ours can signal it."""
    WORK.mkdir(parents=True, exist_ok=True)
    with open(log, "w") as output:
        return handle(subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=output,
                                       stderr=subprocess.STDOUT, start_new_session=True, **kwargs).pid)


def display_is_free(number):
    """Whether nothing claims this display by any of the three ways an X server can."""
    if pathlib.Path(f"/tmp/.X{number}-lock").exists():
        return False
    if any(pathlib.Path(f"/tmp/.X11-unix/X{number}{suffix}").exists() for suffix in ("", "_")):
        return False
    with socket.socket(socket.AF_UNIX) as probe:
        try:
            probe.bind(f"\0/tmp/.X11-unix/X{number}")
        except OSError:
            return False
    return True


def ensure_display(state):
    """
    A private X server, on a number no desktop uses. Never Xvfb's -displayfd: it takes the first number
    without an abstract socket and unlinks that number's socket path, without honouring the lock file.
    Hyprland's Xwayland has no abstract socket, so -displayfd takes over the user's :0 (see SKILL.md).
    """
    if alive(state.get("xvfb")):
        return state["display"]
    if not shutil.which("Xvfb"):
        die("Xvfb is not installed; see Prerequisites in SKILL.md")
    for number in range(90, 200):
        if not display_is_free(number):
            continue
        xvfb = handle(subprocess.Popen(["Xvfb", f":{number}", "-screen", "0", "1280x900x24", "-nolisten", "tcp"],
                                       stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                                       start_new_session=True).pid)
        deadline = time.time() + 10
        while alive(xvfb) and time.time() < deadline:
            if pathlib.Path(f"/tmp/.X11-unix/X{number}").exists():
                state.update(xvfb=xvfb, display=f":{number}")
                save_state(state)
                return state["display"]
            time.sleep(0.05)
        stop_process(xvfb)  # Another server won the race for this number, or this one hung: try the next.
    die("no free X display between :90 and :199")


def private_env(display):
    env = dict(os.environ, DISPLAY=display)
    env.pop("WAYLAND_DISPLAY", None)
    return env


def stop_display_if_unused(state):
    if not alive(state.get("viewer")) and not alive(state.get("browser")):
        stop_process(state.pop("xvfb", None))
        state.pop("display", None)


# ---- native libraries ------------------------------------------------------------------------

def pinned_commit():
    for line in (ROOT / "upstream" / "pins.properties").read_text().splitlines():
        if line.startswith("google.ink.commit="):
            return line.split("=", 1)[1]
    die("upstream/pins.properties has no google.ink.commit")


def native_out():
    return ROOT / "native" / "build" / "out" / pinned_commit()


def gradle_platform_args():
    """The loader jar bundles all four platforms by default; a checkout with fewer can still run here."""
    libraries = {"linux-x86_64": "libink.so", "linux-arm64": "libink.so",
                 "windows-x86_64": "ink.dll", "windows-arm64": "ink.dll"}
    present = [name for name, library in libraries.items() if (native_out() / name / library).exists()]
    return [] if not present or len(present) == len(libraries) else [f"-PbyteinkBundledPlatforms={','.join(present)}"]


def gradle(args):
    env = wasm_env()
    env.pop("DISPLAY", None)
    env.pop("WAYLAND_DISPLAY", None)
    node = wasm_gradle_args() if "-PbyteinkWasm=true" in args else []
    sys.exit(subprocess.run(["./gradlew", "--console=plain", *node, *gradle_platform_args(), *args], cwd=ROOT, env=env).returncode)


# ---- viewer ----------------------------------------------------------------------------------

def find_viewer(display):
    """The viewer JVM is the Gradle daemon's child, not ours, so find it by main class and display."""
    for entry in pathlib.Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            if VIEWER_MAIN.encode() not in (entry / "cmdline").read_bytes():
                continue
            if f"DISPLAY={display}".encode() in (entry / "environ").read_bytes().split(b"\0"):
                return int(entry.name)
        except OSError:
            continue
    return None


def mouse(state, args):
    java = os.readlink(f"/proc/{pid_of(state['viewer'])}/exe")
    SHOTS.mkdir(parents=True, exist_ok=True)
    return subprocess.run([java, "-Djava.awt.headless=false", str(HERE / "MouseDriver.java"), *args],
                          cwd=SHOTS, env=private_env(state["display"])).returncode


def png_names(args):
    """shot NAME gets its extension here, so both drivers accept the same word."""
    result = list(args)
    for i, word in enumerate(result[:-1]):
        if word == "shot" and not result[i + 1].endswith(".png"):
            result[i + 1] += ".png"
    return result


def viewer_start(args, state):
    if alive(state.get("viewer")):
        die(f"the viewer is already running on {state['display']}; run viewer-stop first")
    if not (native_out() / "linux-x86_64" / "libink.so").exists():
        die(f"{native_out()}/linux-x86_64/libink.so is missing; run native/build-linux.sh first (see Build in SKILL.md)")
    command = ["./gradlew", ":samples:viewer:run", "--console=plain", *gradle_platform_args()]
    if args:
        notebook = pathlib.Path(args[0]).resolve()
        if not notebook.is_file():
            die(f"no such notebook: {notebook}")
        # Gradle runs the application from samples/viewer, so a relative path would miss.
        command.append(f"--args={notebook}")
    display = ensure_display(state)
    log = WORK / "viewer.log"
    gradle = background(command, log, env=private_env(display))
    state["viewer_gradle"] = gradle
    save_state(state)
    deadline = time.time() + float(os.environ.get("BYTEINK_START_TIMEOUT", 600))
    while True:
        pid = find_viewer(display)
        if pid:
            break
        if not alive(gradle):
            stop_display_if_unused(state)
            save_state(state)
            die("./gradlew :samples:viewer:run stopped before the viewer started", log)
        if time.time() > deadline:
            die("the viewer did not start in time; build first or raise BYTEINK_START_TIMEOUT", log)
        time.sleep(0.25)
    state["viewer"] = handle(pid)
    save_state(state)
    if mouse(state, ["window", "90"]) != 0:
        die("the viewer started but never painted a window", log)
    mouse(state, ["shot", "viewer-start.png", "layout"])
    print(f"viewer pid {pid} on {display}. Log: {log}")


def viewer(args, state):
    if not alive(state.get("viewer")):
        die("the viewer is not running; run viewer-start first", WORK / "viewer.log")
    sys.exit(mouse(state, png_names(args)))


def viewer_stop(state):
    stop_process(state.pop("viewer", None))
    # Killing the application makes its Gradle task fail: that exit is this stop, not a problem.
    deadline = time.time() + 15
    while alive(state.get("viewer_gradle")) and time.time() < deadline:
        time.sleep(0.1)
    stop_process(state.pop("viewer_gradle", None))
    stop_display_if_unused(state)
    save_state(state)
    print("viewer stopped")


# ---- wasm ------------------------------------------------------------------------------------

def wasm_env(env=None):
    """Gradle's Wasm tasks need node on PATH; a machine without one uses the Emscripten SDK's."""
    env = dict(env or os.environ)
    if not shutil.which("node", path=env.get("PATH")):
        nodes = sorted((ROOT / "native" / "build" / "emsdk" / "node").glob("*/bin"))
        if nodes:
            env["PATH"] = f"{nodes[-1]}{os.pathsep}{env['PATH']}"
    return env


def wasm_gradle_args():
    """
    A Gradle daemon finds commands on the PATH it started with, not the client's. One started without Node,
    as the viewer's is on a machine whose only Node is the SDK's, can't find npm, so use a fresh JVM there.
    """
    return [] if shutil.which("node") else ["--no-daemon"]


def wasm_setup():
    pip = VENV_PYTHON.with_name("pip")
    if not pip.exists():  # Without python3-venv a first attempt leaves a venv that has no pip.
        WORK.mkdir(parents=True, exist_ok=True)
        subprocess.run([sys.executable, "-m", "venv", "--clear", str(VENV_PYTHON.parents[1])], check=True)
    subprocess.run([str(pip), "install", "--quiet", "--disable-pip-version-check", PLAYWRIGHT], check=True)
    browser = system_chromium()
    if browser:
        print(f"Using the installed {browser}; set BYTEINK_CHROMIUM to choose another")
        return
    # Playwright knows which system libraries its Chromium needs on Debian and Ubuntu. Needs root or sudo.
    deps = ["--with-deps"] if shutil.which("apt-get") else []
    subprocess.run([str(VENV_PYTHON), "-m", "playwright", "install", *deps, "chromium"], check=True)
    print("Playwright and its Chromium are installed")


def system_chromium():
    return os.environ.get("BYTEINK_CHROMIUM") or next(
        filter(None, map(shutil.which, ["chromium-browser", "chromium", "google-chrome"])), None)


def free_port():
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 0))
        return probe.getsockname()[1]


def wasm_start(args, state):
    if alive(state.get("browser")):
        die("the Wasm sample is already open; run wasm-stop first")
    if not VENV_PYTHON.exists():
        die("Playwright is not set up; run wasm-setup first")
    if "--no-build" not in args:
        built = subprocess.run(["./gradlew", "-PbyteinkWasm=true", ":samples:wasm:wasmJsBrowserDistribution",
                                "--console=plain", *wasm_gradle_args()], cwd=ROOT, env=wasm_env(),
                               stdout=open(WORK / "wasm-build.log", "w"), stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL)
        if built.returncode != 0:
            die("the Wasm sample did not build", WORK / "wasm-build.log")
    if not (SITE / "index.html").is_file():
        die(f"{SITE} has not been built; run wasm-start without --no-build")
    port = free_port()
    state["server"] = background([sys.executable, "-m", "http.server", str(port), "--bind", "127.0.0.1",
                                  "--directory", str(SITE)], WORK / "wasm-server.log")
    state["url"] = f"http://127.0.0.1:{port}/"
    # Compose paints with WebGL2; a display makes that as reliable here as in CI (see native/WASM.md).
    display = ensure_display(state)
    SOCKET.unlink(missing_ok=True)
    state["browser"] = background([str(VENV_PYTHON), str(pathlib.Path(__file__).resolve()), "_wasm-session", state["url"]],
                                  WORK / "wasm-session.log", env=private_env(display))
    save_state(state)
    deadline = time.time() + 90
    while not SOCKET.exists():
        if not alive(state["browser"]):
            wasm_stop(load_state(), quiet=True)
            die("the browser session stopped before the sample was ready", WORK / "wasm-session.log")
        if time.time() > deadline:
            die("the sample was not ready in 90 s", WORK / "wasm-session.log")
        time.sleep(0.2)
    print(f"Wasm sample at {state['url']} in headless Chromium on {display}.")
    sys.exit(wasm(["status", "shot", "wasm-start"], state, exit_when_done=False))


def wasm(args, state, exit_when_done=True):
    if not alive(state.get("browser")) or not SOCKET.exists():
        die("the Wasm sample is not open; run wasm-start first", WORK / "wasm-session.log")
    with socket.socket(socket.AF_UNIX) as connection:
        connection.connect(str(SOCKET))
        connection.sendall((json.dumps(png_names(args)) + "\n").encode())
        reply = json.loads(connection.makefile().readline())
    if reply["output"]:
        print(reply["output"])
    if reply["error"]:
        print(f"ERROR: {reply['error']}", file=sys.stderr)
    code = 1 if reply["error"] else 0
    if exit_when_done:
        sys.exit(code)
    return code


def wasm_stop(state, quiet=False):
    stop_process(state.pop("browser", None))
    stop_process(state.pop("server", None))
    state.pop("url", None)
    SOCKET.unlink(missing_ok=True)
    stop_display_if_unused(state)
    save_state(state)
    if not quiet:
        print("Wasm sample stopped")


def wasm_test(state):
    if not VENV_PYTHON.exists():
        die("Playwright is not set up; run wasm-setup first")
    display = ensure_display(state)
    command = [str(VENV_PYTHON), "native/wasm/browser-test.py", "--engine", "chromium", "--output", str(WORK / "browser-test")]
    browser = system_chromium()
    code = subprocess.run(command + (["--browser", browser] if browser else []), cwd=ROOT, env=private_env(display)).returncode
    stop_display_if_unused(state)
    save_state(state)
    sys.exit(code)


def wasm_session(url):
    """The long-lived browser: the sample keeps all its ink in memory, so one page must serve every command."""
    from playwright.sync_api import sync_playwright

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(headless=True, executable_path=system_chromium())
        page = browser.new_page(viewport={"width": 1100, "height": 760})
        errors = []
        page.on("pageerror", lambda error: errors.append(f"pageerror: {error}"))

        def on_console(message):
            # The sample has no favicon, so every page load logs one harmless 404.
            if message.type == "error" and not message.location.get("url", "").endswith("/favicon.ico"):
                errors.append(f"console: {message.text} ({message.location.get('url')})")

        page.on("console", on_console)

        def load(address):
            page.goto(address)
            page.wait_for_function("globalThis.byteinkReady === true", timeout=60_000)

        def at(x, y):
            box = page.locator("#ink-surface").bounding_box()
            return box["x"] + float(x), box["y"] + float(y)

        def glide(x, y, steps):
            page.mouse.move(*at(x, y), steps=steps)

        def run(words):
            output = []
            words = list(words)
            while words:
                command = words.pop(0)
                if command == "tool":  # marker, highlighter, erase or object: the dropdown's values.
                    page.select_option("#tool", words.pop(0))
                elif command == "button":  # move, undo, reload or clear: the buttons' ids.
                    page.click(f"#{words.pop(0)}")
                    page.wait_for_timeout(250)
                elif command == "drag":
                    x1, y1, x2, y2 = (words.pop(0) for _ in range(4))
                    steps = int(words.pop(0)) if words and words[0].isdigit() else 30
                    page.mouse.move(*at(x1, y1))
                    page.mouse.down()
                    glide(x2, y2, steps)
                    page.mouse.up()
                    page.wait_for_timeout(250)
                elif command == "move":
                    x, y = words.pop(0), words.pop(0)
                    glide(x, y, int(words.pop(0)) if words and words[0].isdigit() else 30)
                elif command == "down":
                    page.mouse.down()
                elif command == "up":
                    page.mouse.up()
                    page.wait_for_timeout(250)
                elif command == "click":
                    page.mouse.click(*at(words.pop(0), words.pop(0)))
                    page.wait_for_timeout(250)
                elif command == "sleep":
                    page.wait_for_timeout(int(words.pop(0)))
                elif command == "shot":
                    SHOTS.mkdir(parents=True, exist_ok=True)
                    path = SHOTS / words.pop(0)
                    page.screenshot(path=str(path))
                    output.append(f"screenshot {path}")
                elif command == "status":
                    output.append(f"status: {page.locator('#status').inner_text()}")
                elif command == "eval":
                    output.append(json.dumps(page.evaluate(words.pop(0))))
                elif command == "errors":
                    output.append("\n".join(errors) or "no page errors")
                elif command == "open":  # For example: open verify=1
                    load(url + (f"?{words.pop(0)}" if words else ""))
                else:
                    raise ValueError(f"unknown command: {command}")
            return "\n".join(output)

        load(url)
        with socket.socket(socket.AF_UNIX) as server:
            server.bind(str(SOCKET))
            server.listen()
            while True:
                connection, _ = server.accept()
                with connection:
                    try:
                        reply = {"output": run(json.loads(connection.makefile().readline())), "error": None}
                    except Exception as failure:  # The session must survive one bad command.
                        reply = {"output": "", "error": str(failure)}
                    connection.sendall((json.dumps(reply) + "\n").encode())


# ---- both ------------------------------------------------------------------------------------

def status(state):
    print(f"viewer: {'pid ' + str(pid_of(state['viewer'])) if alive(state.get('viewer')) else 'stopped'}")
    print(f"wasm:   {state['url'] if alive(state.get('browser')) else 'stopped'}")
    print(f"display: {state.get('display') or 'none'}")
    print(f"screenshots: {SHOTS}")
    print(f"logs: {WORK}/*.log")


def main():
    signal.signal(signal.SIGPIPE, signal.SIG_DFL)  # So `driver.py status | head` ends quietly.
    if len(sys.argv) < 2 or sys.argv[1] in ("-h", "--help", "help"):
        print(__doc__)
        return
    command, args = sys.argv[1], sys.argv[2:]
    if command == "_wasm-session":
        wasm_session(args[0])
        return
    state = load_state()
    if command == "viewer-start":
        viewer_start(args, state)
    elif command == "viewer":
        viewer(args, state)
    elif command == "viewer-stop":
        viewer_stop(state)
    elif command == "wasm-setup":
        wasm_setup()
    elif command == "wasm-start":
        wasm_start(args, state)
    elif command == "wasm":
        wasm(args, state)
    elif command == "wasm-stop":
        wasm_stop(state)
    elif command == "wasm-test":
        wasm_test(state)
    elif command == "gradle":
        gradle(args)
    elif command == "status":
        status(state)
    elif command == "stop":
        viewer_stop(state)
        wasm_stop(load_state())
    else:
        die(f"unknown command: {command}; run with --help")


if __name__ == "__main__":
    main()
