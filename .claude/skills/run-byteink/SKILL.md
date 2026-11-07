---
name: run-byteink
description: Build, run, drive and screenshot ByteInk's sample apps, the Compose Desktop notebook viewer (:samples:viewer:run) and the Kotlin/Wasm browser sample (:samples:wasm). Use when asked to start or run the viewer or Wasm sample, try a drawing, eraser, rendering or UI change in the real app, take a screenshot of it, build the native libraries or the Wasm engine on a clean machine, or run the JVM, Wasm or browser tests.
---

ByteInk is a library, so its running surfaces are two samples. Drive both with
`.claude/skills/run-byteink/driver.py`: it starts a private Xvfb, sends the viewer real X11 mouse
input through `MouseDriver.java`, and keeps the Wasm sample open in a long-lived headless Chromium.
Library and native changes often need only the "Direct invocation" section. Paths are relative to
the repo root.

Every `bash` block below was run literally, in this order, on a brand-new Ubuntu 24.04 rootfs (rootless chroot,
no GPU, no display), and all but Prerequisites and Build also on Fedora 44. The `sh` blocks were run on Fedora 44.

## Prerequisites

Bare Ubuntu 24.04. `apt-get` hangs at a `tzdata` prompt without the noninteractive frontend, even with `-y`.

```bash
sudo apt-get update
sudo env DEBIAN_FRONTEND=noninteractive apt-get install -y git curl ca-certificates libxml2 python3 python3-venv xvfb openjdk-21-jdk-headless libxi6 libxtst6 xz-utils cmake ninja-build g++
```

Any JDK that can start `./gradlew` will do: Gradle downloads the Azul JDK 27 it asks for itself. `xz-utils`,
`cmake`, `ninja-build` and `g++` are only for the Wasm engine.

## Build

Only on a clone without `native/build/out/<commit>/{linux-x86_64,wasm}/`, and only the half you need: the
first line for the viewer, the rest for the Wasm sample. About 3 minutes and 1.5 minutes on 24 cores, with
Bazel's 1.7 GB download cache already filled. `JAVA_HOME` is needed even for the browser engine, because
its host `protoc` build runs a CMake step that looks for `jni.h`.

```bash
native/build-linux.sh
export JAVA_HOME="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
native/setup-wasm.sh
native/build-wasm.sh
```

## Run the viewer (agent path)

```bash
.claude/skills/run-byteink/driver.py viewer-start "$PWD/conformance/android/fixtures/matrix/synthetic.vive"
.claude/skills/run-byteink/driver.py viewer control "Partial eraser" drag 200 195 215 640 40 shot cut
.claude/skills/run-byteink/driver.py viewer-stop
```

Omit the notebook for a blank page in Pen mode. Screenshots land in `build/run-byteink/shots/`, logs in
`build/run-byteink/*.log`. **Open the screenshot and look at it.** `viewer-start` prints every control's
position; a first build needs up to `BYTEINK_START_TIMEOUT` seconds (default 600).

| `viewer` command | what it does |
|---|---|
| `control NAME` | click a toolbar label by name: Open, Previous, Next, Minus, Plus, Fit, Pan, Pen, Calligraphy, Highlighter, "Partial eraser", "Object eraser", Smoothing |
| `layout` | print each control's position, and `Page` (where the canvas starts) |
| `click X Y`, `drag X1 Y1 X2 Y2 [STEPS]` | real mouse input, in screenshot pixels |
| `move X Y [STEPS]`, `down`, `up` | hold a gesture open, for example to screenshot the eraser swath |
| `sleep MS`, `shot NAME` | wait; save `shots/NAME.png` |

## Run the Wasm sample (agent path)

```bash
.claude/skills/run-byteink/driver.py wasm-setup
.claude/skills/run-byteink/driver.py wasm-start
.claude/skills/run-byteink/driver.py wasm drag 60 90 900 120 tool erase drag 470 30 480 430 status shot erased
.claude/skills/run-byteink/driver.py wasm button reload status errors
.claude/skills/run-byteink/driver.py wasm open verify=1 status eval 'byteinkRenderingChecks.cases' errors
.claude/skills/run-byteink/driver.py wasm-stop
```

