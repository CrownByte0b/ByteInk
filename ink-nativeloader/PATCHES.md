# byteink's fork of `androidx.ink:ink-nativeloader`

Google's AndroidX Ink jars for the JVM (`ink-brush`, `ink-geometry`, `ink-strokes`,
`ink-storage`) get their native library through `androidx.ink.nativeloader.NativeLoader`. Google's
loader knows only Linux x86_64 and macOS arm64, so byteink replaces that one module and uses
Google's other jars unchanged. This fork has the same classes, with the same binary interface,
plus a loader for byteink's own native builds.

## What is upstream's

`src/commonMain`, `src/jvmAndAndroidMain` and `src/jvmMain` hold AndroidX's `ink/ink-nativeloader`
sources at the `androidx.support.commit` pin in `upstream/pins.properties`, with `patches/` applied.
The Android and iOS source sets are left out. Everything under `com/vivenotes/byteink` is byteink's
own. The build compiles upstream's `jvmAndAndroidMain` into `jvmMain` and keeps upstream's Kotlin
module name, `ink-nativeloader`.

## The patch

`patches/0001-load-byteink-natives.patch` changes `jvmMain/…/NativeLoader.jvm.kt` so that it
hands loading to `InkNativeLibrary`. Upstream's version has four problems for byteink:

- It maps only `linux-x86_64` and `macos-arm64`, and fails on Windows.
- It extracts the library to a new temporary file on every run. That fails where `/tmp` is mounted
  noexec, and on Windows it leaves a locked DLL behind each time.
- It cannot load a library installed with a packaged application.
- It reads resources whose path Google's own jar also uses, so the jar that comes first on the
  classpath wins.

The patched file notes the change in its licence header, as Apache 2.0 requires.

## What byteink adds

`com.vivenotes.byteink.nativeloader.InkNativeLibrary` loads the library once per class loader:

- **Library property:** `-Dbyteink.ink.library=<absolute path>` names a library to load instead of
  the bundled one.
- **Bundled library:** otherwise the jar's library for the platform is used: Linux x86_64 or
  Windows x86_64, with anything else refused with advice.
- **Cache:** the bundled library is extracted into a per-user cache keyed by its SHA-256:
  `$XDG_CACHE_HOME/byteink/natives/<sha256>/libink.so` (or under `~/.cache`) on Linux, and
  `%LOCALAPPDATA%\byteink\natives\<sha256>\ink.dll` on Windows.
  - A verified file is reused as is.
  - A missing or damaged one is written beside its final name and moved over it in one step, so
    concurrent processes never see half a library.
  - `-Dbyteink.ink.cache=<directory>` names another cache.
  - If the per-user cache cannot be used, a new private temporary directory is.
- **`InkNativeLibrary.loaded`** says which file was loaded, its SHA-256, and where it came from.

The build bundles byteink's natives from `native/build-linux.sh` and `native/build-windows.sh` (or
`-PbyteinkLinuxLibrary` / `-PbyteinkWindowsLibrary`). They go under
`com/vivenotes/byteink/nativeloader/<platform>/`, with `natives.properties` listing their SHA-256.
The loader checks every extraction against that list. Debug symbols are not bundled.

## Checks

Both run as part of `./gradlew :ink-nativeloader:check`:

- **`verifyForkSources`:** `src/` holds exactly the pinned upstream sources with `patches/`
  applied. It fails on any edited, missing or added upstream file.
- **`verifyUpstreamAbi`:** the compiled `androidx.ink.nativeloader` classes offer exactly the
  classes, supertypes and public or protected members that Google's `ink-nativeloader-jvm` jar
  does, so Google's other jars link against them unchanged.

## Moving to another AndroidX commit

1. Move `androidx.support.commit` in `upstream/pins.properties`, and `androidx-ink` in the version
   catalog, together (see `upstream/README.md`).
2. Run `./gradlew :ink-nativeloader:syncForkSources`. It rewrites the upstream sources from the new
   commit with the patches applied, and leaves byteink's own package alone.
3. If a patch no longer applies:
   - Redo the change on the new upstream file in `src/`.
   - Regenerate the patch from the module directory:

     ```sh
     diff -u --label a/jvmMain/kotlin/androidx/ink/nativeloader/NativeLoader.jvm.kt \
       --label b/jvmMain/kotlin/androidx/ink/nativeloader/NativeLoader.jvm.kt \
       build/upstream/ink/ink-nativeloader/src/jvmMain/kotlin/androidx/ink/nativeloader/NativeLoader.jvm.kt \
       src/jvmMain/kotlin/androidx/ink/nativeloader/NativeLoader.jvm.kt \
       > patches/0001-load-byteink-natives.patch
     ```
4. Review `git diff`, then run `./gradlew check`. `verifyUpstreamAbi` compares against the new
   release's jar.
