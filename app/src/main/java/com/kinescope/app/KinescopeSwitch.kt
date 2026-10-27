package com.kinescope.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable

/** Brand orange when enabled, neutral gray when disabled, in light and dark themes. */
@Composable
internal fun KinescopeSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    enabled: Boolean = true
) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.onPrimary,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = scheme.primary,
            uncheckedThumbColor = scheme.onSurfaceVariant,
            uncheckedTrackColor = scheme.surfaceVariant,
            uncheckedBorderColor = scheme.outline,
            disabledCheckedThumbColor = scheme.onPrimary.copy(alpha = 0.60f),
            disabledCheckedTrackColor = scheme.primary.copy(alpha = 0.50f),
            disabledUncheckedThumbColor = scheme.onSurfaceVariant.copy(alpha = 0.65f),
            disabledUncheckedTrackColor = scheme.surfaceVariant.copy(alpha = 0.65f)
        )
    )
}
