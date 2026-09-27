#!/usr/bin/env python3
"""
Patch 30 -- persistent strategy search + bypass root-cause fix.

Housekeeping note first: two later-numbered scripts already existed in the repository root,
`patch_030_persistent_search_service.py` and `patch_031_docs_investigation.py`. Neither had
actually been run against this codebase -- none of their target code changes were present in
any file they were meant to touch, and `ROADMAP.md` / `CHANGELOG.md` / `HANDOFF.md` still only
reflected patch 29. Per `AGENTS.md` ("read the actual current file content ... never assume
memory from earlier in the conversation is still accurate"), this patch treats the repository's
real content as ground truth rather than any prior narrative about patches 30/31 already having
been applied or device-tested. Both stale scripts are superseded and deleted by this one (see
`remove_stale_scripts` below).

Two things in one delivery, because the second was blocked on the first:

1. Persistent strategy search. The search previously ran inside `BypassSettingsSection`'s own
   `rememberCoroutineScope()`, cancelled by an explicit `DisposableEffect(Unit) { onDispose {
   searchJob?.cancel() } }` the instant that screen left composition -- so navigating away from
   Settings or backgrounding the app hard enough killed it before it could finish. It now runs in
   a new foreground `DpiSearchService`, with a `DpiSearchController` `StateFlow` (mirroring the
   existing `BypassVpnController` pattern) that `BypassSettingsSection` observes reactively. The
   service's own notification carries a Stop action, a second control surface beyond Settings.

2. Root cause for "the search finds zero working strategies" (the user's separately installed
   ByeByeDPI finds several on the same network). Re-reading `DpiSearch.kt`: `fullPass` required
   ALL THREE probe hosts -- `www.youtube.com`, `i.ytimg.com`, and `redirector.googlevideo.com` --
   to fully pass a bare, hand-rolled TLS handshake plus one legacy HTTP/1.1 `HEAD` request, with
   no ALPN/H2 negotiation and no session state. `redirector.googlevideo.com` is a CDN redirector,
   not a page or image host, and is a plausible poor fit for exactly that kind of synthetic probe
   regardless of whether a strategy actually works for real YouTube/yt-dlp traffic. (Kinescope's
   12 built-in strategies were separately checked against `hufrea/byedpi`'s own documented
   reference examples and already match them verbatim -- the strategy list itself was not the
   suspect.) Now only the two core hosts are required for a strategy to verify; the CDN
   redirector host stays probed (still counted in the `passed`/`total` shown in the UI and in
   `DpiStrategySearch.best()`'s ranking) but no longer gates pass/fail. The per-stage search
   timeout is also more generous (2.5s -> 4s), and every probe now logs its host and the exact
   stage it passed or failed at to the existing privacy-redacted diagnostic log journal, so a
   real device run leaves actual per-host, per-stage evidence behind instead of only a bare "0
   passed" count if this relaxation is not sufficient by itself. `POST_NOTIFICATIONS` is also now
   requested (if not already granted) when a search or the Home-screen YouTube bypass action
   starts its service, not only on the first accepted download -- the most likely reason a
   foreground service's own notification/Stop button would go unseen even though the service
   still runs.

Verification performed this session (see CHANGELOG.md's Patch 30 entry for full detail): every
anchor below was checked against this repository's actual current file content, not memory or
the stale scripts' anchors. The modified `DpiStrategySearch`/`StrategyResult` (`DpiSearch.kt`)
was compiled with the real `kotlinc 2.1.0` (matching this project's pinned Kotlin version) and
exercised against a hand-written harness reproducing all 5 existing `DpiStrategySearchTest`
scenarios verbatim (unchanged results) plus 4 new checks of the `requiredHosts` relaxation. The
modified `DpiBypass.kt` was compiled with `kotlinc` against the real, unmodified
`NetworkCheck.kt` / `DpiSearch.kt` / `DpiStrategies.kt` and minimal same-signature stubs for the
Android-context-only pieces (`Context`, `DpiEngine`, `AppLog`, `DpiStrategyStore.candidates`),
the same "stub only what's actually Android-shaped" approach the prior session used for
`DpiSearchService.kt`. `DpiSearchService.kt`'s own content is reused as previously designed
(reported compiled clean against a real API 35 `android.jar` in an earlier session); this
session re-verified it by hand against the current `DpiBypass`/`DpiPrefs`/`R.string` surface
instead of re-running that compile. `BypassSettings.kt` and `MainActivity.kt`'s Compose edits
were reviewed by hand and by brace-balance check, reusing the exact `X by Y.state.collectAsState()`
pattern already proven working in the same file for `BypassVpnController`. **None of this is
device-confirmed yet** -- see `ROADMAP.md`'s Patch 30 section for the exact checklist, including
what to check first if the search still finds nothing.

Safe to run twice. Run from the repository root:

    python3 patch_030_bypass_root_cause_and_persistent_search.py
"""
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent
APP = ROOT / "app" / "src" / "main" / "java" / "com" / "kinescope" / "app"
RES = ROOT / "app" / "src" / "main" / "res"
MANIFEST = ROOT / "app" / "src" / "main" / "AndroidManifest.xml"


class AnchorNotFound(Exception):
    pass


def read(path: pathlib.Path) -> str:
    if not path.exists():
        raise AnchorNotFound(f"{path} does not exist")
    return path.read_text(encoding="utf-8")


def write(path: pathlib.Path, text: str) -> None:
    path.write_text(text, encoding="utf-8")


def replace_once(path: pathlib.Path, old: str, new: str, *, already_applied: str = None) -> None:
    """Exact-match guarded replace. Idempotent: checks the marker's presence on its
    own (not "old is gone"), since an additive edit's `new` text often still
    contains `old` as a substring, which would otherwise defeat that check."""
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


def write_new_file(path: pathlib.Path, content: str) -> None:
    if path.exists():
        if read(path) == content:
            return  # already applied
        raise AnchorNotFound(f"{path} already exists with different content; refusing to overwrite")
    path.parent.mkdir(parents=True, exist_ok=True)
    write(path, content)


# ---------------------------------------------------------------------------
# 1. New file: DpiSearchService.kt -- runs the strategy search as a foreground service
#    independent of any screen's lifecycle. Content as previously designed; re-verified this
#    session by hand against the current DpiBypass/DpiPrefs/R.string surface (see module
#    docstring).
# ---------------------------------------------------------------------------

