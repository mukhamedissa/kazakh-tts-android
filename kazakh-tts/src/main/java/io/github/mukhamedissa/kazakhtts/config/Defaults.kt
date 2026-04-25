package io.github.mukhamedissa.kazakhtts.config

object TtsConfigDefaults {

    /** Number of CPU inference threads. Balances latency vs. battery on mid-range devices. */
    const val NUM_THREADS = 2

    /** Maximum sentences per synthesis call before the input is split into chunks. */
    const val MAX_NUM_SENTENCES = 100

    /** Relative inter-sentence silence duration. `0.0` = none, `1.0` = full native pause. */
    const val SILENCE_SCALE = 0.2f
}

/**
 * File layout constants for the `vits-piper-kk_KZ-issai-high` sherpa-onnx model.
 *
 * All three [ModelSource] variants ([ModelSource.Assets], [ModelSource.Files],
 * [ModelSource.Remote]) expect the same directory structure:
 * ```
 * <root>/
 * ├── kk_KZ-issai-high.onnx ← [MODEL_FILE]
 * ├── tokens.txt ← [TOKENS_FILE]
 * └── espeak-ng-data/ ← [ESPEAK_DIR]
 * ```
 */
object ModelDefaults {
    /** Default asset folder name and extracted directory name for the model. */
    const val ASSET_ROOT = "vits-piper-kk_KZ-issai-high"

    /** VITS ONNX model filename. */
    const val MODEL_FILE = "kk_KZ-issai-high.onnx"

    /** Vocabulary / token mapping filename required by the VITS decoder. */
    const val TOKENS_FILE = "tokens.txt"

    /**
     * espeak-ng phoneme data directory.
     * Must reside on disk (not in assets) at runtime. [ModelManager] copies it from assets
     * to `filesDir` on first use.
     */
    const val ESPEAK_DIR = "espeak-ng-data"

    /**
     * Default download URL for [ModelSource.Remote].
     * Points to the official sherpa-onnx tts-models release on GitHub.
     */
    const val REMOTE_URL =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "tts-models/vits-piper-kk_KZ-issai-high.tar.bz2"
}