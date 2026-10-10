package com.vivenotes.byteink.consumer

import java.io.File
import java.net.URI
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.UnexpectedBuildFailure
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** Executes the same independent app with published artifacts and with an included ByteInk build. */
class PublishedConsumerSmokeTest {
    @get:Rule
    val temporary = TemporaryFolder()

    @Test
    fun publishedArtifactsLoadAuthorEncodeHitTestAndRender() = smoke(composite = false)

    @Test
    fun includeBuildSuppliesTheSameDesktopLibrary() = smoke(composite = true)

    private fun smoke(composite: Boolean) {
        val mode = if (composite) "composite" else "published"
        val app = temporary.newFolder("app")
        val fixture = File(property("fixture"))
        // A developer can also run this fixture in place. Carry only its build scripts and app
        // source, so a previous standalone run's output/caches cannot influence the test.
        for (relative in listOf("build.gradle.kts", "settings.gradle.kts", "gradle.properties", "src")) {
            assertTrue(fixture.resolve(relative).copyRecursively(app.resolve(relative)))
        }
        val reports = File(property("consumerReports"), mode)
        reports.deleteRecursively()
        val runtime = File(System.getProperty("java.home")).canonicalPath
        val arguments = mutableListOf(
            "run", "--stacktrace", "--max-workers=2",
            "-Dorg.gradle.java.home=$runtime",
            "-PconsumerJavaHome=$runtime",
            "-PconsumerReportDirectory=${reports.absolutePath}",
            "-PkotlinVersion=${property("kotlinVersion")}",
            "-PcomposeVersion=${property("composeVersion")}",
            "-PbyteinkGroup=${property("group")}",
            "-PbyteinkVersion=${property("version")}",
            "-PbyteinkNativeLoaderVersion=${property("nativeloaderVersion")}",
        )
        val included = if (composite) temporary.newFolder("byteink") else null
        if (composite) {
            // Deliberately omit the local Maven repository. Every ByteInk module must come from the
            // included build. Its sources and outputs are private to this test, so recompilation
            // cannot remove classes that the outer build's parallel suites are still loading.
            assertTrue(File(property("compositeSource")).copyRecursively(checkNotNull(included)))
            arguments += "-PbyteinkCompositePath=${included.absolutePath}"
        } else {
            arguments += "-PbyteinkRepository=${File(property("repository")).toURI()}"
        }
        reports.mkdirs()
        val result = try {
            GradleRunner.create().withProjectDir(app).withArguments(arguments).build()
        } catch (failure: UnexpectedBuildFailure) {
            reports.resolve("gradle.log").writeText(failure.buildResult.output)
            throw failure
        }
        reports.resolve("gradle.log").writeText(result.output)
        assertContains(result.output, "byteink-consumer-smoke: passed")
        for (module in listOf("byteink-core", "byteink-compose", "byteink-kit", "ink-nativeloader")) {
            assertContains(result.output, "byteink-consumer-component: $module=" + if (composite) "project" else "published")
        }
        val report = Properties().apply { reports.resolve("smoke.properties").inputStream().use { load(it) } }
        assertEquals("passed", report.getProperty("status"))
        assertEquals("com.vivenotes.byteink.kit", report.getProperty("api.kit.package"))
        assertEquals(runtime, report.getProperty("java.home"), "Consumer must use the selected test runtime")
        assertEquals("BUNDLED", report.getProperty("native.origin"))
        assertEquals(if (System.getProperty("os.name").startsWith("Windows")) "ink.dll" else "libink.so", report.getProperty("native.name"))
        assertEquals("2", report.getProperty("codec.rows"))
        assertEquals("marker,highlighter,empty", report.getProperty("hit.test.results"))
        assertTrue(report.getProperty("visible.pixels").toInt() > 1000)
        if (composite) {
            assertTrue(
                File(URI(report.getProperty("loader.source"))).canonicalFile.toPath()
                    .startsWith(checkNotNull(included).canonicalFile.toPath()),
                "Composite consumer must load its private included build's loader",
            )
        } else {
            assertContains(report.getProperty("loader.source"), "ink-nativeloader-jvm-${property("nativeloaderVersion")}.jar")
            assertFalse(report.getProperty("loader.source").contains("/ink-nativeloader/build/"))
        }
        for (image in listOf("live.png", "finished.png")) {
            assertTrue(reports.resolve(image).length() > 100, "Missing consumer render artifact: $image")
        }
    }

    private fun property(name: String): String = checkNotNull(System.getProperty("byteink.test.$name")) {
        "Missing byteink.test.$name; run this suite through :consumer-tests:test"
    }
}
