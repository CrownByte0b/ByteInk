# Get started

## Add ByteInk to your project

ByteInk is published to [GitHub Packages](https://github.com/orgs/CrownByte0b/packages). The current release is `0.1.0-alpha.1`. Use Java 25+, Kotlin 2.4.20 and Compose Multiplatform 1.12.1 for the tested setup. The Linux and Windows natives ship inside the dependency.

### 1. Add a GitHub token

GitHub's Maven registry requires authentication, including for public packages. Create a [classic personal access token](https://github.com/settings/tokens/new?scopes=read:packages&description=ByteInk) with `read:packages` and add it to `~/.gradle/gradle.properties`, outside source control:

```properties
gpr.user=your-github-username
gpr.key=ghp_yourToken
```

In CI, the `GITHUB_ACTOR` and `GITHUB_TOKEN` environment variables are used instead. [Registry documentation](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-gradle-registry).

### 2. Add the repository

In the consuming project's `settings.gradle.kts`:

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

In the consumer's JVM dependencies:

```kotlin
kotlin {
    jvm()
    sourceSets.jvmMain.dependencies {
        implementation("com.vivenotes.byteink:byteink-compose:0.1.0-alpha.1")
        implementation("com.vivenotes.byteink:byteink-kit:0.1.0-alpha.1")
        implementation(compose.desktop.currentOs)
    }
}
compose.desktop {
    application {
        mainClass = "your.package.MainKt"
        jvmArgs += "--enable-native-access=ALL-UNNAMED"
    }
}
```

Apply the Kotlin Compose compiler and Compose Multiplatform plugins in your application. The application supplies its OS-specific Compose/Skiko runtime. `byteink-compose` and `byteink-kit` bring in core, the pinned AndroidX modules and the native loader. Add `com.vivenotes.byteink:byteink-testing:0.1.0-alpha.1` only if you need [notebook utilities](guides/notebooks.md).

For a plain JVM project, put the same dependencies in `dependencies { ... }`. [The example build](examples/build.gradle.kts) uses a standalone JVM consumer.

Avoid adding Google's `androidx.ink:ink-nativeloader` or `ink-nativeloader-jvm` separately: they conflict with ByteInk's loader. See [troubleshooting](troubleshooting.md).

## Create, store and rebuild a stroke

This uses the same input/brush types as AndroidX Ink. `elapsedTimeMillis` starts at zero for each stroke.

```kotlin
--8<-- "docs/examples/src/main/kotlin/wiki/FirstStroke.kt"
```

`ViveInkTool.complete` returns the finished stroke and a new stored row. `canonicalStroke` rebuilds that row through the codec, giving the geometry a reload will use. Persist `row` with a unique ID, correct page ID, and repository-allocated `seq`.

For live drawing, continue to [the Compose surface](guides/authoring.md#compose-surface) or [native low-latency surfaces](guides/authoring.md#native-low-latency-surfaces). [All tool parameters](reference/brushes.md).

## Native Wayland setup

The native Wayland authoring backend is tested with **JBR 25**, a live Wayland compositor and these application JVM arguments:

```kotlin
jvmArgs += listOf(
    "-Dawt.toolkit.name=WLToolkit",
    "--add-opens=java.desktop/sun.awt.wl=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
)
```

Use the [SwingGraphics Compose host or direct Swing panel](guides/authoring.md#native-platform-setup) shown in the native authoring example. An ordinary Java 25 Linux runtime can use X11/XWayland on a Wayland desktop; `WAYLAND_DISPLAY` alone does not select JBR's Wayland toolkit. Windows and X11 native authoring need Java 25+ and native access, without the JBR-specific flags.

## Other repository choices

To build against a ByteInk checkout instead of a published release, use version `0.1.0-SNAPSHOT` in the dependencies above.

| Mode | Setup |
| --- | --- |
| Local build repository | Publish the checkout as shown below and add the repository. |
| Source/composite | Add `includeBuild("../byteink")` to settings; keep the normal dependency declarations. Native outputs are still required. |
| Maven local | Run `./gradlew publishToMavenLocal` in ByteInk; configure `mavenLocal { content { includeGroup("com.vivenotes.byteink") } }`. |

For a local build repository, publish the checkout once; its native build outputs must be present:

```sh
# In ByteInk; omit the native builds when their pinned outputs already exist.
native/build-linux.sh
native/build-windows.sh
./gradlew publishAllPublicationsToBuildRepository
```

Then add it to the consumer's `settings.gradle.kts`:

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

Maintainers can [publish a version from a tag or a manual CI run](guides/publishing.md).