`wasm-setup` installs Playwright in `build/run-byteink/venv` and uses an installed Chromium if it finds
one (`BYTEINK_CHROMIUM` chooses). Otherwise it downloads Playwright's, with its system libraries on Debian
and Ubuntu, which needs root or sudo. `wasm-start --no-build` skips Gradle.
The page keeps its ink in memory, so one browser session serves all `wasm` commands until `wasm-stop`.

| `wasm` command | what it does |
|---|---|
| `tool NAME` | `marker`, `highlighter`, `erase` or `object` |
| `button NAME` | `move`, `undo`, `reload` or `clear` |
| `drag`, `move`, `down`, `up`, `click`, `sleep`, `shot` | as in the viewer, but CSS pixels inside the ink surface |
| `status`, `eval JS`, `errors` | status line, page JavaScript, page errors |
| `open [QUERY]` | reload the page; `verify=1` runs the 63 rendering checks |

`driver.py status` shows what is running; `driver.py stop` stops everything.

## Direct invocation (no window)

`driver.py gradle ARGS...` is `./gradlew` with no desktop display, Node from the Emscripten SDK if there is
no other, and only the native libraries this checkout has. Export every page of a notebook to PNG; use
absolute paths, because the application runs from `samples/viewer`:

```bash
.claude/skills/run-byteink/driver.py gradle :samples:viewer:run --args="--render-all $PWD/conformance/android/fixtures/matrix/synthetic.vive $PWD/build/run-byteink/render"
```

To call library code directly, use a throwaway test, then delete it. This is how the wedge below was found.
Keep `--rerun`: without it Gradle's build cache can skip an unchanged test and print nothing.

```bash
mkdir -p samples/viewer/src/test/kotlin/scratch
cat > samples/viewer/src/test/kotlin/scratch/ScratchTest.kt <<'EOF'
package scratch

import com.vivenotes.byteink.kit.ViveInkPage
import com.vivenotes.byteink.testing.ViveNotebook
import java.io.File
import kotlin.test.Test

class ScratchTest {
    @Test
    fun look() {
        ViveNotebook.open(File("../../conformance/android/fixtures/matrix/synthetic.vive")).use { notebook ->
            val page = notebook.page(notebook.pageIds.first())
            val strokes = ViveInkPage.load(page.strokes, page.erases, page.moves).strokes
            strokes.forEach { println("RESULT ${it.brushFamily} size=${it.stroke.brush.size} bounds=${it.pageBounds}") }
        }
    }
}
EOF
.claude/skills/run-byteink/driver.py gradle :samples:viewer:test --tests 'scratch.ScratchTest' --rerun --info 2>&1 | grep -E 'RESULT|BUILD|FAILED|^e: '
rm -r samples/viewer/src/test/kotlin/scratch
```

## Run (human path)

Both open or serve a real window for a person; neither is useful headless. Ctrl-C stops them.

```sh
./gradlew :samples:viewer:run
./gradlew -PbyteinkWasm=true :samples:wasm:wasmJsBrowserProductionRun
```

The second one serves `http://localhost:8080`, calls your browser, and **binds all network interfaces**.

## Test

```bash
.claude/skills/run-byteink/driver.py gradle :samples:viewer:test :byteink-kit:test
.claude/skills/run-byteink/driver.py gradle -PbyteinkWasm=true :byteink-model:wasmJsNodeTest :byteink-wasm:wasmJsNodeTest :byteink-portable:wasmJsNodeTest
.claude/skills/run-byteink/driver.py wasm-test
.claude/skills/run-byteink/driver.py gradle check -PbyteinkPortable=true -x :consumer-tests:test
```

Expected: 10 viewer and 132 kit tests pass, the browser test prints its JSON report, and `check` runs 3,445 JVM
tests with 0 failures and 13 skipped (real-notebook tests need private notebooks; the rest are native-loader cases).
`check` took 34 s on the clean machine, with its modules already compiled.

With all four platforms' natives in `native/build/out/<commit>/` (your CI artifacts, or an existing checkout), the
complete build also covers `consumer-tests`. On a clone with only Linux natives it stops at the arm64 task below.

```sh
.claude/skills/run-byteink/driver.py gradle build -PbyteinkPortable=true --continue
```

CI's remaining checks (oracle, Android fidelity, native windows, Wayland) are in `.github/workflows/jvm-linux.yml`
and were not run for this skill.

