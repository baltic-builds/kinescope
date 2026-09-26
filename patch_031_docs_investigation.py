#!/usr/bin/env python3
"""
Patch 31 -- documentation only. No code changes.

Records the patch-30 device report and a researched (not guessed) set of
ranked hypotheses for why the strategy search finds zero working strategies
on-device while the standalone ByeByeDPI app finds about seven, plus what
the notification-not-appearing report most likely means. Updates
ROADMAP.md, CHANGELOG.md and HANDOFF.md so a fresh conversation has the
full context without re-deriving it.

Device report (2026-09-26):
- The button did not "appear" -- patch 30 did not add a new button inside
  Settings, only rewired the existing Find/Stop pair and added a Stop
  action on DpiSearchService's own notification. Most likely explanation:
  that notification never showed at all. Android 13+ requires the
  runtime POST_NOTIFICATIONS permission for a notification to display;
  patch 30's own docstring already flagged this as an unaddressed gap.
  If the app never prompted for that permission (it is currently only
  requested on the first accepted download, per patch 25), the search
  still runs as a real foreground service and still works, but nothing
  new becomes visible -- which matches the report closely.
- The strategy test reports zero passes, while the user's separately
  installed ByeByeDPI (github.com/romanvht/ByeByeDPI, itself a fork of
  ByeDPIAndroid wrapping the same upstream hufrea/byedpi engine Kinescope
  vendors) finds ~7 working strategies on the same network right now.

Research performed this session (web search + fetch, not guessed):
- hufrea/byedpi's own documentation gives two general example command
  lines: `--disorder 1 --auto=torst --tlsrec 1+s` and `--fake -1 --ttl 8`.
  Translated to short options these are exactly Kinescope's built-in
  strategies #1 (`-d1 -Atorst -r1+s`) and #4/#6 area (`-f-1 -t8` /
  `-d1 -f-1 -t8`) -- the built-in list is not arbitrary, it already
  matches upstream's own reference examples.
- ByeByeDPI's README describes pinning/importing/exporting strategy
  command lines and a community manual, but no automated multi-candidate
  prober of its own; the "~7 working strategies" almost certainly came
  from manually trying community-recommended strings and confirming
  YouTube itself loads -- a materially different, more forgiving bar
  than Kinescope's synthetic probe.
- Re-read `NetworkCheck.kt`: `checkViaBypass()` correctly does remote
  (proxied) DNS resolution via SOCKS5 CONNECT-by-name, matching yt-dlp's
  actual `socks5h://` usage -- ruling out a local-vs-proxied DNS
  resolution mismatch as the cause.
- Re-read `DpiSearch.kt`: `fullPass` requires ALL THREE probe hosts
  (`www.youtube.com`, `i.ytimg.com`, `redirector.googlevideo.com`) to
  fully pass DNS+TCP+TLS+HTTP -- one bad host fails an otherwise-working
  strategy outright. The probe itself is a hand-rolled bare TLS + one
  legacy HTTP/1.1 `HEAD / ... Connection: close` request with no ALPN/H2
  negotiation, no valid signed path, and no session state -- exactly the
  kind of request Google's video CDN redirector is liable to reject or
  behave unusually on regardless of DPI bypass state.

Leading hypothesis: the "all 3 hosts must fully pass" bar, combined with
`redirector.googlevideo.com` specifically being a poor fit for a bare
synthetic probe, produces false negatives on strategies that would
actually work for real yt-dlp traffic. Not yet confirmed -- needs the
per-stage-per-host failure detail from a real run (already captured by
the existing hidden log journal) rather than just "0 passed".

Safe to run twice. Run from the repository root:

    python3 patch_031_docs_investigation.py
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent


class AnchorNotFound(Exception):
    pass


def read(path: pathlib.Path) -> str:
    if not path.exists():
        raise AnchorNotFound(f"{path} does not exist")
    return path.read_text(encoding="utf-8")


def write(path: pathlib.Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(path: pathlib.Path, old: str, new: str, *, already_applied: str = None) -> None:
    text = read(path)
    marker = already_applied if already_applied is not None else new
    if marker in text:
        return  # already applied
    count = text.count(old)
    if count != 1:
        raise AnchorNotFound(
            f"expected exactly one match for anchor in {path}, found {count}\n--- anchor ---\n{old}"
        )
    write(path, text.replace(old, new, 1))


# ---------------------------------------------------------------------------
# ROADMAP.md
# ---------------------------------------------------------------------------

def patch_roadmap():
    path = ROOT / "ROADMAP.md"

    old_p30_item = (
        "- [ ] **Needs a real device to confirm:** the search keeps running and finishes after\n"
        "  minimizing the app or navigating to another screen; the notification's Stop button works;\n"
        "  Settings shows the correct state after leaving and returning while a search is in progress;\n"
        "  a verified strategy is picked up correctly whether the automatic or manual search found it.\n"
    )
    new_p30_item = (
        "- [x] **Device report (2026-09-26):** the strategy search reports zero working strategies "
        "(vs. ~7 the user's separately installed ByeByeDPI finds on the same network right now), and "
        "no new button/notification was visibly seen. See Patch 31 below for the researched "
        "hypotheses and next steps -- not yet root-caused, so the checklist above stays open.\n"
    )
    replace_once(path, old_p30_item, new_p30_item, already_applied="Device report (2026-09-26)")

    anchor = "### Patch 27 network bypass: verified baseline"
    new_section = """### Patch 31 investigation: zero strategies pass vs. ~7 in ByeByeDPI

