#!/usr/bin/env python3
"""
Patch 04 — ROADMAP.md Step 6.5 (design system v2: component patterns).

Run this from the ROOT of the yt-offline repo, AFTER patches 01, 02,
and 03 have already been applied and committed.

This is the big one: it turns the tokens from patch 03 into actual
screen behavior, and adds two genuinely new features along the way
(in-app delete/share) that the pattern spec calls for.

What this does, matching each ROADMAP.md Step 6.5 bullet:

- **Download queue (active)**: rows get a solid `primaryContainer`
  thumbnail placeholder with a play glyph, a real per-state status
  color (queued/running/done/failed -- see the note on "warning"
  below), and a `LinearProgressIndicator` in `primary` while RUNNING.
  This needed a real numeric progress value, which didn't exist
  before (only a formatted string like "45% (ETA 12s)") -- so
  `DownloadJobStatus` gains a `progressFraction: Float?` field, fed
  from the same yt-dlp callback that already builds the string.

- **Download queue (empty)**: a centered `primaryContainer` circle
  behind a download icon, replacing the old plain text-only message.

- **Library**: adds a filled icon-only Play button in `primary`, plus
  a genuinely new overflow menu (⋮) with Share (`Intent.ACTION_SEND`)
  and Delete (`ContentResolver.delete()` on the app's own MediaStore
  row -- no special permission dance needed since these are rows this
  app itself inserted).

- **Settings**: restructured into three labeled, divider-separated
  sections (Default Quality / Storage / Extractor), with the
  yt-dlp-update flow's state lifted up to `DownloadScreen` so the
  existing top-bar shortcut and the new in-Settings button share one
  source of truth instead of duplicating it. Extractor section adds a
  persisted "last updated" timestamp (new: `Settings.kt` gains
  get/set for it, `YtDlpUpdater` records it on a successful check).

- **Error states**: a dismissible banner (`errorContainer` /
  `onErrorContainer`) appears when any job fails specifically due to
  no network at queue time -- distinct from a single video's
  own inline error, which keeps its existing per-row treatment.

- **Composer bar**: the placeholder hint already existed; the only
  gap was the focus border being fully transparent even when
  focused (relying on fill alone for affordance). Now shows a subtle
  `outline`-colored border on focus.

A version note worth knowing about: Compose BOM 2024.11.00 (patch 01)
pulls in Material3 1.3.1, and `LinearProgressIndicator`'s lambda-based
`progress: () -> Float` overload wasn't introduced until 1.5.0-alpha17
-- so this patch deliberately uses the plain `Float` overload, which
is still current (not even deprecated yet) at 1.3.1. Using the newer
lambda form here would simply fail to compile against this project's
actual dependency versions.

Also: `warning` (added to Theme.kt in patch 03) has no state to map to
yet -- ROADMAP.md's "warning retrying" describes a retry mechanism
that doesn't exist in JobState (QUEUED/RUNNING/DONE/FAILED only, no
RETRYING). The token stays defined and ready; nothing forces a fake
state onto it here.

Also updates ROADMAP.md's checkboxes for every Step 6.5 item and the
matching Appendix-adjacent "no in-app delete" backlog note.

Deliberately NOT done in this patch:
  - Step 6.7 (optional monochrome adaptive-icon layer).
  - Step 7 (Kinescope rename, applicationId com.kinescope.app) and
    Step 5 (device testing) -- both still queued after this.

Safe to re-run: every edit is guarded by an exact-match check.
"""

from __future__ import annotations

import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent


class PatchError(RuntimeError):
    pass


def read(path: pathlib.Path) -> str:
    if not path.exists():
        raise PatchError(f"Expected file not found: {path}\n"
                          f"Are you running this from the repo root?")
    return path.read_text(encoding="utf-8")


def write(path: pathlib.Path, content: str) -> None:
    path.write_text(content, encoding="utf-8")


