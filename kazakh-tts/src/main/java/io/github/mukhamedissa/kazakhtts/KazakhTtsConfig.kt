package io.github.mukhamedissa.kazakhtts

import io.github.mukhamedissa.kazakhtts.config.TtsConfigDefaults

/**
 * Configuration for the Kazakh TTS engine.
 *
 * @param source           Where the model files are loaded from. Defaults to [ModelSource.Remote],
 *                         which downloads the official sherpa-onnx release on first use.
 * @param numThreads       Number of CPU threads used for inference.
 *                         Higher values reduce latency on multi-core devices at the cost of
 *                         increased CPU usage. Must be >= 1.
 * @param maxNumSentences  Maximum number of sentences processed in a single synthesis call.
 *                         Longer inputs are split automatically. Must be >= 1.
 * @param silenceScale     Relative duration of the silence inserted between sentences.
 *                         `0.0` = no pause, `1.0` = full default pause. Must be in [0.0, 1.0].
 */
data class KazakhTtsConfig(
    val source: ModelSource = ModelSource.Remote(),
    val numThreads: Int = TtsConfigDefaults.NUM_THREADS,
    val maxNumSentences: Int = TtsConfigDefaults.MAX_NUM_SENTENCES,
    val silenceScale: Float = TtsConfigDefaults.SILENCE_SCALE,
) {
    init {
        require(numThreads >= 1) {
            "numThreads must be >= 1, got $numThreads"
        }
        require(maxNumSentences >= 1) {
            "maxNumSentences must be >= 1, got $maxNumSentences"
        }
        require(silenceScale in 0f..1f) {
            "silenceScale must be in [0.0, 1.0], got $silenceScale"
        }
    }
}