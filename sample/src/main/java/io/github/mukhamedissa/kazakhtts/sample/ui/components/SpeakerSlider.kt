package io.github.mukhamedissa.kazakhtts.sample.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun SpeakerSlider(
    speakerId: Int,
    numSpeakers: Int,
    onSpeakerIdChange: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val maxSpeakerId = (numSpeakers - 1).coerceAtLeast(0)
    val hasMultipleSpeakers = maxSpeakerId > 0

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Speaker: $speakerId / $maxSpeakerId",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = speakerId.toFloat(),
            onValueChange = { onSpeakerIdChange(it.toInt()) },
            valueRange = 0f..maxSpeakerId.toFloat(),
            steps = (maxSpeakerId - 1).coerceAtLeast(0),
            enabled = enabled && hasMultipleSpeakers,
        )
    }
}
