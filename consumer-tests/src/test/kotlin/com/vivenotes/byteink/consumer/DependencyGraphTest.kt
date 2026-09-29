package com.vivenotes.byteink.consumer

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import org.gradle.testkit.runner.GradleRunner
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * What a Gradle build that depends on byteink's published modules resolves to, in each of the
 * ways a consumer might combine them with Google's Ink.
 */
class DependencyGraphTest {

    @get:Rule
    val temporary = TemporaryFolder()

    private val repository = System.getProperty("byteink.test.repository")
    private val byteink = System.getProperty("byteink.test.version")
    private val nativeloader = System.getProperty("byteink.test.nativeloaderVersion")
    private val ink = System.getProperty("byteink.test.inkVersion")

    /** byteink-core's run-time classpath: Google's four Ink modules and byteink's loader, not Google's. */
    private val coreClasspath: Set<String>
        get() = setOf(
            "byteink-core-$byteink.jar",
            "ink-brush-jvm-$ink.jar",
            "ink-geometry-jvm-$ink.jar",
            "ink-nativeloader-jvm-$nativeloader.jar",
            "ink-storage-jvm-$ink.jar",
            "ink-strokes-jvm-$ink.jar",
        )

    @Test
    fun byteinkCoreBringsGooglesInkWithByteinksLoader() {
        assertEquals(coreClasspath, inkJars(resolve("core")))
    }

    @Test
    fun byteinkViveBringsTheCoreWithIt() {
        assertEquals(coreClasspath + "byteink-vive-$byteink.jar", inkJars(resolve("vive")))
    }

    @Test
    fun thePublishedRendererBringsTheCoreAndCompose() {
        val classpath = resolve("compose")
        assertEquals(coreClasspath + "byteink-compose-$byteink.jar", inkJars(classpath))
        assertContains(classpath.joinToString(), "ui-desktop-")
    }

    @Test
    fun theLoaderAloneIsOneJar() {
        assertEquals(setOf("ink-nativeloader-jvm-$nativeloader.jar"), inkJars(resolve("loader")))
    }

    @Test
    fun googlesLoaderAlongsideByteinkFailsToResolve() {
        val output = runner("core+google").buildAndFail().output

        assertContains(output, "Both provide capability 'androidx.ink:ink-nativeloader")
    }

    @Test
    fun theDocumentedSubstitutionPutsByteinksLoaderInGooglesPlace() {
        assertEquals(coreClasspath, inkJars(resolve("core+google+substitution")))
    }

    /** The consumer's run-time classpath, as file names. */
    private fun resolve(scenario: String): List<String> =
        runner(scenario).build().output.lines().filter { it.startsWith(PREFIX) }.map { it.removePrefix(PREFIX) }

    private fun inkJars(classpath: List<String>): Set<String> = classpath.filterTo(sortedSetOf()) { "ink" in it }

    private fun runner(scenario: String): GradleRunner = GradleRunner.create()
        .withProjectDir(consumer)
        .withArguments("classpath", "-Pscenario=$scenario", "--stacktrace")

    private val consumer: File by lazy {
        temporary.newFolder("consumer").apply {
            resolve("settings.gradle.kts").writeText(
                """
                rootProject.name = "consumer"
                dependencyResolutionManagement {
                    repositories {
                        maven { url = uri(${quoted(File(repository).toURI().toString())}) }
                        google { mavenContent { includeGroupAndSubgroups("androidx") } }
                        mavenCentral()
                    }
                }
                """.trimIndent(),
            )
            resolve("build.gradle.kts").writeText(
                """
                plugins { `java-library` }

                val scenario = providers.gradleProperty("scenario").get()
                dependencies {
                    when (scenario) {
                        "loader" -> implementation("com.vivenotes.byteink:ink-nativeloader:$nativeloader")
                        "core" -> implementation("com.vivenotes.byteink:byteink-core:$byteink")
                        "vive" -> implementation("com.vivenotes.byteink:byteink-vive:$byteink")
                        "compose" -> implementation("com.vivenotes.byteink:byteink-compose:$byteink")
                        else -> {
                            implementation("com.vivenotes.byteink:byteink-core:$byteink")
                            implementation("androidx.ink:ink-strokes:$ink")
                        }
                    }
                }

                if (scenario.endsWith("+substitution")) {
                    // The snippet the README gives consumers that depend on Google's Ink themselves.
                    configurations.configureEach {
                        resolutionStrategy.dependencySubstitution {
                            substitute(module("androidx.ink:ink-nativeloader"))
                                .using(module("com.vivenotes.byteink:ink-nativeloader:$nativeloader"))
                        }
                    }
                }

                val runtime: FileCollection = configurations.runtimeClasspath.get()
                tasks.register("classpath") {
                    doLast { runtime.files.forEach { println("$PREFIX" + it.name) } }
                }
                """.trimIndent(),
            )
        }
    }

    private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private companion object {
        const val PREFIX = "classpath: "
    }
}
