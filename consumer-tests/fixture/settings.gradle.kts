pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        kotlin("jvm") version providers.gradleProperty("kotlinVersion").getOrElse("2.4.20")
    }
}

rootProject.name = "byteink-consumer"

// Supply -PbyteinkCompositePath=../byteink in a neighboring application checkout.
providers.gradleProperty("byteinkCompositePath").orNull?.let { includeBuild(it) }

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        providers.gradleProperty("byteinkRepository").orNull?.let { repository ->
            maven {
                url = uri(repository)
                content { includeGroup(providers.gradleProperty("byteinkGroup").getOrElse("com.vivenotes.byteink")) }
            }
        }
        google { mavenContent { includeGroupAndSubgroups("androidx") } }
        mavenCentral()
    }
}
