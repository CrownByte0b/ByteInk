# ByteInk — AndroidX Ink for Kotlin Desktop

[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.20-blue.svg?logo=kotlin)](https://kotlinlang.org)
[![Compose Multiplatform](https://img.shields.io/badge/Compose%20Multiplatform-1.12.1-4285F4.svg)](https://www.jetbrains.com/compose-multiplatform/)
[![GitHub Packages](https://img.shields.io/badge/GitHub%20Packages-0.1.0--alpha.1-2ea44f.svg?logo=github)](https://github.com/orgs/CrownByte0b/packages)
[![CI](https://github.com/CrownByte0b/ByteInk/actions/workflows/ci.yml/badge.svg)](https://github.com/CrownByte0b/ByteInk/actions/workflows/ci.yml)
[![Wiki](https://img.shields.io/badge/docs-wiki-8A2BE2.svg)](https://crownbyte0b.github.io/ByteInk/)

ByteInk is a reusable Kotlin/JVM library that brings the real **AndroidX Ink / `google/ink` stroke engine** to **Linux x86_64 and Windows x86_64**,
primarily for ViveNotes’ Kotlin Multiplatform desktop app.

The goal is to generate and render strokes using the same engine and brush definitions as the Android app, preserving stroke geometry, stabilization
behaviour and stored ink compatibility across platforms.

## Why use ?

- AndroidX Ink's desktop support stops at "compute strokes on Linux or macOS". ByteInk adds everything else a desktop notes app needs.
- 1 to 1 compatibility with AndroidX Ink **1.1.0-alpha06** (see [Compatible version](#compatible-version))
- Wayland native support.
- Only choice for Kotlin Multi platform on desktop

### Compatible version

| ByteInk         | AndroidX Ink    | Native engine (`google/ink`) |
| --------------- | --------------- | ---------------------------- |
| `0.1.0-alpha.1` | `1.1.0-alpha06` | `96e50239e1c8`               |

### Why not just use AndroidX Ink's KMP artifacts?

1. **Only the engine half runs on the JVM.** Google publishes JVM builds of `geometry`, `brush`, `strokes`, `storage` and
   `nativeloader`, but not of `ink-rendering`, `ink-authoring` or any `*-compose` module, since those depend on Android's
   Canvas, View and MotionEvent. You can compute a stroke on the desktop, but you can't draw it or capture a pen. ByteInk
   renders with Compose/Skia and captures pen input on Windows, X11 and Wayland.
2. **No Windows.** Google's JVM native loader ships only `linux-x86_64` and `macos-arm64` binaries and throws
   "Unsupported platform" anywhere else. ByteInk builds `ink.dll` and loads it with a narrow fork of the loader.
3. **Google's Linux binary doesn't produce exactly the same geometry as Android.** Android's math library rounds `atan2f`
   and `hypotf` slightly differently from desktop libm, which changes the shape of some real strokes. ByteInk's native
   build uses Android's arithmetic, so desktop geometry matches Android exactly.
4. **Erasing depends on the exact native version.** Partial-erase results changed between `google/ink` commits, so
   ByteInk builds from the commit that matches the AndroidX Ink release the Android app uses, not the latest one.
5. **The ViveNotes note format isn't part of AndroidX Ink.** How notes are saved (stroke rows, partial-erase operations,
   unknown fields, custom brushes) lives in the Android app. `byteink-kit` implements it, so notes written on the desktop
   open on Android and the other way round.
6. **An upstream use-after-free.** AndroidX Ink's JVM classes pass raw native pointers, so garbage collection can free
   an object while native code is still reading it, corrupting memory on Windows. ByteInk's loader delays those frees.

## 🌍 Supported platforms

| Platform | Architecture | Notes                                                       |
| -------- | ------------ | ----------------------------------------------------------- |
| Linux    | `x86_64`     | X11, XWayland, and native Wayland pen/touch input on JBR 25 |
| Windows  | `x86_64`     | `WM_POINTER` pen input                                      |

Requires **Java 25+**. Tested with Kotlin 2.4.20 and Compose Multiplatform 1.12.1. The native `libink.so` and `ink.dll` ship inside the
dependency, so you don't build anything native.

## Add ByteInk to your project

### 1. Add a GitHub token

ByteInk is published to [GitHub Packages](https://github.com/orgs/CrownByte0b/packages). GitHub's Maven registry requires a
login even for public packages. Create a [classic personal access token](https://github.com/settings/tokens/new?scopes=read:packages&description=ByteInk)
with the `read:packages` scope, then add it to `~/.gradle/gradle.properties` (not your project, so it stays out of source control):

```properties
gpr.user=your-github-username
gpr.key=ghp_yourToken
```

In CI, the `GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables work instead.

### 2. Add the repository

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()        // androidx.ink comes from Google's Maven
        mavenCentral()  // Kotlin, Compose
        maven("https://maven.pkg.github.com/crownbyte0b/byteink") {
            content { includeGroup("com.vivenotes.byteink") }
            credentials {
                username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
        }
    }
}
```

### 3. Add the dependencies

In a Compose Desktop app's `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.12.1"
}

kotlin { jvmToolchain(25) }

dependencies {
    implementation(compose.desktop.currentOs)
    implementation("com.vivenotes.byteink:byteink-compose:0.1.0-alpha.1")
    implementation("com.vivenotes.byteink:byteink-kit:0.1.0-alpha.1")
    testImplementation("com.vivenotes.byteink:byteink-testing:0.1.0-alpha.1") // optional test helpers
}

compose.desktop {
    application {
        mainClass = "MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
```

In a Kotlin Multiplatform project, put the two ByteInk dependencies in `jvmMain`:

```kotlin
kotlin {
    jvm()
    sourceSets.jvmMain.dependencies {
        implementation("com.vivenotes.byteink:byteink-compose:0.1.0-alpha.1")
        implementation("com.vivenotes.byteink:byteink-kit:0.1.0-alpha.1")
    }
}
```

`byteink-core`, the native loader (with `libink.so` and `ink.dll`) and the `androidx.ink` modules come in automatically. Don't add Google's
`androidx.ink:ink-nativeloader` yourself; it conflicts with ByteInk's loader.

### 4. Draw

```kotlin
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.ink.strokes.Stroke
import com.vivenotes.byteink.compose.InkDrawingSurface
import com.vivenotes.byteink.compose.drawInk
import com.vivenotes.byteink.compose.rememberInkAuthoringController
import com.vivenotes.byteink.compose.rememberInkMeshRenderer
import com.vivenotes.byteink.kit.ViveInkTool

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "ByteInk") {
        val tool = remember { ViveInkTool(sizeDp = 3f) }
        val controller = rememberInkAuthoringController()
        val renderer = rememberInkMeshRenderer()
        val strokes = remember { mutableStateListOf<Stroke>() }
        DisposableEffect(controller) { onDispose { controller.close() } }

        InkDrawingSurface(
            controller = controller,
            brush = tool.brush,
            modifier = Modifier.fillMaxSize(),
            renderer = renderer,
            onStrokeFinished = { strokes += it },
            drawContent = { strokes.forEach { drawInk(renderer, it) } },
        )
    }
}
```

Next: saving strokes in the Android format, pen and Wayland input, erasing and selection are covered in the
**[wiki](https://crownbyte0b.github.io/ByteInk/)**, starting with [Get started](https://crownbyte0b.github.io/ByteInk/getting-started/).

## Technical approach

ByteInk uses AndroidX Ink’s published JVM APIs, backed by native C++ code through JNI. It builds and packages `libink.so` for Linux and `ink.dll`
for Windows, with a narrowly scoped native-loader fork handling platform detection, extraction and loading.

Rendering is implemented through **Compose Desktop and Skia**, using engine-generated outlines and triangle geometry. The renderer supports finished
and in-progress strokes, affine transforms, viewport culling and bounded geometry caching.

## Intended capabilities

- **Drawing:** pens, highlighters, pressure pens, dashed strokes and ViveNotes’ custom calligraphy brushes, including stabilization.
- **Editing and selection:** hit testing, lasso selection, moves, resizes, whole-stroke erasing and replay of existing partial erases.
- **Persistence:** Android-compatible stroke encoding and decoding, preserving existing rows and unknown data during notebook round trips.
- **Distribution:** reusable Gradle dependencies, with packaged native binaries and isolated upstream modifications.
- **Verification:** native/JVM tests, cross-platform geometry comparisons, Android rendering comparisons and save/load tests in both directions.

# Local builds

## Gui demo

```bash
./gradlew :samples:viewer:run
```

# Dev

## Publish local package

```bash
./gradlew publishToMavenLocal
```

# getting android-ink from google

AndroidX Ink's `ink/` sources at the pinned release, 1.1.0-alpha06: `frameworks/support` commit
`61ee8cd421d0` (see `upstream/pins.properties`), from AndroidX's GitHub mirror.

```bash
git init androidx-ink
cd androidx-ink
git remote add origin https://github.com/androidx/androidx.git
git sparse-checkout set --cone ink
git fetch --depth 1 --filter=blob:none origin 61ee8cd421d0c0252d8db0253b739de537999371
git checkout --detach FETCH_HEAD
```
