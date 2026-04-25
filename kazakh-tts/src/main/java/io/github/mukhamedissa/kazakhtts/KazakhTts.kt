package io.github.mukhamedissa.kazakhtts

import android.content.Context
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import io.github.mukhamedissa.kazakhtts.logger.Logger
import io.github.mukhamedissa.kazakhtts.logger.d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level Kazakh TTS engine backed by sherpa-onnx.
 *
 * Instances must be created via [KazakhTts.create], which resolves the model source,
 * performs any necessary I/O (asset copy / download / extraction), and initialises
 * the native engine.
 *
 * Call [release] when the engine is no longer needed to free native resources.
 */
class KazakhTts private constructor(
    private val engine: OfflineTts,
    private val config: KazakhTtsConfig,
) {

    /** Sample rate (Hz) of the PCM audio produced by [generate]. */
    val sampleRate: Int get() = engine.sampleRate()

    /** Number of speakers available in the loaded model. */
    val numSpeakers: Int get() = engine.numSpeakers()

    /**
     * Synthesizes [text] and streams PCM float chunks to [onSamples].
     *
     * Synthesis runs on [Dispatchers.IO]. [onSamples] is invoked on the same thread
     * for each chunk as it becomes available — suitable for low-latency audio playback.
     *
     * @param text      The Kazakh text to synthesize.
     * @param speakerId Zero-based speaker index. Must be in `[0, numSpeakers)`.
     * @param speed     Playback speed multiplier. `1.0` = normal, `0.5` = half speed, `2.0` = double.
     * @param onSamples Callback receiving each PCM chunk. Return `true` to continue synthesis,
     *                  `false` to abort early.
     */
    suspend fun generate(
        text: String,
        speakerId: Int = DEFAULT_SPEAKER_ID,
        speed: Float = DEFAULT_SPEED,
        onSamples: (FloatArray) -> Boolean,
    ) {
        withContext(Dispatchers.IO) {
            val generationConfig = GenerationConfig(
                sid = speakerId,
                speed = speed,
                silenceScale = config.silenceScale,
            )
            engine.generateWithConfigAndCallback(
                text = text,
                config = generationConfig,
                // Object literal required: D8 desugars lambdas to ExternalSyntheticLambda,
                // exposing only invoke(Object)Object. The sherpa-onnx JNI lookup needs the
                // specialized invoke([F)Ljava/lang/Integer; bridge that an explicit
                // anonymous Function1 emits.
                callback = object : Function1<FloatArray, Int> {
                    override fun invoke(samples: FloatArray): Int =
                        if (onSamples(samples)) 1 else 0
                },
            )
        }
    }

    /**
     * Releases native resources held by the engine.
     *
     * The instance must not be used after this call.
     */
    fun release() {
        logger.d(TAG, "Releasing TTS engine")
        engine.release()
    }

    companion object {
        /** Controls where library log output is sent. Defaults to [io.github.mukhamedissa.kazakhtts.logger.Logger.ANDROID]. */
        var logger: Logger = Logger.ANDROID

        private const val TAG = "KazakhTts"
        private const val DEFAULT_SPEAKER_ID = 0
        private const val DEFAULT_SPEED = 1.0f

        /**
         * Creates and initialises a [KazakhTts] instance.
         *
         * Suspends while the model is resolved (copied from assets, or downloaded and
         * extracted on first use). Subsequent calls with the same [config] return
         * immediately once the ready sentinel exists on disk.
         *
         * @param context    Android context used for asset access and `filesDir`.
         * @param config     Engine configuration. Defaults to [KazakhTtsConfig] with [ModelSource.Remote].
         * @param onProgress Optional download progress callback `(bytesDownloaded, totalBytes)`.
         *                   `totalBytes` may be `-1` if the server omits `Content-Length`.
         * @return A fully initialised [KazakhTts] ready for synthesis.
         */
        suspend fun create(
            context: Context,
            config: KazakhTtsConfig = KazakhTtsConfig(),
            onProgress: ((bytesDownloaded: Long, totalBytes: Long) -> Unit)? = null,
        ): KazakhTts = withContext(Dispatchers.IO) {
            logger.d(TAG, "Resolving model source: ${config.source}")
            val resolvedPaths = ModelManager(context).resolve(config.source, onProgress)

            val offlineTtsConfig = OfflineTtsConfig(
                model = OfflineTtsModelConfig(
                    vits = OfflineTtsVitsModelConfig(
                        model = resolvedPaths.modelPath,
                        tokens = resolvedPaths.tokensPath,
                        dataDir = resolvedPaths.dataDirPath,
                    ),
                    numThreads = config.numThreads,
                    debug = false,
                ),
                maxNumSentences = config.maxNumSentences,
                silenceScale = config.silenceScale,
            )

            logger.d(TAG, "Initialising native TTS engine")
            val offlineTts = OfflineTts(
                assetManager = resolvedPaths.assetManager,
                config = offlineTtsConfig,
            )
            logger.d(TAG, "TTS engine ready — sampleRate=${offlineTts.sampleRate()} speakers=${offlineTts.numSpeakers()}")

            KazakhTts(engine = offlineTts, config = config)
        }
    }
}