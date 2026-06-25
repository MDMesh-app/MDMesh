plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.mdmesh.core"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        // Network base URL. Overridable at build time WITHOUT editing this file (so the
        // ephemeral cloudflared quick-tunnel never lands in git): pass `-PmdmBaseUrl=<url>`
        // or set the `MDM_BASE_URL` env var. Defaults to a placeholder for committed builds.
        // The tunnel URL is EPHEMERAL — it changes if cloudflared restarts; rebuild then.
        // Follow-up: make this runtime-configurable (from the provisioning extras).
        val mdmBaseUrl = (project.findProperty("mdmBaseUrl") as String?)
            ?: System.getenv("MDM_BASE_URL")
            ?: "https://mdm.example.com/"
        buildConfigField("String", "MDM_BASE_URL", "\"$mdmBaseUrl\"")

        // Dev/CI fallback enrollment token for ADB enrollment (no QR provisioning
        // bundle). Empty in production builds — real tokens come from the QR bundle.
        buildConfigField("String", "DEV_ENROLL_TOKEN", "\"\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true   // java.time (Instant/LocalDate) on minSdk 24
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    api(project(":proto"))
    api(project(":policy"))
    api(project(":oem"))
    api(project(":kiosk"))
    api(project(":remote"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // Persistence
    implementation(libs.datastore.preferences)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
