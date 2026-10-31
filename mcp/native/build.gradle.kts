plugins {
    kotlin("jvm") version "2.2.21"
    application
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
java { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
sourceSets.main { kotlin.srcDir("providers") }
sourceSets.test { kotlin.srcDir("providers-test") }
dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.0")
    implementation("org.json:json:20240303")
    implementation("org.jsoup:jsoup:1.21.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
application { mainClass.set("com.feldman.scholix.mcp.WorkerKt") }
