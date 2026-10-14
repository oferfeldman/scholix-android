pluginManagement {
    repositories { gradlePluginPortal(); mavenCentral() }
    plugins {
        val versions = file("../../../gradle/libs.versions.toml").readText()
        val kotlinVersion = Regex("(?m)^kotlin\\s*=\\s*\"([^\"]+)\"").find(versions)!!.groupValues[1]
        id("org.jetbrains.kotlin.jvm") version kotlinVersion
    }
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../../gradle/libs.versions.toml")) } }
}

rootProject.name = "lemida-core-checks"
