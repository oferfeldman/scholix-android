import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.jvm") }

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }

// Compile the app's actual classes and existing tests; no copied implementations.
sourceSets {
    main {
        kotlin.srcDir("../../../app/src/main/java")
        kotlin.include(listOf("LemidaParser", "LemidaSms", "LemidaMfaState", "LemidaSmsConsentState",
            "LemidaLoginProbe", "LemidaMfaPoll", "LemidaCredentialPoll", "LemidaCredentialScript", "LemidaRequestScript", "LemidaSession").map { "com/feldman/scholix/lemida/$it.kt" })
    }
    test {
        kotlin.srcDir("../../../app/src/test/java")
        kotlin.include(listOf("LemidaParserTest", "LemidaSmsTest", "LemidaMfaStateTest",
            "LemidaSmsConsentStateTest", "LemidaLoginProbeTest", "LemidaMfaPollTest", "LemidaCredentialPollTest").map { "com/feldman/scholix/lemida/$it.kt" })
    }
}

dependencies {
    implementation(libs.jsoup)
    implementation("org.json:json:20240303")
    testImplementation(libs.junit)
}

tasks.test { useJUnit() }
