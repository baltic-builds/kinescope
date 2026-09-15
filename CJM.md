# Customer Journey Map — Kinescope

This document captures the actual use case that drives every
prioritization decision in `ROADMAP.md`. It exists so future changes
get judged against the real journey a real trip involves, rather than
against abstract "code quality" — a bug's severity in this app is a
function of *where in this journey* it can strike, not just how likely
it is to happen. See `ROADMAP.md`'s Step 8 for why this was written.

## Context

One user (the developer), one phone, no backend, no Play Store. The
app exists for a specific recurring situation: work trips to a region
where YouTube (and often the wider internet) is heavily
network-restricted. Videos have to be downloaded *before* departure,
while full, unrestricted home internet access is available, and
watched entirely offline once in-region.

## The five stages

### 1. Prep at home — the night before a trip

The user browses YouTube on a laptop or phone, finds videos worth
having offline, and shares or pastes several links into Kinescope in
quick succession — realistically 3-10 videos in a single sitting,
often within seconds of each other as tabs get closed one after
another.

**This is the single highest-risk moment in the whole journey**, and
the reason is structural, not just "bugs are bad here too": this is
the *last point of full, trusted internet access* before the trip.
Every other stage either has no internet at all (in-region) or no more
opportunities to add videos (already departed). A failure here is
**silent and undetectable until it's too late** — there's no
"retry from the airport" option once stage 3 has happened.

Concretely, this stage is why:
- The `DownloadService` worker race condition (a job silently stranded
  at "Queued" when a new job arrives at the exact moment the worker
  thread is tearing down) was ranked **Critical**, not "edge case,"
  despite needing precise timing to trigger — queuing several videos
  in quick succession is not an edge case here, it's the *primary*
  usage pattern this stage produces every single time.
- `DownloadQueueBus`'s atomic updates matter: several jobs' progress
  ticks and enqueues genuinely race against each other in this exact
  scenario.
- Host-validating shared/pasted URLs (YouTube only) exists so a
  mis-tapped share from the wrong app fails immediately and visibly at
  this stage, instead of silently queuing something that will never
  produce a usable video.

### 2. Queue & download

The user watches the queue fill in, checks progress bars, and
generally treats "it's in the list with a progress bar" as
confirmation that the video is being taken care of — this is the
implicit contract the UI makes, and everything in Step 6.5's queue-row
design (status line, progress bar, per-state coloring) exists to keep
that contract honest in real time, not just at the end.

This stage relies on:
- The foreground service notification staying alive and accurate —
  it's the only feedback loop between "I tapped share" and "it's
  actually happening" for however long the user has the app in view.
- Human-readable filenames (Step 4) so a *quick visual scan* of the
  queue is enough to confirm the right videos are downloading, without
  having to open each one.
- The catch-all exception handler (Step 3) so one malformed video's
  failure shows up as one failed row, not a crashed app that silently
  drops everything else still queued behind it.

### 3. Departure — loses internet access

The exact moment "downloaded" stops being a checkable claim and starts
being an irreversible fact. Whatever's actually on the device at this
instant is the entire library for the trip. There is no connectivity
left to check a failed download, retry a stalled one, or even notice
one was missing — stage 1's silent-failure risk is precisely the risk
of *not finding out until this moment has already passed*.

### 4. Watch offline, in-region

The user opens the Library tab and plays videos with zero network
available. This stage relies entirely on decisions made *before*
departure ever having gone right:
- `MediaStore`-published entries actually being visible (Step 4's
  `RELATIVE_PATH` trailing-slash fix — a mismatch here means the
  Library tab shows empty even though the files exist on disk, the
  single worst possible failure at this exact stage, since there's no
  connectivity left to investigate why).
- Filenames matching what the user remembers choosing at stage 1.
- Playback working via whatever video player is actually installed
  (the `ActivityNotFoundException` guard on `playItem()`), since
  there's no Play Store access in-region to install one on the spot.

### 5. Return & refresh library

Back home, back on full internet. The user deletes videos already
watched (Library's overflow menu — share/delete, Step 6.5), frees up
space, and queues the next batch for the next trip. **This closes the
loop back to stage 1** — which is exactly why Step 5's manual test
checklist explicitly stress-tests "queue several videos in quick
succession": it's not a synthetic stress test, it's a direct
simulation of stage 1's real, everyday action.

## The key finding

**Stage 1 → Stage 3 is a one-way door.** Nothing that goes wrong
between "I queued it" and "I boarded the plane" is recoverable — there
is no retry, no fallback, no way to even detect the failure until it's
already too expensive to fix. This is the reason every crash / race /
silent-failure bug found in Steps 1, 3, and 4 was ranked Critical or
High **regardless of how narrow or hard-to-trigger the exact condition
seemed on paper** — the failure mode being defended against isn't
"annoying," it's "a specific video simply isn't there when it's
needed, with zero recourse." Severity in this app is about *which side
of the one-way door* a bug lives on, more than about raw likelihood.

## Keeping this current

If the actual travel pattern changes — different regions, different
trip lengths, downloading from a device other than the one used
in-region, multiple travelers sharing one library — revisit this
document before assuming the existing priority ordering in
`ROADMAP.md` still holds. It was derived from this specific journey,
not from general best practice.
