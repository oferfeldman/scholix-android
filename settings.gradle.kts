pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "scholix"
include(":app")
providers.gradleProperty("scholix.motionSource").orNull?.let { source ->
    includeBuild(source) {
        dependencySubstitution {
            substitute(module("io.github.feldmandev:motion")).using(project(":motion"))
        }
    }
}
// Optional focused device harness when private Motion artifacts are unavailable.
if (providers.gradleProperty("scholix.inbarTestApp").orNull == "true") include(":inbar-testapp")
