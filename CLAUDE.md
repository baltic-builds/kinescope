# CLAUDE.md — Project Instructions

Read this file first in any new session working on this repo.

## What this project is
A personal Android app that downloads YouTube videos for offline
viewing. Built by Baltic, who travels for work to a region where
YouTube is network-restricted. Videos are downloaded at home in the
Netherlands (normal residential IP) and watched offline on the phone
during trips.

## Ground rules (do not deviate without being asked)
- **Personal use only.** No Google Play distribution. Distribution is
  via a sideloaded APK (e.g. attached to a private GitHub Release).
- **Language:** all code, comments, commit messages, and this
  documentation are written in English, regardless of the language
  the user and Claude converse in elsewhere.
- **No custom extractor.** Never implement YouTube signature-cipher
  decryption, bot-detection bypass, or any reverse-engineered
  extraction logic from scratch. All extraction goes through the
  actively-maintained `yt-dlp` project (via a Kotlin/Android wrapper
  library). This app is a UI + orchestration layer on top of that
  dependency — nothing more.
- **Zero required cost.** No paid services. A home-hosted backend is
  optional future scope, never required for the app to work.
- **Build target:** the app must build headlessly via
  `./gradlew assembleDebug` inside a GitHub Codespace — there is no
  Android Studio GUI and no emulator available there. Don't add build
  steps that assume an interactive IDE or a connected device/emulator
  (manual install/testing on a real phone afterwards is fine and
  expected).

## Current state
See `ROADMAP.md` for phase status and `HANDOFF.md` for the latest
session snapshot. **Read `HANDOFF.md` first** if you're starting a
fresh conversation with no prior context on this repo.

## User instruction log
(Append new instructions from the user here, most recent last, so
future sessions don't lose them.)

- Write all code and documentation in English.
- Keep writing phases back-to-back without pausing for a build check
  after each one — the user will build and test everything together
  once, later. Still flag unverified/risky assumptions in code
  comments and ROADMAP.md as they come up; just don't stop and wait.
