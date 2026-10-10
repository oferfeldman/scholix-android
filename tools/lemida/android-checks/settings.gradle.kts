pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        val versions = file("../../../gradle/libs.versions.toml").readText()
        val agpVersion = Regex("(?m)^agp\\s*=\\s*\"([^\"]+)\"").find(versions)!!.groupValues[1]
        id("com.android.library") version agpVersion
    }
}

dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../../gradle/libs.versions.toml")) } }
}

rootProject.name = "lemida-android-checks"
