package com.kinescope.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// Patch 55: the hints under the Home dashboard. A separate panel that cannot be closed (it is part of the screen, not a banner):
// both the panel and its text are semi-transparent, so it reads as help in the background and never competes with the
// dashboard. Each line describes one thing the strategy router does, in a few words, and the last says what the "+" of the
// navigation does, so a new person can follow the journey (copy a link, tap +, watch the queue) without opening Settings.

/** The hints, in the order they are shown. Every one has an English and a Russian text. */
private val Hints = listOf(
    R.string.hint_groups,
    R.string.hint_first_check,
    R.string.hint_random_check,
    R.string.hint_auto,
    R.string.hint_no_route,
    R.string.hint_blocked,
    R.string.hint_plus
)

/** How see-through the panel and its text are (1 = opaque). Text stays above 4.5:1 on the screen background in both themes. */
private const val PANEL_ALPHA = 0.55f
private const val TEXT_ALPHA = 0.82f

@Composable
internal fun HintsPanel(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurfaceVariant.copy(alpha = TEXT_ALPHA)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = scheme.surfaceContainerLow.copy(alpha = PANEL_ALPHA)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.hints_title),
                style = MaterialTheme.typography.labelLarge,
                color = textColor
            )
            for (hint in Hints) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .padding(top = 7.dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(scheme.tertiary.copy(alpha = 0.7f))
                    )
                    Text(
                        text = stringResource(hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = textColor,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
