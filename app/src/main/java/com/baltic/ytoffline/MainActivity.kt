package com.baltic.ytoffline

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * "Maximally similar to the Claude app" pass.
 *
 * Restructured around a Scaffold: a top bar with icon actions
 * (update, settings) instead of text buttons, and a bottom-anchored
 * rounded "composer" bar (URL field + send button) instead of an
 * inline text field — the single most recognizable layout pattern
 * from Claude's own app. Colors/type/shape still come from
 * YtOfflineTheme (Theme.kt) — see design.md for what this is and
 * isn't (an approximation, not Anthropic's real spec; no Anthropic
 * fonts, name, or logo used).
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

/** Pulls the first http(s) URL out of arbitrary shared text. */
private fun extractUrl(text: String): String? =
    Regex("""https?://\S+""").find(text)?.value

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DownloadScreen(prefillUrl: String) {
    val context = LocalContext.current

    var url by remember { mutableStateOf("") }
    var selectedQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    var library by remember { mutableStateOf(MediaStorage.listPublished(context)) }
    var updateStatus by remember { mutableStateOf("") }
    var isUpdating by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val jobs by DownloadQueueBus.jobs.collectAsState()

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

    fun runUpdate() {
        isUpdating = true
        updateStatus = "Checking\u2026"
        Thread {
            val result = YtDlpUpdater.updateBlocking(context)
            Handler(Looper.getMainLooper()).post {
                updateStatus = result
                isUpdating = false
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
                    onUrlChange = { url = it },
                    onSend = {
                        DownloadService.enqueue(context, url, selectedQuality)
                        url = ""
                    },
                    selectedQuality = selectedQuality,
                    onQualitySelected = { selectedQuality = it }
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
            if (updateStatus.isNotBlank()) {
                Text(
                    text = "yt-dlp: $updateStatus",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (showSettings) {
                SettingsPanel(
                    onClose = { showSettings = false },
                    onDefaultQualityChanged = { selectedQuality = it }
                )
            } else {
                Text(text = "Queue", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(4.dp))

                if (jobs.isEmpty()) {
                    Text(
                        text = "Nothing queued. Paste a link below, or share one into this app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(jobs) { job ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(text = "${job.qualityLabel} \u2014 ${job.url}", maxLines = 1)
                                    val statusColor = when (job.state) {
                                        JobState.DONE -> SuccessGreen
                                        JobState.FAILED -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                    Text(
                                        text = "${job.state}: ${job.progressText}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = statusColor
                                    )
                                }
                            }
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
                    items(library) { item ->
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
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = item.displayName,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                IconButton(onClick = { playItem(context, item) }) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Play")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

/**
 * Bottom-anchored input bar: quality chips above a rounded pill-style
 * text field with a send icon inside it. This is the layout element
 * "maximally similar to the Claude app" was mainly asking for — see
 * design.md.
 */
@Composable
private fun ComposerBar(
    url: String,
    onUrlChange: (String) -> Unit,
    onSend: () -> Unit,
    selectedQuality: Int,
    onQualitySelected: (Int) -> Unit
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
                    focusedBorderColor = Color.Transparent
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
    context.startActivity(intent)
}

@Composable
private fun SettingsPanel(
    onClose: () -> Unit,
    onDefaultQualityChanged: (Int) -> Unit
) {
    val context = LocalContext.current
    var defaultQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    var subfolder by remember { mutableStateOf(Settings.getDownloadSubfolder(context)) }

    Column {
        Text(text = "Default quality", style = MaterialTheme.typography.titleSmall)
        Spacer(modifier = Modifier.height(8.dp))
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

        Text(text = "Downloads subfolder name", style = MaterialTheme.typography.titleSmall)
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

        Spacer(modifier = Modifier.height(12.dp))

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
