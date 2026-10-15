plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dav.isla"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dav.isla"
        minSdk = 26
        targetSdk = 36
        versionCode = 5
        versionName = "0.5"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
