// Unpublished. The differential oracle: one fixed set of engine operations, run on Google's
// libink.so (oracleGoogle) and on byteink's (oracleByteink), dumped value by value and compared
// (oracleCompare). Both go through byteink's loader: oracleByteink loads the library it bundles
// (-PbyteinkLinuxLibrary picks another build to bundle), oracleGoogle names Google's with the
// loader's override property. -PoracleFixtures=<dir> adds the .vive notebooks in that directory.

import com.vivenotes.byteink.build.InkLibraryOverride
import com.vivenotes.byteink.build.byteinkHostLibrary
import com.vivenotes.byteink.build.isLinuxHost
import com.vivenotes.byteink.build.googleLinuxLibrary

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

// The byteink build's own record of which commit it is, when native/build-linux.sh made it.
val byteinkLabel = byteinkHostLibrary().map { library ->
    val info = library.asFile.resolveSibling("build.properties")
    if (info.isFile) "byteink " + info.readLines().first { it.startsWith("google.ink.commit=") }.substringAfter('=').take(12)
    else "byteink ${library.asFile}"
}
val byteinkMath = byteinkHostLibrary().map { library ->
    val info = library.asFile.resolveSibling("build.properties")
    if (info.isFile) info.readLines().firstOrNull { it.startsWith("float.angle.math=") }?.substringAfter('=') ?: "platform"
    else "platform"
}
val byteink = registerDump("oracleByteink", byteinkLabel).also {
    it.configure {
        description = "Dumps the oracle's results on the libink.so byteink's loader bundles."
        val math = byteinkMath
        inputs.property("angleMath", math)
        argumentProviders.add(CommandLineArgumentProvider { listOf("--angle-math", math.get()) })
    }
}

if (google != null) tasks.register<JavaExec>("oracleCompare") {
    group = "oracle"
    description = "Compares byteink's results with Google's value by value; fails on any difference."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.vivenotes.byteink.oracle.OracleKt"
    dependsOn(google, byteink)
    val reference = dumps.map { it.file("${google.name}.tsv") }
    val candidate = dumps.map { it.file("${byteink.name}.tsv") }
    inputs.files(reference, candidate)
    val report = layout.buildDirectory.file("reports/oracle.md")
    outputs.file(report)
    val allow = providers.gradleProperty("oracleAllowDifferences").isPresent
    argumentProviders.add(CommandLineArgumentProvider {
        listOf(
            "compare",
            "--reference", reference.get().asFile.path,
            "--candidate", candidate.get().asFile.path,
            "--report", report.get().asFile.path,
        ) + if (allow) listOf("--allow-differences") else emptyList()
    })
}
