package com.kinescope.app

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

// Settings: defaults, storage, extractor, bypass, account sessions. Patch 40: moved out of MainActivity.kt unchanged.

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
    requestNotifications: () -> Unit
) {
    val context = LocalContext.current
    var defaultQuality by remember { mutableIntStateOf(Settings.getDefaultQualityIndex(context)) }
    var subfolder by remember { mutableStateOf(Settings.getDownloadSubfolder(context)) }
    var hasSession by remember { mutableStateOf(YouTubeAuth.hasSavedSession(context)) }
    var hasInstagramSession by remember { mutableStateOf(InstagramAuth.hasSavedSession(context)) }

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
}

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
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onOpen) {
            Text(stringResource(if (hasSession) R.string.session_refresh else signInRes))
        }
        if (hasSession) {
            OutlinedButton(onClick = onSignOut) { Text(stringResource(R.string.session_sign_out)) }
        }
    }
}

@Composable
private fun SettingsDivider() {
    Spacer(modifier = Modifier.height(20.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
    Spacer(modifier = Modifier.height(20.dp))
}