DPI_SEARCH_SERVICE_KT = """package com.kinescope.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DpiSearchUiState(
    val running: Boolean = false,
    val progress: SearchProgress? = null,
    @StringRes val resultMessageRes: Int? = null,
    val resultDirectWorks: Boolean = false
)

/**
 * Holds the strategy search's state outside any screen's lifecycle, so the search itself (run by
 * [DpiSearchService]) survives navigating away from Settings or backgrounding the app -- before
 * this it ran in the Settings screen's own coroutine scope and was cancelled the moment that
 * screen left composition (see CHANGELOG.md, patch 30).
 */
internal object DpiSearchController {
    private val mutableState = MutableStateFlow(DpiSearchUiState())
    val state = mutableState.asStateFlow()

    internal fun starting() {
        mutableState.value = DpiSearchUiState(running = true)
    }

    internal fun progress(progress: SearchProgress) {
        mutableState.value = mutableState.value.copy(running = true, progress = progress)
    }

    internal fun finished(@StringRes messageRes: Int, directWorks: Boolean = false) {
        mutableState.value = DpiSearchUiState(resultMessageRes = messageRes, resultDirectWorks = directWorks)
    }
}

/**
 * Runs the strategy search as a foreground service so it keeps testing every remaining candidate
 * when the user leaves Settings or backgrounds the app entirely, instead of the search being tied
 * to that screen's own composition.
 */
class DpiSearchService : Service() {
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "kinescope-strategy-search").apply { isDaemon = true }
    }
    private val stopRequested = AtomicBoolean(false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopRequested.set(true)
            ACTION_START -> {
                if (!DpiSearchController.state.value.running) {
                    stopRequested.set(false)
                    DpiSearchController.starting()
                    startForegroundCompat(buildNotification(null))
                    executor.execute { runSearch() }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun runSearch() {
        try {
            val directWorks = DpiBypass.directConnectionWorks()
            val results = DpiBypass.search(
                applicationContext,
                isCancelled = { stopRequested.get() },
                onProgress = { snapshot ->
                    DpiSearchController.progress(snapshot)
                    runCatching {
                        getSystemService(NotificationManager::class.java)
                            .notify(NOTIFICATION_ID, buildNotification(snapshot))
                    }
                }
            )
            if (stopRequested.get()) {
                DpiSearchController.finished(R.string.bypass_search_stopped)
            } else {
                val passes = results.filter { it.fullPass }
                val working = passes.firstOrNull()
                if (working != null) {
                    DpiPrefs.markStrategiesVerified(applicationContext, working.line, passes.drop(1).map { it.line })
                    DpiPrefs.setEnabled(applicationContext, true)
                    DpiSearchController.finished(
                        if (directWorks) R.string.bypass_search_found_direct else R.string.bypass_search_found,
                        directWorks
                    )
                } else {
                    DpiSearchController.finished(R.string.bypass_search_none)
                }
            }
        } catch (e: Exception) {
            AppLog.e("DpiSearchService", "Strategy search failed", e)
            DpiSearchController.finished(R.string.bypass_operation_failed)
        } finally {
            stopSelf()
        }
    }

    override fun onDestroy() {
        stopRequested.set(true)
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(progress: SearchProgress?): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = Intent(this, DpiSearchService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (progress == null) {
            getString(R.string.bypass_search_notification_starting)
        } else {
            getString(
                R.string.bypass_find_progress,
                (progress.index + 1).coerceAtMost(progress.total.coerceAtLeast(1)),
                progress.total
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.bypass_search_notification_title))
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .addAction(0, getString(R.string.stop), stopPendingIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.bypass_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    companion object {
        private const val ACTION_START = "com.kinescope.app.ACTION_START_STRATEGY_SEARCH"
        private const val ACTION_STOP = "com.kinescope.app.ACTION_STOP_STRATEGY_SEARCH"
        private const val CHANNEL_ID = "youtube_bypass"
        private const val NOTIFICATION_ID = 1102

        fun start(context: Context) {
            val intent = Intent(context, DpiSearchService::class.java).apply { action = ACTION_START }
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch start", it) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DpiSearchService::class.java).apply { action = ACTION_STOP }
            runCatching { context.startService(intent) }
                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch stop", it) }
        }
    }
}
"""


def create_dpi_search_service():
    write_new_file(APP / "DpiSearchService.kt", DPI_SEARCH_SERVICE_KT)


# ---------------------------------------------------------------------------
# 2. DpiSearch.kt: required-host relaxation. Compile- and behavior-verified this session with
#    a real kotlinc against a harness reproducing every existing DpiStrategySearchTest scenario
#    (see module docstring).
# ---------------------------------------------------------------------------

