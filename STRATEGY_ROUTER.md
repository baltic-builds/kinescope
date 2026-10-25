# Strategy router (patch 53)

How Kinescope decides which bypass strategy to use, on which kind of connection, and when to change it. This is the one
place that describes the mechanism end to end; `HANDOFF.md` has the short version and the file map, `CHANGELOG.md` the
history. The code that decides is pure and unit-tested (`NetClass.kt`, `StrategyRating.kt`, `StrategyRouter.kt`,
`ChainPlanner.kt`, `StrategyMemory.kt`); the code that stores and acts is `DpiStrategyStore.kt` (`DpiPrefs`),
`DpiBypass.kt`, `RouteLadder.kt` and `BypassVpnService.kt`.

Status: implemented in patch 53, **not confirmed on a device**. The checklist is in `ROADMAP.md`.

## 1. Why it exists

A strategy is a ByeDPI command line that gets traffic past a network's filter. What works depends on the equipment between
the phone and the internet, so a strategy that is excellent on a Wi-Fi network can be useless on a mobile operator's
network (this was reported on the device). Before patch 53 the app kept ONE chain, ONE learned record and ONE set of test
results for every network, so what it learned on Wi-Fi was applied on mobile data and vice versa.

The router keeps a separate record per **connection class** and chooses, orders and replaces strategies from that record.

## 2. Vocabulary

| Term | Meaning |
|---|---|
| Connection class (`NetClass`) | `WIFI` or `MOBILE`. Everything below is kept once per class. |
| Test | The result of a strategy search on a class (`StoredResult.score` 0-100: hosts reached, a real transfer, simultaneous connections, handshake speed). Stored in `DpiResultsStore`. |
| Real use | What happened when the app really used a strategy: a finished download or a window of real traffic through the YouTube tunnel (`REAL_OK`), a failure that another route then survived (`REAL_BAD`). Stored in `StrategyMemory` (`DpiPrefs.learned`). |
| Rating | One number 0-100 per strategy and class, made of the test and real use (section 4). It is what the stars and the order are made from. |
| Chain | The strategies a class uses, in the order they are tried: six at most (`ChainPlanner.MAX_CHAIN`). Stored per class. |
| Pin | A strategy the user chose; it leads its class's chain. Per class. |
| Seeded chain | A chain borrowed from the other class because this one has never been tested. Marked, and its time stays 0. |
| Quarantine | Ten minutes at the back of the chain after a real failure. Per class. |

## 3. Connection classes

`NetworkState.connectionClass` reads the transports and the "metered" flag of the active network (`NetClass.classify`):

