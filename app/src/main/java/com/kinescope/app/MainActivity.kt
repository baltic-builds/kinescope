package com.kinescope.app

import android.Manifest
import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val sharedUrl = mutableStateOf("")
    private val openHomeRequest = mutableLongStateOf(0L)
    private val interfaceMode = mutableStateOf(InterfaceMode.AUTO)

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
        interfaceMode.value = Settings.getInterfaceMode(this)
        handleIncomingIntent(intent)

        // Patch 41: AdaptiveRoot classifies the window (phone, tablet, TV) for every screen and must sit
        // above the theme, which scales text on a TV.
        setContent {
            AdaptiveRoot(mode = interfaceMode.value) {
                YtOfflineTheme {
                    KinescopeApp(
                        prefillUrl = sharedUrl.value,
                        openHomeRequest = openHomeRequest.longValue,
                        requestNotifications = ::requestNotificationsIfNeeded,
                        interfaceMode = interfaceMode.value,
                        onInterfaceModeChanged = ::changeInterfaceMode
                    )
                }
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

    private fun changeInterfaceMode(mode: InterfaceMode) {
        Settings.setInterfaceMode(this, mode)
        interfaceMode.value = mode
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == AppIntents.ACTION_OPEN_HOME) {
            openHomeRequest.longValue += 1L
        }
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            MediaUrlParser.firstFromText(sharedText)?.canonicalUrl?.let {
                sharedUrl.value = it
                AppLog.i("MainActivity", "Received shared link")
            }
        }
    }
}

internal enum class AppSection { HOME, QUICK_ADD, SETTINGS, LOGS, YOUTUBE_AUTH, INSTAGRAM_AUTH }

private fun clipboardMediaUrl(context: Context): String? {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
    return MediaUrlParser.firstFromText(text)?.canonicalUrl
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

@Composable
private fun KinescopeApp(
    prefillUrl: String,
    openHomeRequest: Long,
    requestNotifications: () -> Unit,
    interfaceMode: InterfaceMode,
    onInterfaceModeChanged: (InterfaceMode) -> Unit
) {
    val context = LocalContext.current
    val jobs by DownloadQueueBus.jobs.collectAsState()
    val completionVersion by DownloadQueueBus.completionVersion.collectAsState()
    val scope = rememberCoroutineScope()
    val bypassState by BypassVpnController.state.collectAsState()
    val searchState by DpiSearchController.state.collectAsState()
    var showStrategyRefreshNotice by remember { mutableStateOf(false) }

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

    LaunchedEffect(openHomeRequest) {
        if (openHomeRequest > 0L) section = AppSection.HOME
    }

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

    // Patch 36: MainActivity is the single owner of automatic strategy refreshes. A fresh install
    // explains the first check before starting it; the periodic re-check (patch 46) is quiet.
    LaunchedEffect(Unit) {
        if (
            !DpiPrefs.hasRunInitialSearch(context) &&
            DpiStrategyStore.verifiedChain(context).isEmpty() &&
            !DpiSearchController.state.value.running
        ) {
            showStrategyRefreshNotice = true
            DpiSearchService.start(context)
        }
    }

    // Patch 46: the re-check no longer follows an app update (every sideloaded update used to start a long full scan
    // and a "keep the app open" popup). It is due 25 days after the last finished search, runs quietly with no popup,
    // and a download that starts meanwhile uses the stored and learned strategies (DpiPrefs.isMaintenanceDue).
    LaunchedEffect(Unit) {
        if (DpiPrefs.isMaintenanceDue(context) && !DpiSearchController.state.value.running) {
            DpiSearchService.startBackground(context)
        }
    }

    LaunchedEffect(searchState.running, searchState.resultMessageRes) {
        if (!searchState.running && searchState.resultMessageRes != null) {
            showStrategyRefreshNotice = false
        }
    }

    suspend fun loadLibrary() {
        library = withContext(Dispatchers.IO) { MediaStorage.listPublished(context) }
    }

    LaunchedEffect(completionVersion) { loadLibrary() }

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
            } else if (result == "ALREADY_UP_TO_DATE") {
                context.getString(R.string.update_current)
            } else {
                context.getString(R.string.update_done)
            }
            isUpdating = false
            lastUpdateTimestamp = Settings.getLastUpdateTimestamp(context)
        }
    }

    fun openQuickAdd() {
        clipboardMediaUrl(context)?.let {
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

    // Android Back and the on-screen back button (top bar or header) go the same way.
    fun goBack() {
        section = when (section) {
            AppSection.LOGS, AppSection.YOUTUBE_AUTH, AppSection.INSTAGRAM_AUTH -> AppSection.SETTINGS
            else -> AppSection.HOME
        }
    }

    BackHandler(enabled = section != AppSection.HOME) { goBack() }

    if (showStrategyRefreshNotice) {
        StrategyRefreshNoticeDialog(
            progress = searchState.progress,
            onDismiss = {
                showStrategyRefreshNotice = false
                requestNotifications()
            }
        )
    }

    AppFrame(
        section = section,
        title = when (section) {
            AppSection.HOME -> stringResource(R.string.app_name)
            AppSection.QUICK_ADD -> stringResource(R.string.quick_add_title)
            AppSection.SETTINGS -> stringResource(R.string.settings_title)
            AppSection.LOGS -> stringResource(R.string.logs_title)
            AppSection.YOUTUBE_AUTH -> stringResource(R.string.youtube_login_title)
            AppSection.INSTAGRAM_AUTH -> stringResource(R.string.instagram_login_title)
        },
        showBack = section != AppSection.HOME,
        showNavigation = section != AppSection.YOUTUBE_AUTH && section != AppSection.INSTAGRAM_AUTH,
        onBack = { goBack() },
        onHome = { section = AppSection.HOME },
        onQuickAdd = { openQuickAdd() },
        onSettings = { openSettingsWithSecretTap() }
    ) { innerPadding ->
        ResponsiveContent(innerPadding) {
            when (section) {
                AppSection.HOME -> HomeScreen(
                    jobs = jobs,
                    library = library,
                    bypassState = bypassState,
                    onOpenBypass = { openByeDpiYouTube() },
                    requestNotifications = requestNotifications,
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
                    onSignIn = { section = AppSection.YOUTUBE_AUTH },
                    onSignInInstagram = { section = AppSection.INSTAGRAM_AUTH }
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
                                urlError = context.getString(R.string.error_unsupported_link)
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
                    onOpenInstagramLogin = { section = AppSection.INSTAGRAM_AUTH },
                    requestNotifications = requestNotifications,
                    interfaceMode = interfaceMode,
                    onInterfaceModeChanged = onInterfaceModeChanged
                )
                AppSection.LOGS -> LogsScreen()
                AppSection.YOUTUBE_AUTH -> WebSessionLoginScreen(
                    site = YouTubeSessionSite,
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
                AppSection.INSTAGRAM_AUTH -> WebSessionLoginScreen(
                    site = InstagramSessionSite,
                    onBack = { section = AppSection.SETTINGS },
                    onSaved = {
                        // Reels that were parked waiting for a login continue now.
                        val loginJobs = jobs.filter {
                            it.state == JobState.PAUSED && it.failureKind == FailureKind.INSTAGRAM_LOGIN
                        }
                        loginJobs.forEach { DownloadService.resume(context, it.id) }
                        section = if (loginJobs.isNotEmpty()) AppSection.HOME else AppSection.SETTINGS
                    }
                )
            }
        }
    }
}
