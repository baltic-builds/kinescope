package com.kinescope.app

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties

/**
 * Patch 63: the question of the first launch of a fresh install: for the best result every strategy should be checked once. Two
 * buttons: "Check now" (the strategy list is updated, then every strategy is tested) and "Do not check" (the app starts from an untested
 * list and the full scan stays in Settings). It has no way out but the buttons: back and a tap outside do nothing, so the choice is
 * always made (`DpiPrefs.firstCheckAnswer`). This brings back the pop-up of patch 33 and replaces patch 51's mandatory first check.
 */
@Composable
internal fun FirstCheckDialog(onCheckNow: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(stringResource(R.string.first_check_title)) },
        text = { Text(stringResource(R.string.first_check_body)) },
        confirmButton = {
            FocusRing { TextButton(onClick = onCheckNow) { Text(stringResource(R.string.first_check_now)) } }
        },
        dismissButton = {
            FocusRing { TextButton(onClick = onSkip) { Text(stringResource(R.string.first_check_skip)) } }
        }
    )
}