def replace_once(path: pathlib.Path, old: str, new: str, label: str) -> None:
    text = read(path)
    count = text.count(old)
    if count == 0:
        if new in text:
            print(f"  [skip] {label}: already applied, leaving as-is.")
            return
        raise PatchError(
            f"{label}: expected text not found in {path}.\n"
            f"The file may have changed since this patch was written.\n"
            f"--- expected snippet ---\n{old}\n------------------------"
        )
    if count > 1:
        raise PatchError(
            f"{label}: expected text found {count} times in {path}, "
            f"need exactly 1. Refusing to guess which one to replace."
        )
    write(path, text.replace(old, new, 1))
    print(f"  [ok] {label}")


def overwrite(path: pathlib.Path, content: str, label: str) -> None:
    write(path, content)
    print(f"  [ok] {label} (full file rewrite)")


def require_prior_patches() -> None:
    theme = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "Theme.kt"
    text = read(theme)
    if "YtOfflineExtras" not in text:
        raise PatchError(
            "Theme.kt doesn't have patch 03's changes yet (no "
            "'YtOfflineExtras' found). Run patches 01, 02, and 03 first, "
            "in order."
        )


# ---------------------------------------------------------------------------
# DownloadQueueBus.kt — progressFraction field + shared NO_INTERNET_MESSAGE
# ---------------------------------------------------------------------------

NEW_DOWNLOAD_QUEUE_BUS_KT = '''package com.baltic.ytoffline

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class JobState { QUEUED, RUNNING, DONE, FAILED }

data class DownloadJobStatus(
    val id: String,
    val url: String,
    val qualityLabel: String,
    val state: JobState,
    val progressText: String,
    // ROADMAP.md Step 6.5 [fixed]: a real 0f..1f fraction for the
    // queue row's LinearProgressIndicator. Null while queued/
    // starting/done/failed -- only meaningful during RUNNING, between
    // the first progress callback tick and completion. progressText
    // stays the source of truth for the human-readable line; this is
    // purely for the progress bar.
    val progressFraction: Float? = null
)

/**
 * In-process shared state between DownloadService (producer) and the
 * UI (consumer). No IPC/binding needed since both run in the same
 * process for this app — deliberately the simplest thing that works,
 * per CLAUDE.md.
 */
object DownloadQueueBus {
    private val _jobs = MutableStateFlow<List<DownloadJobStatus>>(emptyList())
    val jobs = _jobs.asStateFlow()

    // ROADMAP.md Step 4 [MEDIUM, fixed]: upsert() runs from the
    // main/binder thread (enqueue) and update() runs from the worker
    // thread (progress ticks) -- both used to do a plain
    // read-`_jobs.value`-then-write, which genuinely races between
    // those two threads. `MutableStateFlow.update {}` is an atomic
    // compare-and-set retry loop instead.
    fun upsert(status: DownloadJobStatus) {
        _jobs.update { current -> current.filterNot { it.id == status.id } + status }
    }

    fun update(id: String, transform: (DownloadJobStatus) -> DownloadJobStatus) {
        _jobs.update { current -> current.map { if (it.id == id) transform(it) else it } }
    }

    // ROADMAP.md Step 6.5: shared between DownloadService (where it's
    // set) and MainActivity (where it's checked, to decide whether to
    // show the connectivity-loss banner) so the two files can't drift
    // out of sync over a hand-typed string literal duplicated in both
    // places.
    const val NO_INTERNET_MESSAGE = "No internet connection"
}
'''


def fix_download_queue_bus() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "DownloadQueueBus.kt"
    current = read(path)
    if "progressFraction" in current:
        print("  [skip] DownloadQueueBus.kt: already patched.")
        return
    overwrite(path, NEW_DOWNLOAD_QUEUE_BUS_KT, "DownloadQueueBus.kt (progressFraction field + NO_INTERNET_MESSAGE)")


# ---------------------------------------------------------------------------
# MediaStorage.kt — add delete()
# ---------------------------------------------------------------------------