| Reported | Class |
|---|---|
| Ethernet | `WIFI` |
| Wi-Fi, not metered | `WIFI` |
| Wi-Fi, metered by Android (a phone's hotspot) | `MOBILE` (its traffic leaves through a mobile operator) |
| Cellular | `MOBILE` |
| Nothing usable (no network, a VPN with no transport underneath) | none: records are not touched, the tunnel waits, the screens show Wi-Fi |

`NetClassWatcher` keeps the class of the default network as a flow (a default-network callback, started in the main
process by `YtOfflineApp` and in the tunnel's process by `BypassVpnService`). Searches, downloads and the tunnel ask
`NetworkState.currentClass` / `NetClassWatcher.current` when they start.

Deliberate limits: networks of the same class are not told apart (a home and an office Wi-Fi share one record; the SSID needs
the location permission) and neither are mobile operators (that needs the phone-state permission). A third-party VPN is
classified by the transports it reports.

**Legacy data.** The Wi-Fi class uses the preference keys that existed before patch 53 (`NetClass.suffix` is empty), so
everything an older build recorded is the Wi-Fi record. The mobile class has the same keys with `_mobile` added, and the
results store has a second key. Nothing is migrated; the mobile record starts empty.

## 4. The rating

```
testPart  = 50 + (testScore - 50) * 0.5^(ageOfTest / 14 days)       50 when the strategy was never tested; a failed test is 0
evidence  = okWeight + badWeight                                      real use, each fading with a 7-day half-life
realShare = evidence / (evidence + 3)
realPart  = 100 * (ok + 1) / (ok + 2*bad + 2)                         50 when nothing is known; a failure counts double
rating    = round((1 - realShare) * testPart + realShare * realPart)
```

A strategy that was never tested and never used has **no** rating (it is not offered); one that is only known from real use
starts near 50. The line at 50 (`StrategyRating.ELIGIBLE`) is "better than unknown": strategies rated at least 50 may be
offered on Home and put in a chain by a search.

Worked examples (all computed by the code, see `StrategyRatingTest`):

| Situation | Rating |
|---|---|
| Tested 90 just now, never used | 90 |
| The same test, 14 days old / 28 days old | 70 / 60 |
| Tested 90 just now, then 1 / 2 / 3 / 4 / 5 real failures | 74 / 61 / 51 / 44 / 39 |
| Failed its test just now / 14 days ago | 0 / 25 |
| Never tested, 1 / 3 / 9 real successes | 54 / 65 / 81 |
| Never tested, 1 real failure | 44 |
| Tested 40, then 9 real successes | 78 |
| Tested 100, then 3 real successes | 90 (real use now weighs as much as the test) |

So a strategy that tested well but keeps failing for real sinks below the line after four failures within about a week, and
one that tested badly or never but keeps carrying traffic climbs. Old evidence of either kind fades by itself.

Stars on Home are the rating through the user's thresholds: 100 -> 5, 85-99 -> 4, 75-84 -> 3, 60-74 -> 2, below -> 1 (a
strategy with no rating at all is listed without stars). Settings shows the number ("Rating 74") in front of the last test.

## 5. What changes a chain (`StrategyRouter`)

The stored chain of a class is a cache of the router's decisions; it is rewritten by exactly four events:

1. **A search finished on the class** (`afterSearch`). The pool is what passed the search plus every strategy of the class
   rated at least 50, minus those that were tested in this search and failed. It is ordered by rating (the test just stored
   is already part of it), the search's own ranking breaking ties; a pin leads; the strategy that carried a download in the
   last two weeks is kept near the front by a quiet background re-check; a search that was stopped early keeps the old chain
   behind what it found. Six strategies at most.
2. **A strategy carried real traffic** (`afterSuccess`, from `DpiPrefs.promoteVerified`: a download finished through it, or
   `TunnelFlow` saw a healthy window through the tunnel). `REAL_OK` is recorded, the strategy leads, the others follow by
   rating. "It works right now" outranks long-term statistics, and the success also raises its rating.
3. **A strategy failed in real use while another route finished the job** (`afterFailure`, from `DpiPrefs.quarantine`).
   `REAL_BAD` is recorded (the rating drops), the strategy is quarantined for ten minutes (last in the chain), and the stored
   chain is re-ordered at once: it moves down to where its lowered rating puts it, and never up. When the ten minutes pass it
   is not back in front unless its rating still puts it there.
4. **A class has no chain** (`seed`). The first time a class is used with no chain of its own (typically the first time on
   mobile data), it starts from the other class's chain (`DpiStrategyStore.ensureSeeded`): the chain is copied, marked
   seeded, and its time is 0. Those strategies are an untested guess; the next two mechanisms correct it. A search on the
   class replaces it. Nothing is seeded when the other class has no chain either (a fresh install: the mandatory first-run
   check runs).

What the chain is used as (`DpiStrategyStore.chain`): the pin first, then the stored order, with the quarantined
strategies last (`ChainPlanner.order`).

## 6. Where it is applied

**Initial choice by connection type.** Every consumer asks for the chain of the class it runs on, at the moment it starts:
the tunnel when it connects, a download at the start of each attempt (so a download retried after a change of network uses
the new class), the Home dashboard and Settings for what they show. A different class means a different chain, pin and
ratings.

**The YouTube tunnel** (`BypassVpnService`, process `:dpi_vpn`):
- Start: the chain of the current class is tried in order; each strategy is health-checked through the engine and the first
  healthy one is used.
- Monitoring: the engine is probed about every 30 s (the first probe 45 s after the start) and the YouTube app's traffic is
  judged every 10 s. Two failed probes in a row, or three one-way windows in a row (the app sends, nothing comes back), mean
  the strategy stopped working. It is quarantined (`REAL_BAD`, demoted) and the tunnel reconnects on the next strategy of the
  chain, the best rated one that is not quarantined. At most 3 such switches in 10 minutes, at least 90 s apart.
- Change of connection: when the system reports the other class for four ticks in a row (two seconds, so a handover that
  flickers is ignored) the tunnel reconnects on the chain of the new class. That is not a failure: nothing is quarantined and
  it does not count towards the 3 switches.
- A healthy window of real traffic records `REAL_OK` once per tunnel run (`tun proven`) and promotes the strategy.

**Downloads** (`RouteLadder`): the routes of an attempt are the chain of the current class plus the direct route, ordered by
`BypassRoutes.order`: the route that carried the last download on this class in the last ten minutes (`RouteScoreboard` is per
class), then the chain, with a live check per route deciding the order. A route that stalls or fails is abandoned for the next
one; if another route then finishes the job, the finisher is promoted and the ones that failed first are quarantined and
demoted (all for the class the attempt ran on).

**Searches** (`DpiSearchService`, the tunnel's "Update"): a search describes the class it started on. If the connection has
changed class when it ends its results are dropped (`DpiBypass.recordSearch`, message "The connection changed during the
check"), because they would describe neither network. Otherwise the results go to the class's results store and then the
chain is rebuilt (event 1).

**Home** (`BypassDashboard`): the card shows "Strategy · Wi-Fi" or "Strategy · mobile data", the strategies of that class by
rating, the one in use selected, and the automatic switch. Switching it off, or picking another strategy, pins for that class
only. For a seeded class a line says the connection has not been tested and the strategies come from the other one. It
follows a change of connection by itself.

## 7. What it costs on mobile data

A full search moves tens of megabytes, so it is never started on its own on a metered connection: the quiet 25-day re-check
(`MaintenanceSchedule`, `DpiPrefs.isMaintenanceDue`) waits for an unmetered network, which in practice keeps the Wi-Fi record
fresh. The mobile record is built from real use, from the cheap live checks that every download and every tunnel start do
(a few kilobytes per strategy), and from a test the user starts by hand in Settings while on mobile data. The first-run check
is the only automatic full search, and it runs on whatever connection the phone is on.

If every strategy of a seeded class fails, nothing else is tried automatically (borrowing from the other class is done once,
when the chain is created); Home still shows the "not tested" line and Settings -> Test strategies is the way forward.

## 8. Constants

| Constant | Value | Where |
|---|---|---|
| Chain size | 6 | `ChainPlanner.MAX_CHAIN` |
| Rating line ("eligible") | 50 | `StrategyRating.ELIGIBLE` |
| Test half-life | 14 days | `StrategyRating.TEST_HALF_LIFE_MS` |
| Real-use half-life | 7 days | `StrategyMemory.HALF_LIFE_MS` |
| Evidence at which real use weighs as much as the test | 3 units | `StrategyRating.REAL_EVIDENCE_HALF` |
| A failure counts | double | `StrategyMemory.BAD_FACTOR` |
| Quarantine | 10 minutes | `DpiPrefs.QUARANTINE_MS` |
| "Recently carried a download" | 14 days (chain), 10 minutes (route) | `DpiPrefs.PROVEN_MAX_AGE_MS`, `RouteScoreboard` |
| Class change must hold | 4 ticks of 500 ms = 2 s | `NetSwitch.STABLE_TICKS` |
| Tunnel probe / flow window / first probe | 30 s / 10 s / 45 s | `BypassVpnService` |
| Tunnel strategy switches | 2 failed probes or 3 one-way windows; at most 3 per 10 min, 90 s apart | `BypassVpnService` |
| Dropdown size | 15 | `DASH_STRATEGY_LIMIT` |
| Quiet re-check | every 25 days, unmetered only | `MaintenanceSchedule` |

## 9. Reading a report

`Copy report` (Settings) now describes the class the phone is on:
- `#state ... cls=wifi|mobile seeded=0|1 ... chain=N pin= proven= quar= rated=N search_age=`: `seeded=1` means the chain is
  borrowed; `rated` is how many strategies are rated at least 50.
- `#rate pos=s2 rt=74 test=90 ok=0.00 bad=1.00 s=...`: the four best rated strategies: place in the chain (`out` when not
  in it), the rating, the last test score (`-` never tested, `fail` failed), the real evidence.
- `#other net=mobile chain=6 seeded=1 pin=0`: the other class, in one line.
- In the log: `seed net=mobile from=wifi`, `tun up ... net=mobile`, `net change wifi->mobile`, `rotate net=wifi quarantined=1`,
  `tun proven net=`, `ladder ... net=`, `kept net=... rated=`, `search net-changed ...`.

## 10. Rules for changing it

- Everything a strategy has done is per class. A new record, key or cache that is not keyed by `NetClass` reintroduces the
  problem this solves.
- A test and real use are the two halves of the rating; do not record a search's findings as real use (patch 53 removed the
  probe outcomes for that reason) and do not rank by the test alone.
- A change of connection is not a failure: it must not quarantine, demote or count towards the switch limit.
- No full search on a metered connection without the user starting it.
- The pure files take no `Context`; keep policy there and storage in `DpiPrefs`, so it stays testable without a device.
