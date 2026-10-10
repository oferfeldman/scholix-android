import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
    id("com.google.firebase.crashlytics")
}

// A fresh checkout can build without private Firebase configuration.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.feldman.scholix"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.feldman.scholix"
        minSdk = 32
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing credentials come from outside version control: env vars, then
    // local.properties, then ~/.gradle/gradle.properties. Keep secrets out of
    // this file. See signing.properties.example for the property names.
    val signingProps = Properties().apply {
        val local = rootProject.file("local.properties")
        if (local.exists()) local.inputStream().use { load(it) }
        val home = file("${System.getProperty("user.home")}/.gradle/gradle.properties")
        if (home.exists()) home.inputStream().use { load(it) }
    }
    fun signingValue(key: String): String? =
        (System.getenv(key) ?: signingProps.getProperty(key))?.takeIf { it.isNotBlank() }

    val storeFilePath = signingValue("SCHOLIX_STORE_FILE")
    val storePass = signingValue("SCHOLIX_STORE_PASSWORD")
    val keyAliasName = signingValue("SCHOLIX_KEY_ALIAS")
    val keyPass = signingValue("SCHOLIX_KEY_PASSWORD")
    val haveSigning = listOf(storeFilePath, storePass, keyAliasName, keyPass).all { it != null }

    signingConfigs {
        if (haveSigning) {
            create("release") {
                storeFile = file(storeFilePath!!)
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (haveSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        getByName("debug") {
            providers.gradleProperty("scholix.applicationIdSuffix").orNull?.let { applicationIdSuffix = it }
            // Sign debug with the release key only when credentials are available.
            if (haveSigning) signingConfig = signingConfigs.getByName("release")
        }
    }
    buildFeatures {
        compose = true
    }
    // Explicit opt-in for a privately sideloaded debug build; never added to release.
    if (providers.gradleProperty("scholix.directSms").orNull == "true") {
        sourceSets["debug"].manifest.srcFile("src/directSms/AndroidManifest.xml")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
}

dependencies {
    constraints {
        // Keep app/test runtime constraints aligned with AndroidX Test 1.3 / Espresso 3.7.
        implementation("androidx.concurrent:concurrent-futures:1.2.0")
        implementation("androidx.concurrent:concurrent-futures-ktx:1.2.0")
    }
    //BOMS
    implementation(platform(libs.firebase.bom))
    implementation(platform(libs.androidx.compose.bom))

    //Kotlin
    implementation(libs.kotlin.reflect)


    // Core Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material.v1130)

    // Jetpack Compose BOM
    implementation(libs.ui)
    // Jetpack Compose
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.material3)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.animation)
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.work.runtime.ktx)
    ksp(libs.androidx.room.compiler)
    // Optional but useful
    implementation(libs.room.ktx)

    debugImplementation(libs.androidx.ui.tooling)
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    // Optional - navigation
    implementation(libs.navigation.compose)
    implementation(libs.jsoup)
    implementation("com.google.android.gms:play-services-auth-api-phone:18.2.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")

    // Tests
    testImplementation(libs.junit)
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.3.0")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    implementation(libs.accompanist.swiperefresh)
    implementation(libs.okhttp)
    implementation(libs.logging.interceptor)

    //Firebase
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics.ndk)

    implementation(libs.capsule)
    implementation(libs.backdrop)

    //credentials
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)

    //ktor
    // Core Ktor HTTP client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)

    implementation(libs.retrofit)
    implementation(libs.converter.gson)
    implementation(libs.logging.interceptor)

    // Motion & UI
    implementation(libs.motion)
    implementation(libs.material.kolor)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.webkit)
    implementation(libs.coil.compose)
    implementation(libs.pdfbox.android)
}

tasks.matching { it.name.contains("AarMetadata") }.configureEach {
    enabled = false
}
