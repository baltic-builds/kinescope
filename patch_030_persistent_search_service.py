#!/usr/bin/env python3
"""
Patch 30 -- persistent strategy search + docs refresh.

Requires patch 29 already applied (this patch's anchors are patch-29 output).

Two device-reported bugs, both traced to the same root cause:

1. "The bypass strategies still won't run" -- the automatic first-run search
   added in patch 29 marked itself as having run (`hasRunInitialSearch`) even
   when it was blocked (a download was active) and therefore never actually
   started, permanently burning the one automatic attempt.

2. "The strategy search stops if I minimize the app or switch screens" -- the
   search ran inside `BypassSettingsSection`'s own `rememberCoroutineScope()`,
   and an explicit `DisposableEffect(Unit) { onDispose { searchJob?.cancel() } }`
   cancelled it the moment that screen left composition. That is by design in
   Compose (a screen's scope does not outlive the screen), not a bug in that
   one line -- the search needed a lifecycle that isn't tied to one screen.

Fix: the search now runs in a new foreground `DpiSearchService`, with its
progress/result exposed through a `DpiSearchController` StateFlow that
`BypassSettingsSection` observes reactively (mirroring the existing
`BypassVpnController` pattern). This is a real foreground service, so it
survives navigating away from Settings and backgrounding the whole app.
It also gets its own notification with a Stop button (Android requires a
notification for a foreground service, and this doubles as the additional
control surface asked for) -- so the search can be started and stopped from
Settings or from the notification shade, independent of which screen is
open. Concurrency with an active download's own engine use is already safe
without any new locking: `DpiEngine`'s existing `regularGate` semaphore
makes a second concurrent `DpiEngine.start()` call return null immediately
rather than colliding with the native engine's process-wide state.

Verification performed this session (see CHANGELOG.md Patch 30 entry for
detail): the new `DpiSearchService.kt` was compiled clean with kotlinc
against a real API 35 android.jar, the project's actual (unmodified)
`DpiStrategyStore.kt` / `DpiStrategies.kt` / `DpiSearch.kt` / `AppLog.kt`,
and minimal same-shape stubs only for `DpiBypass`, `MainActivity` and
resource IDs. `BypassSettings.kt`'s edits were not independently compiled
(would require stubbing all of Jetpack Compose) -- reviewed by hand and by
brace-balance check instead, reusing the exact `X by Y.state.collectAsState()`
pattern already proven working in the same file for `BypassVpnController`.

Safe to run twice. Run from the repository root:

    python3 patch_030_persistent_search_service.py
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
# 1. New file: DpiSearchService.kt (compiled clean against a real API 35
#    android.jar plus the project's real DpiStrategyStore/DpiStrategies/
#    DpiSearch/AppLog -- see module docstring).
# ---------------------------------------------------------------------------

DPI_SEARCH_SERVICE_KT = 'package com.kinescope.app\n\nimport android.app.Notification\nimport android.app.NotificationChannel\nimport android.app.NotificationManager\nimport android.app.PendingIntent\nimport android.app.Service\nimport android.content.Context\nimport android.content.Intent\nimport android.content.pm.ServiceInfo\nimport android.os.Build\nimport android.os.IBinder\nimport androidx.annotation.StringRes\nimport androidx.core.app.NotificationCompat\nimport androidx.core.content.ContextCompat\nimport java.util.concurrent.Executors\nimport java.util.concurrent.atomic.AtomicBoolean\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.asStateFlow\n\ninternal data class DpiSearchUiState(\n    val running: Boolean = false,\n    val progress: SearchProgress? = null,\n    @StringRes val resultMessageRes: Int? = null,\n    val resultDirectWorks: Boolean = false\n)\n\n/**\n * Holds the strategy search\'s state outside any screen\'s lifecycle, so the search itself (run by\n * [DpiSearchService]) survives navigating away from Settings or backgrounding the app -- before\n * this it ran in the Settings screen\'s own coroutine scope and was cancelled the moment that\n * screen left composition (see CHANGELOG.md, patch 30).\n */\ninternal object DpiSearchController {\n    private val mutableState = MutableStateFlow(DpiSearchUiState())\n    val state = mutableState.asStateFlow()\n\n    internal fun starting() {\n        mutableState.value = DpiSearchUiState(running = true)\n    }\n\n    internal fun progress(progress: SearchProgress) {\n        mutableState.value = mutableState.value.copy(running = true, progress = progress)\n    }\n\n    internal fun finished(@StringRes messageRes: Int, directWorks: Boolean = false) {\n        mutableState.value = DpiSearchUiState(resultMessageRes = messageRes, resultDirectWorks = directWorks)\n    }\n}\n\n/**\n * Runs the strategy search as a foreground service so it keeps testing every remaining candidate\n * when the user leaves Settings or backgrounds the app entirely, instead of the search being tied\n * to that screen\'s own composition.\n */\nclass DpiSearchService : Service() {\n    private val executor = Executors.newSingleThreadExecutor { runnable ->\n        Thread(runnable, "kinescope-strategy-search").apply { isDaemon = true }\n    }\n    private val stopRequested = AtomicBoolean(false)\n\n    override fun onBind(intent: Intent?): IBinder? = null\n\n    override fun onCreate() {\n        super.onCreate()\n        createNotificationChannel()\n    }\n\n    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {\n        when (intent?.action) {\n            ACTION_STOP -> stopRequested.set(true)\n            ACTION_START -> {\n                if (!DpiSearchController.state.value.running) {\n                    stopRequested.set(false)\n                    DpiSearchController.starting()\n                    startForegroundCompat(buildNotification(null))\n                    executor.execute { runSearch() }\n                }\n            }\n        }\n        return START_NOT_STICKY\n    }\n\n    private fun runSearch() {\n        try {\n            val directWorks = DpiBypass.directConnectionWorks()\n            val results = DpiBypass.search(\n                applicationContext,\n                isCancelled = { stopRequested.get() },\n                onProgress = { snapshot ->\n                    DpiSearchController.progress(snapshot)\n                    runCatching {\n                        getSystemService(NotificationManager::class.java)\n                            .notify(NOTIFICATION_ID, buildNotification(snapshot))\n                    }\n                }\n            )\n            if (stopRequested.get()) {\n                DpiSearchController.finished(R.string.bypass_search_stopped)\n            } else {\n                val passes = results.filter { it.fullPass }\n                val working = passes.firstOrNull()\n                if (working != null) {\n                    DpiPrefs.markStrategiesVerified(applicationContext, working.line, passes.drop(1).map { it.line })\n                    DpiPrefs.setEnabled(applicationContext, true)\n                    DpiSearchController.finished(\n                        if (directWorks) R.string.bypass_search_found_direct else R.string.bypass_search_found,\n                        directWorks\n                    )\n                } else {\n                    DpiSearchController.finished(R.string.bypass_search_none)\n                }\n            }\n        } catch (e: Exception) {\n            AppLog.e("DpiSearchService", "Strategy search failed", e)\n            DpiSearchController.finished(R.string.bypass_operation_failed)\n        } finally {\n            stopSelf()\n        }\n    }\n\n    override fun onDestroy() {\n        stopRequested.set(true)\n        super.onDestroy()\n    }\n\n    private fun startForegroundCompat(notification: Notification) {\n        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {\n            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)\n        } else {\n            startForeground(NOTIFICATION_ID, notification)\n        }\n    }\n\n    private fun buildNotification(progress: SearchProgress?): Notification {\n        val contentIntent = PendingIntent.getActivity(\n            this,\n            0,\n            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),\n            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE\n        )\n        val stopIntent = Intent(this, DpiSearchService::class.java).apply { action = ACTION_STOP }\n        val stopPendingIntent = PendingIntent.getService(\n            this,\n            1,\n            stopIntent,\n            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE\n        )\n        val text = if (progress == null) {\n            getString(R.string.bypass_search_notification_starting)\n        } else {\n            getString(\n                R.string.bypass_find_progress,\n                (progress.index + 1).coerceAtMost(progress.total.coerceAtLeast(1)),\n                progress.total\n            )\n        }\n        return NotificationCompat.Builder(this, CHANNEL_ID)\n            .setSmallIcon(R.drawable.ic_download)\n            .setContentTitle(getString(R.string.bypass_search_notification_title))\n            .setContentText(text)\n            .setContentIntent(contentIntent)\n            .setOnlyAlertOnce(true)\n            .setOngoing(true)\n            .addAction(0, getString(R.string.stop), stopPendingIntent)\n            .build()\n    }\n\n    private fun createNotificationChannel() {\n        val manager = getSystemService(NotificationManager::class.java)\n        manager.createNotificationChannel(\n            NotificationChannel(\n                CHANNEL_ID,\n                getString(R.string.bypass_notification_channel),\n                NotificationManager.IMPORTANCE_LOW\n            )\n        )\n    }\n\n    companion object {\n        private const val ACTION_START = "com.kinescope.app.ACTION_START_STRATEGY_SEARCH"\n        private const val ACTION_STOP = "com.kinescope.app.ACTION_STOP_STRATEGY_SEARCH"\n        private const val CHANNEL_ID = "youtube_bypass"\n        private const val NOTIFICATION_ID = 1102\n\n        fun start(context: Context) {\n            val intent = Intent(context, DpiSearchService::class.java).apply { action = ACTION_START }\n            runCatching { ContextCompat.startForegroundService(context, intent) }\n                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch start", it) }\n        }\n\n        fun stop(context: Context) {\n            val intent = Intent(context, DpiSearchService::class.java).apply { action = ACTION_STOP }\n            runCatching { context.startService(intent) }\n                .onFailure { AppLog.e("DpiSearchService", "Could not dispatch stop", it) }\n        }\n    }\n}\n'


def create_dpi_search_service():
    write_new_file(APP / "DpiSearchService.kt", DPI_SEARCH_SERVICE_KT)


# ---------------------------------------------------------------------------
# 2. BypassSettings.kt: observe the service's state instead of owning a Job
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

    old_head = """    val vpnState by BypassVpnController.state.collectAsState()
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
    new_head = """    val vpnState by BypassVpnController.state.collectAsState()
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
        if (!searching && searchState.resultMessageRes != null) {
            verified = DpiPrefs.isStrategyVerified(context, DpiStrategyStore.selected(context))
            verifiedAt = DpiPrefs.verifiedAt(context)
            enabled = DpiPrefs.isEnabled(context)
            message = context.getString(searchState.resultMessageRes)
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
# 3. Manifest: declare the new service
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
# 4. Strings: two new notification strings, EN + RU
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

    ru_old = "<string name=\"bypass_notification_active\">YouTube \u0440\u0430\u0431\u043e\u0442\u0430\u0435\u0442 \u0447\u0435\u0440\u0435\u0437 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 \u043e\u0431\u0445\u043e\u0434.</string>\n<string name=\"cd_remove_download\">\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0443</string>"
    ru_new = (
        "<string name=\"bypass_notification_active\">YouTube \u0440\u0430\u0431\u043e\u0442\u0430\u0435\u0442 \u0447\u0435\u0440\u0435\u0437 \u043b\u043e\u043a\u0430\u043b\u044c\u043d\u044b\u0439 \u043e\u0431\u0445\u043e\u0434.</string>\n"
        "<string name=\"bypass_search_notification_title\">Kinescope \u00b7 \u041f\u0440\u043e\u0432\u0435\u0440\u043a\u0430 \u0441\u043e\u0435\u0434\u0438\u043d\u0435\u043d\u0438\u044f</string>\n"
        "<string name=\"bypass_search_notification_starting\">\u0417\u0430\u043f\u0443\u0441\u043a \u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0438\u2026</string>\n"
        "<string name=\"cd_remove_download\">\u0423\u0434\u0430\u043b\u0438\u0442\u044c \u0437\u0430\u0433\u0440\u0443\u0437\u043a\u0443</string>"
    )
    replace_once(ru_path, ru_old, ru_new, already_applied="bypass_search_notification_title")


# ---------------------------------------------------------------------------
# 5. Docs: ROADMAP.md, CHANGELOG.md, HANDOFF.md
# ---------------------------------------------------------------------------

def patch_roadmap():
    path = ROOT / "ROADMAP.md"

    old_open_item = (
        "- [ ] **Needs a real device to confirm:** the automatic first-run search actually "
        "completes and enables the switch; a fallback strategy is actually used when the "
        "primary one fails to start; the YouTube VPN tunnel start also cascades through "
        "fallbacks; the folder migration takes effect for an existing install; a freshly "
        "downloaded video plays with picture and sound.\n"
    )
    new_open_item = (
        "- [x] **Device report (2026-09-25):** build succeeded, but the bypass still would not "
        "run and the strategy search stopped when the app was minimized or another screen was "
        "opened. Root-caused and fixed in Patch 30 below; the rest of this list is still "
        "otherwise unconfirmed.\n"
    )
    replace_once(path, old_open_item, new_open_item, already_applied="Device report (2026-09-25)")

    anchor = "### Patch 27 network bypass: verified baseline"
    new_section = """### Patch 30 persistent strategy search

