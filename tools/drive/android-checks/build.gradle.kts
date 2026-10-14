import org.gradle.api.file.DirectoryProperty
plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose)
}
abstract class DriveSourceSync : Sync() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}
val sources = tasks.register<DriveSourceSync>("prepareDriveSources") {
    outputDirectory.set(layout.buildDirectory.dir("generated/driveSources"))
    into(outputDirectory)
    from("../../../app/src/main/java") { include("com/feldman/scholix/drive/*.kt") }
}
val appBuild = file("../../../app/build.gradle.kts").readText()
fun appApi(name: String) = Regex("(?m)^\\s*${Regex.escape(name)}\\s*=\\s*(\\d+)")
    .find(appBuild)!!.groupValues[1].toInt()
val authDependency = Regex("com\\.google\\.android\\.gms:play-services-auth:[^\"]+").find(appBuild)!!.value
android {
    namespace = "com.feldman.scholix"
    compileSdk = appApi("compileSdk")
    defaultConfig { minSdk = appApi("minSdk") }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    sourceSets {
        getByName("test") {
            kotlin.directories += "../../../app/src/test/java/com/feldman/scholix/drive"
        }
    }
}
androidComponents.onVariants { variant ->
    variant.sources.kotlin?.addGeneratedSourceDirectory(sources, DriveSourceSync::outputDirectory)
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    implementation(authDependency)
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.0")
}
