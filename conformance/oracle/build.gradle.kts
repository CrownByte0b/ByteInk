// Unpublished. The differential oracle: one fixed set of engine operations, run on Google's
// libink.so (oracleGoogle) and a separate platform-math validation build (oracleByteink), then
// compared strictly (oracleCompare). oracleProduction records the shipped Android-math build.
// All use byteink's loader; baseline and Google select explicit native overrides.
// -PoracleFixtures=<dir> adds the .vive notebooks in that directory.

import com.vivenotes.byteink.build.InkLibraryOverride
import com.vivenotes.byteink.build.byteinkHostLibrary
import com.vivenotes.byteink.build.isLinuxHost
import com.vivenotes.byteink.build.googleLinuxLibrary
import com.vivenotes.byteink.build.upstreamPins

plugins {
    id("byteink.kotlin-jvm")
    application
}

application {
    mainClass = "com.vivenotes.byteink.oracle.OracleKt"
}

dependencies {
    implementation(project(":byteink-core"))
    // The oracle reads internal mesh buffers, behind the loader's opt-in annotation.
    implementation(project(":ink-nativeloader"))
    implementation(libs.sqlite.jdbc)
}

val fixtures = providers.gradleProperty("oracleFixtures")
// -PoracleDetail=<case prefix> keeps only those cases and writes their buffers in full.
val detail = providers.gradleProperty("oracleDetail")
val dumps = layout.buildDirectory.dir("oracle")

fun registerDump(name: String, label: Provider<String>) = tasks.register<JavaExec>(name) {
    group = "oracle"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.vivenotes.byteink.oracle.OracleKt"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    // Extract into the build directory rather than the user's cache.
    systemProperty("byteink.ink.cache", layout.buildDirectory.dir("ink-cache/$name").get().asFile.path)
    val out = dumps.map { it.file("$name.tsv") }
    outputs.file(out)
    val fixtureDirectory = fixtures
    val detailPrefix = detail
    inputs.property("fixtures", fixtureDirectory.orElse(""))
    inputs.property("detail", detailPrefix.orElse(""))
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("dump", "--out", out.get().asFile.path, "--label", label.get()) +
            fixtureDirectory.map { listOf("--fixtures", it) }.getOrElse(emptyList()) +
            detailPrefix.map { listOf("--detail", it) }.getOrElse(emptyList())
    })
}

val googleLibrary = googleLinuxLibrary()
val google = if (isLinuxHost) registerDump("oracleGoogle", provider { "Google's libink.so" }).also {
    it.configure {
        description = "Dumps the oracle's results on Google's libink.so."
        jvmArgumentProviders.add(InkLibraryOverride(googleLibrary))
    }
} else null

// The Google pin proof runs a separate, unshipped platform-math build. Production Android
// arithmetic is mandatory in the Android fidelity matrix and in cross-OS production dumps.
val nativeCommit = upstreamPins().map { it.getProperty("google.ink.commit") }
val oracleRoot = rootProject.layout.projectDirectory
val baselineLibrary = providers.gradleProperty("oracleBaselineLibrary").map(oracleRoot::file)
    .orElse(oracleRoot.file("native/build/oracle-linux-x64/libink.so"))
val productionLibrary = byteinkHostLibrary()
val productionMetadata = productionLibrary.map { it.asFile.resolveSibling("build.properties").absolutePath }
val baselineMetadata = baselineLibrary.map { it.asFile.resolveSibling("build.properties").absolutePath }
val production = registerDump("oracleProduction", provider { "byteink shipped Android-arithmetic native" }).also {
    it.configure {
        description = "Dumps the shipped production native for Android fidelity and strict cross-OS comparisons."
        val metadata = productionMetadata
        val commit = nativeCommit
        inputs.file(metadata).withPropertyName("nativeBuildMetadata")
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("--native-metadata", metadata.get(), "--expected-native-commit", commit.get())
        })
    }
}
val byteink = if (isLinuxHost) registerDump("oracleByteink", provider { "byteink unshipped platform-arithmetic pin-validation baseline" }).also {
    it.configure {
        description = "Dumps the unshipped platform-math baseline; missing or invalid baseline metadata fails."
        val metadata = baselineMetadata
        val commit = nativeCommit
        inputs.file(metadata).withPropertyName("baselineBuildMetadata")
        jvmArgumentProviders.add(InkLibraryOverride(baselineLibrary))
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("--baseline", "--native-metadata", metadata.get(), "--expected-native-commit", commit.get())
        })
    }
} else registerDump("oracleByteink", provider { "byteink shipped Android-arithmetic native (legacy Windows task)" }).also {
    it.configure {
        description = "Legacy Windows production dump; use oracleProduction for cross-OS comparisons."
        val metadata = productionMetadata
        val commit = nativeCommit
        inputs.file(metadata).withPropertyName("nativeBuildMetadata")
        argumentProviders.add(CommandLineArgumentProvider {
            listOf("--native-metadata", metadata.get(), "--expected-native-commit", commit.get())
        })
    }
}
google?.configure {
    val commit = nativeCommit
    argumentProviders.add(CommandLineArgumentProvider {
        listOf("--expected-native-commit", commit.get(), "--library-role", "google-reference")
    })
}

if (google != null) tasks.register<JavaExec>("oracleCompare") {
    group = "oracle"
    description = "Strict Google pin proof against the unshipped platform-math baseline; every geometry/topology check remains enforced."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.vivenotes.byteink.oracle.OracleKt"
    dependsOn(google, byteink)
    val reference = dumps.map { it.file("${google.name}.tsv") }
    val candidate = dumps.map { it.file("${byteink.name}.tsv") }
    inputs.files(reference, candidate)
    val report = layout.buildDirectory.file("reports/oracle.md")
    outputs.file(report)
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "compare", "--require-baseline",
            "--reference", reference.get().asFile.path,
            "--candidate", candidate.get().asFile.path,
            "--report", report.get().asFile.path,
        )
    })
}