No code changed. Full research detail and reasoning in `CHANGELOG.md`; this is the tracking
checklist for what to do about it next session.

- [ ] **"No button appeared" is most likely a missing notification, not a missing feature.**
  Patch 30 added no new in-Settings button -- only a Stop action on `DpiSearchService`'s own
  notification. That notification needs the runtime `POST_NOTIFICATIONS` permission (Android
  13+), which today is only requested on the first accepted download (patch 25). Check whether
  the permission was ever granted; if not, either extend that existing request to also cover
  first use of the bypass, or request it the first time `DpiSearchService` starts.
- [ ] **Get the actual per-host, per-stage failure detail**, not just "0 passed". The hidden
  log journal (five taps on Settings) already records exactly which `CheckStage`
  (DNS/TCP/PROXY/CONNECT/TLS/HTTP) failed for each host and strategy -- pull that instead of
  re-running the search blind.
- [ ] **Ask the user for the specific strategy strings ByeByeDPI has working** (its own
  Import/Export settings feature can produce these). Try them directly against Kinescope's
  engine, bypassing the search/probe entirely, to separate two very different problems: (a) the
  engine/JNI/SOCKS plumbing itself is broken regardless of strategy, vs. (b) the plumbing is
  fine and only the automated search's pass/fail bar is wrong.
- [ ] **Reconsider the "all 3 hosts must fully pass" bar** in `DpiStrategySearch.fullPass`
  (`DpiSearch.kt`). `redirector.googlevideo.com` is a CDN redirector likely to behave oddly
  under a bare hand-rolled HTTP/1.1 HEAD probe (no ALPN/H2, no valid signed path, no session)
  regardless of DPI bypass state. A strategy that fixes `www.youtube.com` and `i.ytimg.com` but
  not that one host may still work fine for a real download. Consider a softer bar (2-of-3, or
  treat the CDN host as informational rather than required) before touching the strategy list
  itself.
- [ ] Only after the above: consider widening `DpiBuiltInStrategies`/the downloaded candidate
  pool. Note this session's research found the *existing* built-ins already match
  `hufrea/byedpi`'s own documented reference examples verbatim -- they are not obviously wrong
  or arbitrary, so a wider pool is a secondary fix, not the first thing to change.

""" + anchor
    replace_once(path, anchor, new_section, already_applied="### Patch 31 investigation: zero strategies pass")


# ---------------------------------------------------------------------------
# CHANGELOG.md
# ---------------------------------------------------------------------------

def patch_changelog():
    path = ROOT / "CHANGELOG.md"
    anchor = "## Patch 30 \u2014 Persistent strategy search"
    new_section = """## Patch 31 \u2014 Investigation: zero strategies pass vs. ~7 in ByeByeDPI (no code change)

Device report after patch 30: the build applied cleanly, but no new button/notification was
seen, and the strategy search still reports zero working strategies. For comparison, the user's
separately installed ByeByeDPI (github.com/romanvht/ByeByeDPI) finds about 7 working strategies
on the same network right now. This patch is documentation only -- research to ground the next
code change in evidence rather than a guess, per this project's own verify-before-coding rule.