def patch_dpi_search():
    path = APP / "DpiSearch.kt"

    old_result = """internal data class StrategyResult(val line: String, val started: Boolean, val passed: Int, val total: Int) {
    val fullPass: Boolean get() = started && total > 0 && passed == total
}"""
    new_result = """internal data class StrategyResult(
    val line: String,
    val started: Boolean,
    val passed: Int,
    val total: Int,
    /**
     * Whether every host in [DpiStrategySearch]'s `requiredHosts` passed -- not necessarily
     * every probed host. See CHANGELOG.md's Patch 30 entry: requiring the CDN redirector host
     * (`redirector.googlevideo.com`) to pass a bare synthetic probe alongside the two core hosts
     * was a likely source of false negatives, so it stays probed for diagnostics/ranking but is
     * no longer required for [fullPass].
     */
    val requiredPassed: Boolean = false
) {
    val fullPass: Boolean get() = started && total > 0 && requiredPassed
}"""
    replace_once(path, old_result, new_result, already_applied="val requiredPassed: Boolean = false")

    old_class = """internal class DpiStrategySearch(
    private val startEngine: (List<String>) -> BypassSession?,
    private val probeHost: (port: Int, host: String) -> Boolean,
    private val hosts: List<String> = DEFAULT_HOSTS
) {"""
    new_class = """internal class DpiStrategySearch(
    private val startEngine: (List<String>) -> BypassSession?,
    private val probeHost: (port: Int, host: String) -> Boolean,
    private val hosts: List<String> = DEFAULT_HOSTS,
    /**
     * Hosts that must pass for [StrategyResult.fullPass]; defaults to every host (the original
     * "every host must pass" behavior, and what every existing caller/test still gets). A caller
     * can name a stricter core subset and still probe extra hosts for diagnostic/ranking purposes
     * only -- see [DpiBypass.search].
     */
    private val requiredHosts: Set<String> = hosts.toSet()
) {"""
    replace_once(path, old_class, new_class, already_applied="private val requiredHosts: Set<String> = hosts.toSet()")

    old_evaluate = """    private fun evaluate(line: String): StrategyResult {
        val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok
            ?: return StrategyResult(line, started = false, passed = 0, total = hosts.size)
        val session = startEngine(parsed.args)
            ?: return StrategyResult(line, started = false, passed = 0, total = hosts.size)
        val pool = Executors.newFixedThreadPool(hosts.size)
        try {
            val checks = hosts.map { host -> pool.submit(Callable { probeHost(session.port, host) }) }
            val passed = checks.count { check ->
                try {
                    check.get(PROBE_DEADLINE_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            return StrategyResult(line, started = true, passed = passed, total = hosts.size)
        } finally {
            pool.shutdownNow()
            session.close()
        }
    }"""
    new_evaluate = """    private fun evaluate(line: String): StrategyResult {
        val parsed = DpiStrategyParser.parse(line) as? DpiStrategyParser.Parsed.Ok
            ?: return StrategyResult(line, started = false, passed = 0, total = hosts.size)
        val session = startEngine(parsed.args)
            ?: return StrategyResult(line, started = false, passed = 0, total = hosts.size)
        val pool = Executors.newFixedThreadPool(hosts.size)
        try {
            val checks = hosts.map { host -> host to pool.submit(Callable { probeHost(session.port, host) }) }
            val outcomes = checks.map { (host, future) ->
                host to try {
                    future.get(PROBE_DEADLINE_SECONDS, TimeUnit.SECONDS)
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            val passed = outcomes.count { it.second }
            val requiredPassed = outcomes.filter { it.first in requiredHosts }.all { it.second }
            return StrategyResult(line, started = true, passed = passed, total = hosts.size, requiredPassed = requiredPassed)
        } finally {
            pool.shutdownNow()
            session.close()
        }
    }"""
    replace_once(path, old_evaluate, new_evaluate, already_applied="val requiredPassed = outcomes.filter { it.first in requiredHosts }.all { it.second }")

    old_companion = """    companion object {
        /** A site page, an image host and a video host: the three kinds of traffic a download needs. */
        val DEFAULT_HOSTS = listOf("www.youtube.com", "i.ytimg.com", "redirector.googlevideo.com")
        private const val PROBE_DEADLINE_SECONDS = 20L

        /** The result to select: most hosts through wins, earlier candidates win ties. */
        fun best(results: List<StrategyResult>): StrategyResult? =
            results.filter { it.started && it.passed > 0 }.maxByOrNull { it.passed }
    }"""
    new_companion = """    companion object {
        /** A site page, an image host and a video host: the three kinds of traffic a download needs. */
        val DEFAULT_HOSTS = listOf("www.youtube.com", "i.ytimg.com", "redirector.googlevideo.com")

        /**
         * The two hosts required for [StrategyResult.fullPass] in [DpiBypass.search]. Patch 30:
         * `redirector.googlevideo.com` (a CDN redirector) stays probed for diagnostics/ranking
         * but is no longer required to pass -- a bare synthetic TLS+HTTP/1.1 probe against a CDN
         * redirector is a plausible false-negative source independent of whether a strategy
         * actually works for real YouTube traffic. See CHANGELOG.md's Patch 30 entry.
         */
        val REQUIRED_HOSTS = setOf("www.youtube.com", "i.ytimg.com")
        private const val PROBE_DEADLINE_SECONDS = 25L

        /** The result to select: most hosts through wins, earlier candidates win ties. */
        fun best(results: List<StrategyResult>): StrategyResult? =
            results.filter { it.started && it.passed > 0 }.maxByOrNull { it.passed }
    }"""
    replace_once(path, old_companion, new_companion, already_applied="val REQUIRED_HOSTS = setOf(\"www.youtube.com\", \"i.ytimg.com\")")


# ---------------------------------------------------------------------------
# 3. DpiBypass.kt: search() root-cause fix (requiredHosts, longer timeout) + per-host,
#    per-stage diagnostic logging. Compile-verified this session against the real
#    NetworkCheck.kt/DpiSearch.kt/DpiStrategies.kt plus minimal same-signature stubs.
# ---------------------------------------------------------------------------

def patch_dpi_bypass():
    path = APP / "DpiBypass.kt"

    old_const = """/** Glue between the settings, the engine and the download service. */
object DpiBypass {
    /** How many working strategies the search keeps looking for: one primary + fallbacks. */
    private const val MAX_VERIFIED_STRATEGIES = 4"""
    new_const = """/** Glue between the settings, the engine and the download service. */
object DpiBypass {
    /** How many working strategies the search keeps looking for: one primary + fallbacks. */
    private const val MAX_VERIFIED_STRATEGIES = 4

    /**
     * Per-stage socket timeout used only while searching (Patch 30). A working desync strategy
     * adds real round-trip latency -- fragmented or delayed TCP segments, sometimes a fake
     * packet -- so the previous 2.5s budget was tight enough to plausibly time out a strategy
     * that would otherwise have passed. The one-off "Test selected" connection check keeps
     * NetworkCheck()'s own default budget; this only affects the multi-candidate search.
     */
    private const val SEARCH_STAGE_TIMEOUT_MS = 4_000"""
    replace_once(path, old_const, new_const, already_applied="private const val SEARCH_STAGE_TIMEOUT_MS = 4_000")

    old_search = """    /** Runs the strategy search over [DpiStrategyStore.candidates]. Blocking; see [DpiStrategySearch]. */
    internal fun search(
        context: Context,
        isCancelled: () -> Boolean,
        onProgress: (SearchProgress) -> Unit
    ): List<StrategyResult> {
        val check = NetworkCheck(stageTimeoutMs = 2_500)
        val search = DpiStrategySearch(
            startEngine = { args -> DpiEngine.start(context, args) },
            probeHost = { port, host -> check.checkViaBypass(SocksEndpoint(DpiEngine.HOST, port), host).ok }
        )
        return search.run(
            DpiStrategyStore.candidates(context),
            isCancelled,
            onProgress,
            stopAfterFullPasses = MAX_VERIFIED_STRATEGIES
        )
    }"""
    new_search = """    /** Runs the strategy search over [DpiStrategyStore.candidates]. Blocking; see [DpiStrategySearch]. */
    internal fun search(
        context: Context,
        isCancelled: () -> Boolean,
        onProgress: (SearchProgress) -> Unit
    ): List<StrategyResult> {
        val check = NetworkCheck(stageTimeoutMs = SEARCH_STAGE_TIMEOUT_MS)
        val search = DpiStrategySearch(
            startEngine = { args -> DpiEngine.start(context, args) },
            probeHost = { port, host ->
                val result = check.checkViaBypass(SocksEndpoint(DpiEngine.HOST, port), host)
                logProbeOutcome(host, result)
                result.ok
            },
            // Patch 30: only the two core hosts gate whether a strategy counts as verified.
            // redirector.googlevideo.com (a CDN redirector) stays probed for the ranking/
            // diagnostic count but is no longer required -- see CHANGELOG.md.
            requiredHosts = DpiStrategySearch.REQUIRED_HOSTS
        )
        return search.run(
            DpiStrategyStore.candidates(context),
            isCancelled,
            onProgress,
            stopAfterFullPasses = MAX_VERIFIED_STRATEGIES
        )
    }

    /**
     * Logs which stage failed for a probe host during the strategy search, so a real device run
     * leaves the actual per-host, per-stage detail (DNS/TCP/PROXY/CONNECT/TLS/HTTP) in the hidden
     * diagnostic log journal instead of only a bare pass/fail count. See CHANGELOG.md's Patch 30
     * entry: this is exactly the missing evidence the prior investigation asked for.
     */
    private fun logProbeOutcome(host: String, result: PathResult) {
        val failed = result.failed
        if (failed != null) {
            AppLog.i("DpiSearch", "probe failed host=$host stage=${failed.stage} detail=${failed.detail}")
        } else {
            AppLog.i("DpiSearch", "probe passed host=$host")
        }
    }"""
    replace_once(path, old_search, new_search, already_applied="private fun logProbeOutcome(host: String, result: PathResult)")


