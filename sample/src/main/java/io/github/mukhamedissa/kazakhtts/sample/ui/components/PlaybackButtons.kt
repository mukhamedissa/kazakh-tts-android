package io.github.mukhamedissa.kazakhtts.sample.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun PlaybackButtons(
    canGenerate: Boolean,
    canStop: Boolean,
    onGenerate: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onGenerate,
            enabled = canGenerate,
            modifier = Modifier.weight(1f),
        ) { Text("Generate & Play") }

        OutlinedButton(
            onClick = onStop,
            enabled = canStop,
            modifier = Modifier.weight(1f),
        ) { Text("Stop") }
    }
}