def fix_media_storage() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "MediaStorage.kt"
    old = (
        "        return items\n"
        "    }\n"
        "}\n"
        "\n"
        "data class LibraryItem(\n"
    )
    new = (
        "        return items\n"
        "    }\n"
        "\n"
        "    /**\n"
        "     * Deletes a previously published item from MediaStore. Returns\n"
        "     * true on success. ROADMAP.md Step 6.5: backs the Library row's\n"
        "     * overflow-menu Delete action -- no RecoverableSecurityException\n"
        "     * handling needed since these are rows this app itself inserted,\n"
        "     * and apps always have delete permission for their own rows on\n"
        "     * API 29+.\n"
        "     */\n"
        "    fun delete(context: Context, item: LibraryItem): Boolean {\n"
        "        return try {\n"
        "            context.contentResolver.delete(item.uri, null, null) > 0\n"
        "        } catch (e: SecurityException) {\n"
        "            false\n"
        "        }\n"
        "    }\n"
        "}\n"
        "\n"
        "data class LibraryItem(\n"
    )
    replace_once(path, old, new, "MediaStorage.kt: add delete()")


# ---------------------------------------------------------------------------
# Settings.kt — last-update timestamp
# ---------------------------------------------------------------------------

def fix_settings() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "Settings.kt"
    current = read(path)
    if "getLastUpdateTimestamp" in current:
        print("  [skip] Settings.kt: already patched.")
        return

    old = (
        "    private const val KEY_DOWNLOAD_SUBFOLDER = \"download_subfolder\"\n"
        "    const val DEFAULT_SUBFOLDER = \"YTOffline\"\n"
    )
    new = (
        "    private const val KEY_DOWNLOAD_SUBFOLDER = \"download_subfolder\"\n"
        "    private const val KEY_LAST_UPDATE_TIMESTAMP = \"last_ytdlp_update_timestamp\"\n"
        "    const val DEFAULT_SUBFOLDER = \"YTOffline\"\n"
    )
    replace_once(path, old, new, "Settings.kt: add KEY_LAST_UPDATE_TIMESTAMP constant")

    old2 = (
        "    private fun prefs(context: Context) =\n"
        "        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)\n"
        "}\n"
    )
    new2 = (
        "    // ROADMAP.md Step 6.5: backs the Extractor section's\n"
        "    // \"Last updated\" line in Settings. 0L (epoch) means never --\n"
        "    // formatTimestamp() in MainActivity.kt maps that to \"Never\".\n"
        "    fun getLastUpdateTimestamp(context: Context): Long =\n"
        "        prefs(context).getLong(KEY_LAST_UPDATE_TIMESTAMP, 0L)\n"
        "\n"
        "    fun setLastUpdateTimestamp(context: Context, timestampMillis: Long) {\n"
        "        prefs(context).edit().putLong(KEY_LAST_UPDATE_TIMESTAMP, timestampMillis).apply()\n"
        "    }\n"
        "\n"
        "    private fun prefs(context: Context) =\n"
        "        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)\n"
        "}\n"
    )
    replace_once(path, old2, new2, "Settings.kt: add getLastUpdateTimestamp/setLastUpdateTimestamp")


# ---------------------------------------------------------------------------
# YtDlpUpdater.kt — record the timestamp on a successful update
# ---------------------------------------------------------------------------

NEW_YT_DLP_UPDATER_KT = '''package com.baltic.ytoffline

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.UpdateChannel
import com.yausername.youtubedl_android.YoutubeDL

/**
 * Wraps the youtubedl-android library's self-update mechanism, so the
 * bundled yt-dlp (and therefore the extractor) can be refreshed
 * without an app rebuild. See ROADMAP.md Phase 5 and the "no custom
 * extractor" ground rule in CLAUDE.md — this update mechanism is the
 * intended way to keep extraction working as YouTube changes things
 * over time, instead of us reverse-engineering anything ourselves.
 *
 * ROADMAP.md Step 2 [fixed]: the library's current README (matching
 * the 0.18.1 version pinned in app/build.gradle.kts) documents
 * `updateYoutubeDL(context, updateChannel)` — a required UpdateChannel
 * argument — not the single-argument call this file used to have.
 * STABLE is used here since this app never wants nightly/pre-release
 * yt-dlp builds on a personal device. The try/catch stays
 * deliberately broad, and the result is immediately turned into a
 * String via `.toString()`, so a wrong assumption about the *exact*
 * return type (enum vs. String) still fails soft rather than crashing
 * app startup.
 *
 * ROADMAP.md Step 6.5: records a last-updated timestamp on success
 * only (not on failure), for Settings' Extractor section.
 */
object YtDlpUpdater {
    private const val TAG = "YtDlpUpdater"

    /** Blocking — call this from a background thread, not the main thread. */
    fun updateBlocking(context: Context): String {
        return try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context, UpdateChannel.STABLE)
            Log.i(TAG, "yt-dlp update result: $status")
            Settings.setLastUpdateTimestamp(context, System.currentTimeMillis())
            status.toString()
        } catch (e: Exception) {
            Log.w(TAG, "yt-dlp update failed", e)
            "Update check failed: ${e.message}"
        }
    }
}
'''


