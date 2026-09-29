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

# Using byteink

byteink is not published to a remote repository yet. Publish it into `build/repo` with
`./gradlew :ink-nativeloader:publishAllPublicationsToBuildRepository :byteink-core:publishAllPublicationsToBuildRepository`.

| Module | Coordinates |
|---|---|
| Core | `com.vivenotes.byteink:byteink-core:0.1.0-SNAPSHOT` |
| Native loader (a dependency of the core) | `com.vivenotes.byteink:ink-nativeloader:1.1.0-alpha06-byteink.1` |

`byteink-core` brings Google's AndroidX Ink JVM modules (`ink-brush`, `ink-geometry`,
`ink-storage`, `ink-strokes`, 1.1.0-alpha06) unchanged. The one difference is the native loader:
Google's is replaced by byteink's fork of it (`ink-nativeloader/PATCHES.md`), which bundles byteink's
native library for Linux x86_64 and Windows x86_64.

## The native library

- **When it loads:** on first use of Ink. `InkRuntime.load()` loads it explicitly, and fails fast if
  Google's loader has got onto the classpath.
- **Where it goes:** it is extracted once per user and reused from there:
  - Linux: `$XDG_CACHE_HOME/byteink/natives/<sha256>/libink.so`, or under `~/.cache`.
  - Windows: `%LOCALAPPDATA%\byteink\natives\<sha256>\ink.dll`.
  - If that cache cannot be written, a new private temporary directory is used.
- **`-Dbyteink.ink.library=<absolute path>`:** loads a copy installed with the application instead,
  for Flatpak or jpackage builds, for example.
- **`-Dbyteink.ink.cache=<directory>`:** extracts somewhere else.
- **`InkNativeLibrary.loaded`:** says which file was loaded, and its SHA-256.

## If your build also depends on Google's Ink

`byteink-core` claims the capability of Google's `androidx.ink:ink-nativeloader`. A build that also
pulls in Google's loader, through `androidx.ink:ink-strokes` for example, therefore fails to resolve
with "Both provide capability 'androidx.ink:ink-nativeloader…'". Put byteink's loader in its place:

```kotlin
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("androidx.ink:ink-nativeloader"))
            .using(module("com.vivenotes.byteink:ink-nativeloader:1.1.0-alpha06-byteink.1"))
    }
}
```

`consumer-tests` checks this snippet against the published metadata.

# Building byteink

The loader jar bundles both native libraries, so a build needs them first:

- Build them with `native/build-linux.sh` and `native/build-windows.sh`. These run on Linux with
  Bazel, which the scripts fetch; see `upstream/README.md`.
- Or name existing builds with `-PbyteinkLinuxLibrary=<file> -PbyteinkWindowsLibrary=<file>`, as CI
  does.

Then `./gradlew build` runs everything:

- the loader's tests, on JDK 27 and on JDK 25;
- AndroidX's own Ink suites, on byteink's library and on Google's;
- the consumer tests, and the checks that the fork is upstream's code plus `patches/`.
