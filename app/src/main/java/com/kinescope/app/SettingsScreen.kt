package com.kinescope.app

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

// Settings: defaults, storage, extractor, bypass, account sessions, interface. Patch 40: moved out of
// MainActivity.kt unchanged. Patch 41: the Interface section, two columns on wide windows (everyday settings and
// accounts on the left, the long bypass section on the right) and TV focus rings. Patch 48: a visible Diagnostics
// entry (the logs no longer depend on five quick taps a remote cannot make), the TV edge margin, buttons that wrap
// (the account buttons) instead of being squeezed, and the folder name survives a theme or language change.

private fun formatTimestamp(millis: Long): String {
    if (millis == 0L) return ""
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(millis))
}

@Composable
private fun SettingsSectionHeader(text: String) {
    Text(text = text, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(12.dp))
}

@Composable
internal fun SettingsScreen(
    onDefaultQualityChanged: (Int) -> Unit,
    updateStatus: String,
    isUpdating: Boolean,
    lastUpdateTimestamp: Long,
    onCheckForUpdate: () -> Unit,
    onOpenYouTubeLogin: () -> Unit,
    onOpenInstagramLogin: () -> Unit,
    requestNotifications: () -> Unit,
    interfaceMode: InterfaceMode,
    onInterfaceModeChanged: (InterfaceMode) -> Unit,
    overscanPercent: Int,
    onOverscanChanged: (Int) -> Unit,
    onOpenLogs: () -> Unit
) {
    val context = LocalContext.current
    val spec = LocalAdaptive.current
    var defaultQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    // Patch 48: typed text must survive the activity being recreated (dark mode, language) and process death.
    var subfolder by rememberSaveable { mutableStateOf(Settings.getDownloadSubfolder(context)) }
    var hasSession by remember { mutableStateOf(YouTubeAuth.hasSavedSession(context)) }
    var hasInstagramSession by remember { mutableStateOf(InstagramAuth.hasSavedSession(context)) }

    // The sections, grouped the way both layouts need them: one column shows them in this order (general,
    // bypass, accounts, interface); two columns put the bypass section alone on the right.
    val general: @Composable () -> Unit = {
        SettingsSectionHeader(stringResource(R.string.settings_default_quality))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            qualityPresets.forEachIndexed { index, preset ->
                FocusRing(shape = MaterialTheme.shapes.small) {
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
        FocusRing {
            Button(onClick = {
                Settings.setDownloadSubfolder(context, subfolder)
                subfolder = Settings.getDownloadSubfolder(context)
                Toast.makeText(context, R.string.saved, Toast.LENGTH_SHORT).show()
            }) {
                Text(stringResource(R.string.save))
            }
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
        FocusRing {
            OutlinedButton(enabled = !isUpdating, onClick = onCheckForUpdate) {
                Text(if (isUpdating) stringResource(R.string.update_checking) else stringResource(R.string.check_for_update))
            }
        }
        // Patch 51: power saving stops background downloads; the permanent place to turn it off.
        Spacer(modifier = Modifier.height(16.dp))
        BatterySettingsSection()
    }

    val bypass: @Composable () -> Unit = {
        BypassSettingsSection(requestNotifications = requestNotifications)
        // Patch 51: which app "Unblock YouTube" opens (and the tunnel carries).
        Spacer(modifier = Modifier.height(16.dp))
        YoutubeAppSection()
    }

    val accounts: @Composable () -> Unit = {
        AccountSection(
            title = R.string.settings_youtube_account,
            savedRes = R.string.youtube_session_saved,
            notSavedRes = R.string.youtube_session_not_saved,
            warningRes = R.string.youtube_login_warning,
            signInRes = R.string.youtube_sign_in_action,
            hasSession = hasSession,
            onOpen = onOpenYouTubeLogin,
            onSignOut = { YouTubeAuth.clearSession(context) { hasSession = false } }
        )

        SettingsDivider()
        AccountSection(
            title = R.string.settings_instagram_account,
            savedRes = R.string.instagram_session_saved,
            notSavedRes = R.string.instagram_session_not_saved,
            warningRes = R.string.instagram_login_warning,
            signInRes = R.string.instagram_sign_in_action,
            hasSession = hasInstagramSession,
            onOpen = onOpenInstagramLogin,
            onSignOut = { InstagramAuth.clearSession(context) { hasInstagramSession = false } }
        )
    }

    val interfaceSection: @Composable () -> Unit = {
        InterfaceSection(
            mode = interfaceMode,
            onModeChanged = onInterfaceModeChanged,
            overscanPercent = overscanPercent,
            onOverscanChanged = onOverscanChanged
        )
        SettingsDivider()
        DiagnosticsSection(onOpenLogs = onOpenLogs)
        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = stringResource(R.string.powered_by),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.58f),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
    }

    if (spec.twoPane) {
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(spec.paneGapDp.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)
            ) {
                general()
                SettingsDivider()
                accounts()
                SettingsDivider()
                interfaceSection()
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = 16.dp)
            ) {
                bypass()
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp)
        ) {
            general()
            SettingsDivider()
            bypass()
            SettingsDivider()
            accounts()
            SettingsDivider()
            interfaceSection()
        }
    }
}