Not yet device-verified. Full detail in `CHANGELOG.md`.

- [x] The strategy search now runs in a new foreground `DpiSearchService` instead of the
  Settings screen's own coroutine scope, so it keeps running when the user leaves Settings or
  backgrounds the app -- previously an explicit `onDispose` cancelled it the moment the screen
  left composition.
- [x] The search's own notification has a Stop button, giving a second control surface for it
  beyond the Settings screen.
- [x] Fixed the patch-29 auto-run flag being consumed even when the search never actually
  started (blocked by an active download): now only consumed once `startSearch()` is actually
  called.
- [ ] **Needs a real device to confirm:** the search keeps running and finishes after
  minimizing the app or navigating to another screen; the notification's Stop button works;
  Settings shows the correct state after leaving and returning while a search is in progress;
  a verified strategy is picked up correctly whether the automatic or manual search found it.

""" + anchor
    replace_once(path, anchor, new_section, already_applied="### Patch 30 persistent strategy search")


def patch_changelog():
    path = ROOT / "CHANGELOG.md"
    anchor = "## Patch 29 \u2014 Bypass reliability, fallback strategies, plain-language UI"
    new_section = """## Patch 30 \u2014 Persistent strategy search

Device report after patch 29: build succeeded, but the bypass still would not run, and the
strategy search stopped whenever the app was minimized or another screen was opened.

