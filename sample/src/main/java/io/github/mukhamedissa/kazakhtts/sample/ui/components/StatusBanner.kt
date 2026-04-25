package io.github.mukhamedissa.kazakhtts.sample.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.mukhamedissa.kazakhtts.sample.InitState
import io.github.mukhamedissa.kazakhtts.sample.PlaybackState

@Composable
fun StatusBanner(
    initState: InitState,
    playbackState: PlaybackState,
    modifier: Modifier = Modifier,
) {
    val (title, detail, tone) = describe(initState, playbackState)

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tone.background()),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (initState is InitState.Downloading) {
                if (initState.percent != null) {
                    LinearProgressIndicator(
                        progress = { (initState.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                    )
                }
            }
        }
    }
}

private data class BannerContent(val title: String, val detail: String?, val tone: Tone)

private enum class Tone {
    NEUTRAL, INFO, SUCCESS, ERROR;

    @Composable
    fun background(): Color = when (this) {
        NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant
        INFO -> MaterialTheme.colorScheme.secondaryContainer
        SUCCESS -> MaterialTheme.colorScheme.primaryContainer
        ERROR -> MaterialTheme.colorScheme.errorContainer
    }
}

private fun describe(initState: InitState, playbackState: PlaybackState): BannerContent =
    when (initState) {
        InitState.Loading ->
            BannerContent("Initialising…", "Preparing the TTS engine.", Tone.INFO)

        is InitState.Downloading -> {
            val detail = initState.percent?.let { "Downloading model: $it%" } ?: "Downloading model…"
            BannerContent("Downloading", detail, Tone.INFO)
        }

        is InitState.Ready -> describePlayback(initState, playbackState)

        is InitState.Failed ->
            BannerContent("Initialisation failed", initState.message, Tone.ERROR)
    }

private fun describePlayback(ready: InitState.Ready, playback: PlaybackState): BannerContent {
    val readySubtitle = "${ready.numSpeakers} speakers · ${ready.sampleRateHz} Hz"
    return when (playback) {
        PlaybackState.Idle ->
            BannerContent("Ready", readySubtitle, Tone.SUCCESS)
        PlaybackState.Generating ->
            BannerContent("Generating…", readySubtitle, Tone.INFO)
        PlaybackState.Done ->
            BannerContent("Done", readySubtitle, Tone.SUCCESS)
        PlaybackState.Stopped ->
            BannerContent("Stopped", readySubtitle, Tone.NEUTRAL)
        is PlaybackState.Failed ->
            BannerContent("Synthesis failed", playback.message, Tone.ERROR)
    }
}
