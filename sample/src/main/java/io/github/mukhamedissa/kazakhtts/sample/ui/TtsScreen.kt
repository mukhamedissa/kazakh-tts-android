package io.github.mukhamedissa.kazakhtts.sample.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.mukhamedissa.kazakhtts.sample.TtsViewModel
import io.github.mukhamedissa.kazakhtts.sample.ui.components.PlaybackButtons
import io.github.mukhamedissa.kazakhtts.sample.ui.components.SpeakerSlider
import io.github.mukhamedissa.kazakhtts.sample.ui.components.SpeedSlider
import io.github.mukhamedissa.kazakhtts.sample.ui.components.StatusBanner
import io.github.mukhamedissa.kazakhtts.sample.ui.components.TextInputField

@Composable
fun TtsScreen(
    modifier: Modifier = Modifier,
    viewModel: TtsViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        StatusBanner(
            initState = uiState.initState,
            playbackState = uiState.playbackState,
        )

        TextInputField(
            value = uiState.text,
            onValueChange = viewModel::onTextChanged,
            enabled = !uiState.isGenerating,
        )

        SpeakerSlider(
            speakerId = uiState.speakerId,
            numSpeakers = uiState.numSpeakers,
            onSpeakerIdChange = viewModel::onSpeakerChanged,
            enabled = uiState.isReady && !uiState.isGenerating,
        )

        SpeedSlider(
            speed = uiState.speed,
            onSpeedChange = viewModel::onSpeedChanged,
            enabled = !uiState.isGenerating,
        )

        PlaybackButtons(
            canGenerate = uiState.isReady && !uiState.isGenerating,
            canStop = uiState.isGenerating,
            onGenerate = viewModel::generateAndPlay,
            onStop = viewModel::stop,
        )
    }
}