### "No button appeared"
Patch 30 did not add a new button inside the Settings screen -- it rewired the existing
Find/Stop pair to drive `DpiSearchService`, and added a Stop action on that service's own
notification. The most likely explanation for nothing new being visible is that the
notification itself never showed: Android 13+ requires the runtime `POST_NOTIFICATIONS`
permission, which today is only requested on the first accepted download (patch 25). The
foreground service still starts and still runs the search either way -- `startForeground()`
does not fail for lack of that permission -- but nothing new becomes visible without it, which
matches the report closely. Not yet confirmed; needs checking whether that permission was ever
granted on this device.

### Zero strategies passing: research (web search + reading the actual code, not guessed)
- **The built-in strategies are not arbitrary.** `hufrea/byedpi`'s own documentation gives two
  general example command lines: `--disorder 1 --auto=torst --tlsrec 1+s` and
  `--fake -1 --ttl 8`. In Kinescope's short-option form these are, verbatim, built-ins #1
  (`-d1 -Atorst -r1+s`) and the `-f-1 -t8` / `-d1 -f-1 -t8` pair -- already exactly upstream's
  own reference examples, not something invented in patch 27.
- **ByeByeDPI has no automated multi-candidate prober of its own**, per its README (pinning,
  import/export of strategy strings, a community manual -- no built-in test-many-and-rank
  feature). The user's "~7 working strategies" almost certainly came from manually trying
  community-recommended strings and confirming YouTube itself loads: a materially more
  forgiving, real-world bar than Kinescope's synthetic probe.
- **Ruled out:** a local-vs-proxied DNS mismatch in the probe. Re-reading `NetworkCheck.kt`
  confirms `checkViaBypass()` does remote (proxied) DNS resolution via SOCKS5 CONNECT-by-name --
  the same thing `socks5h://` means for yt-dlp's real download traffic. The probe methodology
  is consistent with real usage on this specific point.
- **Leading hypothesis:** `DpiStrategySearch.fullPass` (`DpiSearch.kt`) requires ALL THREE probe
  hosts (`www.youtube.com`, `i.ytimg.com`, `redirector.googlevideo.com`) to fully pass
  DNS+TCP+TLS+HTTP -- one bad host fails an otherwise-working strategy outright. The probe
  itself is a hand-rolled bare TLS handshake plus one legacy HTTP/1.1 `HEAD / ... Connection:
  close` request, with no ALPN/HTTP2 negotiation, no valid signed path, and no session state.
  `redirector.googlevideo.com` is a CDN redirector that is plausibly a poor fit for exactly that
  kind of bare synthetic request regardless of whether the DPI bypass strategy itself is good --
  producing false negatives across the board. Not yet confirmed.

### Next steps (tracked in ROADMAP.md, Patch 31 section)
Get the real per-host, per-stage failure detail from the hidden log journal instead of just "0
passed"; ask the user to export ByeByeDPI's known-working strategy strings and try them directly
against Kinescope's engine (isolates plumbing-is-broken vs. bar-is-wrong); reconsider the
all-3-hosts-must-pass bar before touching the strategy list itself, since the list is already
evidenced to be reasonable.

""" + anchor
    replace_once(path, anchor, new_section, already_applied="## Patch 31 \u2014 Investigation: zero strategies pass")


# ---------------------------------------------------------------------------
# HANDOFF.md
# ---------------------------------------------------------------------------

def patch_handoff():
    path = ROOT / "HANDOFF.md"

    old_anchor = "### Patch 29-30 / bypass reliability + persistent search status"
    new_section = """### Patch 31 / zero-strategies-pass investigation (no code change)

