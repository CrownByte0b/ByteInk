pluginManagement { repositories { gradlePluginPortal(); mavenCentral() } }
rootProject.name = "byteink-published-wasm-consumer"
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        maven { url = uri("../../build/repo"); content { includeGroup("com.vivenotes.byteink") } }
        mavenCentral()
    }
}
