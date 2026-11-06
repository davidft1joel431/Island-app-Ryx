plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.21"
}

android {
    // Ryx Isla derivative metadata; upstream Hyper Bridge source is Apache-2.0.
    namespace = "com.d4viddf.hyperbridge"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.dav.isla"
        minSdk = 35
        targetSdk = 37
        versionCode = 204
        versionName = "2.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // The project distributes its own builds, so the release APK uses the
            // standard Android debug key instead of requiring a private keystore.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            optimization {
                enable = true // Enables code and resource optimizations.
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.palette.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.hyperisland.kit)
    implementation(libs.haze)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)

    // Shizuku
    implementation(libs.api)
    implementation(libs.provider)
}

configurations.all {
    exclude(group = "com.intellij", module = "annotations")
}

// ---------------------------------------------------------------------------
// Missing string translations
//
// Compares the keys of every values-*/strings.xml with values/strings.xml.
// Existing locales are partially translated, so by default the task only
// reports the gaps; pass -PfailOnMissingStrings=true to make them fail a build.
// ---------------------------------------------------------------------------

val missingStringsReport = layout.buildDirectory.file("reports/missing-strings.txt")
val failOnMissingStrings = providers.gradleProperty("failOnMissingStrings")
    .map { it.toBoolean() }
    .orElse(false)

tasks.register("checkMissingStrings") {
    group = "verification"
    description = "Lists string keys from values/strings.xml that are missing in each locale. " +
        "Use -PfailOnMissingStrings=true to fail when any key is missing."

    val resDir = file("src/main/res")
    val reportFile = missingStringsReport.get().asFile
    val shouldFail = failOnMissingStrings.get()

    doLast {
        val baseFile = File(resDir, "values/strings.xml")
        check(baseFile.isFile) { "Base strings file not found: $baseFile" }

        fun readKeys(file: File): Set<String> {
            val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            val nodes = factory.newDocumentBuilder().parse(file).getElementsByTagName("string")
            return (0 until nodes.length).mapNotNull { index ->
                val element = nodes.item(index) as org.w3c.dom.Element
                element.getAttribute("name")
                    .takeIf { element.getAttribute("translatable") != "false" }
            }.toSet()
        }

        val baseKeys = readKeys(baseFile)
        val localeDirPattern = Regex("values-[a-z]{2,3}(-r[A-Z]{2})?")
        val localeDirs = resDir.listFiles()
            ?.filter { it.isDirectory && localeDirPattern.matches(it.name) && File(it, "strings.xml").isFile }
            ?.sortedBy { it.name }
            .orEmpty()

        val missing = localeDirs.map { dir ->
            dir.name to (baseKeys - readKeys(File(dir, "strings.xml"))).sorted()
        }
        val affected = missing.filter { it.second.isNotEmpty() }
        val total = affected.sumOf { it.second.size }

        reportFile.parentFile?.mkdirs()
        reportFile.writeText(buildString {
            appendLine("Missing string translations")
            appendLine("Base: values/strings.xml (${baseKeys.size} keys)")
            appendLine("Locales checked: ${localeDirs.size}")
            appendLine()
            affected.forEach { (locale, keys) ->
                appendLine("$locale (${keys.size} missing)")
                keys.forEach { appendLine("  $it") }
                appendLine()
            }
            appendLine(
                "Summary: $total missing keys across ${affected.size} of ${localeDirs.size} locales"
            )
        })

        if (affected.isEmpty()) {
            logger.lifecycle("Missing strings: none (${baseKeys.size} keys present in all ${localeDirs.size} locales).")
        } else {
            logger.lifecycle("Missing strings: $total keys across ${affected.size} of ${localeDirs.size} locales.")
            affected.forEach { (locale, keys) -> logger.lifecycle("  $locale: ${keys.size} missing") }
            logger.lifecycle("Full report: ${reportFile.absolutePath}")
        }

        if (shouldFail && total > 0) {
            throw GradleException(
                "$total string keys are missing in ${affected.size} locales. " +
                    "See ${reportFile.absolutePath}"
            )
        }
    }
}
