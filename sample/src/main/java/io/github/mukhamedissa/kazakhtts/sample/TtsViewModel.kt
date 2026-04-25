package io.github.mukhamedissa.kazakhtts.sample

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.mukhamedissa.kazakhtts.KazakhTts
import io.github.mukhamedissa.kazakhtts.KazakhTtsConfig
import io.github.mukhamedissa.kazakhtts.ModelSource
import io.github.mukhamedissa.kazakhtts.logger.d
import io.github.mukhamedissa.kazakhtts.logger.e
import io.github.mukhamedissa.kazakhtts.logger.w
import io.github.mukhamedissa.kazakhtts.player.KazakhTtsPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class TtsViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TtsUiState())
    val uiState: StateFlow<TtsUiState> = _uiState.asStateFlow()

    private var ttsEngine: KazakhTts? = null
    @Volatile private var activePlayer: KazakhTtsPlayer? = null
    private var generationJob: Job? = null

    init {
        initializeEngine()
    }

    private fun initializeEngine() {
        viewModelScope.launch {
            try {
                val engine = KazakhTts.create(
                    context = getApplication(),
                    config = KazakhTtsConfig(source = ModelSource.Remote()),
                    onProgress = { downloaded, total ->
                        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else null
                        _uiState.update { it.copy(initState = InitState.Downloading(percent)) }
                    },
                )
                ttsEngine = engine
                _uiState.update {
                    it.copy(
                        initState = InitState.Ready(
                            numSpeakers = engine.numSpeakers,
                            sampleRateHz = engine.sampleRate,
                        ),
                    )
                }
            } catch (e: Throwable) {
                KazakhTts.logger.e(TAG, "Engine initialisation failed", e)
                _uiState.update { it.copy(initState = InitState.Failed(e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    fun onTextChanged(text: String) =
        _uiState.update { it.copy(text = text) }

    fun onSpeakerChanged(speakerId: Int) =
        _uiState.update { it.copy(speakerId = speakerId) }

    fun onSpeedChanged(speed: Float) =
        _uiState.update { it.copy(speed = speed) }

    fun generateAndPlay() {
        val engine = ttsEngine ?: run {
            KazakhTts.logger.w(TAG, "generateAndPlay: engine not ready")
            return
        }
        if (generationJob?.isActive == true) {
            KazakhTts.logger.w(TAG, "generateAndPlay: already generating")
            return
        }

        val snapshot = _uiState.value
        generationJob = viewModelScope.launch {
            _uiState.update { it.copy(playbackState = PlaybackState.Generating) }

            val player = KazakhTtsPlayer(engine.sampleRate).also {
                activePlayer = it
                it.init()
            }
            try {
                KazakhTts.logger.d(TAG, "generate: text=\"${snapshot.text}\" speakerId=${snapshot.speakerId} speed=${snapshot.speed}")
                engine.generate(
                    text = snapshot.text,
                    speakerId = snapshot.speakerId,
                    speed = snapshot.speed,
                ) { samples ->
                    player.write(samples)
                }
                KazakhTts.logger.d(TAG, "generate: completed")
                player.drainAndRelease()
                _uiState.update { it.copy(playbackState = PlaybackState.Done) }
            } catch (_: CancellationException) {
                player.release()
                _uiState.update { it.copy(playbackState = PlaybackState.Stopped) }
                throw CancellationException()
            } catch (e: Throwable) {
                KazakhTts.logger.e(TAG, "Synthesis failed", e)
                player.release()
                _uiState.update {
                    it.copy(playbackState = PlaybackState.Failed(e.message ?: e.javaClass.simpleName))
                }
            } finally {
                activePlayer = null
            }
        }
    }

    fun stop() {
        activePlayer?.stop()
        generationJob?.cancel()
    }

    override fun onCleared() {
        super.onCleared()
        stop()
        ttsEngine?.release()
        ttsEngine = null
    }

    private companion object {
        const val TAG = "TtsViewModel"
    }
}
