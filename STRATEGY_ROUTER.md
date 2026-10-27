# Strategy router (patches 53-55)

How Kinescope decides which bypass strategy to use, on which kind of connection, and when to change it. This is the one
place that describes the mechanism end to end; `HANDOFF.md` has the short version and the file map, `CHANGELOG.md` the
history. The code that decides is pure and unit-tested (`NetClass.kt`, `StrategyRating.kt`, `StrategyRouter.kt` (which also holds
`StrategyRank`, `RandomCheckPolicy` and `ClassCheckPolicy`), `ChainPlanner.kt`, `StrategyMemory.kt`, `DpiSearch.kt` (`SearchGoal`));
the code that stores and acts is `DpiStrategyStore.kt` (`DpiPrefs`), `DpiBypass.kt`, `DpiSearchService.kt` (`RandomCheck`), `RouteLadder.kt`
and `BypassVpnService.kt`.

Status: per-class records and the rating (patch 53), the reachability verdict (patch 54) and the goal-bound, per-class and random checks
(patch 55) are implemented, **none confirmed on a device**. The checklist is in `ROADMAP.md`.

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
| Goal (`SearchGoal`) | When a check has found enough: `STANDARD` = 5 working strategies, at least 3 of them perfect; `QUICK` = 2 working. |
| Working / perfect | Working (`StrategyResult.works`): every required host answered and a real transfer completed. Perfect (`perfect`, "100%"): every probed host, the transfer and every simultaneous connection completed. |
| Ranking (`StrategyRank`) | ALL the strategies of a class in order: good evidence first, never tested next, did badly last with the newest failure at the very bottom. Derived, not stored. |
| Frozen | Among the last 15 of the ranking, the ones that did badly and were tested less than 75 days ago: left alone until the 75 days pass. |
| Class check (`ClassCheckPolicy`) | The check of one class for one app version (after an install or an update). |
| Random check (`RandomCheckPolicy`, `RandomCheck`) | Two random strategies of the class, tested the first time the bypass is switched on in each twelve hours. |
| Reach verdict (`DpiBypass.reach`) | Whether the engine can reach the hosts at all on this connection (section 7). |

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

