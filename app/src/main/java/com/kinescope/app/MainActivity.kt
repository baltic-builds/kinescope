package com.kinescope.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf("")

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Downloads still work without notifications permission.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            YtOfflineTheme {
                KinescopeApp(prefillUrl = sharedUrl.value)
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
            extractUrl(sharedText)?.let {
                sharedUrl.value = it
                AppLog.i("MainActivity", "Received shared YouTube URL")
            }
        }
    }
}

private enum class AppSection { HOME, QUICK_ADD, SETTINGS, LOGS, YOUTUBE_AUTH }

private val YOUTUBE_HOSTS = setOf(
    "youtube.com", "www.youtube.com", "m.youtube.com", "music.youtube.com", "youtu.be", "www.youtu.be"
)

private fun isYouTubeUrl(url: String): Boolean =
    runCatching { Uri.parse(url).host?.lowercase() }.getOrNull() in YOUTUBE_HOSTS

private fun extractUrl(text: String): String? =
    Regex("""https?://\S+""").findAll(text).map { it.value }.firstOrNull { isYouTubeUrl(it) }

private fun formatTimestamp(millis: Long): String {
    if (millis == 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}

private fun clipboardYouTubeUrl(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    return extractUrl(text)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KinescopeApp(prefillUrl: String) {
    val context = LocalContext.current
    val jobs by DownloadQueueBus.jobs.collectAsState()

    var section by remember { mutableStateOf(AppSection.HOME) }
    var url by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var selectedQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    var library by remember { mutableStateOf(MediaStorage.listPublished(context)) }
    var updateStatus by remember { mutableStateOf("") }
    var isUpdating by remember { mutableStateOf(false) }
    var lastUpdateTimestamp by remember { mutableLongStateOf(Settings.getLastUpdateTimestamp(context)) }
    var settingsTapCount by remember { mutableIntStateOf(0) }
    var lastSettingsTapAt by remember { mutableLongStateOf(0L) }

    LaunchedEffect(prefillUrl) {
        if (prefillUrl.isNotBlank()) {
            url = prefillUrl
            urlError = null
            section = AppSection.QUICK_ADD
        }
    }

    LaunchedEffect(jobs) {
        library = MediaStorage.listPublished(context)
    }

    fun runUpdate() {
        isUpdating = true
        updateStatus = context.getString(R.string.update_checking)
        Thread {
            val result = YtDlpUpdater.updateBlocking(context)
            Handler(Looper.getMainLooper()).post {
                updateStatus = result
                isUpdating = false
                lastUpdateTimestamp = Settings.getLastUpdateTimestamp(context)
            }
        }.start()
    }

    fun openQuickAdd() {
        clipboardYouTubeUrl(context)?.let {
            url = it
            urlError = null
        }
        section = AppSection.QUICK_ADD
    }

    fun openSettingsWithSecretTap() {
        val now = SystemClock.elapsedRealtime()
        val count = if (now - lastSettingsTapAt <= 650L) settingsTapCount + 1 else 1
        lastSettingsTapAt = now
        settingsTapCount = count
        if (count >= 5) {
            settingsTapCount = 0
            section = AppSection.LOGS
            AppLog.i("MainActivity", "Hidden log journal opened")
        } else {
            section = AppSection.SETTINGS
        }
    }

    BackHandler(enabled = section != AppSection.HOME) {
        section = when (section) {
            AppSection.LOGS, AppSection.YOUTUBE_AUTH -> AppSection.SETTINGS
            else -> AppSection.HOME
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = when (section) {
                            AppSection.HOME -> stringResource(R.string.app_name)
                            AppSection.QUICK_ADD -> stringResource(R.string.quick_add_title)
                            AppSection.SETTINGS -> stringResource(R.string.settings_title)
                            AppSection.LOGS -> stringResource(R.string.logs_title)
                            AppSection.YOUTUBE_AUTH -> stringResource(R.string.youtube_login_title)
                        },
                        style = MaterialTheme.typography.headlineSmall
                    )
                },
                navigationIcon = {
                    if (section != AppSection.HOME) {
                        IconButton(onClick = {
                            section = if (section == AppSection.LOGS || section == AppSection.YOUTUBE_AUTH) {
                                AppSection.SETTINGS
                            } else {
                                AppSection.HOME
                            }
                        }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            if (section != AppSection.YOUTUBE_AUTH) {
                GlassBottomBar(
                    section = section,
                    onHome = { section = AppSection.HOME },
                    onQuickAdd = { openQuickAdd() },
                    onSettings = { openSettingsWithSecretTap() }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 20.dp)
        ) {
            when (section) {
                AppSection.HOME -> HomeScreen(
                    jobs = jobs,
                    library = library,
                    onRefreshLibrary = { library = MediaStorage.listPublished(context) },
                    onPlay = { playItem(context, it) },
                    onShare = { shareItem(context, it) },
                    onDelete = { item ->
                        if (MediaStorage.delete(context, item)) {
                            AppLog.i("Library", "Deleted ${item.displayName}")
                            library = MediaStorage.listPublished(context)
                        } else {
                            Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                            AppLog.w("Library", "Delete failed for ${item.displayName}")
                        }
                    },
                    onPause = { DownloadService.pause(context, it) },
                    onResume = { DownloadService.resume(context, it) },
                    onStop = { DownloadService.stop(context, it) },
                    onSignIn = { section = AppSection.YOUTUBE_AUTH }
                )
                AppSection.QUICK_ADD -> QuickAddScreen(
                    url = url,
                    onUrlChange = { url = it; urlError = null },
                    selectedQuality = selectedQuality,
                    onQualitySelected = { selectedQuality = it },
                    errorText = urlError,
                    onDownload = {
                        val trimmed = url.trim()
                        if (isYouTubeUrl(trimmed)) {
                            DownloadService.enqueue(context, trimmed, selectedQuality)
                            url = ""
                            urlError = null
                            section = AppSection.HOME
                        } else {
                            urlError = context.getString(R.string.error_only_youtube)
                        }
                    }
                )
                AppSection.SETTINGS -> SettingsScreen(
                    onDefaultQualityChanged = { selectedQuality = it },
                    updateStatus = updateStatus,
                    isUpdating = isUpdating,
                    lastUpdateTimestamp = lastUpdateTimestamp,
                    onCheckForUpdate = { runUpdate() },
                    onOpenYouTubeLogin = { section = AppSection.YOUTUBE_AUTH }
                )
                AppSection.LOGS -> LogsScreen()
                AppSection.YOUTUBE_AUTH -> YouTubeLoginScreen(
                    onBack = { section = AppSection.SETTINGS },
                    onSaved = {
                        val verificationJobs = jobs.filter {
                            it.state == JobState.PAUSED &&
                                it.failureKind == FailureKind.YOUTUBE_VERIFICATION
                        }
                        verificationJobs.forEach { DownloadService.resume(context, it.id) }
                        section = if (verificationJobs.isNotEmpty()) AppSection.HOME else AppSection.SETTINGS
                    }
                )
            }
        }
    }
}

@Composable
private fun GlassBottomBar(
    section: AppSection,
    onHome: () -> Unit,
    onQuickAdd: () -> Unit,
    onSettings: () -> Unit
) {
    Surface(color = Color.Transparent) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f),
                    shape = MaterialTheme.shapes.extraLarge
                ),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.88f),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GlassNavIcon(
                    selected = section == AppSection.HOME,
                    onClick = onHome
                ) {
                    Icon(Icons.Default.Home, contentDescription = stringResource(R.string.cd_home))
                }

                Surface(
                    modifier = Modifier
                        .size(56.dp)
                        .clickable(onClick = onQuickAdd),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
                    tonalElevation = 6.dp,
                    shadowElevation = 8.dp
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = stringResource(R.string.cd_quick_add),
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }

                GlassNavIcon(
                    selected = section == AppSection.SETTINGS || section == AppSection.LOGS,
                    onClick = onSettings
                ) {
                    Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.cd_settings))
                }
            }
        }
    }
}