### Root cause
Both symptoms traced back to the same design flaw: the strategy search ran inside
`BypassSettingsSection`'s own `rememberCoroutineScope()`, and an explicit
`DisposableEffect(Unit) { onDispose { searchJob?.cancel() } }` cancelled it the moment that
screen left composition -- which happens on navigating to another screen, and can happen when
backgrounding the app hard enough for the composition to be torn down. A search that never
finishes never verifies a strategy, so the bypass switch (which requires a verified strategy)
stayed disabled no matter how many times "Test strategies" was pressed. Separately, patch 29's
auto-run-once flag was consumed (`setHasRunInitialSearch(true)`) even when the search was
blocked by an active download and therefore never actually started, permanently wasting the
one automatic attempt on devices where Settings happened to first open mid-download.

### Fixed
- **New `DpiSearchService`** (foreground service, mirroring `BypassVpnService`'s existing
  notification/lifecycle pattern) runs the search independently of any screen's lifecycle. Its
  state (`running`, current `SearchProgress`, and the final result) is exposed through a new
  `DpiSearchController` `StateFlow`, the same pattern `BypassVpnController` already uses.
  `BypassSettingsSection` now observes this via `collectAsState()` instead of owning a `Job`
  and cancelling it on `onDispose`.
- The service's notification carries a Stop action button, so the search can be stopped from
  the notification shade as well as from Settings -- a second, always-reachable control for it.