## Gotchas

- **Never run `Xvfb -displayfd` or `xvfb-run -d`.** On a desktop whose Xwayland has no abstract socket
  (Hyprland), they take `:0` and unlink the desktop's `/tmp/.X11-unix/X0`, so new X11 apps go to the
  invisible server and then fail. The driver uses `:90` and up; `xvfb-run -a` starts at 99. A driver
  reporting a display below 90 is a stop-now bug. Repair: stop that Xvfb, then
  `ln /tmp/.X11-unix/X0_ /tmp/.X11-unix/X0`.
- **Don't hard-code toolbar coordinates.** Fonts move labels between machines ("Partial eraser" was at
  394,104 on Fedora and 407,99 on Ubuntu, and the page starts 12 px higher there), and a selected
  tool's brackets shift later labels. Use `control NAME`. It fails if the toolbar no longer matches
  `CONTROLS` in `MouseDriver.java`; update that when `Main.kt` changes.
- **A wedge of extra ink after a cut is the engine, not the viewer.** `Stroke.subtract` at the pinned
  google/ink commit can return a spurious triangle, in under 0.1% of test cuts and large only for long,
  exactly vertical masks. Drag at a slight slant unless testing this.
- **`test` is not all the tests.** It runs 451; the conformance and loader suites are `jvmTest` tasks that only
  `check` runs. Old XML under `build/test-results/` also survives a narrower run, so don't count across runs.
- **Pixel tests miss layout bugs.** A label wrapping in the toolbar pushed the page down 49 px with every
  pixel test green; only a screenshot showed it. The Wasm `byteinkVisible()` and `byteinkPixel()` probes
  read a software bitmap, so they can't prove painting either (see `native/WASM.md`).
- **`viewer-stop` makes the viewer's Gradle task exit 1.** That is the stop, not a failure.
- **Other Gradle commands work while the viewer runs**; they start a second daemon.

## Troubleshooting

- **`apt-get install` sits at a `tzdata` prompt forever**: use the noninteractive line in Prerequisites.
- **`ld.lld: error while loading shared libraries: libxml2.so.2`** in `native/build-linux.sh`: install `libxml2`.
- **`['tar', '-xf', '.../node-...tar.xz', ...] failed with error code 2`** in `native/setup-wasm.sh`: install `xz-utils`.
- **`native/build-wasm.sh: line 26: cmake: command not found`**: install `cmake ninja-build g++`.
- **`Could not find JNI_INCLUDE_DIR using the following files: jni.h`** in `native/build-wasm.sh`: export `JAVA_HOME`
  as in Build; it must be a JDK with `include/jni.h`.
- **`Missing byteink-engine.mjs; run native/setup-wasm.sh and native/build-wasm.sh`** from `wasm-start`: build the engine.
- **`python3 -m venv ... returned non-zero exit status 1`** from `wasm-setup`: install `python3-venv`.
- **`A problem occurred starting process 'command 'npm''`** in `:kotlinWasmToolingSetup`: Wasm tasks need Node, and a
  reused Gradle daemon finds commands on the `PATH` it started with. Use `driver.py gradle`, which runs a fresh
  JVM when the Emscripten SDK has the only Node.
- **`.../linux-arm64/libink.so does not exist: build it with native/build-linux-arm64.sh`** from plain Gradle
  on a fresh clone: the loader bundles four platforms by default. Add `-PbyteinkBundledPlatforms=linux-x86_64`;
  the driver does this itself.
- **`UnsatisfiedLinkError: .../libawt_xawt.so: libXi.so.6: cannot open shared object file`**: install `libxi6 libxtst6`.
- **`JAVA_HOME is not set and no 'java' command could be found`**: install a JDK.
- **`Some problems were found with the configuration of task ':ink-nativeloader:linuxArm64DebugSymbolsMetadata'`**
  on a clone with only Linux natives: `consumer-tests` needs every platform's. Use `check -x :consumer-tests:test`,
  as in Test.
- **`HeadlessException: No X11 DISPLAY variable was set`**: run through the driver, or set `DISPLAY`.
- **`NoSuchFileException: conformance/...`**: a relative path through Gradle; use an absolute one.
- **`project 'wasm' not found in project ':samples'`**: add `-PbyteinkWasm=true`.
