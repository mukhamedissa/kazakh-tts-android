package io.github.mukhamedissa.kazakhtts.sample.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.mukhamedissa.kazakhtts.sample.TtsUiState
import java.util.Locale

@Composable
fun SpeedSlider(
    speed: Float,
    onSpeedChange: (Float) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = String.format(Locale.US, "Speed: %.2f×", speed),
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = speed,
            onValueChange = onSpeedChange,
            valueRange = TtsUiState.MIN_SPEED..TtsUiState.MAX_SPEED,
            enabled = enabled,
        )
    }
}
