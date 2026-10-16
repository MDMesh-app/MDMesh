plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mdmesh.policy"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":proto"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}

// TogglePolicyWiringTest scans every module's production sources: make them test inputs, so a change in
// another module (the Hilt wiring in :app) re-runs it instead of leaving it UP-TO-DATE.
tasks.withType<Test>().configureEach {
    inputs.files(
        rootProject.fileTree(rootProject.projectDir) { include("*/src/main/**/*.kt", "*/src/main/**/*.java") },
    ).withPropertyName("productionSources").withPathSensitivity(PathSensitivity.RELATIVE)
}
