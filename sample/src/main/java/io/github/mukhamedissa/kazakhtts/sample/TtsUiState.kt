package io.github.mukhamedissa.kazakhtts.sample

/**
 * Lifecycle of the TTS engine from the UI's perspective.
 *
 * Transitions: [Loading] → [Downloading]* → [Ready] (success) or [Failed] (terminal failure).
 */
sealed interface InitState {

    /** Engine creation in progress, no progress reported yet. */
    data object Loading : InitState

    /**
     * Model download in progress.
     *
     * @param percent Whole-number percentage in `[0, 100]`, or `null` when the server
     *                does not report `Content-Length`.
     */
    data class Downloading(val percent: Int?) : InitState

    /**
     * Engine is initialised and ready to synthesize.
     *
     * @param numSpeakers  Number of distinct speakers in the loaded model.
     * @param sampleRateHz Output PCM sample rate in Hz.
     */
    data class Ready(
        val numSpeakers: Int,
        val sampleRateHz: Int,
    ) : InitState

    /** Engine failed to initialise. Terminal state — no recovery is attempted. */
    data class Failed(val message: String) : InitState
}

/**
 * Lifecycle of a single synthesis request.
 *
 * Transitions: [Idle] → [Generating] → ([Done] | [Stopped] | [Failed]) → [Idle] (when user retries).
 */
sealed interface PlaybackState {

    data object Idle : PlaybackState

    /** Synthesis is actively producing samples and feeding them to the player. */
    data object Generating : PlaybackState

    /** Last synthesis finished naturally. */
    data object Done : PlaybackState

    /** User cancelled the active synthesis. */
    data object Stopped : PlaybackState

    /** Synthesis failed mid-stream. */
    data class Failed(val message: String) : PlaybackState
}

/**
 * Single source of truth for the sample screen.
 *
 * Held inside [TtsViewModel] as a `StateFlow` and rendered by composables.
 */
data class TtsUiState(
    val initState: InitState = InitState.Loading,
    val playbackState: PlaybackState = PlaybackState.Idle,
    val text: String = DEFAULT_TEXT,
    val speakerId: Int = 0,
    val speed: Float = DEFAULT_SPEED,
) {
    /** Whether the engine is initialised and accepting synthesis requests. */
    val isReady: Boolean
        get() = initState is InitState.Ready

    /** Whether a synthesis is currently in flight. */
    val isGenerating: Boolean
        get() = playbackState is PlaybackState.Generating

    /** Number of speakers in the loaded model, or `1` while still initialising. */
    val numSpeakers: Int
        get() = (initState as? InitState.Ready)?.numSpeakers ?: 1

    companion object {
        const val DEFAULT_TEXT = "Сәлем әлем! Бұл қазақ тілінің синтезаторы."
        const val DEFAULT_SPEED = 1.0f
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.0f
    }
}