# ---------------------------------------------------------------------------
# 4. BypassSettings.kt: observe DpiSearchService's state instead of owning a Job, and request
#    the notification permission before starting a search (previously only requested on the
#    first accepted download).
# ---------------------------------------------------------------------------

def patch_bypass_settings():
    path = APP / "BypassSettings.kt"

    old_imports = """import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext"""
    new_imports = """import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext"""
    replace_once(path, old_imports, new_imports)

    # SEARCHING is now unused: the search's busy/idle state comes from `searching`
    # (DpiSearchController.state.running) instead of the local BypassBusy enum.
    old_enum = "private enum class BypassBusy { NONE, SEARCHING, UPDATING }"
    new_enum = "private enum class BypassBusy { NONE, UPDATING }"
    replace_once(path, old_enum, new_enum)

    old_head = """@Composable
fun BypassSettingsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val vpnState by BypassVpnController.state.collectAsState()
    val queueJobs by DownloadQueueBus.jobs.collectAsState()

    val initialStrategy = remember { DpiStrategyStore.selected(context) }
    val initialVerified = remember { DpiPrefs.isStrategyVerified(context, initialStrategy) }
    var enabled by remember { mutableStateOf(DpiPrefs.isEnabled(context) && initialVerified) }
    var verified by remember { mutableStateOf(initialVerified) }
    var verifiedAt by remember { mutableStateOf(DpiPrefs.verifiedAt(context)) }
    var listUpdatedAt by remember { mutableStateOf(DpiPrefs.listUpdatedAt(context)) }
    var busy by remember { mutableStateOf(BypassBusy.NONE) }
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableStateOf<SearchProgress?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (DpiPrefs.isEnabled(context) && !initialVerified) DpiPrefs.setEnabled(context, false)
    }
    DisposableEffect(busy) {
        view.keepScreenOn = busy != BypassBusy.NONE
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(Unit) {
        onDispose { searchJob?.cancel() }
    }

    val downloadActive = queueJobs.any {
        it.state in setOf(JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING, JobState.SAVING)
    }
    val controlsEnabled = busy == BypassBusy.NONE && !vpnState.starting && !vpnState.active && !downloadActive"""
    new_head = """@Composable
fun BypassSettingsSection(requestNotifications: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    val vpnState by BypassVpnController.state.collectAsState()
    val queueJobs by DownloadQueueBus.jobs.collectAsState()
    // The strategy search runs in DpiSearchService (a foreground service), not in this screen's
    // own coroutine scope, so it keeps testing every candidate when the user leaves this screen
    // or backgrounds the app instead of being cancelled (see CHANGELOG.md, patch 30).
    val searchState by DpiSearchController.state.collectAsState()
    val searching = searchState.running

    val initialStrategy = remember { DpiStrategyStore.selected(context) }
    val initialVerified = remember { DpiPrefs.isStrategyVerified(context, initialStrategy) }
    var enabled by remember { mutableStateOf(DpiPrefs.isEnabled(context) && initialVerified) }
    var verified by remember { mutableStateOf(initialVerified) }
    var verifiedAt by remember { mutableStateOf(DpiPrefs.verifiedAt(context)) }
    var listUpdatedAt by remember { mutableStateOf(DpiPrefs.listUpdatedAt(context)) }
    var busy by remember { mutableStateOf(BypassBusy.NONE) }
    var message by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (DpiPrefs.isEnabled(context) && !initialVerified) DpiPrefs.setEnabled(context, false)
    }
    DisposableEffect(busy, searching) {
        view.keepScreenOn = busy != BypassBusy.NONE || searching
        onDispose { view.keepScreenOn = false }
    }
    // Refreshes the on-screen state once the service-run search finishes -- whether that
    // happens while this screen is open or the result is just being picked up on return to it.
    LaunchedEffect(searchState) {
        // Read once into a local val: searchState is itself a delegated property
        // (by ...collectAsState()), so Kotlin cannot smart-cast searchState.resultMessageRes
        // directly (same reason vpnErrorRes is extracted from vpnState.errorRes above).
        val resultMessageRes = searchState.resultMessageRes
        if (!searching && resultMessageRes != null) {
            verified = DpiPrefs.isStrategyVerified(context, DpiStrategyStore.selected(context))
            verifiedAt = DpiPrefs.verifiedAt(context)
            enabled = DpiPrefs.isEnabled(context)
            message = context.getString(resultMessageRes)
        }
    }

    val downloadActive = queueJobs.any {
        it.state in setOf(JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING, JobState.SAVING)
    }
    val controlsEnabled = busy == BypassBusy.NONE && !searching && !vpnState.starting && !vpnState.active && !downloadActive"""
    replace_once(path, old_head, new_head, already_applied="val searchState by DpiSearchController.state.collectAsState()")

    old_search_block = """    val startSearch: () -> Unit = {
        busy = BypassBusy.SEARCHING
        message = null
        progress = null
        searchJob = scope.launch {
            try {
                val directWorks = withContext(Dispatchers.IO) { DpiBypass.directConnectionWorks() }
                val results = withContext(Dispatchers.IO) {
                    DpiBypass.search(
                        context,
                        isCancelled = { !isActive },
                        onProgress = { snapshot -> scope.launch { progress = snapshot } }
                    )
                }
                val passes = results.filter { it.fullPass }
                val working = passes.firstOrNull()
                if (working != null) {
                    DpiPrefs.markStrategiesVerified(context, working.line, passes.drop(1).map { it.line })
                    // The user asked to turn the bypass on manually before; requiring that extra
                    // tap after a successful test read as "the button does nothing". Turn it on
                    // as soon as a strategy is verified instead.
                    DpiPrefs.setEnabled(context, true)
                    enabled = true
                    verified = true
                    verifiedAt = DpiPrefs.verifiedAt(context)
                    message = context.getString(
                        if (directWorks) R.string.bypass_search_found_direct else R.string.bypass_search_found
                    )
                } else {
                    message = context.getString(R.string.bypass_search_none)
                }
            } catch (e: CancellationException) {
                message = context.getString(R.string.bypass_search_stopped)
                throw e
            } catch (e: Exception) {
                AppLog.e("BypassSettings", "Strategy search failed", e)
                message = context.getString(R.string.bypass_operation_failed)
            } finally {
                busy = BypassBusy.NONE
                progress = null
                searchJob = null
            }
        }
    }

    // Runs the strategy search by itself, once, the first time this screen is opened on a
    // device -- so a fresh install/update does not require the user to know to press
    // "Test strategies" before the bypass (or the Home-screen YouTube action) can do anything.
    LaunchedEffect(Unit) {
        if (!DpiPrefs.hasRunInitialSearch(context)) {
            DpiPrefs.setHasRunInitialSearch(context, true)
            if (!initialVerified && controlsEnabled) startSearch()
        }
    }"""
    new_search_block = """    val startSearch: () -> Unit = {
        message = null
        requestNotifications()
        DpiSearchService.start(context)
    }

    // Runs the strategy search by itself, once, the first time this screen is opened on a
    // device -- so a fresh install/update does not require the user to know to press
    // "Test strategies" before the bypass (or the Home-screen YouTube action) can do anything.
    // The flag is only consumed once the search actually starts: if the screen first opens
    // while blocked (a download is active), the one automatic attempt is not silently wasted --
    // it simply waits for a later visit where controlsEnabled is true.
    LaunchedEffect(Unit) {
        if (!DpiPrefs.hasRunInitialSearch(context) && !initialVerified && controlsEnabled) {
            DpiPrefs.setHasRunInitialSearch(context, true)
            startSearch()
        }
    }"""
    replace_once(path, old_search_block, new_search_block, already_applied="DpiSearchService.start(context)")

    old_render = """    Spacer(Modifier.height(12.dp))
    if (busy == BypassBusy.SEARCHING) {
        val current = progress
        Text(
            stringResource(
                R.string.bypass_find_progress,
                ((current?.index ?: 0) + 1).coerceAtMost((current?.total ?: 1).coerceAtLeast(1)),
                current?.total ?: 0
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = if (current == null || current.total == 0) 0f else current.index.toFloat() / current.total,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { searchJob?.cancel() }) {
            Text(stringResource(R.string.bypass_stop_search))
        }
    } else {"""
    new_render = """    Spacer(Modifier.height(12.dp))
    if (searching) {
        val current = searchState.progress
        Text(
            stringResource(
                R.string.bypass_find_progress,
                ((current?.index ?: 0) + 1).coerceAtMost((current?.total ?: 1).coerceAtLeast(1)),
                current?.total ?: 0
            ),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = if (current == null || current.total == 0) 0f else current.index.toFloat() / current.total,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(8.dp))
        OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { DpiSearchService.stop(context) }) {
            Text(stringResource(R.string.bypass_stop_search))
        }
    } else {"""
    replace_once(path, old_render, new_render, already_applied="onClick = { DpiSearchService.stop(context) }")