@Composable
private fun GlassNavIcon(
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.78f)
                else Color.Transparent
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            icon()
        }
    }
}

@Composable
private fun HomeScreen(
    jobs: List<DownloadJobStatus>,
    library: List<LibraryItem>,
    onRefreshLibrary: () -> Unit,
    onPlay: (LibraryItem) -> Unit,
    onShare: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit,
    onSignIn: () -> Unit
) {
    val networkFailure = jobs.any { it.state == JobState.FAILED && it.failureKind == FailureKind.NO_INTERNET }
    val verificationFailure = jobs.any {
        it.failureKind == FailureKind.YOUTUBE_VERIFICATION &&
            (it.state == JobState.PAUSED || it.state == JobState.FAILED)
    }
    var networkBannerDismissed by remember { mutableStateOf(false) }
    var verificationBannerDismissed by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        if (networkFailure && !networkBannerDismissed) {
            item {
                ErrorBanner(
                    text = stringResource(R.string.banner_no_internet),
                    onDismiss = { networkBannerDismissed = true }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
        if (verificationFailure && !verificationBannerDismissed) {
            item {
                VerificationBanner(
                    onDismiss = { verificationBannerDismissed = true },
                    onSignIn = onSignIn
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        item {
            Text(text = stringResource(R.string.queue_title), style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (jobs.isEmpty()) {
            item { EmptyQueueState() }
        } else {
            items(jobs, key = { it.id }) { job ->
                QueueRow(job, onPause, onResume, onStop)
            }
        }

        item {
            Spacer(modifier = Modifier.height(20.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = stringResource(R.string.library_title), style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onRefreshLibrary) { Text(stringResource(R.string.refresh)) }
            }
        }

        items(library, key = { it.uri.toString() }) { item ->
            LibraryRow(
                item = item,
                onPlay = { onPlay(item) },
                onShare = { onShare(item) },
                onDelete = { onDelete(item) }
            )
        }
    }
}

@Composable
private fun QueueRow(
    job: DownloadJobStatus,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit
) {
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
                    text = "${job.qualityLabel} — ${job.url}",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val statusColor = when (job.state) {
                    JobState.QUEUED, JobState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                    JobState.RUNNING -> MaterialTheme.colorScheme.primary
                    JobState.PAUSED -> YtOfflineExtras.colors.warning
                    JobState.DONE -> YtOfflineExtras.colors.success
                    JobState.FAILED -> MaterialTheme.colorScheme.error
                }
                Text(
                    text = stringResource(
                        R.string.job_status_line,
                        jobStateLabel(job.state),
                        job.progressText
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = statusColor
                )
                if (job.state == JobState.RUNNING) {
                    Spacer(modifier = Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = job.progressFraction ?: 0f,
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surface
                    )
                }
            }
            when (job.state) {
                JobState.RUNNING -> {
                    IconButton(onClick = { onPause(job.id) }) {
                        Icon(painterResource(R.drawable.ic_pause), contentDescription = stringResource(R.string.cd_pause))
                    }
                    IconButton(onClick = { onStop(job.id) }) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.cd_stop))
                    }
                }
                JobState.PAUSED -> {
                    IconButton(onClick = { onResume(job.id) }) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_resume))
                    }
                    IconButton(onClick = { onStop(job.id) }) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.cd_stop))
                    }
                }
                JobState.QUEUED -> {
                    IconButton(onClick = { onStop(job.id) }) {
                        Icon(painterResource(R.drawable.ic_stop), contentDescription = stringResource(R.string.cd_stop))
                    }
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun jobStateLabel(state: JobState): String = stringResource(
    when (state) {
        JobState.QUEUED -> R.string.job_state_queued
        JobState.RUNNING -> R.string.job_state_running
        JobState.PAUSED -> R.string.job_state_paused
        JobState.DONE -> R.string.job_state_done
        JobState.FAILED -> R.string.job_state_failed
        JobState.STOPPED -> R.string.job_state_stopped
    }
)

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
                painter = painterResource(id = R.drawable.ic_download),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.empty_queue),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorBanner(text: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.errorContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_dismiss))
            }
        }
    }
}

