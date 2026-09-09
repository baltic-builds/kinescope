package com.baltic.ytoffline

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
    Regex("""https?://\S+""").findAll(text).map { it.value }.firstOrNull { isYouTubeUrl(it) }

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
        updateStatus = "Checking\u2026"
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
                    text = "${job.qualityLabel} \u2014 ${job.url}",
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
                text = "No internet connection \u2014 some downloads couldn't start.",
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
                placeholder = { Text("Paste a YouTube link\u2026") },
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
                "affects new downloads \u2014 it won't move files already " +
                "saved under the old name.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        Spacer(modifier = Modifier.height(20.dp))

        SettingsSectionHeader("Extractor")
        Text(
            text = "yt-dlp powers every download (see CLAUDE.md \u2014 this app never " +
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
            Text(if (isUpdating) "Checking\u2026" else "Check for yt-dlp update")
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