# ---------------------------------------------------------------------------
# 5. MainActivity.kt: thread requestNotifications into Settings (for the search button) and
#    call it before the Home-screen YouTube bypass action starts its own foreground service.
# ---------------------------------------------------------------------------

def patch_main_activity():
    path = APP / "MainActivity.kt"

    old_open = """        if (bypassState.starting) return
        val permissionIntent = VpnService.prepare(context)"""
    new_open = """        if (bypassState.starting) return
        // Patch 30: request the notification permission (if not already granted) before this
        // starts BypassVpnService's own foreground-service notification, not only on the first
        // accepted download (patch 25) -- otherwise that notification can silently never show.
        requestNotifications()
        val permissionIntent = VpnService.prepare(context)"""
    replace_once(path, old_open, new_open, already_applied="starts BypassVpnService's own foreground-service notification")

    old_signature = """private fun SettingsScreen(
    onDefaultQualityChanged: (Int) -> Unit,
    updateStatus: String,
    isUpdating: Boolean,
    lastUpdateTimestamp: Long,
    onCheckForUpdate: () -> Unit,
    onOpenYouTubeLogin: () -> Unit
) {"""
    new_signature = """private fun SettingsScreen(
    onDefaultQualityChanged: (Int) -> Unit,
    updateStatus: String,
    isUpdating: Boolean,
    lastUpdateTimestamp: Long,
    onCheckForUpdate: () -> Unit,
    onOpenYouTubeLogin: () -> Unit,
    requestNotifications: () -> Unit
) {"""
    replace_once(path, old_signature, new_signature, already_applied="onOpenYouTubeLogin: () -> Unit,\n    requestNotifications: () -> Unit\n) {")

    old_call = """                AppSection.SETTINGS -> SettingsScreen(
                    onDefaultQualityChanged = { selectedQuality = it },
                    updateStatus = updateStatus,
                    isUpdating = isUpdating,
                    lastUpdateTimestamp = lastUpdateTimestamp,
                    onCheckForUpdate = { runUpdate() },
                    onOpenYouTubeLogin = { section = AppSection.YOUTUBE_AUTH }
                )"""
    new_call = """                AppSection.SETTINGS -> SettingsScreen(
                    onDefaultQualityChanged = { selectedQuality = it },
                    updateStatus = updateStatus,
                    isUpdating = isUpdating,
                    lastUpdateTimestamp = lastUpdateTimestamp,
                    onCheckForUpdate = { runUpdate() },
                    onOpenYouTubeLogin = { section = AppSection.YOUTUBE_AUTH },
                    requestNotifications = requestNotifications
                )"""
    replace_once(path, old_call, new_call, already_applied="requestNotifications = requestNotifications\n                )")

    old_bypass_call = """        SettingsDivider()
        BypassSettingsSection()

        SettingsDivider()"""
    new_bypass_call = """        SettingsDivider()
        BypassSettingsSection(requestNotifications = requestNotifications)

        SettingsDivider()"""
    replace_once(path, old_bypass_call, new_bypass_call, already_applied="BypassSettingsSection(requestNotifications = requestNotifications)")


# ---------------------------------------------------------------------------
# 6. AndroidManifest.xml: register DpiSearchService.
# ---------------------------------------------------------------------------

