package com.kinescope.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * Patch 51: Settings -> "App for Watch YouTube": the official YouTube app (default), another app that opens YouTube
 * links, or a browser. The choice is a package name in [Settings]; the tunnel and the Home button both read it.
 */
@Composable
internal fun YoutubeAppSection() {
    val context = LocalContext.current
    val apps = remember { YoutubeApps.candidates(context) }
    var chosen by remember { mutableStateOf(YoutubeApps.chosenPackage(context)) }

    Text(stringResource(R.string.settings_youtube_app), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.youtube_app_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    if (apps.isEmpty()) {
        Text(
            text = stringResource(R.string.youtube_app_none),
            style = MaterialTheme.typography.bodyMedium,
            color = YtOfflineExtras.colors.warning
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (app in apps) {
            FocusRing(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = app.packageName == chosen,
                            role = Role.RadioButton,
                            onClick = {
                                Settings.setYoutubeApp(context, app.packageName)
                                chosen = app.packageName
                                AppLog.i("YoutubeApps", "chosen default=${app.packageName == YoutubeApps.DEFAULT_PACKAGE}")
                            }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioButton(selected = app.packageName == chosen, onClick = null)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = app.label, style = MaterialTheme.typography.bodyLarge)
                        if (app.packageName == YoutubeApps.DEFAULT_PACKAGE) {
                            Text(
                                text = stringResource(R.string.youtube_app_default),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text(
        text = stringResource(R.string.youtube_app_note),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
