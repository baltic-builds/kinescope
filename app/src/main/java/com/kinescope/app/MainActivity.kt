package com.kinescope.app

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf("")

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Downloads still work without notifications permission.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Patch 32: one edge-to-edge model on every supported Android version (Android 15+
        // enforces it for targetSdk 35 anyway) plus system-bar icon contrast that follows the
        // system dark mode, which is what YtOfflineTheme follows. Must run before super.onCreate.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            YtOfflineTheme {
                KinescopeApp(
                    prefillUrl = sharedUrl.value,
                    requestNotifications = ::requestNotificationsIfNeeded
                )
            }
        }
    }

    private fun requestNotificationsIfNeeded() {
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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
            YouTubeUrlParser.firstFromText(sharedText)?.canonicalUrl?.let {
                sharedUrl.value = it
                AppLog.i("MainActivity", "Received shared YouTube URL")
            }
        }
    }
}

private enum class AppSection { HOME, QUICK_ADD, SETTINGS, LOGS, YOUTUBE_AUTH }

private fun formatTimestamp(millis: Long): String {
    if (millis == 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}

private fun clipboardYouTubeUrl(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    return YouTubeUrlParser.firstFromText(text)?.canonicalUrl
}

private fun openYouTubeApp(context: Context): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(BypassVpnService.YOUTUBE_PACKAGE) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching {
        context.startActivity(intent)
        true
    }.getOrElse {
        AppLog.e("MainActivity", "Could not open YouTube", it)
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KinescopeApp(prefillUrl: String, requestNotifications: () -> Unit) {
    val context = LocalContext.current
    val jobs by DownloadQueueBus.jobs.collectAsState()
    val scope = rememberCoroutineScope()
    val bypassState by BypassVpnController.state.collectAsState()
    val searchState by DpiSearchController.state.collectAsState()
    var showFirstRunNotice by remember { mutableStateOf(false) }

    var section by remember { mutableStateOf(AppSection.HOME) }
    var url by remember { mutableStateOf("") }
    var urlError by remember { mutableStateOf<String?>(null) }
    var selectedQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    var library by remember { mutableStateOf<List<LibraryItem>>(emptyList()) }
    var updateStatus by remember { mutableStateOf("") }
    var isUpdating by remember { mutableStateOf(false) }
    var lastUpdateTimestamp by remember { mutableLongStateOf(Settings.getLastUpdateTimestamp(context)) }
    var settingsTapCount by remember { mutableIntStateOf(0) }
    var lastSettingsTapAt by remember { mutableLongStateOf(0L) }
    var openYouTubeWhenReady by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            openYouTubeWhenReady = BypassVpnService.start(context)
            if (!openYouTubeWhenReady) Toast.makeText(context, R.string.bypass_vpn_tunnel_failed, Toast.LENGTH_SHORT).show()
        } else {
            openYouTubeWhenReady = false
            Toast.makeText(context, R.string.bypass_vpn_permission_denied, Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(prefillUrl) {
        if (prefillUrl.isNotBlank()) {
            url = prefillUrl
            urlError = null
            section = AppSection.QUICK_ADD
        }
    }

    // Patch 33: on a fresh install the check of all bundled strategies starts by itself, with a
    // popup right away (shown once per install). It counts as done only when a search finishes,
    // so an interrupted first check simply restarts on the next launch, without the popup.
    LaunchedEffect(Unit) {
        if (
            !DpiPrefs.hasRunInitialSearch(context) &&
            DpiStrategyStore.verifiedChain(context).isEmpty() &&
            !DpiSearchController.state.value.running
        ) {
            if (!DpiPrefs.hasShownFirstRunNotice(context)) {
                DpiPrefs.setFirstRunNoticeShown(context)
                showFirstRunNotice = true
            }
            DpiSearchService.start(context)
        }
    }
    LaunchedEffect(searchState.running, searchState.resultMessageRes) {
        if (!searchState.running && searchState.resultMessageRes != null) showFirstRunNotice = false
    }

    // Patch 35: the first launch of a new build re-checks all strategies quietly, once. No popup:
    // the previous strategies keep serving downloads and the tunnel while it runs (see DpiSearchService).
    LaunchedEffect(Unit) {
        val version = DpiSearchService.appVersionCode(context)
        if (
            DpiPrefs.hasRunInitialSearch(context) &&
            DpiPrefs.lastSearchVersion(context) != version &&
            !DpiSearchController.state.value.running
        ) {
            DpiSearchService.startBackground(context)
        }
    }

    suspend fun loadLibrary() {
        library = withContext(Dispatchers.IO) { MediaStorage.listPublished(context) }
    }

    val completedCount = jobs.count { it.state == JobState.DONE }
    LaunchedEffect(completedCount) { loadLibrary() }

    fun refreshLibrary() {
        scope.launch { loadLibrary() }
    }

    fun runUpdate() {
        isUpdating = true
        val engineBusy = jobs.any { it.state in setOf(JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING, JobState.SAVING) }
        updateStatus = context.getString(if (engineBusy) R.string.update_waiting_for_idle else R.string.update_checking)
        scope.launch {
            val result = withContext(Dispatchers.IO) { YtDlpUpdater.updateBlocking(context) }
            updateStatus = if (result.startsWith("ERROR:")) {
                context.getString(R.string.update_failed, result.removePrefix("ERROR:").ifBlank { context.getString(R.string.error_unknown) })
            } else {
                context.getString(R.string.update_done)
            }
            isUpdating = false
            lastUpdateTimestamp = Settings.getLastUpdateTimestamp(context)
        }
    }

    fun openQuickAdd() {
        clipboardYouTubeUrl(context)?.let {
            url = it
            urlError = null
        }
        section = AppSection.QUICK_ADD
    }

    fun openByeDpiYouTube() {
        val strategy = DpiStrategyStore.selected(context)
        if (!DpiPrefs.isStrategyVerified(context, strategy)) {
            section = AppSection.SETTINGS
            Toast.makeText(context, R.string.bypass_need_test, Toast.LENGTH_SHORT).show()
            return
        }
        if (bypassState.active) {
            if (!openYouTubeApp(context)) Toast.makeText(context, R.string.bypass_vpn_youtube_missing, Toast.LENGTH_SHORT).show()
            return
        }
        if (bypassState.starting) return
        // Patch 30: request the notification permission (if not already granted) before this
        // starts BypassVpnService's own foreground-service notification, not only on the first
        // accepted download (patch 25) -- otherwise that notification can silently never show.
        requestNotifications()
        val permissionIntent = VpnService.prepare(context)
        if (permissionIntent == null) {
            openYouTubeWhenReady = BypassVpnService.start(context)
            if (!openYouTubeWhenReady) Toast.makeText(context, R.string.bypass_vpn_tunnel_failed, Toast.LENGTH_SHORT).show()
        } else {
            openYouTubeWhenReady = true
            vpnPermissionLauncher.launch(permissionIntent)
        }
    }

    LaunchedEffect(bypassState.active, bypassState.errorRes, openYouTubeWhenReady) {
        if (bypassState.active && openYouTubeWhenReady) {
            openYouTubeWhenReady = false
            if (!openYouTubeApp(context)) Toast.makeText(context, R.string.bypass_vpn_youtube_missing, Toast.LENGTH_SHORT).show()
        } else if (bypassState.errorRes != null && openYouTubeWhenReady) {
            val errorRes = bypassState.errorRes ?: return@LaunchedEffect
            openYouTubeWhenReady = false
            Toast.makeText(context, errorRes, Toast.LENGTH_LONG).show()
        }
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

    if (showFirstRunNotice) {
        FirstRunNoticeDialog(
            progress = searchState.progress,
            onDismiss = {
                showFirstRunNotice = false
                requestNotifications()
            }
        )
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
                // Patch 31: the small top-bar bypass action was easy to miss (a plain
                // TextButton tucked in the corner). It moved to a large, centered card at the
                // top of HomeScreen (BypassHomeCard) instead -- see CHANGELOG.md.
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
        ResponsiveContent(innerPadding) {
            when (section) {
                AppSection.HOME -> HomeScreen(
                    jobs = jobs,
                    library = library,
                    bypassState = bypassState,
                    onOpenBypass = { openByeDpiYouTube() },
                    onRefreshLibrary = { refreshLibrary() },
                    onPlay = { playItem(context, it) },
                    onShare = { shareItem(context, it) },
                    onDelete = { item ->
                        scope.launch {
                            val deleted = withContext(Dispatchers.IO) { MediaStorage.delete(context, item) }
                            if (deleted) {
                                AppLog.i("Library", "Deleted library item")
                                loadLibrary()
                            } else {
                                Toast.makeText(context, R.string.delete_failed, Toast.LENGTH_SHORT).show()
                            }
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
                        when (DownloadService.enqueue(context, url, selectedQuality)) {
                            DownloadService.EnqueueResult.ACCEPTED -> {
                                requestNotifications()
                                url = ""
                                urlError = null
                                section = AppSection.HOME
                            }
                            DownloadService.EnqueueResult.DUPLICATE -> {
                                urlError = context.getString(R.string.error_duplicate_job)
                            }
                            DownloadService.EnqueueResult.INVALID -> {
                                urlError = context.getString(R.string.error_only_youtube_video)
                            }
                            DownloadService.EnqueueResult.START_FAILED -> {
                                urlError = context.getString(R.string.error_service_start)
                            }
                        }
                    }
                )
                AppSection.SETTINGS -> SettingsScreen(
                    onDefaultQualityChanged = { selectedQuality = it },
                    updateStatus = updateStatus,
                    isUpdating = isUpdating,
                    lastUpdateTimestamp = lastUpdateTimestamp,
                    onCheckForUpdate = { runUpdate() },
                    onOpenYouTubeLogin = { section = AppSection.YOUTUBE_AUTH },
                    requestNotifications = requestNotifications
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

/**
 * Places screen content inside the Scaffold's insets with the usual 20dp side margins.
 *
 * - Width: centered and capped at 640dp, so landscape, tablets and unfolded foldables do not
 *   stretch lists and cards into one unreadably wide column. On a phone in portrait the cap is
 *   never reached, so nothing changes there. (`widthIn` must come before `fillMaxSize`: the
 *   other order forces the full width first and the cap is ignored.)
 * - Keyboard: edge-to-edge windows are not resized for the keyboard, so `imePadding` lifts the
 *   content above it. `consumeWindowInsets(innerPadding)` first marks the space the Scaffold
 *   already reserved (top bar, bottom bar and system bars) as used, so only the remainder of the
 *   keyboard height is added instead of the whole of it.
 */
@Composable
private fun ResponsiveContent(innerPadding: PaddingValues, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(innerPadding)
            .consumeWindowInsets(innerPadding)
            .imePadding(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 640.dp)
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            content = content
        )
    }
}

/**
 * Patch 33: shown once on the first launch while the bundled strategies are checked. One language
 * only: the text comes from values/ (English) or values-ru/ (Russian) by the device language.
 */
@Composable
private fun FirstRunNoticeDialog(progress: SearchProgress?, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.first_run_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.first_run_body))
                if (progress != null && progress.total > 0) {
                    Text(
                        stringResource(
                            R.string.first_run_progress,
                            (progress.index + 1).coerceAtMost(progress.total),
                            progress.total
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        progress = progress.index.toFloat() / progress.total,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.first_run_ok)) }
        }
    )
}

@Composable
private fun GlassBottomBar(
    section: AppSection,
    onHome: () -> Unit,
    onQuickAdd: () -> Unit,
    onSettings: () -> Unit
) {
    // Patch 32: on Android 15+ (targetSdk 35) the system draws this app edge-to-edge, so a
    // hand-built composable like this one -- unlike Material3's NavigationBar/BottomAppBar,
    // which reserve their own insets -- must reserve space above the navigation bar itself, or
    // the classic three-button bar (opaque, ~48dp) is drawn over it. Gesture navigation's bar is
    // a thin transparent strip, which is why this only showed with gestures turned off.
    Surface(
        color = Color.Transparent,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            // Patch 33: the pill wraps its three buttons instead of stretching to a fixed
            // 330dp, which left a wide empty gap between them.
            Surface(
                modifier = Modifier
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.42f),
                        shape = MaterialTheme.shapes.extraLarge
                    ),
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                tonalElevation = 4.dp,
                shadowElevation = 6.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    GlassNavIcon(selected = section == AppSection.HOME, onClick = onHome) {
                        Icon(Icons.Default.Home, contentDescription = stringResource(R.string.cd_home))
                    }
                    Surface(
                        modifier = Modifier.size(48.dp).clickable(onClick = onQuickAdd),
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.94f),
                        tonalElevation = 3.dp,
                        shadowElevation = 4.dp
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = stringResource(R.string.cd_quick_add),
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(25.dp)
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
private fun BypassHomeCard(state: BypassVpnUiState, onClick: () -> Unit) {
    val errorRes = state.errorRes
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (state.active) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        tonalElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(
                    if (state.active) R.string.bypass_youtube_active else R.string.bypass_home_hint
                ),
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
                color = if (state.active) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )
            if (errorRes != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(errorRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onClick,
                enabled = !state.starting,
                modifier = Modifier.fillMaxWidth(0.85f),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(
                    text = stringResource(
                        when {
                            state.active -> R.string.bypass_open_youtube
                            state.starting -> R.string.bypass_starting_short
                            else -> R.string.bypass_quick_action
                        }
                    ),
                    style = MaterialTheme.typography.titleMedium
                )
            }
        }
    }
}

@Composable
private fun HomeScreen(
    jobs: List<DownloadJobStatus>,
    library: List<LibraryItem>,
    bypassState: BypassVpnUiState,
    onOpenBypass: () -> Unit,
    onRefreshLibrary: () -> Unit,
    onPlay: (LibraryItem) -> Unit,
    onShare: (LibraryItem) -> Unit,
    onDelete: (LibraryItem) -> Unit,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit,
    onSignIn: () -> Unit
) {
    val networkFailure = jobs.any {
        it.failureKind == FailureKind.NO_INTERNET && it.state in setOf(JobState.INTERRUPTED, JobState.FAILED)
    }
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
        item {
            // Patch 31: a large, obvious, centered card -- previously the only bypass control
            // on Home was a small TextButton tucked into the top bar's corner.
            BypassHomeCard(state = bypassState, onClick = onOpenBypass)
            Spacer(modifier = Modifier.height(16.dp))
        }
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

        if (library.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.library_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }
        } else {
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QueueRow(
    job: DownloadJobStatus,
    onPause: (String) -> Unit,
    onResume: (String) -> Unit,
    onStop: (String) -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.EndToStart && job.state != JobState.SAVING) {
                onStop(job.id)
                true
            } else {
                false
            }
        },
        positionalThreshold = { distance -> distance * 0.35f }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = job.state != JobState.SAVING,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 4.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.cd_remove_download),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant,
            tonalElevation = 0.dp
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
                        JobState.PREPARING, JobState.RUNNING, JobState.PROCESSING, JobState.SAVING ->
                            MaterialTheme.colorScheme.onPrimaryContainer
                        JobState.PAUSED, JobState.INTERRUPTED -> YtOfflineExtras.colors.warning
                        JobState.DONE -> YtOfflineExtras.colors.success
                        JobState.FAILED -> MaterialTheme.colorScheme.error
                    }
                    Text(
                        text = stringResource(R.string.job_status_line, jobStateLabel(job.state), job.progressText),
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
                    }
                    JobState.PAUSED, JobState.INTERRUPTED, JobState.FAILED -> {
                        IconButton(onClick = { onResume(job.id) }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cd_resume))
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun jobStateLabel(state: JobState): String = stringResource(
    when (state) {
        JobState.QUEUED -> R.string.job_state_queued
        JobState.PREPARING -> R.string.job_state_preparing
        JobState.RUNNING -> R.string.job_state_running
        JobState.PROCESSING -> R.string.job_state_processing
        JobState.SAVING -> R.string.job_state_saving
        JobState.PAUSED -> R.string.job_state_paused
        JobState.INTERRUPTED -> R.string.job_state_interrupted
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
    var confirmDelete by remember { mutableStateOf(false) }

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
                .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val mediaType = when {
                    item.mimeType.startsWith("audio/") -> stringResource(R.string.media_type_audio)
                    item.mimeType.startsWith("video/") -> stringResource(R.string.media_type_video)
                    else -> item.mimeType
                }
                val mediaDate = if (item.dateAddedSeconds > 0) {
                    DateFormat.getDateInstance(DateFormat.SHORT).format(Date(item.dateAddedSeconds * 1_000L))
                } else {
                    "—"
                }
                Text(
                    text = stringResource(R.string.library_meta, mediaType, formatFileSize(item.sizeBytes), mediaDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = stringResource(R.string.cd_play),
                    tint = MaterialTheme.colorScheme.primary
                )
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
                        onClick = { menuExpanded = false; confirmDelete = true }
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_confirm_title)) },
            text = { Text(stringResource(R.string.delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024L -> "%.0f KB".format(bytes / 1_024.0)
    bytes > 0 -> "$bytes B"
    else -> "—"
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
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.personal_use_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
    onOpenYouTubeLogin: () -> Unit,
    requestNotifications: () -> Unit
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
        BypassSettingsSection(requestNotifications = requestNotifications)

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
                    YouTubeAuth.clearSession(context) { hasSession = false }
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

// The same accounts.google.com entry point youtube.com's own "Sign in" button navigates to
// (verified against YouTube's real sign-in redirect chain), so the WebView opens straight on
// the sign-in form instead of the homepage.
private const val YOUTUBE_SIGN_IN_URL =
    "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"

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
                    // Google rejects sign-in from the stock WebView user agent: it contains a
                    // "; wv" marker that Google's login explicitly detects and blocks with
                    // "This browser or app may not be secure" (disallowed_useragent). Stripping
                    // just that marker -- keeping the real device/Android/Chrome version as-is
                    // -- is the documented minimal fix (see CHANGELOG.md's Patch 31 entry for
                    // sources); a made-up user agent would be both less reliable and less honest.
                    settings.userAgentString = settings.userAgentString
                        .replace("; wv)", ")")
                        .replace("; wv ", " ")
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
                    // Opens straight on Google's sign-in form instead of the YouTube homepage,
                    // so the user does not have to find "Sign in" themselves.
                    loadUrl(YOUTUBE_SIGN_IN_URL)
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
        // Patch 34: Copy report / Share put the report header plus the newest lines on the clipboard.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = { copyReport(context) }) {
                Text(stringResource(R.string.copy_report))
            }
            OutlinedButton(onClick = { shareLogs(context) }) {
                Text(stringResource(R.string.share_logs))
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
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
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.logs_privacy_notice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
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
        AppLog.i("Library", "Opened library item")
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, R.string.no_player, Toast.LENGTH_SHORT).show()
        AppLog.e("Library", "No player for library item", e)
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
        AppLog.e("Library", "No share target for library item", e)
    }
}

/** Patch 34: header (build, device, network, strategies, legend) plus the newest lines, on the clipboard. */
private fun copyReport(context: Context) {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return
    manager.setPrimaryClip(ClipData.newPlainText("Kinescope report", DiagnosticReport.build(context)))
    Toast.makeText(context, R.string.report_copied, Toast.LENGTH_SHORT).show()
}

private fun shareLogs(context: Context) {
    val payload = DiagnosticReport.build(context)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, payload)
        putExtra(Intent.EXTRA_TITLE, context.getString(R.string.logs_title))
    }
    runCatching {
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.share_logs)))
    }.onFailure { AppLog.e("Logs", "Could not open share sheet", it) }
}