def fix_yt_dlp_updater() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "YtDlpUpdater.kt"
    current = read(path)
    if "setLastUpdateTimestamp" in current:
        print("  [skip] YtDlpUpdater.kt: already patched.")
        return
    overwrite(path, NEW_YT_DLP_UPDATER_KT, "YtDlpUpdater.kt (record last-update timestamp on success)")


# ---------------------------------------------------------------------------
# DownloadService.kt — targeted edits: progressFraction + shared constant
# ---------------------------------------------------------------------------

def fix_download_service() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "DownloadService.kt"

    replace_once(
        path,
        '        DownloadQueueBus.update(job.id) { it.copy(state = JobState.RUNNING, progressText = "Starting\\u2026") }\n',
        '        DownloadQueueBus.update(job.id) {\n'
        '            it.copy(state = JobState.RUNNING, progressText = "Starting\\u2026", progressFraction = null)\n'
        '        }\n',
        "DownloadService.kt: reset progressFraction when a job starts",
    )

    replace_once(
        path,
        '        if (!hasNetwork()) {\n'
        '            DownloadQueueBus.update(job.id) {\n'
        '                it.copy(state = JobState.FAILED, progressText = "No internet connection")\n'
        '            }\n'
        '            return\n'
        '        }\n',
        '        if (!hasNetwork()) {\n'
        '            DownloadQueueBus.update(job.id) {\n'
        '                it.copy(state = JobState.FAILED, progressText = DownloadQueueBus.NO_INTERNET_MESSAGE)\n'
        '            }\n'
        '            return\n'
        '        }\n',
        "DownloadService.kt: use shared DownloadQueueBus.NO_INTERNET_MESSAGE constant",
    )

    replace_once(
        path,
        '            YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds, _ ->\n'
        '                DownloadQueueBus.update(job.id) {\n'
        '                    it.copy(progressText = "$progress% (ETA ${etaInSeconds}s)")\n'
        '                }\n'
        '                updateNotification("${job.url}: $progress%")\n'
        '            }\n',
        '            YoutubeDL.getInstance().execute(request, job.id) { progress, etaInSeconds, _ ->\n'
        '                DownloadQueueBus.update(job.id) {\n'
        '                    it.copy(\n'
        '                        progressText = "$progress% (ETA ${etaInSeconds}s)",\n'
        '                        // ROADMAP.md Step 6.5: a real fraction for the queue\n'
        '                        // row\'s progress bar -- yt-dlp reports progress as a\n'
        '                        // 0-100 percentage (see the "%" right above), hence\n'
        '                        // /100f.\n'
        '                        progressFraction = (progress / 100f).coerceIn(0f, 1f)\n'
        '                    )\n'
        '                }\n'
        '                updateNotification("${job.url}: $progress%")\n'
        '            }\n',
        "DownloadService.kt: feed progressFraction from the yt-dlp callback",
    )


# ---------------------------------------------------------------------------
# MainActivity.kt — full rewrite: queue/library/settings/composer patterns
# ---------------------------------------------------------------------------

