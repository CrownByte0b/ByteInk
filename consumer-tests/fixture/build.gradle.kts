import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    application
}

val byteinkGroup = providers.gradleProperty("byteinkGroup").getOrElse("com.vivenotes.byteink")
val byteinkVersion = providers.gradleProperty("byteinkVersion").getOrElse("0.1.0-SNAPSHOT")
val composeVersion = providers.gradleProperty("composeVersion").getOrElse("1.12.1")
val composite = providers.gradleProperty("byteinkCompositePath").isPresent
val operatingSystem = System.getProperty("os.name").let {
    when {
        it.startsWith("Linux") -> "linux"
        it.startsWith("Windows") -> "windows"
        else -> error("ByteInk supports Linux x86_64 and Windows x86_64: $it")
    }
}

kotlin.compilerOptions {
    jvmTarget = JvmTarget.JVM_25
    freeCompilerArgs.add("-Xjdk-release=25")
}
tasks.withType<JavaCompile>().configureEach { options.release = 25 }

dependencies {
    implementation("$byteinkGroup:byteink-compose:$byteinkVersion")
    implementation("$byteinkGroup:byteink-vive:$byteinkVersion")
    // The application's OS-specific Compose runtime supplies Skia. ByteInk itself stays portable.
    runtimeOnly("org.jetbrains.compose.desktop:desktop-jvm-$operatingSystem-x64:$composeVersion")
}

application {
    mainClass = "com.vivenotes.byteink.consumer.SmokeKt"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
}

val verifyConsumerClasspath = tasks.register("verifyConsumerClasspath") {
    val runtime = configurations.runtimeClasspath
    doLast {
        val components = runtime.get().incoming.resolutionResult.allComponents.map { it.id }
        for (module in listOf("byteink-core", "byteink-compose", "byteink-vive", "ink-nativeloader")) {
            val matching = components.filter { component ->
                when (component) {
                    is ModuleComponentIdentifier -> component.group == byteinkGroup &&
                        component.module in setOf(module, "$module-jvm")
                    is ProjectComponentIdentifier -> component.projectPath == ":$module"
                    else -> false
                }
            }
            check(matching.isNotEmpty()) { "Missing $module: $components" }
            check(matching.all { if (composite) it is ProjectComponentIdentifier else it is ModuleComponentIdentifier }) {
                "Consumer resolved $module through the wrong source: $matching"
            }
            println("byteink-consumer-component: $module=" + if (composite) "project" else "published")
        }
        check(components.none { it is ModuleComponentIdentifier && it.group == "androidx.ink" &&
            it.module in setOf("ink-nativeloader", "ink-nativeloader-jvm") }) {
            "Google's loader must be excluded from the application runtime: $components"
        }
    }
}

tasks.named<JavaExec>("run") {
    dependsOn(verifyConsumerClasspath)
    // TestKit executes on the outer test's selected Zulu/JBR runtime as well.
    providers.gradleProperty("consumerJavaHome").orNull?.let {
        setExecutable(file("$it/bin/" + if (operatingSystem == "windows") "java.exe" else "java").absolutePath)
    }
    val reportDirectory = providers.gradleProperty("consumerReportDirectory")
        .orElse(layout.buildDirectory.dir("reports/consumer").map { it.asFile.absolutePath })
    args(reportDirectory.get())
    systemProperty("byteink.ink.cache", layout.buildDirectory.dir("ink-cache").get().asFile.absolutePath)
}
