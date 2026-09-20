# Kinescope

[![Build and Publish Signed Release](https://github.com/baltic-builds/kinescope/actions/workflows/build-release.yml/badge.svg)](https://github.com/baltic-builds/kinescope/actions/workflows/build-release.yml)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF?logo=kotlin&logoColor=white)
![Jetpack Compose](https://img.shields.io/badge/Jetpack%20Compose-Material3-4285F4?logo=jetpackcompose&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-29-blue)
![targetSdk](https://img.shields.io/badge/targetSdk-35-blue)
![Distribution](https://img.shields.io/badge/distribution-personal%20sideload-lightgrey)

A personal Android app for downloading individual YouTube videos before travel and watching them offline later. Kinescope is sideload-only: no Google Play requirement, no Kinescope backend and no required paid service.

**Status after patch 25:** the original implementation roadmap and the later reliability audit have been folded into the codebase. Patch 24's local `assembleDebug` is user-confirmed (`91169f5`); patch 25 adds durable queue recovery, strict URL handling, storage/privacy hardening, tests and stronger release verification. Remaining work is real-device / real-Actions verification listed in `ROADMAP.md`, not another autonomous implementation phase.

## Product flow

- **Home:** download queue + offline library.
- **Add:** paste/share a single YouTube video URL, choose quality and enqueue quickly.
- **Settings:** default quality, storage folder, yt-dlp updater and optional local YouTube web session.
- **Diagnostics:** five rapid taps on the Settings navbar icon opens the private log journal.
- **Localization:** English is the default; Russian devices automatically use `values-ru`.

Accepted jobs are persisted before the foreground service is dispatched. If Android kills the process, a non-terminal job returns as explicit `INTERRUPTED` and can be resumed instead of disappearing from the UI. Pause keeps partial fragments, Resume reuses them, and Stop removes the job workspace.

## YouTube reliability model

Kinescope never implements its own YouTube extractor or anti-bot bypass. Every download remains inside `yt-dlp` through `youtubedl-android`.

For YouTube verification / 403 / 429 failures the app uses a bounded recovery chain: current session -> nightly yt-dlp refresh -> supported yt-dlp client/network fallbacks. Optional YouTube cookies can be captured in an app-private WebView session and supplied to yt-dlp. If YouTube still refuses the request, the job is parked as resumable Pause instead of being retried forever.

This is failure recovery, not a guarantee that YouTube will accept every IP/session. See `ROADMAP.md` for the remaining real-device verification and upstream compatibility limitations.

## Storage and privacy

Finished files are committed through `MediaStore.Downloads` only after the copy succeeds; the private source is retained until `IS_PENDING` is cleared successfully. Library queries include both the current Kinescope download folder and historical folders previously configured by the user.

Cookies, logs and the durable job journal remain in app-private storage. Android backup is disabled. Diagnostic logging redacts URLs, known cookie values and private app paths before writing to the rotating local journal or Logcat.

## Tech stack

| | |
|---|---|
| Language | Kotlin `2.1.0` |
| UI | Jetpack Compose + Material3, Compose BOM `2024.11.00` |
| Extraction | `youtubedl-android 0.18.1` -> `yt-dlp` + bundled ffmpeg/ffprobe |
| Concurrency | Kotlin coroutines + one serialized download worker |
| Build | Gradle `8.10.2`, AGP `8.7.2`, JDK 21 |
| Android | `minSdk 29`, `compileSdk 35`, `targetSdk 35` |
| ABI | `arm64-v8a` only |
| Distribution | Signed APK through GitHub Releases |

Third-party dependency/licensing inventory: `THIRD_PARTY_NOTICES.md`.

## Building in Codespaces

There is no Android Studio/emulator requirement. Development and verification are headless.

1. Open a GitHub Codespace on the repo.
2. If the post-create setup did not finish, run `bash .devcontainer/setup.sh`. The Android command-line tools package is pinned by URL + SHA-256; the script also installs platform/build tools and pins Gradle to a compatible local JDK without committing machine-specific paths.
3. Run the normal local verification gate:

```bash
./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug
```

A plain `assembleDebug` is still useful for a quick compile, but patch delivery should use the full gate above.

## Signed releases

The only GitHub Actions build workflow is `.github/workflows/build-release.yml`, triggered manually. It:

1. validates the requested version and signing secrets;
2. runs unit tests + Android lint;
3. builds the signed release APK;
4. verifies APK signature, zip alignment, application ID, version name and arm64-only native payload;
5. writes SHA-256;
6. uploads an Actions artifact backup;
7. creates a versioned GitHub Release containing the APK + checksum.

See `RELEASE.md` for signing-key setup and the exact release process.

## Documentation map

- **`CLAUDE.md`** — non-negotiable project/product constraints and the user decision log.
- **`AGENTS.md`** — coding-agent workflow, verification and patch-delivery rules.
- **`HANDOFF.md`** — latest cross-session state; read this first when resuming work.
- **`ROADMAP.md`** — only remaining verification gates / upstream blockers. The former lowercase audit roadmap has been consumed and remains deleted.
- **`CHANGELOG.md`** — cumulative patch history and rationale.
- **`CJM.md`** — customer journey behind reliability priorities.
- **`design.md`** — current visual tokens, typography, navigation and icon rules.
- **`RELEASE.md`** — release signing and CI publishing.
- **`THIRD_PARTY_NOTICES.md`** — dependency/licensing inventory for release review.

## Scope boundaries

- No Play Store distribution.
- No required backend or cross-device sync.
- No custom YouTube extraction, signature deciphering, BotGuard or PO-token implementation.
- No automatic playlist expansion; the active product flow deliberately queues individual videos.
- Built for one person's private use, not as a public downloader product.
