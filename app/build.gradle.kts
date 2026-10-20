plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dav.isla"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dav.isla"
        minSdk = 29
        targetSdk = 36
        versionCode = 41
        versionName = "0.9.34"
        // Número de la ejecución de GitHub Actions (el tag del release es v<número>); en local queda en 0
        buildConfigField("int", "BUILD_NUMBER", (project.findProperty("buildNumber") ?: "0").toString())
    }

    buildFeatures {
        buildConfig = true
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
    // Permite usar las opciones de ventana libre de Android sin Shizuku ni root
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
}
