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
 * Patch 64: Settings -> "DNS for YouTube": the server the tunnel gives the app (see [TunnelDns]). A running tunnel is rebuilt at once, the
 * address being fixed when the tunnel is built.
 */
@Composable
internal fun TunnelDnsSection() {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(Settings.getTunnelDns(context)) }
    Text(stringResource(R.string.settings_tunnel_dns), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.tunnel_dns_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (value in TunnelDns.PRESETS) {
            FocusRing(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = value == chosen,
                            role = Role.RadioButton,
                            onClick = {
                                if (value != chosen) {
                                    Settings.setTunnelDns(context, value)
                                    chosen = value
                                    AppLog.i("TunnelDns", "chosen ${if (value == TunnelDns.SYSTEM) "system" else value}")
                                    BypassVpnService.rebuild(context)
                                }
                            }
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RadioButton(selected = value == chosen, onClick = null)
                    Text(
                        text = stringResource(
                            when (value) {
                                TunnelDns.CLOUDFLARE -> R.string.dns_cloudflare
                                TunnelDns.GOOGLE -> R.string.dns_google
                                TunnelDns.QUAD9 -> R.string.dns_quad9
                                TunnelDns.YANDEX -> R.string.dns_yandex
                                else -> R.string.dns_system
                            }
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
