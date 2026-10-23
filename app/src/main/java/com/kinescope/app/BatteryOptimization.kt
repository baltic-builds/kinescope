package com.kinescope.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Patch 51: Android's power saving closes Kinescope in the background and stops downloads. The cure Android offers is the
// battery-optimization exemption: `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` shows the system's own "let this app
// always run in the background?" dialog (it needs the `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permission, which Google
// Play restricts and a sideloaded app may use), `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` lists every app's setting
// and the app's own system page (`ACTION_APPLICATION_DETAILS_SETTINGS`) is where the vendor-specific switches live
// (Xiaomi, Huawei, Samsung and others add their own battery and autostart rules on top of the standard one).
//
// The user sees it twice: one pop-up on the second launch (never the first, which belongs to the strategy check, and never
// again after it was shown), and a permanent "Background work" section in Settings.

internal object BatteryOptimization {
    /** The pop-up is for the second launch (or the first later one, if the second was busy with the first-run check). */
    const val PROMPT_LAUNCH = 2

    /** Pure rule of the pop-up (unit-tested): from the second launch on, once, and not when the exemption is already granted. */
    fun shouldPrompt(launchCount: Int, alreadyAsked: Boolean, ignoring: Boolean): Boolean =
        launchCount >= PROMPT_LAUNCH && !alreadyAsked && !ignoring

    /** Whether Android already lets the app run without battery restrictions. */
    fun isIgnoring(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull() ?: false

    /** The system dialog that asks for the exemption for this app. */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** The list of every app's battery-optimization setting (the fallback where the dialog does not exist). */
    fun listIntent(): Intent = Intent(AndroidSettings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** The app's own system page, where a vendor's extra battery or autostart switch lives. */
    fun appInfoIntent(context: Context): Intent =
        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
}

/** The two ways into the system's battery settings; each returns false when no screen could be opened. */
internal class BatteryOpener(val exemption: () -> Boolean, val appInfo: () -> Boolean)

/**
 * A launcher for the battery screens. [onReturned] runs when the user comes back from one of them, so the status on the
 * screen is read again (the exemption dialog and the settings page both end with a result).
 */
@Composable
internal fun rememberBatteryOpener(onReturned: () -> Unit): BatteryOpener {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onReturned() }
    return remember(launcher) {
        fun launchFirst(intents: List<Intent>): Boolean {
            for (intent in intents) {
                if (runCatching { launcher.launch(intent) }.isSuccess) return true
            }
            AppLog.w("BatteryOptimization", "no battery settings screen could be opened")
            return false
        }
        BatteryOpener(
            exemption = {
                launchFirst(
                    listOf(
                        BatteryOptimization.requestIntent(context),
                        BatteryOptimization.listIntent(),
                        BatteryOptimization.appInfoIntent(context)
                    )
                )
            },
            appInfo = { launchFirst(listOf(BatteryOptimization.appInfoIntent(context))) }
        )
    }
}

/** The one-time pop-up of the second launch. */
@Composable
internal fun BatteryPromptDialog(onOpen: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.battery_prompt_title)) },
        text = { Text(stringResource(R.string.battery_prompt_body)) },
        confirmButton = {
            FocusRing { TextButton(onClick = onOpen) { Text(stringResource(R.string.battery_prompt_open)) } }
        },
        dismissButton = {
            FocusRing { TextButton(onClick = onLater) { Text(stringResource(R.string.battery_prompt_later)) } }
        }
    )
}

/** Settings -> Background work: what power saving does, the current state and the buttons into the system screens. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BatterySettingsSection() {
    val context = LocalContext.current
    var ignoring by remember { mutableStateOf(BatteryOptimization.isIgnoring(context)) }
    val opener = rememberBatteryOpener { ignoring = BatteryOptimization.isIgnoring(context) }
    val failed = {
        Toast.makeText(context, R.string.battery_open_failed, Toast.LENGTH_SHORT).show()
    }

    Text(stringResource(R.string.settings_battery), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(12.dp))
    Text(
        text = stringResource(R.string.battery_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(if (ignoring) R.string.battery_status_ok else R.string.battery_status_limited),
        style = MaterialTheme.typography.bodyMedium,
        color = if (ignoring) YtOfflineExtras.colors.success else YtOfflineExtras.colors.warning
    )
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (!ignoring) {
            FocusRing {
                Button(onClick = { if (!opener.exemption()) failed() }) {
                    Text(stringResource(R.string.battery_open))
                }
            }
        }
        FocusRing {
            OutlinedButton(onClick = { if (!opener.appInfo()) failed() }) {
                Text(stringResource(R.string.battery_app_info))
            }
        }
    }
}
