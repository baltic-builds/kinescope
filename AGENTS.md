# AGENTS.md — How to work on this repo

A process guide for any AI coding agent (Claude or otherwise) picking
up work on Kinescope. This complements rather than duplicates
`CLAUDE.md` (project ground rules — what the app is and isn't allowed
to do) and `HANDOFF.md` (a point-in-time snapshot of what's done and
what's next). This file is about *how* to work here, not *what* the
current state is — that's always `HANDOFF.md`'s job, and it changes
every patch.

## Read this first, in this order

1. **`CLAUDE.md`** — ground rules. Personal use only, no custom
   extraction, English-only docs, no paid services, no Anthropic
   branding. Don't deviate without being asked.
2. **`HANDOFF.md`** — what's actually done, what's actually open, the
   full patch-by-patch narrative, and hard-won key learnings. Read
   this before touching anything; a fresh session has no memory of
   *why* things are the way they are otherwise.
3. **`ROADMAP.md`** — current status and open technical debt. Short by
   design; the step-by-step history of how each item got closed lives
   in `CHANGELOG.md`, not here.
4. **`CHANGELOG.md`** — one entry per patch, newest first: exactly
   what changed and why, including findings that turned out wrong
   (dead ends are worth keeping on record, not just successes).

Never assume memory from earlier in a conversation is still accurate,
especially several patches in. Read the actual current file content
before editing anything.

## Environment

- Development happens exclusively in **GitHub Codespaces** — no
  Android Studio GUI, no emulator, no connected device. Every build
  has to succeed headlessly.
- Verification build: `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`. A plain `assembleDebug` is acceptable only as a quick compile while iterating. Signed releases are built by `.github/workflows/build-release.yml`; see `RELEASE.md`.
- If `gradlew` is missing or the devcontainer's `postCreateCommand`
  didn't finish, run `bash .devcontainer/setup.sh` manually.
- CI: `.github/workflows/build-release.yml` only, manually triggered (`workflow_dispatch`) and hand-versioned per run. It publishes the signed APK + checksum to GitHub Releases. Debug CI was deliberately removed in patch 24; `./gradlew assembleDebug` remains the local sanity check.
- Real-device testing is manual, on the user's own phone, and always
  needs an explicit report back — see "What 'done' means" below.

## Verification discipline

Don't guess at a library/API/version from general familiarity. This
project has been burned twice by exactly that: a fabricated Compose
BOM version that didn't exist (patch 01), and a plausible-but-wrong
guess at where `UpdateChannel` lived in `youtubedl-android` — a README
snippet had silently dropped the qualifying outer-class name, and the
guess held until a real compile error contradicted it (patch 14).
Whenever a claim about a third-party library, framework API, or
version matters for correctness:

- Prefer the library's own tagged source (`git clone` + checkout the
  exact pinned version) or official documentation over a README
  snippet, a blog post, or an old sample app.
- If a real error contradicts an earlier "verified" assumption,
  re-verify against the primary source directly — don't re-read the
  same secondary source that produced the wrong conclusion the first
  time.
- This applies to platform APIs too, not just third-party libraries —
  patch 22's fix (a Compose `painterResource()` crash on a framework
  `AnimatedVectorDrawable`) was confirmed against `painterResource()`'s
  own documentation before being called the root cause, not assumed
  from the stack trace alone.

## Patch delivery process

Every code or doc change is delivered as a single, self-contained
Python patch script, not as inline instructions to run by hand:

1. **Exact-match guarded edits.** Every edit raises a clear error if
   its anchor text isn't found, rather than silently no-op'ing or
   guessing at a fuzzy match.
2. **Idempotent.** Safe to run twice without duplicating content —
   check for a distinguishing marker of the change already being
   applied and skip if so.
3. **A module docstring** explaining what the patch does and why,
   referencing the relevant `ROADMAP.md`/`CHANGELOG.md` item.
4. **Tested before delivery, every time:** extract the current repo
   into a local working copy, run the script against it, diff the
   result against intent, check bracket/brace balance in any touched
   source files, validate any touched XML is well-formed, and run the
   script a second (and, for anything touching a file several past
   patches also edit, a third+) time to confirm idempotency. Never
   hand over an untested script.
5. **Docs updated in the same patch as the code they describe** —
   `ROADMAP.md`'s technical-debt list and `CHANGELOG.md`'s new entry
   land together with the change, not as a follow-up.
6. **Alongside every patch script:** the command to run it and the git
   add/commit/push commands with a descriptive commit message. The script
   itself should run `./gradlew --no-daemon testDebugUnitTest lintDebug assembleDebug`
   before self-deleting whenever practical, so verification cannot be
   accidentally skipped.
7. **Scoped to one coherent unit of work** — one `ROADMAP.md` item, or
   a tightly related group — rather than sprawling across unrelated
   changes. Work in sprints across a conversation (a batch of related
   items handled back-to-back without stopping for approval between
   them), then deliver **one** patch script per sprint, not one per
   tiny step.

**A later patch can silently break an earlier patch's own idempotency
check**, if the earlier patch's "already applied" detection doesn't
also recognize the later patch's marker (this happened repeatedly
across patches 12/14/16/17/19 — see `HANDOFF.md`'s "Key learnings").
Any patch touching a file several previous patches already edited
(`ROADMAP.md`, `HANDOFF.md`, `CHANGELOG.md` especially) should budget
time to also patch its immediate predecessor's idempotency check, and
run the full patch chain multiple times from a clean copy to catch it
— a single dry run isn't enough.

**Never write a machine-specific path** (a Codespace's local
filesystem layout, an absolute SDK/JDK location, etc.) into a file
that gets `git add`ed. Anything derived from the current environment
belongs in a user-level/local config location instead — patch 18
exists because patch 12 got this wrong once already.

## What "done" means

Nothing gets marked done, confirmed, or closed without an explicit
report from the user that they actually ran it and it actually
worked. "The app compiles" is not "the app works"; "CI succeeded" is
not "it installs on a real device." This project's entire `ROADMAP.md`
existed, for most of its life, because seven early development phases
were written with no intermediate compilation at all — don't recreate
that gap in a different shape by assuming a later stage is fine just
because an earlier one was.

## Language

All code, code comments, commit messages, and technical documentation
(`ROADMAP.md`, `HANDOFF.md`, `CLAUDE.md`, `design.md`, this file,
etc.) are written in English, regardless of what language the
conversation itself is in. Conversational replies to the user can be
in whatever language they write in.

## Scope discipline

Personal, single-user app. No feature should introduce a required
paid service, a backend, or a dependency on Anthropic branding/name.
Extraction always goes through `yt-dlp` (via `youtubedl-android`) —
never write custom YouTube extraction logic. See `CLAUDE.md` for the
full ground rules.

There is now only one roadmap file: `ROADMAP.md`. The former lowercase
`roadmap.md` audit was consumed into patch 25 and deleted. Do not
recreate it or restore its obsolete historical assumptions; add new
work to `ROADMAP.md` instead.