package com.kinescope.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
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

// Patch 64: which app opens a file of the library, chosen separately for video and for audio (Settings). Before, Android picked (its own
// default, or a chooser); that stays the default, so nothing changes until a player is chosen. The choice is a package name; an app that
// has since been uninstalled is ignored and the system chooses again.

internal enum class MediaKind {
    VIDEO,
    AUDIO;

    companion object {
        fun of(mimeType: String): MediaKind = if (mimeType.startsWith("audio/", ignoreCase = true)) AUDIO else VIDEO
    }
}

internal data class PlayerApp(val packageName: String, val label: String)

internal object LibraryPlayers {
    /** The stored value of "let Android choose". */
    const val SYSTEM = ""

    /** Pure (unit-tested): no duplicates, never [exclude] (the app itself), by name. */
    fun order(found: List<PlayerApp>, exclude: String? = null): List<PlayerApp> {
        val unique = LinkedHashMap<String, PlayerApp>()
        for (app in found) if (app.packageName != exclude) unique.putIfAbsent(app.packageName, app)
        return unique.values.sortedBy { it.label.lowercase() }
    }

    private fun sample(kind: MediaKind): Intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(
            Uri.parse(if (kind == MediaKind.AUDIO) "content://media/external/audio/media/1" else "content://media/external/video/media/1"),
            if (kind == MediaKind.AUDIO) "audio/mpeg" else "video/mp4"
        )

    /** The apps that can open a file of [kind]. */
    fun candidates(context: Context, kind: MediaKind): List<PlayerApp> {
        val pm = context.packageManager
        val probe = sample(kind)
        val resolved = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
            } else {
                @Suppress("DEPRECATION")
                pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
            }
        }.getOrDefault(emptyList())
        val found = resolved.mapNotNull { info ->
            val packageName = info.activityInfo?.packageName ?: return@mapNotNull null
            PlayerApp(packageName, info.loadLabel(pm).toString())
        }
        return order(found, exclude = context.packageName)
    }

    /** The package to use now for [kind], or [SYSTEM] when none is chosen or the chosen one is gone. */
    fun chosen(context: Context, kind: MediaKind): String {
        val stored = Settings.getLibraryPlayer(context, kind)
        return if (stored.isNotEmpty() && YoutubeApps.isInstalled(context, stored)) stored else SYSTEM
    }

    /** The view intent for a library item, aimed at the chosen player of its kind when there is one. */
    fun viewIntent(context: Context, item: LibraryItem): Intent {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, item.mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val packageName = chosen(context, MediaKind.of(item.mimeType))
        if (packageName != SYSTEM) intent.setPackage(packageName)
        return intent
    }
}

/** Settings -> "Open library files with": one choice for video and one for audio. */
@Composable
internal fun LibraryPlayerSection() {
    Text(stringResource(R.string.settings_library_players), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.library_players_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    PlayerChoice(MediaKind.VIDEO, R.string.library_players_video)
    Spacer(Modifier.height(12.dp))
    PlayerChoice(MediaKind.AUDIO, R.string.library_players_audio)
}

@Composable
private fun PlayerChoice(kind: MediaKind, @androidx.annotation.StringRes titleRes: Int) {
    val context = LocalContext.current
    val apps = remember(kind) { LibraryPlayers.candidates(context, kind) }
    var chosen by remember(kind) { mutableStateOf(LibraryPlayers.chosen(context, kind)) }
    Text(stringResource(titleRes), style = MaterialTheme.typography.titleSmall)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        PlayerRow(
            label = stringResource(R.string.library_players_system),
            selected = chosen == LibraryPlayers.SYSTEM,
            onSelect = {
                Settings.setLibraryPlayer(context, kind, LibraryPlayers.SYSTEM)
                chosen = LibraryPlayers.SYSTEM
            }
        )
        for (app in apps) {
            PlayerRow(
                label = app.label,
                selected = chosen == app.packageName,
                onSelect = {
                    Settings.setLibraryPlayer(context, kind, app.packageName)
                    chosen = app.packageName
                    AppLog.i("LibraryPlayers", "chosen kind=${kind.name.lowercase()}")
                }
            )
        }
    }
}

@Composable
private fun PlayerRow(label: String, selected: Boolean, onSelect: () -> Unit) {
    FocusRing(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            RadioButton(selected = selected, onClick = null)
            Text(text = label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        }
    }
}
