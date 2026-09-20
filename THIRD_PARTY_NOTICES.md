# Third-party notices

Kinescope is a personal sideloaded Android application. This file is an inventory for maintenance and release review; it is not legal advice and does not replace the license texts shipped by upstream dependencies.

## Runtime dependencies

| Component | Pinned / resolved version | Role | Upstream license / notice source |
|---|---:|---|---|
| `youtubedl-android` | `0.18.1` | Android wrapper, bundled Python runtime and native payload used to run yt-dlp | GPL-3.0; https://github.com/yausername/youtubedl-android |
| `yt-dlp` | updated at runtime by `youtubedl-android` | YouTube extraction engine | Unlicense for the yt-dlp project; release bundles can contain components under other licenses, documented by upstream in its third-party license material: https://github.com/yt-dlp/yt-dlp |
| AndroidX / Jetpack Compose / Material3 | Gradle-resolved from the versions in `app/build.gradle.kts` and Compose BOM | Android UI/runtime support | Apache-2.0; https://source.android.com/docs/setup/about/licenses and individual AndroidX artifacts |
| Kotlin / kotlinx.coroutines | Kotlin `2.1.0`, coroutines `1.9.0` | Language/runtime concurrency | Apache-2.0; https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |

## Fonts

Kinescope does not bundle Anthropic fonts. `Theme.kt` requests Inter and Lora through Android's Google Fonts provider; if the provider cannot supply them, Android falls back to a system font. Inter and Lora are distributed under the SIL Open Font License by their respective upstream projects.

## Test-only dependency

JUnit `4.13.2` is used only for local/CI JVM tests and is not part of the application runtime.

## Release maintenance rule

When changing a runtime dependency, review its current upstream license and bundled third-party notices before publishing the next APK. In particular, `youtubedl-android` packages native/Python components into the APK, so a wrapper upgrade is not just a Kotlin API change: re-check its bundled notices, ABI payloads and Android compatibility at the same time.