def patch_manifest():
    old = """            <meta-data
                android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON"
                android:value="false" />
        </service>

    </application>"""
    new = """            <meta-data
                android:name="android.net.VpnService.SUPPORTS_ALWAYS_ON"
                android:value="false" />
        </service>

        <!-- Patch 30: runs the strategy search as a foreground service so it survives leaving
             Settings or backgrounding the app; the notification's Stop button doubles as an
             additional control surface for it. -->
        <service
            android:name=".DpiSearchService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="network-diagnostics" />
        </service>

    </application>"""
    replace_once(MANIFEST, old, new, already_applied='android:name=".DpiSearchService"')


# ---------------------------------------------------------------------------
# 7. Strings: two new notification strings, EN + RU.
# ---------------------------------------------------------------------------

def patch_strings():
    en_path = RES / "values" / "strings.xml"
    ru_path = RES / "values-ru" / "strings.xml"

    en_old = """<string name="bypass_notification_active">YouTube is using the local bypass.</string>
<string name="cd_remove_download">Remove download</string>"""
    en_new = """<string name="bypass_notification_active">YouTube is using the local bypass.</string>
<string name="bypass_search_notification_title">Kinescope \u00b7 Testing connection</string>
<string name="bypass_search_notification_starting">Starting the test\u2026</string>
<string name="cd_remove_download">Remove download</string>"""
    replace_once(en_path, en_old, en_new, already_applied="bypass_search_notification_title")

    ru_old = ("<string name=\"bypass_notification_active\">YouTube \u0440\u0430\u0431\u043e\u0442\u0430\u0435\u0442 "
              "\u0447\u0435\u0440\u0435\u0437 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 \u043e\u0431\u0445\u043e\u0434.</string>\n"
              "<string name=\"cd_remove_download\">\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0443</string>")
    ru_new = (
        "<string name=\"bypass_notification_active\">YouTube \u0440\u0430\u0431\u043e\u0442\u0430\u0435\u0442 "
        "\u0447\u0435\u0440\u0435\u0437 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 \u043e\u0431\u0445\u043e\u0434.</string>\n"
        "<string name=\"bypass_search_notification_title\">Kinescope \u00b7 \u041f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 "
        "\u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f</string>\n"
        "<string name=\"bypass_search_notification_starting\">\u0417\u0430\u043f\u0443\u0441\u043a \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438\u2026</string>\n"
        "<string name=\"cd_remove_download\">\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0443</string>"
    )
    replace_once(ru_path, ru_old, ru_new, already_applied="bypass_search_notification_title")


# ---------------------------------------------------------------------------
# 8. Docs: ROADMAP.md, CHANGELOG.md, HANDOFF.md
# ---------------------------------------------------------------------------

def patch_roadmap():
    path = ROOT / "ROADMAP.md"

    old_tail = """- [ ] **Needs a real device to confirm:** the automatic first-run search actually completes and enables the switch; a fallback strategy is actually used when the primary one fails to start; the YouTube VPN tunnel start also cascades through fallbacks; the folder migration takes effect for an existing install; a freshly downloaded video plays with picture and sound.

---

## Deliberately out of scope"""
    new_tail = """- [x] **Superseded by Patch 30 below**, which fixes a researched root cause for the search
  finding nothing rather than simply re-testing the same behavior.

### Patch 30 persistent strategy search + required-host relaxation (bypass root-cause fix)

Not yet device-verified. Full detail in `CHANGELOG.md`.

Two later-numbered scripts (`patch_030_persistent_search_service.py`,
`patch_031_docs_investigation.py`) already existed in the repository root but had never actually
been run against this codebase -- none of their target code changes were present, and these docs
still only reflected patch 29. This patch supersedes and deletes both stale scripts with a single
consolidated delivery.

- [x] The strategy search now runs in a new foreground `DpiSearchService` instead of the Settings
  screen's own coroutine scope, so it keeps running when the user leaves Settings or backgrounds
  the app -- previously an explicit `onDispose` cancelled it the moment the screen left
  composition. Its own notification has a Stop button, a second control surface beyond Settings.
- [x] **Root cause for "the search finds zero working strategies":** `DpiStrategySearch.fullPass`
  required ALL THREE probe hosts -- including `redirector.googlevideo.com`, a CDN redirector --
  to fully pass a bare, hand-rolled TLS+HTTP/1.1 probe with no ALPN/H2 negotiation. That host is a
  plausible false-negative source independent of whether a strategy genuinely works.
  `www.youtube.com` and `i.ytimg.com` are now the only hosts required for a strategy to verify;
  the CDN redirector host stays probed for the diagnostic/ranking count but no longer gates
  pass/fail. (Kinescope's built-in strategy list itself was separately checked and already
  matches `hufrea/byedpi`'s own documented reference examples verbatim -- it was not the suspect.)
- [x] The search's per-stage socket timeout is more generous (2.5s -> 4s) to reduce false
  timeouts from a working strategy's added desync latency.
- [x] Every probe now logs its host and the exact stage (DNS/TCP/PROXY/CONNECT/TLS/HTTP) it
  passed or failed at to the hidden diagnostic log journal, so a real run leaves actual evidence
  behind instead of only a bare "0 passed" count if this relaxation is not sufficient by itself.
- [x] `POST_NOTIFICATIONS` is now requested (if not already granted) when a strategy search
  starts and when the Home-screen YouTube bypass action starts the VPN tunnel, not only on the
  first accepted download -- the most likely reason a foreground service's own notification/Stop
  button would go unseen even though the service itself still runs.
- [ ] **Needs a real device to confirm:** the strategy search now finds at least one working
  strategy on the restricted network; the search survives minimizing the app / switching screens
  and finishes; the notification appears with a working Stop button; the download bypass switch
  and the Home-screen YouTube action both become usable once a strategy verifies; a
  bypass-enabled download and the YouTube VPN tunnel both actually work end to end. If the search
  still finds zero strategies, read the per-host/per-stage detail now in the log journal (five
  taps on Settings) before guessing at another fix.

---

## Deliberately out of scope"""
    replace_once(path, old_tail, new_tail, already_applied="### Patch 30 persistent strategy search + required-host relaxation")


