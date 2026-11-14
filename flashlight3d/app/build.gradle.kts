plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.alexvishnevsky.flashlight3d"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.alexvishnevsky.flashlight3d"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "0.1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    signingConfigs {
        // A fixed key committed to the repo, so every CI build can be installed
        // over the previous one. Not a secret; do not use it for a store release.
        getByName("debug") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "glb"
    }
}

dependencies {
    val filament = "1.57.1"
    implementation("com.google.android.filament:filament-android:$filament")
    implementation("com.google.android.filament:gltfio-android:$filament")
    implementation("com.google.android.filament:filament-utils-android:$filament")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    // Bundled model: face tracking works offline, without Play Services downloads.
    implementation("com.google.mlkit:face-detection:16.1.7")
}
