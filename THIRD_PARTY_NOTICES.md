# Third-party notices

Kinescope is a personal sideloaded Android application. This file is an inventory for maintenance and release review; it is not legal advice and does not replace the license texts shipped by upstream dependencies.

## Runtime dependencies

| Component | Pinned / resolved version | Role | Upstream license / notice source |
|---|---:|---|---|
| `youtubedl-android` | `0.18.1` | Android wrapper, bundled Python runtime and native payload used to run yt-dlp | GPL-3.0; https://github.com/yausername/youtubedl-android |
| `byedpi` (vendored C sources, unmodified) | commit `ba532298` | Optional local SOCKS5 DPI-bypass engine; Kinescope's own JNI glue (`dpi_jni.c`) calls it, no code from ByeByeDPI's own GPL-3.0 Kotlin/Java/native-lib.c is used | MIT; https://github.com/hufrea/byedpi -- provenance note at `app/src/main/cpp/byedpi/KINESCOPE_VENDOR.txt` |
| `hev-socks5-tunnel` (vendored arm64 shared library) | `2.17.1` / `9a06bc6` | TUN-to-SOCKS bridge used by the YouTube-only Android VPN route | MIT; https://github.com/heiher/hev-socks5-tunnel -- provenance + license under `app/src/main/jniLibs/` |
| `yt-dlp` | updated at runtime by `youtubedl-android` | YouTube extraction engine | Unlicense for the yt-dlp project; release bundles can contain components under other licenses, documented by upstream in its third-party license material: https://github.com/yt-dlp/yt-dlp |
| AndroidX / Jetpack Compose / Material3 | Gradle-resolved from the versions in `app/build.gradle.kts` and Compose BOM | Android UI/runtime support | Apache-2.0; https://source.android.com/docs/setup/about/licenses and individual AndroidX artifacts |
| Kotlin / kotlinx.coroutines | Kotlin `2.1.0`, coroutines `1.9.0` | Language/runtime concurrency | Apache-2.0; https://github.com/JetBrains/kotlin and https://github.com/Kotlin/kotlinx.coroutines |

## Fonts

Kinescope does not bundle Anthropic fonts. `Theme.kt` requests Inter and Lora through Android's Google Fonts provider; if the provider cannot supply them, Android falls back to a system font. Inter and Lora are distributed under the SIL Open Font License by their respective upstream projects.

## Test-only dependency

JUnit `4.13.2` is used only for local/CI JVM tests and is not part of the application runtime.

## Network bypass strategy list (patch 27)

Settings -> Network bypass -> "Update the strategy list" performs a plain HTTPS GET of https://raw.githubusercontent.com/romanvht/ByeByeDPI/master/app/src/main/assets/proxytest_strategies.list (size- and count-bounded). This is read-only, public, community-maintained data, not a code dependency; every line is re-validated by `DpiStrategyParser`'s allowlist before it can ever reach the bundled engine. ByeByeDPI's own application code (GPL-3.0) is not used. Patch 28 follows its documented local Android VPN architecture but implements Kinescope's service/UI independently and uses the separately MIT-licensed `hev-socks5-tunnel` transport.

## Release maintenance rule

When changing a runtime dependency, review its current upstream license and bundled third-party notices before publishing the next APK. In particular, `youtubedl-android` packages native/Python components into the APK, so a wrapper upgrade is not just a Kotlin API change: re-check its bundled notices, ABI payloads and Android compatibility at the same time.