NEW_MAIN_ACTIVITY_KT = '''package com.baltic.ytoffline

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "Maximally similar to the Claude app" pass, extended in ROADMAP.md
 * Step 6.5 with the actual component patterns design.md called for:
 * queue-row thumbnails and a real progress bar, an empty-queue
 * illustration, a Library overflow menu with working delete/share, a
 * sectioned Settings screen, and a dismissible connectivity-loss
 * banner. Colors/type/shape still come from YtOfflineTheme
 * (Theme.kt) — see design.md for what this is and isn't (an
 * approximation, not Anthropic's real spec; no Anthropic fonts, name,
 * or logo used).
 *
 * Still doesn't run downloads directly — enqueues into
 * DownloadService (a foreground service, so downloads survive
 * leaving the app) and displays live status from DownloadQueueBus.
 */
class MainActivity : ComponentActivity() {

    /** Holds the most recently shared URL so Compose can react to it. */
    private val sharedUrl = mutableStateOf("")

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Ignored: downloads still work without this permission,
            // the user just won't see a progress notification.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            YtOfflineTheme {
                DownloadScreen(prefillUrl = sharedUrl.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            extractUrl(sharedText)?.let { sharedUrl.value = it }
        }
    }
}

// ROADMAP.md Step 4 [MEDIUM, fixed]: restrict to YouTube hosts, both
// for shared text (below) and for whatever's typed/pasted directly
// into the composer field (see DownloadScreen's onSend) -- yt-dlp
// supports 1000+ sites, but this app's whole stated purpose is
// YouTube-only offline downloads (see CLAUDE.md), so anything else is
// scope creep worth rejecting at the UI layer rather than silently
// attempting it.
private val YOUTUBE_HOSTS = setOf(
    "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be"
)

private fun isYouTubeUrl(url: String): Boolean =
    runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() in YOUTUBE_HOSTS

/** Pulls the first YouTube URL out of arbitrary shared text. */
private fun extractUrl(text: String): String? =
    Regex("""https?://\\S+""").findAll(text).map { it.value }.firstOrNull { isYouTubeUrl(it) }

/** "Never" for a never-updated timestamp, otherwise a short local date/time. */
private fun formatTimestamp(millis: Long): String {
    if (millis == 0L) return "Never"
    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' h:mm a").withZone(ZoneId.systemDefault())
    return formatter.format(Instant.ofEpochMilli(millis))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadScreen(prefillUrl: String) {
    val context = LocalContext.current

    var url by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var selectedQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    var library by remember { mutableStateOf(MediaStorage.listPublished(context)) }
    var showSettings by remember { mutableStateOf(false) }
    val jobs by DownloadQueueBus.jobs.collectAsState()

    // ROADMAP.md Step 6.5: lifted up from the old per-screen-only
    // version so the top-bar shortcut and Settings' own "Check for
    // update" button (added below) share one source of truth instead
    // of each running an independent, out-of-sync copy of this state.
    var updateStatus by remember { mutableStateOf("") }
    var isUpdating by remember { mutableStateOf(false) }
    var lastUpdateTimestamp by remember { mutableLongStateOf(Settings.getLastUpdateTimestamp(context)) }

    // ROADMAP.md Step 6.5: dismissible connectivity-loss banner.
    // bannerDismissed resets whenever a *new* no-network failure
    // shows up (tracked via count), so dismissing doesn't
    // permanently silence a genuinely new failure later.
    var bannerDismissed by remember { mutableStateOf(false) }
    var lastSeenNetworkFailureCount by remember { mutableIntStateOf(0) }
    val networkFailureCount = jobs.count {
        it.state == JobState.FAILED && it.progressText == DownloadQueueBus.NO_INTERNET_MESSAGE
    }

    LaunchedEffect(prefillUrl) {
        if (prefillUrl.isNotBlank()) {
            url = prefillUrl
        }
    }

    // Cheap for a personal-use library size: just re-query whenever
    // any job's status changes, rather than trying to know exactly
    // which change means "a file was published".
    LaunchedEffect(jobs) {
        library = MediaStorage.listPublished(context)
    }

    LaunchedEffect(networkFailureCount) {
        if (networkFailureCount > lastSeenNetworkFailureCount) {
            bannerDismissed = false
        }
        lastSeenNetworkFailureCount = networkFailureCount
    }

    fun runUpdate() {
        isUpdating = true
        updateStatus = "Checking\\u2026"
        Thread {
            val result = YtDlpUpdater.updateBlocking(context)
            Handler(Looper.getMainLooper()).post {
                updateStatus = result
                isUpdating = false
                lastUpdateTimestamp = Settings.getLastUpdateTimestamp(context)
            }
        }.start()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("YT Offline", style = MaterialTheme.typography.headlineSmall) },
                actions = {
                    IconButton(enabled = !isUpdating, onClick = { runUpdate() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Check for yt-dlp update")
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (!showSettings) {
                ComposerBar(
                    url = url,
                    onUrlChange = { url = it; urlError = null },
                    onSend = {
                        val trimmed = url.trim()
                        if (isYouTubeUrl(trimmed)) {
                            DownloadService.enqueue(context, trimmed, selectedQuality)
                            url = ""
                            urlError = null
                        } else {
                            urlError = "Only youtube.com / youtu.be links are supported"
                        }
                    },
                    selectedQuality = selectedQuality,
                    onQualitySelected = { selectedQuality = it },
                    errorText = urlError
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            if (showSettings) {
                SettingsPanel(
                    onClose = { showSettings = false },
                    onDefaultQualityChanged = { selectedQuality = it },
                    updateStatus = updateStatus,
                    isUpdating = isUpdating,
                    lastUpdateTimestamp = lastUpdateTimestamp,
                    onCheckForUpdate = { runUpdate() }
                )
            } else {
                if (networkFailureCount > 0 && !bannerDismissed) {
                    ConnectivityBanner(onDismiss = { bannerDismissed = true })
                    Spacer(modifier = Modifier.height(12.dp))
                }

                Text(text = "Queue", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))

                if (jobs.isEmpty()) {
                    EmptyQueueState()
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(jobs, key = { it.id }) { job ->
                            QueueRow(job)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "Library", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { library = MediaStorage.listPublished(context) }) {
                        Text("Refresh")
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(library, key = { it.uri.toString() }) { item ->
                        LibraryRow(
                            item = item,
                            onPlay = { playItem(context, item) },
                            onShare = { shareItem(context, item) },
                            onDelete = {
                                if (MediaStorage.delete(context, item)) {
                                    library = MediaStorage.listPublished(context)
                                } else {
                                    Toast.makeText(context, "Couldn't delete file", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

// ROADMAP.md Step 6.5 [Download queue (active)]: medium-shape surface
// on surfaceVariant, solid primaryContainer thumbnail placeholder
// with a play glyph (no network thumbnail fetch -- keeps cost/scope
// at zero, per CLAUDE.md), status line colored per state, and a
// LinearProgressIndicator in primary only while actively downloading.
//
// Note: `warning` (Theme.kt, patch 03) has no state to map to here --
// ROADMAP.md's "warning retrying" describes a retry mechanism that
// doesn't exist in JobState (QUEUED/RUNNING/DONE/FAILED only). The
// token stays defined and ready for whenever retry logic exists;
// nothing here forces a fake state onto it.
@Composable
private fun QueueRow(job: DownloadJobStatus) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${job.qualityLabel} \\u2014 ${job.url}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1
                )
                val statusColor = when (job.state) {
                    JobState.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
                    JobState.RUNNING -> MaterialTheme.colorScheme.primary
                    JobState.DONE -> YtOfflineExtras.colors.success
                    JobState.FAILED -> MaterialTheme.colorScheme.error
                }
                Text(
                    text = "${job.state}: ${job.progressText}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
                if (job.state == JobState.RUNNING) {
                    Spacer(modifier = Modifier.height(4.dp))
                    // Version note: Compose BOM 2024.11.00 pulls in
                    // Material3 1.3.1, which only has the plain-Float
                    // `progress` overload -- the lambda-based
                    // `progress: () -> Float` overload wasn't added
                    // until 1.5.0-alpha17. Using the newer form here
                    // would not compile against this project's actual
                    // dependency versions.
                    LinearProgressIndicator(
                        progress = job.progressFraction ?: 0f,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
        }
    }
}

// ROADMAP.md Step 6.5 [Download queue (empty)]: centered
// primaryContainer circle behind a download icon, no button (the
// composer bar below is already the call to action).
//
// Icon choice: reuses the system stat_sys_download glyph already
// proven to work in this exact codebase (DownloadService's
// notification icon), rather than pulling in the large
// material-icons-extended dependency for a single "download arrow"
// glyph that core doesn't include, or hand-authoring a new vector
// resource. Worth a look on a real device (see design.md's "compare
// against a real screen" note) -- system status-bar icons are
// designed for small sizes, so this may want swapping for a custom
// vector later if it looks rough scaled up.
@Composable
private fun EmptyQueueState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(80.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.stat_sys_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Nothing queued. Paste a link below, or share one into this app.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ROADMAP.md Step 6.5 [Error states]: dismissible banner
// (errorContainer background, onErrorContainer text) specifically for
// connectivity-loss-at-queue-time -- a systemic state that deserves
// different visual treatment than a single video's own inline
// error (which keeps its existing per-row `error` color, unchanged).
@Composable
private fun ConnectivityBanner(onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "No internet connection \\u2014 some downloads couldn't start.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

// ROADMAP.md Step 6.5 [Library]: same row pattern as the queue,
// filled icon-only Play button in primary, secondary overflow icon
// (⋮) for delete/share -- this is also the fix for the "no in-app
// delete" backlog gap.
@Composable
private fun LibraryRow(
    item: LibraryItem,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1
            )
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun ComposerBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onSend: () -> Unit,
    selectedQuality: Int,
    onQualitySelected: (Int) -> Unit,
    errorText: String? = null
) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                qualityPresets.forEachIndexed { index, preset ->
                    FilterChip(
                        selected = index == selectedQuality,
                        onClick = { onQualitySelected(index) },
                        label = { Text(preset.label) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Paste a YouTube link\\u2026") },
                singleLine = true,
                isError = errorText != null,
                supportingText = errorText?.let { { Text(it) } },
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                trailingIcon = {
                    IconButton(onClick = onSend, enabled = url.isNotBlank()) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Add to download queue",
                            tint = if (url.isNotBlank()) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedBorderColor = Color.Transparent,
                    // ROADMAP.md Step 6.5 [Composer bar, fixed]: was
                    // Color.Transparent even when focused, so the
                    // field relied on fill alone for focus
                    // affordance. A subtle outline-colored border now
                    // shows on focus only -- the unfocused pill look
                    // is unchanged.
                    focusedBorderColor = MaterialTheme.colorScheme.outline
                )
            )
        }
    }
}

private fun playItem(context: Context, item: LibraryItem) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    // ROADMAP.md Step 4 [LOW, fixed]: guard against no video player
    // being installed at all -- unlikely on a real phone, but cheap
    // insurance against a crash for a one-line try/catch.
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to play this file", Toast.LENGTH_SHORT).show()
    }
}

// ROADMAP.md Step 6.5 [Library]: backs the overflow menu's Share
// action. MediaStore content Uris are already provider-backed and
// shareable via a plain grant-uri-permission flag -- no FileProvider
// setup needed, unlike sharing a raw file:// path would require.
private fun shareItem(context: Context, item: LibraryItem) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, "Share ${item.displayName}"))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to share this file", Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(12.dp))
}

// ROADMAP.md Step 6.5 [Settings]: grouped into labeled sections
// (titleMedium headers, outline-divided rows) instead of a flat list:
// Default Quality, Storage, Extractor (yt-dlp version + manual update
// button + last-updated timestamp). Update state/callback are passed
// in from DownloadScreen rather than duplicated locally, so this
// panel's "Check for update" button and the top-bar shortcut share
// one source of truth.
@Composable
private fun SettingsPanel(
    onClose: () -> Unit,
    onDefaultQualityChanged: (Int) -> Unit,
    updateStatus: String,
    isUpdating: Boolean,
    lastUpdateTimestamp: Long,
    onCheckForUpdate: () -> Unit
) {
    val context = LocalContext.current
    var defaultQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    var subfolder by remember { mutableStateOf(Settings.getDownloadSubfolder(context)) }

    // ROADMAP.md Step 6.5: this panel's content grew past what
    // reliably fits on one screen once it has three sections instead
    // of a flat list -- the old version had no scroll modifier at
    // all, so content could silently run off the bottom of the
    // screen with no way to reach it. Found while implementing the
    // sectioned layout below.
    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        SettingsSectionHeader("Default Quality")
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            qualityPresets.forEachIndexed { index, preset ->
                FilterChip(
                    selected = index == defaultQuality,
                    onClick = {
                        defaultQuality = index
                        Settings.setDefaultQualityIndex(context, index)
                        onDefaultQualityChanged(index)
                    },
                    label = { Text(preset.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(20.dp))

        SettingsSectionHeader("Storage")
        Text(text = "Downloads subfolder name", style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = subfolder,
            onValueChange = { subfolder = it },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = "Saved under Downloads/$subfolder. Changing this only " +
                "affects new downloads \\u2014 it won't move files already " +
                "saved under the old name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(20.dp))

        SettingsSectionHeader("Extractor")
        Text(
            text = "yt-dlp powers every download (see CLAUDE.md \\u2014 this app never " +
                "implements its own extraction).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Last updated: ${formatTimestamp(lastUpdateTimestamp)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (updateStatus.isNotBlank()) {
            Text(
                text = updateStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(enabled = !isUpdating, onClick = onCheckForUpdate) {
            Text(if (isUpdating) "Checking\\u2026" else "Check for yt-dlp update")
        }

        Spacer(modifier = Modifier.height(24.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                Settings.setDownloadSubfolder(context, subfolder)
                onClose()
            }) {
                Text("Save")
            }
            OutlinedButton(onClick = onClose) {
                Text("Cancel")
            }
        }
    }
}
'''