- `BypassSettings.kt`'s auto-run effect now only sets `hasRunInitialSearch` when it actually
  calls `startSearch()`, so a first Settings visit that happens to be blocked by an active
  download no longer burns the one automatic attempt; it simply waits for a later, unblocked
  visit.
- Confirmed (see `DpiEngine.kt`, unchanged): a search running concurrently with a
  bypass-enabled download is already safe without any new locking, because `DpiEngine`'s
  existing `regularGate` semaphore makes a second concurrent `DpiEngine.start()` call return
  `null` immediately (the download then simply proceeds without the bypass for that attempt)
  rather than colliding with the native engine's process-wide state. This is why the Settings
  search button still requires no active download (`controlsEnabled`) -- not for native-layer
  safety, but because a search that cannot acquire the gate would otherwise burn through every
  candidate and falsely report "no working strategy found".

### Verification status
`DpiSearchService.kt` was compiled clean with `kotlinc` against a real Android API 35
`android.jar`, together with the project's actual, unmodified `DpiStrategyStore.kt` /
`DpiStrategies.kt` / `DpiSearch.kt` / `AppLog.kt`, and minimal same-signature stubs only for
`DpiBypass`, `MainActivity`, and resource IDs (a full Compose-dependency compile of
`BypassSettings.kt` was not attempted; its edits were reviewed by hand and by brace-balance
check, reusing the `X by Y.state.collectAsState()` pattern already working in the same file
for `BypassVpnController`). **Not yet confirmed on a real device** -- see `ROADMAP.md` -> Patch
30 for the specific checks still needed.

