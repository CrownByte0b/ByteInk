# ByteInk dependencies and releases

ByteInk targets Linux x86_64 and Windows x86_64, Java 25 or newer. The build uses the
pinned Zulu 27 daemon, Gradle 9.8.0, Kotlin 2.4.20 and Compose Multiplatform 1.12.1.
AndroidX Ink remains pinned to 1.1.0-alpha06. The native source, toolchain and math
pins live in `upstream/pins.properties` and are recorded in each published POM.

The default group is `com.vivenotes.byteink`. The development coordinates are:

| Artifact | Version | Purpose |
| --- | --- | --- |
| `byteink-core` | `0.1.0-SNAPSHOT` | Stroke engine, mesh geometry, spatial index and hit testing |
| `byteink-compose` | `0.1.0-SNAPSHOT` | Skia rendering and live authoring |
| `byteink-vive` | `0.1.0-SNAPSHOT` | ViveNotes brush catalog, row codecs and operation replay |
| `byteink-testing` | `0.1.0-SNAPSHOT` | Optional JVM fixture and comparison support |
| `ink-nativeloader` / `ink-nativeloader-jvm` | `1.1.0-alpha06-byteink.1` | Transitive loader with both native platforms |

`byteink-compose` and `byteink-vive` bring in `byteink-core`, the four unchanged
AndroidX Ink modules and ByteInk's native loader. They exclude Google's loader.
Adding Google's loader separately causes a Gradle capability conflict; remove that
dependency, or exclude both `androidx.ink:ink-nativeloader` and
`androidx.ink:ink-nativeloader-jvm` from the dependency that introduces it.

## Local consumption

Build both native platforms once on Linux:

```sh
native/build-linux.sh
native/build-windows.sh
./gradlew publishAllPublicationsToBuildRepository
```

The development repository is `build/repo`. Add a repository pointing to that
directory in the consuming project's `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri("../byteink/build/repo")
            content { includeGroup("com.vivenotes.byteink") }
        }
        google()
        mavenCentral()
    }
}
```

Use these in the desktop application's `jvmMain` dependencies:

```kotlin
implementation("com.vivenotes.byteink:byteink-compose:0.1.0-SNAPSHOT")
implementation("com.vivenotes.byteink:byteink-vive:0.1.0-SNAPSHOT")
implementation(compose.desktop.currentOs)
```

The application supplies the platform-specific Compose/Skiko runtime. Enable
native access in its JVM arguments: `--enable-native-access=ALL-UNNAMED`. Use an
ASCII native-cache path with Zulu on Windows if the user profile contains names
outside the native code page; the pinned JBR 25 supports the wider Unicode paths.

`./gradlew publishToMavenLocal` publishes the same artifacts into Maven local.
Use `mavenLocal { content { includeGroup("com.vivenotes.byteink") } }` instead of
the directory repository to consume those. For a sandboxed verification, use
`./gradlew publishToMavenLocal -Dmaven.repo.local=/tmp/byteink-maven-local`.

For source development add `includeBuild("../byteink")` to the consumer's settings
and keep the same dependency declarations. Native binaries must still exist in
ByteInk's `native/build/out/<pinned-commit>/` tree. The committed smoke fixture
supports `-PbyteinkCompositePath=../byteink` for this mode and verifies that every
ByteInk component resolves from the included build.

The standalone published-artifact smoke is:

```sh
./gradlew :consumer-tests:test
./gradlew -p consumer-tests/fixture run \
  -PbyteinkRepository="$(pwd)/build/repo"
```

The TestKit suite tests both published artifacts and `includeBuild`, loads the
host native library, authors/replays real Ink strokes, renders them to a PNG and
records native provenance. Reports are in `consumer-tests/build/reports/consumer/`.

## GitHub Packages

The default remote repository is
`https://maven.pkg.github.com/crownbyte0b/byteink`. Configure the consumer Maven
repository with a GitHub username and a personal access token (classic) with
`read:packages`. Even public GitHub Maven packages require authentication. Keep
credentials in your user Gradle properties or environment, outside source control.
For example:

```kotlin
maven {
    url = uri("https://maven.pkg.github.com/crownbyte0b/byteink")
    content { includeGroup("com.vivenotes.byteink") }
    credentials {
        username = providers.environmentVariable("GITHUB_ACTOR").orNull
        password = providers.environmentVariable("GITHUB_TOKEN").orNull
    }
}
```