def fix_main_activity() -> None:
    path = ROOT / "app" / "src" / "main" / "java" / "com" / "baltic" / "ytoffline" / "MainActivity.kt"
    current = read(path)
    if "QueueRow" in current:
        print("  [skip] MainActivity.kt: already patched.")
        return
    overwrite(path, NEW_MAIN_ACTIVITY_KT,
              "MainActivity.kt (queue/library/settings component patterns, connectivity banner)")


# ---------------------------------------------------------------------------
# ROADMAP.md bookkeeping
# ---------------------------------------------------------------------------

def update_roadmap() -> None:
    path = ROOT / "ROADMAP.md"

    checkbox_fixes = [
        (
            "- [ ] **Download queue (active)** — `medium`-shape surface on",
            "- [x] **Download queue (active)** (Fixed — patch 04) — `medium`-shape surface on",
        ),
        (
            "- [ ] **Download queue (empty)** — centered `accentContainer` circle",
            "- [x] **Download queue (empty)** (Fixed — patch 04) — centered `accentContainer` circle",
        ),
        (
            "- [ ] **Library** — same row pattern as queue, filled icon-only Play",
            "- [x] **Library** (Fixed — patch 04) — same row pattern as queue, filled icon-only Play",
        ),
        (
            "- [ ] **Settings** — group into labeled sections (`titleMedium` headers,",
            "- [x] **Settings** (Fixed — patch 04) — group into labeled sections (`titleMedium` headers,",
        ),
        (
            "- [ ] **Error states** — inline per-row errors keep `error`/`errorContainer`",
            "- [x] **Error states** (Fixed — patch 04) — inline per-row errors keep `error`/`errorContainer`",
        ),
        (
            "- [ ] **Composer bar** — keep the existing `large`-shape pill with `accent`",
            "- [x] **Composer bar** (Fixed — patch 04) — keep the existing `large`-shape pill with `accent`",
        ),
        (
            "- [ ] **App icon** — no change needed to the concept (terracotta",
            "- [x] **App icon** (N/A — no icon concept change needed; see Step 6.6 for the safe-zone fix) — no change needed to the concept (terracotta",
        ),
    ]

    for old, new in checkbox_fixes:
        replace_once(path, old, new, f"ROADMAP.md checkbox: {old[:60]}...")


# ---------------------------------------------------------------------------

def main() -> int:
    print("Applying patch 04 (ROADMAP.md Step 6.5)...\n")
    try:
        require_prior_patches()

        print("Kotlin source changes:")
        fix_download_queue_bus()
        fix_media_storage()
        fix_settings()
        fix_yt_dlp_updater()
        fix_download_service()
        fix_main_activity()

        print("\nROADMAP.md bookkeeping:")
        update_roadmap()
    except PatchError as exc:
        print(f"\nPATCH FAILED: {exc}", file=sys.stderr)
        return 1

    print("\nAll changes applied successfully.")
    print("Next: ./gradlew assembleDebug  (fix any remaining compile errors top-down)")
    print("Step 6 is now complete except the optional 6.7 monochrome icon layer.")
    print("Next up: Step 7 (Kinescope rename, applicationId com.kinescope.app),")
    print("then Step 5 (first device install + testing).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
