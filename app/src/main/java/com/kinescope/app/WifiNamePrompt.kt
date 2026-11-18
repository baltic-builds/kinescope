package com.kinescope.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * Patch 64: asked once, on Wi-Fi, before the first update decision there. Android shows the name of a Wi-Fi network to an app only when it
 * holds the location permission (a platform rule, not a use of the location); the app uses the name to know whether it has already updated its
 * strategies on this network (`LaunchUpdate`). "Not now" is fine: the network is then recognised by its gateway and subnet instead.
 * Replaces patch 63's first-launch question (`FirstCheckDialog`).
 */
@Composable
internal fun WifiNameDialog(onAllow: () -> Unit, onLater: () -> Unit) {
    AlertDialog(
        onDismissRequest = onLater,
        title = { Text(stringResource(R.string.wifi_name_title)) },
        text = { Text(stringResource(R.string.wifi_name_body)) },
        confirmButton = {
            FocusRing { TextButton(onClick = onAllow) { Text(stringResource(R.string.wifi_name_allow)) } }
        },
        dismissButton = {
            FocusRing { TextButton(onClick = onLater) { Text(stringResource(R.string.wifi_name_later)) } }
        }
    )
}
