pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    plugins {
        val versions = file("../../../gradle/libs.versions.toml").readText()
        val agp = Regex("(?m)^agp\\s*=\\s*\"([^\"]+)\"").find(versions)!!.groupValues[1]
        id("com.android.library") version agp
    }
}
dependencyResolutionManagement {
    repositories { google(); mavenCentral() }
    versionCatalogs { create("libs") { from(files("../../../gradle/libs.versions.toml")) } }
}
rootProject.name = "drive-android-checks"