Publishing accepts `GitHubPackagesUsername` and `GitHubPackagesPassword` Gradle
properties, falling back to `GITHUB_ACTOR` and `GITHUB_TOKEN`. The target repository
is selected by `byteinkGitHubRepository`, then `GITHUB_REPOSITORY`, then
`CrownByte0b/ByteInk`. Repository owner names are lowercased for the registry URL.
The `publish` aggregate task publishes to both GitHub Packages and `build/repo`;
`publishAllPublicationsToGitHubPackagesRepository` targets the registry alone.

## Tag publication and versions

A pushed `v<major.minor.patch[-prerelease]>` tag runs the complete CI pipeline.
For example, `v0.1.0` selects:

```text
byteinkVersion=0.1.0
byteinkNativeLoaderVersion=1.1.0-alpha06-byteink.0.1.0
```

The release script rejects malformed tags and SNAPSHOT releases. Every JVM
matrix job tests these exact coordinates. The publishing job starts only after
all native checks, Linux C++ tests, pin scans and the six Ubuntu/Windows ×
Zulu 25/Zulu 27/JBR 25 JVM jobs pass. It uses the checked native artifacts from
that same run and a job-scoped `GITHUB_TOKEN` with `packages:write` permission.
The loader version includes the release version so subsequent module releases
cannot overwrite an older loader containing different native bytes. Tags and
published release coordinates must remain immutable.

Ordinary builds retain the catalog's development versions. The Gradle properties
`byteinkGroup`, `byteinkVersion` and `byteinkNativeLoaderVersion` override them.
The loader version must keep the pinned prefix `1.1.0-alpha06-byteink.`. To stage
and test a proposed release locally:

```sh
./gradlew :consumer-tests:test publishAllPublicationsToBuildRepository \
  -PbyteinkVersion=0.1.0 \
  -PbyteinkNativeLoaderVersion=1.1.0-alpha06-byteink.0.1.0
```

This setup does not create or push a tag, create a GitHub release, or publish any
remote package during local verification. The repository workflow implements
publication when the user later pushes a release tag.

## Separate Linux debug symbols

The runtime loader jar contains stripped `libink.so` and `ink.dll`. It excludes
the unstripped ELF and the test-only platform-math baseline. Linux debug symbols
are published as a separate ZIP classifier:

```text
com.vivenotes.byteink:ink-nativeloader-jvm:1.1.0-alpha06-byteink.1:linux-x86_64-debug-symbols@zip
```

For a release, substitute its release-specific loader version. The archive
contains `linux-x86_64/libink.so.debug`, the corresponding native build properties,
the stripped-library SHA-256, debug-file SHA-256 and shared GNU ELF build ID, plus
license files. Publication checks the hashes and matching build IDs on either
build host. The symbols remain outside the application's runtime classpath.
CI also uploads the unstripped ELF as the separate
`libink-linux-x86_64-debug-symbols` artifact. `-PbyteinkLinuxLibrary`,
`-PbyteinkWindowsLibrary` and `-PbyteinkLinuxDebugSymbols` can select prebuilt
inputs; the Linux native `build.properties` must accompany the selected ELF.

Synthetic Android-produced fixtures always run in CI, including all 280 brush,
tool, stabilization and replay cases. Optional private notebook inputs remain
opt-in through `byteinkNotebooks` / `BYTEINK_NOTEBOOKS` and
`byteinkPerformanceNotebook` / `BYTEINK_PERFORMANCE_NOTEBOOK`. Public CI never
requires or uploads private notebook archives. Fidelity, replay, performance,
test and consumer reports are uploaded for every runtime job, including failures.
Measured renderer limits and differences remain in `conformance/android/FIDELITY.md`.

References: [GitHub's Gradle registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry),
[GitHub's Gradle publication workflow](https://docs.github.com/en/actions/tutorials/publish-packages/publish-java-packages-with-gradle),
[Gradle Maven publication](https://docs.gradle.org/current/userguide/publishing_maven.html) and
[Gradle's classifier artifact behavior](https://docs.gradle.org/current/userguide/publishing_customization.html#sec:publishing_custom_artifacts).
