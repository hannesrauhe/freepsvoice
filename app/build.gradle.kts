import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Created by tools/make-keystore.sh; absent = release builds stay unsigned.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Releases are tagged vX.Y.Z; CI passes the tag as RELEASE_TAG and the version
// is derived from it. Local/debug builds fall back to these values.
val releaseTag = System.getenv("RELEASE_TAG") ?: ""
val tagVersion = Regex("""v(\d+)\.(\d+)\.(\d+)""").matchEntire(releaseTag)
    ?.destructured?.let { (maj, min, patch) ->
        Triple(maj.toInt(), min.toInt(), patch.toInt())
    }

android {
    namespace = "eu.rauhe.freepsvoice"
    compileSdk = 35

    defaultConfig {
        applicationId = "eu.rauhe.freepsvoice"
        minSdk = 26
        targetSdk = 35
        versionName = tagVersion?.let { "${it.first}.${it.second}.${it.third}" } ?: "0.1.1-dev"
        versionCode = tagVersion?.let { (maj, min, patch) -> maj * 10000 + min * 100 + patch } ?: 2
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    flavorDimensions += "device"
    productFlavors {
        create("phone") {
            dimension = "device"
            buildConfigField("boolean", "WALL_MODE", "false")
        }
        create("wall") {
            dimension = "device"
            applicationIdSuffix = ".wall"
            buildConfigField("boolean", "WALL_MODE", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                // PKCS12 has one entry and no separate key password, so both have defaults.
                keyAlias = keystoreProps.getProperty("keyAlias") ?: "freepsvoice"
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: keystoreProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Real org.json on the JVM; on device the platform one wins.
    testImplementation("org.json:json:20240303")
    testImplementation("junit:junit:4.13.2")
}
