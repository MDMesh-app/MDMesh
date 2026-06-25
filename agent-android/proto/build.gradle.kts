// :proto is a pure Kotlin/JVM library — it holds only the serializable wire
// contract and has no Android dependencies, so it can also be unit-tested fast
// and (in principle) shared with a JVM tool.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