Settings shows the number ("Rating 74") in front of the last test. (Patch 51's stars on Home went with the Home drop-down.)

## 5. What changes a chain (`StrategyRouter`)

The stored chain of a class is a cache of the router's decisions; it is rewritten by exactly five events:

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

5. **A random check finished** (patch 55, `RandomCheck`). A strategy that passed joins the chain by its rating (`applySearchResults`, partial);
   one that did not is taken out (`DpiPrefs.removeFromChain`; a pin stays) and its failed test, the newest on record, puts it at the very bottom of
   the ranking. A chain left empty is cleared and the class borrows the other one's again until it is checked.

What the chain is used as (`DpiStrategyStore.chain`): the pin first, then the stored order, with the quarantined
strategies last (`ChainPlanner.order`).

## 6. Where it is applied

**Initial choice by connection type.** Every consumer asks for the chain of the class it runs on, at the moment it starts:
the tunnel when it connects, a download at the start of each attempt (so a download retried after a change of network uses
the new class), the Home dashboard and Settings for what they show. A different class means a different chain, pin and
ratings.

**The YouTube tunnel** (`BypassVpnService`, process `:dpi_vpn`):
- Start: the chain of the current class is tried in order; each strategy is health-checked through the engine and the first
  healthy one is used (patch 54: a check that fails at CONNECT with "unreachable" asks `DpiBypass.reach` once; no route stops the start with a
  message, IPv4-only restarts the chain, and such a failure is never counted against a strategy; patch 54/55: when every strategy was blocked
  at the TLS stage a short search of the class (`SearchGoal.QUICK`) runs and its chain is tried; the notification shows its progress).
  Once the tunnel is up the random check starts on its own thread.
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

**Searches** (`DpiSearchService`, the tunnel's "Update"): a search stops at its goal (section 7) and describes the class it started on. If the connection has
changed class when it ends its results are dropped (`DpiBypass.recordSearch`, message "The connection changed during the
check"), because they would describe neither network. Otherwise the results go to the class's results store and then the
chain is rebuilt (event 1).

**Home and Settings** (`BypassDashboard`, `BypassSettings`): since the October change the Home card has the tunnel switch (with the
heartbeat line), the automatic switch and the connection label; switching the automatic switch off pins the strategy in use for that
class only, and the choice of another one is the last section of Settings (`StrategyPicker`, the strategies of that class by rating). The
card follows a change of connection by itself.

## 7. Checks (patch 55): when, how much, which strategies

A check no longer scans the list. It walks `DpiBypass.searchOrder` (the class's current chain, then `StrategyRank` order with the frozen ones
last) and stops when its goal is reached or the list runs out.

| Check | When | Goal | Mode |
|---|---|---|---|
| First check of a fresh install | the first launch; mandatory, no Stop | `STANDARD` (5 working, 3 perfect) | `initial` |
| Check of a class after an install or an update | the first launch (the class the phone is on) and the first time the phone is on the other class with the app open; once per app version and class (`ClassCheckPolicy`, `DpiPrefs.checkedVersion`); a check that did not finish waits 6 hours | `STANDARD` | `check` (quiet) |
| Settings -> Test strategies | by hand | `STANDARD` | `manual` |
| Test of one strategy | by hand | that strategy | `test` |
| Start-up search | the tunnel is starting and every strategy of its chain was blocked | `QUICK` (2 working) | in `BypassVpnService` |
| Random check | the first time the bypass is switched on in each twelve hours, per class (`RandomCheckPolicy`); after the tunnel is up, on its own thread | two random strategies | `RandomCheck` |

The first-run and class checks also refresh the community strategy list once per app version (`AppUpdate.refreshStrategyListOncePerVersion`),
and yt-dlp is updated on a fresh install and on an update (`YtOfflineApp`).

**The ranking** (`StrategyRank.order`): 1. strategies rated at least 50, best first; 2. strategies with no evidence on this class, in the order of the
list; 3. strategies rated below 50, the one tested longest ago first, so the one that failed last is at the very bottom. A failed check makes its
strategy the newest failure and so "moves it to the very bottom". **Frozen** (`StrategyRank.frozen`): among the last 15 of the ranking, the ones
in group 3 that were tested less than 75 days ago. The random check never picks a frozen strategy or the one carrying the tunnel; a check that
needs more than the rest of the list can give still reaches the frozen ones, because they are last, not removed.

**The random check** (`RandomCheck.runIfDue`): skipped before the first check is done, within twelve hours of the last one, or while a search, a
download or another engine run is busy. It picks two strategies, runs `DpiBypass.reach` first (no route: nothing is stored), tests them with the full
probe, drops results where the engine could not reach the host, stores the rest, lets what passed join the chain and takes what failed out of it.

## 8. Reachability (patch 54)

A strategy only changes what is done to the TLS ClientHello after the TCP connection exists. A failure at the CONNECT stage with SOCKS reply
0x03 or 0x04 (`StageResult.isReachFault`) therefore says the engine could not reach the host (a failed name lookup, an unreachable address),
not that the strategy is blocked. `DpiBypass.reach` probes one strategy: reached -> `OK`; failed at TLS or HTTP -> `BLOCKED` (the ordinary case a
search is for); failed at CONNECT -> the same strategy with the engine's `-X` (IPv4 only). If that passes (or gets as far as TLS) the class is
remembered as IPv4-only (`DpiPrefs.ipv4Only`, applied by `DpiEngine` to every engine start of that class) -> `IPV4_ONLY`; if not, `NO_ROUTE`: the
search or the tunnel stops with a message, the log gets one line with the direct DNS and TCP picture per IP family (`NetworkCheck.familyReport`),
and nothing is stored or blamed. The vendored engine resolves with `AF_UNSPEC` and keeps only the first address, which is why a half-working
IPv6 can defeat it on a network where IPv4 would have worked. Results where every failed probe was such a fault (`StrategyResult.connectFault`)
are dropped before they are stored (`DpiBypass.withoutReachFaults`).

## 9. What it costs on mobile data

Most of a check is cheap: a strategy that fails costs a few kilobytes (a light probe of three hosts); the cost is in the ones that pass
(a real transfer and a load test, about 0.8 MB each), and the goal caps those at about five. So the check of the mobile class after an
install or an update, the quiet check and the random check all run on mobile data without asking; the whole list is never scanned
for its own sake (patch 55 removed the 25-day full re-check and its wait for an unmetered network). The mobile record is also built from real
use and from the cheap live checks that every download and every tunnel start do.

If every strategy of a seeded class is blocked, the tunnel runs a short search of its own at start-up (`SearchGoal.QUICK`); if the connection
does not reach YouTube at all it says so (section 8).

## 10. Constants

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
| Check goal (first, class, manual) | 5 working, 3 perfect | `SearchGoal.STANDARD` |
| Start-up search goal | 2 working | `SearchGoal.QUICK` |
| Random check | 2 strategies, 12 h apart per class | `RandomCheckPolicy.PICKS`, `INTERVAL_MS` |
| Frozen bottom | 15 strategies, 75 days | `StrategyRank.BOTTOM_COUNT`, `FREEZE_MS` |
| Retry after an unfinished class check | 6 h | `ClassCheckPolicy.RETRY_GAP_MS` |
| yt-dlp update | every 25 days, and on a fresh install / an update | `MaintenanceSchedule.INTERVAL_MS`, `YtOfflineApp` |

## 11. Reading a report

`Copy report` (Settings) describes the class the phone is on (patches 54-55 add `v4only=0|1`, `chk=ok|due` and `rand=<hours>|none` to `#state`):
- `#state ... cls=wifi|mobile seeded=0|1 ... chain=N pin= proven= quar= rated=N search_age=`: `seeded=1` means the chain is
  borrowed; `rated` is how many strategies are rated at least 50.
- `#rate pos=s2 rt=74 test=90 ok=0.00 bad=1.00 s=...`: the four best rated strategies: place in the chain (`out` when not
  in it), the rating, the last test score (`-` never tested, `fail` failed), the real evidence.
- `#other net=mobile chain=6 seeded=1 pin=0`: the other class, in one line.
- In the log (patches 54-55: `reach net=... v4only=learned|none`, `srch rand net=... works= fault=`, `byp start search net= found=`, `vpn chk ... rc=`): `seed net=mobile from=wifi`, `tun up ... net=mobile`, `net change wifi->mobile`, `rotate net=wifi quarantined=1`,
  `tun proven net=`, `ladder ... net=`, `kept net=... rated=`, `search net-changed ...`.

## 12. Rules for changing it

- Everything a strategy has done is per class. A new record, key or cache that is not keyed by `NetClass` reintroduces the
  problem this solves.
- A test and real use are the two halves of the rating; do not record a search's findings as real use (patch 53 removed the
  probe outcomes for that reason) and do not rank by the test alone.
- A change of connection is not a failure: it must not quarantine, demote or count towards the switch limit.
- A failure that happens before a strategy acts (CONNECT, "unreachable") is the route's, never the strategy's: it is not stored, not quarantined, not counted.
- A check has a goal and ends when it is reached; nothing scans the whole list for its own sake. The frozen bottom of the ranking is last, not removed.
- The pure files take no `Context`; keep policy there and storage in `DpiPrefs`, so it stays testable without a device.