Device report after patch 30: no new button/notification was visibly seen, and the strategy
search still reports zero working strategies, while the user's separately installed ByeByeDPI
(a fork of ByeDPIAndroid, wrapping the same upstream `hufrea/byedpi` engine Kinescope vendors)
finds about 7 working strategies on the same network right now. Patch 31 is documentation only:
researched, ranked hypotheses rather than a guessed code fix. Two things worth knowing before
touching anything: (1) Kinescope's built-in strategy list is not arbitrary -- it already matches
`hufrea/byedpi`'s own documented reference examples verbatim, so widening that list is a
secondary fix, not the first thing to try; (2) `DpiStrategySearch.fullPass` requires all 3 probe
hosts (including `redirector.googlevideo.com`, a CDN redirector) to fully pass a bare hand-rolled
HTTP/1.1 probe with no ALPN/H2/session state -- a strong candidate for false negatives regardless
of whether a strategy actually works. Separately, "no button appeared" most likely means the
service's notification never displayed for lack of the runtime `POST_NOTIFICATIONS` permission
(only requested today on the first accepted download, patch 25) -- the search itself still runs
either way. Full research and the concrete next-step checklist: `ROADMAP.md` -> Patch 31,
`CHANGELOG.md` -> Patch 31.

""" + old_anchor
    replace_once(path, old_anchor, new_section, already_applied="### Patch 31 / zero-strategies-pass investigation")

    old_next_step = """## Immediate next step for Claude (in a new conversation)

**Collect device verification for patches 29-30 first** -- both are code-reviewed/compile-checked
but device-unconfirmed. Exact checklist in `ROADMAP.md`'s Patch 29 and Patch 30 sections: does
the strategy search now survive minimizing the app / switching screens and actually finish; does
it correctly enable the bypass switch and pick a verified strategy + fallbacks; does the
notification Stop button work; does a bypass-enabled download still work correctly if it starts
while a search is running (should simply proceed without the bypass for that attempt, per
`DpiEngine`'s existing gate -- not a bug if so); does the `YTOffline` -> `Kinescope` folder
migration take effect on an existing install; does a freshly downloaded video now play with
picture and sound (H.264/AAC preference). If any of these fails, diagnose that concrete failure
first and update `CHANGELOG.md` / `HANDOFF.md` in the same patch as the fix -- do not mark a
device-dependent item done from source inspection or a green compile alone."""
    new_next_step = """## Immediate next step for Claude (in a new conversation)

**Root-cause the Patch 31 investigation first** -- this is now the blocking item, ahead of the
rest of the patch 29/30 device checklist. Read `ROADMAP.md`'s Patch 31 section and
`CHANGELOG.md`'s Patch 31 entry for the full research already done (do not re-derive it from
scratch). Concretely, in order: (1) check whether `POST_NOTIFICATIONS` was ever granted on this
device, and if not, decide whether to extend the existing download-time request or add one for
the bypass; (2) pull the actual per-host, per-stage failure detail from the hidden log journal
for a real search run, instead of treating "0 passed" as sufficient information; (3) ask the user
to export their working ByeByeDPI strategy strings and try them directly against Kinescope's
engine to separate "the plumbing is broken" from "the pass bar is wrong"; (4) if the evidence
points that way, relax `DpiStrategySearch.fullPass`'s all-3-hosts requirement (`DpiSearch.kt`)
before considering a wider strategy list -- the existing built-ins are already evidenced to match
upstream's own reference examples, so they are not the first suspect. Do not ship a code fix for
this from a guess; every prior guess-vs-verify split in this project has favored verifying first.

Once that is resolved, the rest of the patch 29/30 device checklist is still open: does the
strategy search survive minimizing the app / switching screens and actually finish; does it
correctly enable the bypass switch and pick a verified strategy + fallbacks; does a
bypass-enabled download still work correctly if it starts while a search is running (should
simply proceed without the bypass for that attempt, per `DpiEngine`'s existing gate -- not a bug
if so); does the `YTOffline` -> `Kinescope` folder migration take effect on an existing install;
does a freshly downloaded video now play with picture and sound (H.264/AAC preference). If any of
these fails, diagnose that concrete failure first and update `CHANGELOG.md` / `HANDOFF.md` in the
same patch as the fix -- do not mark a device-dependent item done from source inspection or a
green compile alone."""
    replace_once(path, old_next_step, new_next_step, already_applied="Root-cause the Patch 31 investigation first")


# ---------------------------------------------------------------------------

def main():
    steps = [patch_roadmap, patch_changelog, patch_handoff]
    for step in steps:
        try:
            step()
        except AnchorNotFound as exc:
            print(f"FAILED at {step.__name__}: {exc}", file=sys.stderr)
            sys.exit(1)
        print(f"applied: {step.__name__}")
    print("Patch 31 applied successfully.")


if __name__ == "__main__":
    main()