def patch_changelog():
    path = ROOT / "CHANGELOG.md"

    old_anchor = """edits it makes.


<!-- moved to the top of CHANGELOG.md by patch 28 -->"""
    new_section = """edits it makes.


<!-- moved to the top of CHANGELOG.md by patch 30 -->
## Patch 30 \u2014 Persistent strategy search + required-host relaxation (bypass root-cause fix)

Two later-numbered scripts, `patch_030_persistent_search_service.py` and
`patch_031_docs_investigation.py`, already existed in the repository root, but neither had
actually been run against this codebase: none of their target code changes were present in any
file they were meant to touch, and `ROADMAP.md` / `HANDOFF.md` still only reflected patch 29.
Per `AGENTS.md`'s "read the actual current file content ... never assume memory from earlier in
the conversation is still accurate," this patch treats the repository's real content as ground
truth rather than any narrative about patches 30/31 already having been applied or device-tested.
Both stale scripts are superseded and deleted here with a single consolidated delivery covering
what they were meant to do, plus a researched fix for the root cause patch 31 was investigating.

### Why the strategy search found zero working strategies
`DpiStrategySearch.fullPass` (`DpiSearch.kt`) required every one of the three probe hosts --
`www.youtube.com`, `i.ytimg.com`, and `redirector.googlevideo.com` -- to fully pass a bare,
hand-rolled TLS handshake plus one legacy HTTP/1.1 `HEAD` request, with no ALPN/H2 negotiation and
no session state. `redirector.googlevideo.com` is a CDN redirector, not a page or image host, and
is a plausible poor fit for exactly that kind of synthetic probe regardless of whether the DPI
bypass strategy itself works for real YouTube/yt-dlp traffic. Kinescope's built-in strategy list
was separately checked against `hufrea/byedpi`'s own documented reference examples
(`--disorder 1 --auto=torst --tlsrec 1+s` and `--fake -1 --ttl 8`) and already matches them
verbatim -- the list itself was not the problem.

### Fixed
- **Required-host relaxation.** `DpiStrategySearch` gained a `requiredHosts` parameter (default:
  every host, so all 5 existing unit tests are unchanged); `StrategyResult` gained a
  `requiredPassed` field and `fullPass` now checks that instead of "every probed host passed."
  `DpiBypass.search()` passes the two core hosts (`www.youtube.com`, `i.ytimg.com`) as
  `DpiStrategySearch.REQUIRED_HOSTS`; the CDN redirector host stays probed (it still counts
  toward the `passed`/`total` shown in the UI and toward `DpiStrategySearch.best()`'s ranking)
  but no longer blocks a strategy from verifying.
- **More generous search timeouts.** The search's `NetworkCheck` per-stage socket timeout went
  from 2.5s to 4s (`DpiBypass.SEARCH_STAGE_TIMEOUT_MS`) to reduce false timeouts from a working
  desync strategy's added round-trip latency. The one-off "Test selected" connection check is
  unaffected (still `NetworkCheck()`'s own 3s default).
- **Real diagnostic evidence, not just "0 passed."** `DpiBypass.search()`'s probe callback now
  logs the host and the exact `CheckStage` (DNS/TCP/PROXY/CONNECT/TLS/HTTP) each probe passed or
  failed at, via `AppLog` (the existing privacy-redacted, five-taps-on-Settings log journal). If
  the required-host relaxation above is not sufficient by itself, the next device report can be
  diagnosed from real per-host, per-stage evidence instead of another guess.
- **Persistent strategy search (`DpiSearchService`).** The search now runs in a new foreground
  service instead of `BypassSettingsSection`'s own `rememberCoroutineScope()`, so it survives
  navigating away from Settings or backgrounding the app -- previously an explicit
  `DisposableEffect(Unit) { onDispose { searchJob?.cancel() } }` cancelled it the instant that
  screen left composition. Its state (`running`, current `SearchProgress`, final result) is
  exposed through a new `DpiSearchController` `StateFlow`, mirroring the existing
  `BypassVpnController` pattern; `BypassSettingsSection` now observes it via `collectAsState()`
  instead of owning a `Job`. The service's own notification carries a Stop action, so the search
  can be stopped from the notification shade as well as from Settings.
- **`POST_NOTIFICATIONS` requested earlier.** Previously only requested on the first accepted
  download (patch 25), so `DpiSearchService`'s notification -- and `BypassVpnService`'s, for
  anyone who tries the YouTube bypass before ever downloading anything -- could silently never
  display even though the underlying foreground service runs correctly either way
  (`startForeground()` does not require the notification permission to succeed). Both the
  Settings strategy-search button and the Home-screen YouTube bypass action now request the
  permission (if not already granted) before starting their respective service.
- Fixed the patch-29 auto-run flag being consumed even when the search never actually started
  (blocked by an active download): now only consumed once `startSearch()` is actually called.
- Confirmed unchanged (`DpiEngine.kt`): a search running concurrently with a bypass-enabled
  download is already race-free without new locking -- `DpiEngine`'s existing `regularGate`
  semaphore makes a second concurrent `DpiEngine.start()` return `null` immediately rather than
  colliding with the native engine's process-wide C state.

### Verification status
This session re-read every touched file's actual current content before writing any edit (per
`AGENTS.md`), rather than trusting the stale scripts' own anchors or any prior session's claims
about them. The modified `DpiStrategySearch`/`StrategyResult` (`DpiSearch.kt`) was compiled with
a real `kotlinc 2.1.0` (matching this project's pinned Kotlin version) and exercised against a
harness reproducing all 5 existing `DpiStrategySearchTest` scenarios verbatim (identical results)
plus 4 new checks of the `requiredHosts` relaxation and its all-hosts-required default. The
modified `DpiBypass.kt` was compiled with `kotlinc` against the real, unmodified
`NetworkCheck.kt`/`DpiSearch.kt`/`DpiStrategies.kt` plus minimal same-signature stubs for the
purely-Android-context pieces. `DpiSearchService.kt` reuses a design previously reported compiled
clean against a real API 35 `android.jar`; `BypassSettings.kt`/`MainActivity.kt`'s Compose edits
were reviewed by hand and by brace-balance check, reusing the `X by Y.state.collectAsState()`
pattern already working in the same file for `BypassVpnController`. **Not yet confirmed on a real
device** -- the required-host relaxation in particular is a reasoned hypothesis, not a verified
fix; see `ROADMAP.md` -> Patch 30 for the exact checklist, including what to check first if the
search still finds nothing.

<!-- moved to the top of CHANGELOG.md by patch 28 -->"""
    replace_once(path, old_anchor, new_section, already_applied="## Patch 30 \u2014 Persistent strategy search + required-host relaxation")


