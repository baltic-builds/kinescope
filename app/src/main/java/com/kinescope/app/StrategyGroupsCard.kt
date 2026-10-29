package com.kinescope.app

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// Patch 56: the strategy groups on Home. Patches 53 and 55 kept a separate list of strategies for Wi-Fi and for mobile data (a
// phone hotspot counts as mobile data), checked and ranked on their own, but the only trace on screen was a small label under the
// automatic switch. This card lists both groups, the one the phone is on first: how many ways the router holds, how many are known
// to work, when the group was last checked and what its last random check found. What it says comes from `StrategyGroups.kt`; this
// file reads the preferences and draws it. It cannot be focused (it is information, like the hints panel).

/** "just now" or a relative time localised by the framework ("3 hr. ago" / "3 ч. назад"). */
@Composable
internal fun agoText(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < DateUtils.MINUTE_IN_MILLIS) {
        stringResource(R.string.dash_just_now)
    } else {
        DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
    }
}

/** What the preferences hold about [cls]: reads only, so showing the card never changes a chain (`chain()` would borrow one). */
private fun readGroup(context: Context, cls: NetClass, current: NetClass): GroupState = GroupState(
    cls = cls,
    current = cls == current,
    inRouter = DpiStrategyStore.verifiedChain(context, cls).size,
    known = StrategyRating.eligible(DpiPrefs.ratings(context, cls)).size,
    seeded = DpiPrefs.isSeeded(context, cls),
    checkedAt = DpiPrefs.verifiedAt(context, cls),
    random = DpiPrefs.randomLast(context, cls)
)

@Composable
internal fun StrategyGroupsCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val netClass by NetClassWatcher.state.collectAsState()
    val current = netClass ?: NetClass.WIFI
    val search by DpiSearchController.state.collectAsState()
    val tunnel by BypassVpnController.state.collectAsState()
    // Read again whenever something that can change them happens: a search or a random check starting or ending, the tunnel
    // coming up or going down (a rotation reorders the chain), or the connection changing.
    val groups = remember(current, search.running, search.randomResult, search.resultMessageRes, tunnel.active) {
        groupOrder(current).map { readGroup(context, it, current) }
    }
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerLow,
        border = BorderStroke(1.dp, scheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.groups_title),
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface
            )
            for (group in groups) GroupRow(group)
        }
    }
}

@Composable
private fun GroupRow(group: GroupState) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        shape = MaterialTheme.shapes.large,
        color = if (group.current) scheme.tertiaryContainer.copy(alpha = 0.45f) else scheme.surface
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(if (group.cls == NetClass.WIFI) R.string.group_wifi_title else R.string.group_mobile_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                if (group.current) {
                    Surface(shape = CircleShape, color = scheme.tertiaryContainer) {
                        Text(
                            text = stringResource(R.string.group_now),
                            style = MaterialTheme.typography.labelSmall,
                            color = scheme.onTertiaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                        )
                    }
                }
            }
            when (group.status) {
                GroupStatus.NOT_CHECKED -> GroupLine(stringResource(R.string.group_not_checked))
                GroupStatus.BORROWED -> {
                    GroupLine(stringResource(R.string.group_router, group.inRouter, group.known))
                    GroupLine(stringResource(R.string.group_borrowed))
                }
                GroupStatus.READY -> {
                    GroupLine(stringResource(R.string.group_router, group.inRouter, group.known))
                    if (group.checkedAt > 0L) GroupLine(stringResource(R.string.group_checked, agoText(group.checkedAt)))
                    val random = group.random
                    GroupLine(
                        if (random == null) {
                            stringResource(R.string.group_random_none)
                        } else {
                            stringResource(R.string.group_random, agoText(random.at), random.works, random.tested)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