""" + anchor
    replace_once(path, anchor, new_section, already_applied="## Patch 30 \u2014 Persistent strategy search")


def patch_handoff():
    path = ROOT / "HANDOFF.md"

    old_anchor = "### Patch 28 / YouTube split-tunnel status"
    new_section = """### Patch 29-30 / bypass reliability + persistent search status

The user reported after patch 29: build succeeded, but the bypass still would not run, and the
strategy search stopped when the app was minimized or another screen was opened. Patch 29 had
already fixed the *reason nothing was ever verified* (nothing triggered the first search
automatically); patch 30 fixes *why the search itself never finished*: it ran in the Settings
screen's own coroutine scope and was cancelled by an explicit `onDispose` the moment that screen
left composition. The search now runs in a new foreground `DpiSearchService`
(`DpiSearchController` StateFlow, mirroring `BypassVpnController`), with its own notification and
Stop button, independent of which screen is open or whether the app is backgrounded. Patch 29
also fixed a multi-process `Application.onCreate()` race, added verified-strategy fallbacks (best
+ up to 3), migrated the pre-rename `YTOffline` folder name, preferred H.264+AAC for playback
compatibility, and removed jargon from the bypass UI strings. **None of patches 29-30 has a
confirmed real-device pass yet** -- see `ROADMAP.md`'s Patch 29 and Patch 30 sections for the
exact remaining checks. Full detail in `CHANGELOG.md`.

""" + old_anchor
    replace_once(path, old_anchor, new_section, already_applied="### Patch 29-30 / bypass reliability + persistent search status")

    old_filemap_line = (
        "- `DpiNative.kt` / `DpiEngineService.kt` / `DpiEngine.kt` -- bundled DPI-bypass engine "
        "(patch 27): JNI binding, `:dpi`-process host service, bind/wait/close client.\n"
    )
    new_filemap_line = (
        "- `DpiNative.kt` / `DpiEngineService.kt` / `DpiEngine.kt` -- bundled DPI-bypass engine "
        "(patch 27): JNI binding, `:dpi`-process host service, bind/wait/close client.\n"
        "- `DpiVpnEngineService.kt` -- the long-lived `:dpi_vpn` counterpart used by the YouTube "
        "split tunnel (patch 28).\n"
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
device-dependent item done from source inspection or a green compile alone.

Once patches 29-30 are confirmed (or fixed again), fall back to the pre-existing backlog: Sprint
1 (patches 24-25) device/Actions verification -- repeated YouTube recovery/session behavior,
process-death + resume, queue stress/dedupe, typed error cases, airplane mode, large/audio
downloads and MediaStore cleanup, RU/EN/navigation/logging/icon/notifications, then a fresh
signed GitHub Release installed over the prior signed build -- and the patch-28 YouTube VPN
lifecycle gate (first-run consent, playback under the tunnel, share -> download while the
tunnel is active). The Patch-27 engine question itself (does GitHub Actions build, does ByeDPI
work on-device) is already confirmed; do not reopen it absent a regression.

The 16 KB page-size item is upstream-dependent with the currently pinned wrapper. Re-check
upstream before changing `youtubedl-android`; do not vendor a custom native payload as a
shortcut without a new explicit user decision.

For general repo process (guarded/idempotent patch scripts, verification discipline,
English-only code/docs, no machine-specific committed paths) see `AGENTS.md`."""
    replace_once(
        path,
        old_next_step_full,
        new_next_step_full,
        already_applied="Collect device verification for patches 29-30 first"
    )


# ---------------------------------------------------------------------------

def main():
    steps = [
        create_dpi_search_service,
        patch_bypass_settings,
        patch_manifest,
        patch_strings,
        patch_roadmap,
        patch_changelog,
        patch_handoff,
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
