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
        versionCode = 11
        versionName = "0.9.2"
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
    // Shizuku: permite ejecutar los comandos de la ventana flotante con permisos de shell
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
