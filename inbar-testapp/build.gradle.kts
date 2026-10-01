import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val inbarSources = tasks.register<Sync>("prepareInbarSources") {
    from("../app/src/main/java") {
        include("com/feldman/scholix/api/Platform.kt", "com/feldman/scholix/api/LoginFields.kt",
            "com/feldman/scholix/api/platforms/Inbar*.kt", "com/feldman/scholix/ui/InbarLogin.kt")
    }
    into(layout.buildDirectory.dir("generated/inbar/main"))
}
val inbarTests = tasks.register<Sync>("prepareInbarTests") {
    from("../app/src/test/java") { include("com/feldman/scholix/api/platforms/InbarHttpTest.kt") }
    into(layout.buildDirectory.dir("generated/inbar/test"))
}

android {
    namespace = "com.feldman.scholix.inbartest"
    compileSdk = 37
    defaultConfig {
        applicationId = "com.feldman.scholix.inbartest"
        minSdk = 32
        targetSdk = 37
        versionCode = 1
        versionName = "inbar-test"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
    // Compile the actual provider and consent UI, not a second implementation.
    sourceSets["main"].kotlin.srcDir(layout.buildDirectory.dir("generated/inbar/main").get().asFile)
    sourceSets["test"].kotlin.srcDir(layout.buildDirectory.dir("generated/inbar/test").get().asFile)
}

tasks.matching { it.name.endsWith("Kotlin") }.configureEach { dependsOn(inbarSources, inbarTests) }

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.foundation)
    implementation(libs.okhttp)
    implementation(libs.jsoup)
    implementation("com.google.android.gms:play-services-auth-api-phone:18.2.0")
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303")
}