@Composable
private fun VerificationBanner(onDismiss: () -> Unit, onSignIn: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.banner_youtube_verification),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_dismiss))
                }
            }
            TextButton(onClick = onSignIn) { Text(stringResource(R.string.youtube_sign_in_action)) }
        }
    }
}

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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item.displayName,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onPlay) {
                Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_play), tint = MaterialTheme.colorScheme.primary)
            }
            Box {
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_more_options))
                }
                DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.share)) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null) },
                        onClick = { menuExpanded = false; onShare() }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menuExpanded = false; onDelete() }
                    )
                }
            }
        }
    }
}

@Composable
private fun QuickAddScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    selectedQuality: Int,
    onQualitySelected: (Int) -> Unit,
    errorText: String?,
    onDownload: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = 12.dp)
    ) {
        Text(stringResource(R.string.quick_add_hint), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.youtube_link_placeholder)) },
            singleLine = true,
            isError = errorText != null,
            supportingText = errorText?.let { { Text(it) } },
            shape = MaterialTheme.shapes.extraLarge,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.outline
            )
        )
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            qualityPresets.forEachIndexed { index, preset ->
                FilterChip(
                    selected = index == selectedQuality,
                    onClick = { onQualitySelected(index) },
                    label = { Text(stringResource(preset.labelRes)) }
                )
            }
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = onDownload,
            enabled = url.isNotBlank(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.start_download))
        }
    }
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
private fun SettingsScreen(
    onDefaultQualityChanged: (Int) -> Unit,
    updateStatus: String,
    isUpdating: Boolean,
    lastUpdateTimestamp: Long,
    onCheckForUpdate: () -> Unit,
    onOpenYouTubeLogin: () -> Unit
) {
    val context = LocalContext.current
    var defaultQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    var subfolder by remember { mutableStateOf(Settings.getDownloadSubfolder(context)) }
    var hasSession by remember { mutableStateOf(YouTubeAuth.hasSavedSession(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        SettingsSectionHeader(stringResource(R.string.settings_default_quality))
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
                    label = { Text(stringResource(preset.labelRes)) }
                )
            }
        }

        SettingsDivider()
        SettingsSectionHeader(stringResource(R.string.settings_storage))
        Text(stringResource(R.string.downloads_subfolder), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = subfolder,
            onValueChange = { subfolder = it },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.storage_hint, subfolder),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(onClick = {
            Settings.setDownloadSubfolder(context, subfolder)
            subfolder = Settings.getDownloadSubfolder(context)
            Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
        }) {
            Text(stringResource(R.string.save))
        }

        SettingsDivider()
        SettingsSectionHeader(stringResource(R.string.settings_extractor))
        Text(
            text = stringResource(R.string.extractor_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = if (lastUpdateTimestamp == 0L) {
                stringResource(R.string.last_updated_never)
            } else {
                stringResource(R.string.last_updated, formatTimestamp(lastUpdateTimestamp))
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (updateStatus.isNotBlank()) {
            Text(updateStatus, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(enabled = !isUpdating, onClick = onCheckForUpdate) {
            Text(if (isUpdating) stringResource(R.string.update_checking) else stringResource(R.string.check_for_update))
        }

        SettingsDivider()
        SettingsSectionHeader(stringResource(R.string.settings_youtube_account))
        Text(
            text = if (hasSession) stringResource(R.string.youtube_session_saved) else stringResource(R.string.youtube_session_not_saved),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.youtube_login_warning),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onOpenYouTubeLogin) {
                Text(stringResource(if (hasSession) R.string.youtube_refresh_session else R.string.youtube_sign_in_action))
            }
            if (hasSession) {
                OutlinedButton(onClick = {
                    YouTubeAuth.clearSession(context) {
                        Handler(Looper.getMainLooper()).post { hasSession = false }
                    }
                }) {
                    Text(stringResource(R.string.youtube_sign_out))
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(modifier = Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(modifier = Modifier.height(20.dp))
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeLoginScreen(onBack: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var status by remember { mutableStateOf("") }

    BackHandler(enabled = true) {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) webView.goBack() else onBack()
    }

    DisposableEffect(Unit) {
        onDispose { webViewRef?.destroy() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.youtube_login_instructions),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val result = YouTubeAuth.captureCurrentSession(
                    context = context,
                    userAgent = webViewRef?.settings?.userAgentString
                )
                status = if (result.looksSignedIn) {
                    context.getString(R.string.youtube_session_captured)
                } else {
                    context.getString(R.string.youtube_session_capture_uncertain)
                }
                if (result.looksSignedIn) onSaved()
            }) {
                Text(stringResource(R.string.youtube_use_session))
            }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.cancel)) }
        }
        if (status.isNotBlank()) {
            Text(status, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.height(8.dp))
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { webContext ->
                WebView(webContext).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.safeBrowsingEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = WebChromeClient()
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val uri = request?.url ?: return false
                            if (uri.scheme == "http" || uri.scheme == "https") return false
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                            }
                            return true
                        }
                    }
                    loadUrl("https://www.youtube.com/")
                    webViewRef = this
                }
            }
        )
    }
}

