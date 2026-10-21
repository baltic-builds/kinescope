package com.kinescope.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// The Add screen: link field, quality chips, clipboard prefill. Patch 40: moved out of MainActivity.kt unchanged.
// Patch 41: the form keeps a readable width in a wide window, the keyboard's action key starts the download (a
// TV on-screen keyboard or a tablet's hardware keyboard has no other way to submit), and TV focus rings.

@Composable
internal fun QuickAddScreen(
    url: String,
    onUrlChange: (String) -> Unit,
    selectedQuality: Int,
    onQualitySelected: (Int) -> Unit,
    errorText: String?,
    onDownload: () -> Unit
) {
    val tv = LocalAdaptive.current.isTv
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(top = 12.dp)
        ) {
            Text(stringResource(R.string.quick_add_hint), style = MaterialTheme.typography.bodyMedium)
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(
                value = url,
                onValueChange = onUrlChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.link_placeholder)) },
                singleLine = true,
                isError = errorText != null,
                supportingText = errorText?.let { { Text(it) } },
                shape = MaterialTheme.shapes.extraLarge,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (url.isNotBlank()) onDownload() }),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    unfocusedBorderColor = Color.Transparent,
                    // The field's own border is its focus indicator; on TV it has to be seen from the couch.
                    focusedBorderColor = if (tv) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline
                )
            )
            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                qualityPresets.forEachIndexed { index, preset ->
                    FocusRing(shape = MaterialTheme.shapes.small) {
                        FilterChip(
                            selected = index == selectedQuality,
                            onClick = { onQualitySelected(index) },
                            label = { Text(stringResource(preset.labelRes)) }
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(20.dp))
            FocusRing(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onDownload,
                    enabled = url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.start_download))
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.personal_use_notice),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