def patch_handoff():
    path = ROOT / "HANDOFF.md"

    old_anchor = "### Patch 28 / YouTube split-tunnel status"
    new_section = """### Patch 29-30 / bypass reliability, persistent search and root-cause status

Patch 29 (process guard, auto-run search, verified-strategy fallbacks, folder migration,
H.264/AAC playback preference, plain-language bypass strings) is implemented in the codebase but
**not yet device-confirmed** in this project's actual tracked history.

**Discrepancy found and resolved this session:** two further patch scripts,
`patch_030_persistent_search_service.py` and `patch_031_docs_investigation.py`, existed in the
repository root, but neither had actually been run -- their target code changes were absent from
every file they were meant to touch, and `ROADMAP.md`/`CHANGELOG.md`/`HANDOFF.md` still only
reflected patch 29. Per `AGENTS.md`'s "read the actual current file content, never assume memory
from earlier in the conversation is still accurate," this session treated the repository's actual
code as ground truth rather than any prior narrative about patches 30/31 having been applied or
device-tested. Both stale scripts are removed and superseded by Patch 30 below.

**Patch 30** does two things in one delivery: (1) implements the persistent-search-service design
those stale scripts were building toward -- `DpiSearchService` (a foreground service) plus a
`DpiSearchController` `StateFlow`, so the strategy search survives leaving Settings or
backgrounding the app, with a Stop action on its own notification; and (2) a researched root-cause
fix for the search finding zero working strategies: `DpiStrategySearch.fullPass` required all
three probe hosts including `redirector.googlevideo.com` (a CDN redirector) to pass a bare
synthetic TLS+HTTP/1.1 probe -- now only `www.youtube.com` and `i.ytimg.com` are required, the
redirector host stays probed for ranking/diagnostics only, per-stage socket timeouts are more
generous, and every probe now logs its host/stage outcome to the diagnostic journal so a real
device run leaves actual evidence if this is not sufficient by itself. `POST_NOTIFICATIONS` is now
requested before the search or the Home-screen YouTube bypass action starts its service, not only
on the first accepted download. **None of this is device-confirmed yet** -- see `ROADMAP.md`'s
Patch 30 section for the exact checklist.

""" + old_anchor
    replace_once(path, old_anchor, new_section, already_applied="### Patch 29-30 / bypass reliability, persistent search and root-cause status")

    old_filemap_line = (
        "- `DpiNative.kt` / `DpiEngineService.kt` / `DpiEngine.kt` -- bundled DPI-bypass engine "
        "(patch 27): JNI binding, `:dpi`-process host service, bind/wait/close client.\n"
    )
    new_filemap_line = (
        "- `DpiNative.kt` / `DpiEngineService.kt` / `DpiEngine.kt` -- bundled DPI-bypass engine "
        "(patch 27): JNI binding, `:dpi`-process host service, bind/wait/close client.\n"
        "- `DpiSearchService.kt` -- foreground service running the strategy search independently "
        "of any screen's lifecycle, plus its `DpiSearchController` state (patch 30).\n"
    )
    replace_once(path, old_filemap_line, new_filemap_line, already_applied="DpiSearchService.kt` -- foreground service")

    old_next_step_start = "## Immediate next step for Claude (in a new conversation)"
    old_next_step_full = old_next_step_start + """

**Run/collect verification for patches 24-25.** The autonomous roadmap work is complete. The exact remaining checks are in `ROADMAP.md`: repeated YouTube recovery/session behavior, process-death + resume, queue stress/dedupe, typed error cases, airplane mode, large/audio downloads and MediaStore cleanup, RU/EN/navigation/logging/icon/notifications, then a fresh signed GitHub Release installed over the prior signed build.

**Network-bypass follow-up:** the user has confirmed the Patch-27 engine on the real target path: GitHub Actions builds successfully and ByeDPI works on-device. Do not reopen that old gate unless a regression appears. Patch 28's only remaining network gate is the new YouTube-only Android VPN lifecycle (`ROADMAP.md`).

Do not mark a device-dependent item done from source inspection or a green compile. If a verification fails, diagnose that concrete failure first and update `CHANGELOG.md` / `HANDOFF.md` in the same patch as the fix.

The 16 KB page-size item is upstream-dependent with the currently pinned wrapper. Re-check upstream before changing `youtubedl-android`; do not vendor a custom native payload as a shortcut without a new explicit user decision.

For general repo process (guarded/idempotent patch scripts, verification discipline, English-only code/docs, no machine-specific committed paths) see `AGENTS.md`."""
    new_next_step_full = old_next_step_start + """

**Collect device verification for Patch 30 first** -- this is now the single blocking item.
Exact checklist in `ROADMAP.md`'s Patch 30 section: does the strategy search now find at least
one working strategy on the restricted network; does it survive minimizing the app / switching
screens; does the notification (with a working Stop button) actually appear; does the download
bypass switch and the Home-screen YouTube action become usable once a strategy verifies; does a
bypass-enabled download and the YouTube VPN tunnel both work end to end. If the search still
finds zero strategies, read the per-host/per-stage detail Patch 30 now logs to the hidden
diagnostic journal (five taps on Settings) before guessing at another fix -- do not re-guess
blind.

Once Patch 30 is confirmed (or fixed again), the pre-existing patch 24/25 device/Actions
checklist is still open: repeated YouTube recovery/session behavior, process-death + resume,
queue stress/dedupe, typed error cases, airplane mode, large/audio downloads and MediaStore
cleanup, RU/EN/navigation/logging/icon/notifications, then a fresh signed GitHub Release
installed over the prior signed build -- and the patch-28 YouTube VPN lifecycle gate (first-run
consent, playback under the tunnel, share -> download while the tunnel is active). The Patch-27
engine question itself (does GitHub Actions build, does ByeDPI work on-device) is already
confirmed; do not reopen it absent a regression.

The 16 KB page-size item is upstream-dependent with the currently pinned wrapper. Re-check
upstream before changing `youtubedl-android`; do not vendor a custom native payload as a
shortcut without a new explicit user decision.

For general repo process (guarded/idempotent patch scripts, verification discipline,
English-only code/docs, no machine-specific committed paths) see `AGENTS.md`."""
    replace_once(
        path,
        old_next_step_full,
        new_next_step_full,
        already_applied="Collect device verification for Patch 30 first"
    )


# ---------------------------------------------------------------------------
# 9. Remove the two stale, never-applied patch scripts this patch supersedes.
# ---------------------------------------------------------------------------

def remove_stale_scripts():
    for name in ("patch_030_persistent_search_service.py", "patch_031_docs_investigation.py"):
        stale = ROOT / name
        if stale.exists():
            stale.unlink()


# ---------------------------------------------------------------------------

def main():
    steps = [
        create_dpi_search_service,
        patch_dpi_search,
        patch_dpi_bypass,
        patch_bypass_settings,
        patch_main_activity,
        patch_manifest,
        patch_strings,
        patch_roadmap,
        patch_changelog,
        patch_handoff,
        remove_stale_scripts,
    ]
    for step in steps:
        try:
            step()
        except AnchorNotFound as exc:
            print(f"FAILED at {step.__name__}: {exc}", file=sys.stderr)
            sys.exit(1)
        print(f"applied: {step.__name__}")
    print("Patch 30 applied successfully.")


if __name__ == "__main__":
    main()