@Composable
private fun InterfaceSection(
    mode: InterfaceMode,
    onModeChanged: (InterfaceMode) -> Unit,
    overscanPercent: Int,
    onOverscanChanged: (Int) -> Unit
) {
    SettingsSectionHeader(stringResource(R.string.settings_interface))
    Text(
        text = stringResource(R.string.interface_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(10.dp))
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        InterfaceMode.entries.forEach { option ->
            FocusRing(shape = MaterialTheme.shapes.small) {
                FilterChip(
                    selected = option == mode,
                    onClick = { onModeChanged(option) },
                    label = {
                        Text(
                            stringResource(
                                when (option) {
                                    InterfaceMode.AUTO -> R.string.interface_auto
                                    InterfaceMode.TV -> R.string.interface_tv
                                    InterfaceMode.TOUCH -> R.string.interface_touch
                                }
                            )
                        )
                    }
                )
            }
        }
    }
    // Patch 48: the edge margin only means something on a TV (the layout it changes is the TV layout).
    if (LocalAdaptive.current.isTv) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(text = stringResource(R.string.overscan_title), style = MaterialTheme.typography.titleSmall)
        Text(
            text = stringResource(R.string.overscan_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(10.dp))
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            for (percent in 0..OVERSCAN_MAX_PERCENT) {
                FocusRing(shape = MaterialTheme.shapes.small) {
                    FilterChip(
                        selected = percent == overscanPercent,
                        onClick = { onOverscanChanged(percent) },
                        label = { Text(stringResource(R.string.overscan_percent, percent)) }
                    )
                }
            }
        }
    }
}

/** Patch 48: the technical log as an ordinary, visible Settings entry (five taps on the Settings icon still work). */
@Composable
private fun DiagnosticsSection(onOpenLogs: () -> Unit) {
    SettingsSectionHeader(stringResource(R.string.settings_diagnostics))
    Text(
        text = stringResource(R.string.diagnostics_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(10.dp))
    FocusRing {
        OutlinedButton(onClick = onOpenLogs) { Text(stringResource(R.string.diagnostics_open)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccountSection(
    title: Int,
    savedRes: Int,
    notSavedRes: Int,
    warningRes: Int,
    signInRes: Int,
    hasSession: Boolean,
    onOpen: () -> Unit,
    onSignOut: () -> Unit
) {
    SettingsSectionHeader(stringResource(title))
    Text(
        text = stringResource(if (hasSession) savedRes else notSavedRes),
        style = MaterialTheme.typography.bodyMedium
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        text = stringResource(warningRes),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(modifier = Modifier.height(10.dp))
    // Patch 48: FlowRow, not Row. "Sign in to Instagram" next to "Sign out" does not fit a 320dp column (a phone,
    // a TV pane); the second button used to be squeezed, now it drops to the next line.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        FocusRing {
            Button(onClick = onOpen) {
                Text(stringResource(if (hasSession) R.string.session_refresh else signInRes))
            }
        }
        if (hasSession) {
            FocusRing {
                OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.session_sign_out)) }
            }
        }
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(modifier = Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(modifier = Modifier.height(20.dp))
}
