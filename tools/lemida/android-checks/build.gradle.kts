import org.gradle.api.file.DirectoryProperty

plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android) apply false
}

// AGP's source-directory API has no include/exclude filter. Sync unmodified files
// into build outputs, and register the producing tasks with the variant API.
abstract class CheckSourceSync : Sync() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}
val mainSources = tasks.register<CheckSourceSync>("prepareLemidaSources") {
    outputDirectory.set(layout.buildDirectory.dir("generated/lemidaSources"))
    into(outputDirectory)
    from("../../../app/src/main/java") {
        include("com/feldman/scholix/lemida/*.kt")
        exclude("com/feldman/scholix/lemida/LemidaPage.kt")
    }
    from("src/fixture/kotlin")
}
val notificationIcon = tasks.register<CheckSourceSync>("prepareNotificationIcon") {
    outputDirectory.set(layout.buildDirectory.dir("generated/lemidaResources"))
    into(outputDirectory)
    from("../../../app/src/main/res") { include("drawable/ic_docs.xml") }
}
val appBuild = file("../../../app/build.gradle.kts").readText()
fun appApi(name: String) = Regex("(?m)^\\s*${Regex.escape(name)}\\s*=\\s*(\\d+)")
    .find(appBuild)?.groupValues?.get(1)?.toInt() ?: error("Cannot identify app $name")
fun appJava(name: String) = Regex("${Regex.escape(name)}\\s*=\\s*JavaVersion\\.(VERSION_\\w+)")
    .find(appBuild)?.groupValues?.get(1)?.let { JavaVersion.valueOf(it) } ?: error("Cannot identify app $name")
val smsDependency = Regex("com\\.google\\.android\\.gms:play-services-auth-api-phone:[^\"]+")
    .find(appBuild)?.value ?: error("Cannot identify app SMS dependency")

android {
    namespace = "com.feldman.scholix"
    compileSdk = appApi("compileSdk")
    defaultConfig { minSdk = appApi("minSdk") }
    compileOptions {
        sourceCompatibility = appJava("sourceCompatibility")
        targetCompatibility = appJava("targetCompatibility")
    }
    sourceSets {
        getByName("test") {
            kotlin.directories += "../../../app/src/test/java/com/feldman/scholix/lemida"
        }
    }
}

androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(mainSources, CheckSourceSync::outputDirectory)
    variant.sources.res?.addGeneratedSourceDirectory(notificationIcon, CheckSourceSync::outputDirectory)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.jsoup)
    implementation(smsDependency)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
