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
    namespace = "com.kinescope.app"
    compileSdk = 35

    // Patch 43: pin the NDK. No version was pinned before, so AGP 8.7.2 used its own default,
    // 27.0.12077973 (what every CI and Codespace build has used), and downloaded it in the middle of the
    // build. The release workflow and .devcontainer/setup.sh install exactly this version and CMake 3.22.1
    // (see `externalNativeBuild` below); keep the three places in step.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.kinescope.app"
        // Bumped from 26 to 29 in Phase 3: MediaStore.Downloads (used
        // to publish finished files to the public Downloads folder)
        // doesn't exist before Android 10. Personal app, one device —
        // not worth a legacy fallback path for pre-2019 phones.
        minSdk = 29
        targetSdk = 35
        // Patch 16, retained after patch 24: overridable via
        // `-PappVersionCode=<n> -PappVersionName=<name>` so the release
        // workflow can stamp each signed build without editing this file.
        // Local Codespace builds still fall back to the defaults below.
        versionCode = (project.findProperty("appVersionCode") as String?)?.toIntOrNull() ?: 7
        versionName = (project.findProperty("appVersionName") as String?) ?: "1.0.0"

        // youtubedl-android bundles a full Python runtime, ffmpeg,
        // ffprobe, and QuickJS as native libraries -- multiplied per
        // ABI, this is what made the release APK ~200MB (confirmed by
        // a real build, patch 21; see CHANGELOG.md). arm64-v8a covers
        // the overwhelming majority of real Android phones since
        // ~2019; x86/x86_64 only matter for emulators, and
        // armeabi-v7a only for older 32-bit devices. Add
        // "armeabi-v7a" back to this list if you ever need to install
        // on one of those.
        ndk {
            abiFilters += listOf("arm64-v8a")
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

    packaging {
        jniLibs {
            // Patch 43: youtubedl-android ships its Python and ffmpeg payload as libpython.zip.so and
            // libffmpeg.zip.so and needs them extracted to the app's native library directory at install
            // time. This used to be `android:extractNativeLibs="true"` in the manifest, which AGP 8 warns
            // about; this DSL is the same setting.
            useLegacyPackaging = true
            // Those two files are zip archives with a .so name, so llvm-strip cannot read them ("not a valid
            // object file") and the build packaged them unstripped anyway. Skipping them only silences
            // the error lines; the APK content is the same.
            keepDebugSymbols += "**/*.zip.so"
        }
    }

    lint {
        // The release workflow runs `lintDebug` before the release build, over the same code. The extra
        // fatal-only pass that `assembleRelease` would add repeats it, so it is switched off.
        checkReleaseBuilds = false
    }

    // Patch 27: the bundled DPI-bypass engine (app/src/main/cpp) is vendored C, built by CMake.
    // Requires the Android NDK to be installed (see .devcontainer/setup.sh /
    // .github/workflows/build-release.yml); not just the SDK.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // Every pinned version here is from the same window (AGP 8.7.2, Kotlin 2.1.0, activity-compose 1.9.3,
    // core-ktx 1.15.0, coroutines 1.9.0); 2024.11.00 is the Compose BOM release from it (pulls Material3 1.3.1,
    // see https://developer.android.com/jetpack/compose/bom/bom-mapping). Recheck API call sites if it is bumped.
    implementation(platform("androidx.compose:compose-bom:2024.11.00"))

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose:1.9.3")

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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Design pass: downloadable Google Fonts (Inter/Lora) instead of
    // bundling font files. See Theme.kt and design.md.
    implementation("androidx.compose.ui:ui-text-google-fonts")

    testImplementation("junit:junit:4.13.2")
}