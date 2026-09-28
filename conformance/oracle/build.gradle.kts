// Unpublished. The differential oracle: one fixed set of engine operations, run on Google's
// libink.so (oracleGoogle) and on byteink's (oracleByteink), dumped value by value and compared
// (oracleCompare). -PbyteinkLinuxLibrary picks the byteink build; -PoracleFixtures=<dir> adds the
// .vive notebooks in that directory.

import com.vivenotes.byteink.build.byteinkLinuxLibrary
import com.vivenotes.byteink.build.registerNativesDirectory

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
    implementation(libs.androidx.ink.nativeloader)
    implementation(libs.sqlite.jdbc)
}

val byteinkLibrary = byteinkLinuxLibrary()
val byteinkNatives = registerNativesDirectory("byteinkNatives", byteinkLibrary)
val fixtures = providers.gradleProperty("oracleFixtures")
// -PoracleDetail=<case prefix> keeps only those cases and writes their buffers in full.
val detail = providers.gradleProperty("oracleDetail")
val dumps = layout.buildDirectory.dir("oracle")

fun registerDump(name: String, label: Provider<String>, natives: FileCollection) = tasks.register<JavaExec>(name) {
    group = "oracle"
    classpath = natives + sourceSets.main.get().runtimeClasspath
    mainClass = "com.vivenotes.byteink.oracle.OracleKt"
    jvmArgs("--enable-native-access=ALL-UNNAMED")
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

val google = registerDump("oracleGoogle", provider { "Google's libink.so" }, files())
    .also { it.configure { description = "Dumps the oracle's results on Google's libink.so." } }

// The byteink build's own record of which commit it is, when native/build-linux.sh made it.
val byteinkLabel = byteinkLibrary.map { library ->
    val info = library.asFile.resolveSibling("build.properties")
    if (info.isFile) "byteink " + info.readLines().first { it.startsWith("google.ink.commit=") }.substringAfter('=').take(12)
    else "byteink ${library.asFile}"
}
val byteink = registerDump("oracleByteink", byteinkLabel, files(byteinkNatives))
    .also { it.configure { description = "Dumps the oracle's results on byteink's libink.so." } }

tasks.register<JavaExec>("oracleCompare") {
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
