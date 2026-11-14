plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.orokichi.orbitpalette"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.orokichi.orbitpalette"
        minSdk = 30
        targetSdk = 35
        versionCode = run
        versionName = "1.0.$run"
    }
    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/release.p12")
            storePassword = "fluxui2026"
            keyAlias = "flux"
            keyPassword = "fluxui2026"
            storeType = "pkcs12"
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    lint { disable += "InvalidFragmentVersionForActivityResult"; abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3:1.4.0-alpha14")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.savedstate:savedstate-ktx:1.3.0")
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:6.1")
    implementation("com.google.mlkit:digital-ink-recognition:18.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
}