@Composable
private fun LogsScreen() {
    val context = LocalContext.current
    var lines by remember { mutableStateOf(AppLog.readLines()) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = { lines = AppLog.readLines() }) {
                Text(stringResource(R.string.refresh))
            }
            OutlinedButton(onClick = {
                AppLog.clear()
                lines = emptyList()
            }) {
                Text(stringResource(R.string.clear_logs))
            }
            Button(onClick = { shareLogs(context, lines) }, enabled = lines.isNotEmpty()) {
                Text(stringResource(R.string.share_logs))
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        if (lines.isEmpty()) {
            Text(stringResource(R.string.logs_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                    )
                }
            }
        }
    }
}

private fun playItem(context: Context, item: LibraryItem) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(item.uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
        AppLog.i("Library", "Opened ${item.displayName}")
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_player, Toast.LENGTH_SHORT).show()
        AppLog.e("Library", "No player for ${item.displayName}", e)
    }
}

private fun shareItem(context: Context, item: LibraryItem) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, item.uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_file, item.displayName)))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_share_app, Toast.LENGTH_SHORT).show()
        AppLog.e("Library", "No share target for ${item.displayName}", e)
    }
}

private fun shareLogs(context: Context, lines: List<String>) {
    val payload = lines.joinToString("\n")
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, payload)
        putExtra(Intent.EXTRA_TITLE, context.getString(R.string.logs_title))
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_logs)))
    }.onFailure { AppLog.e("Logs", "Could not open share sheet", it) }
}
