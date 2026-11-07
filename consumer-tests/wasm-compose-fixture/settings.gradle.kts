pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
rootProject.name = "byteink-published-compose-wasm-consumer"
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.PREFER_SETTINGS
    repositories {
        maven { url = uri("../../build/repo"); content { includeGroup("com.vivenotes.byteink") } }
        google(); mavenCentral()
        ivy("https://github.com/WebAssembly/binaryen/releases/download") {
            patternLayout { artifact("version_[revision]/binaryen-version_[revision]-[classifier].[ext]") }
            metadataSources { artifact() }
            content { includeModule("com.github.webassembly", "binaryen") }
        }
    }
}
