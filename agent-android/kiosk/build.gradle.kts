plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.mdmesh.kiosk"
    compileSdk = 35

    defaultConfig {
        minSdk = 24
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
    // NOTE: :kiosk must NOT depend on :policy. KioskResult is defined locally
    // (see KioskController.kt) to keep this module independent of :policy.
    implementation(libs.androidx.core.ktx)

    testImplementation(libs.junit)
}
