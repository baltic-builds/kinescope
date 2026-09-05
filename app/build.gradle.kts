import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Phase 7: release signing config. Reads from keystore.properties,
// which is gitignored and never committed — see RELEASE.md for how
// to create it. If the file doesn't exist (e.g. a fresh clone before
// signing is set up), release builds fall back to unsigned rather
// than failing the whole build.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
val hasKeystoreConfig = keystorePropertiesFile.exists()
if (hasKeystoreConfig) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.baltic.ytoffline"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.baltic.ytoffline"
        // Bumped from 26 to 29 in Phase 3: MediaStore.Downloads (used
        // to publish finished files to the public Downloads folder)
        // doesn't exist before Android 10. Personal app, one device —
        // not worth a legacy fallback path for pre-2019 phones.
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "1.0.0"

        // youtubedl-android bundles native Python/yt-dlp binaries per
        // ABI; without this the APK would try to include every ABI
        // and bloat, or fail to package correctly on some setups.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }
    }

    signingConfigs {
        if (hasKeystoreConfig) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Left off deliberately: minification/R8 can break
            // reflection-heavy libraries (coroutines, the yt-dlp
            // wrapper) in ways that are painful to debug for a
            // personal app with exactly one user. Not worth it just
            // to shrink the APK.
            isMinifyEnabled = false
            if (hasKeystoreConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")

    debugImplementation("androidx.compose.ui:ui-tooling")

    // Android wrapper around the yt-dlp executable (bundles yt-dlp +
    // a Python runtime). We depend on this instead of writing any
    // extraction logic ourselves — see CLAUDE.md ground rules.
    // https://github.com/yausername/youtubedl-android
    val youtubedlAndroid = "0.18.1"
    implementation("io.github.junkfood02.youtubedl-android:library:$youtubedlAndroid")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:$youtubedlAndroid")

    // Phase 4: foreground service + notification + shared queue state.
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    // Design pass: downloadable Google Fonts (Inter/Lora) instead of
    // bundling font files. See Theme.kt and design.md.
    implementation("androidx.compose.ui:ui-text-google-fonts")
}
