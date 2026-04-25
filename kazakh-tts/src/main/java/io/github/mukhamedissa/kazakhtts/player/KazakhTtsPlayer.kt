package io.github.mukhamedissa.kazakhtts.player

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import io.github.mukhamedissa.kazakhtts.KazakhTts
import io.github.mukhamedissa.kazakhtts.logger.d
import io.github.mukhamedissa.kazakhtts.logger.w
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Streams PCM float audio to an [AudioTrack] in [AudioTrack.MODE_STREAM] mode.
 *
 * ### Lifecycle
 * ```
 * init() → write() × N → drainAndRelease()   // normal playback
 *                       → stop()              // user-initiated cancel
 * ```
 * [init] can be called again after [release] or [stop] to reuse the instance.
 *
 * All methods are thread-safe. [drainAndRelease] must be called from a coroutine.
 *
 * @param sampleRate PCM sample rate in Hz — must match the TTS engine's [KazakhTts.sampleRate].
 */
class KazakhTtsPlayer(private val sampleRate: Int) : TtsPlayer {

    private companion object {
        const val TAG = "KazakhTtsPlayer"

        /** Multiplier applied to [AudioTrack.getMinBufferSize] to reduce underrun risk. */
        const val BUFFER_SIZE_MULTIPLIER = 4

        /** Consecutive stale polls tolerated *after* the head moves before forcing a stop. */
        const val MAX_STALE_POLL_COUNT = 50

        /** Extra wait budget on top of audio duration to absorb device output latency. */
        const val DRAIN_LATENCY_BUDGET_MS = 5_000L

        /** Polling interval for the drain loop in milliseconds. */
        const val DRAIN_POLL_INTERVAL_MS = 20L

        /** Masks [AudioTrack.playbackHeadPosition] to handle uint32 rollover. */
        const val UINT32_MASK = 0xFFFFFFFFL

        const val BYTES_PER_FLOAT_FRAME = 4
    }

    private val trackLock = Any()
    private var audioTrack: AudioTrack? = null

    /**
     * Cumulative number of PCM frames written since the last [init], masked to uint32
     * to match [AudioTrack.playbackHeadPosition]'s range.
     */
    private val totalWrittenFrames = AtomicLong(0L)

    /** Set to `true` by [stop] or [release] to abort any in-progress [write] or drain loop. */
    private val isStopped = AtomicBoolean(false)

    /**
     * Initialises (or re-initialises) the [AudioTrack] and begins playback.
     *
     * Safe to call again after [release] or [stop]. Any previously held [AudioTrack]
     * is released before the new one is created.
     */
    override fun init() {
        release()
        totalWrittenFrames.set(0L)
        isStopped.set(false)

        val minBufferBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufferSizeBytes = minBufferBytes.coerceAtLeast(sampleRate * BUFFER_SIZE_MULTIPLIER)
        KazakhTts.logger.d(TAG, "init: sampleRate=$sampleRate Hz, minBuffer=$minBufferBytes B, bufferSize=$bufferSizeBytes B")

        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSizeBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        // On API 31+, AudioTrack's default start threshold equals the buffer capacity in
        // frames, which means short clips that don't fill the buffer never trigger playback.
        // Lower the threshold to the minimum so playback begins as soon as a meaningful
        // chunk is written.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val startThresholdFrames = (minBufferBytes / BYTES_PER_FLOAT_FRAME).coerceAtLeast(1)
            runCatching { newTrack.setStartThresholdInFrames(startThresholdFrames) }
                .onSuccess { KazakhTts.logger.d(TAG, "init: startThreshold=$startThresholdFrames frames (was buffer capacity)") }
                .onFailure { KazakhTts.logger.w(TAG, "init: setStartThresholdInFrames failed", it) }
        }

        synchronized(trackLock) { audioTrack = newTrack }
        newTrack.play()
    }

    /**
     * Writes a PCM float chunk to the [AudioTrack].
     *
     * Blocks until the audio hardware has accepted all [samples] (WRITE_BLOCKING).
     * Returns immediately with `false` if [stop] has been called.
     *
     * @param samples PCM float frames in the range [-1.0, 1.0].
     * @return `true` if all samples were written, `false` if stopped or a write error occurred.
     */
    override fun write(samples: FloatArray): Boolean {
        if (isStopped.get()) return false
        val currentTrack = synchronized(trackLock) { audioTrack } ?: return false

        return when (val bytesWritten = currentTrack.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)) {
            AudioTrack.ERROR_INVALID_OPERATION -> {
                KazakhTts.logger.w(TAG, "write: invalid operation — track may not be initialised")
                false
            }
            AudioTrack.ERROR_BAD_VALUE -> {
                KazakhTts.logger.w(TAG, "write: bad value — check sample array and size")
                false
            }
            AudioTrack.ERROR -> {
                KazakhTts.logger.w(TAG, "write: unknown AudioTrack error")
                false
            }
            else -> {
                if (bytesWritten > 0) {
                    val total = totalWrittenFrames.addAndGet(bytesWritten.toLong())
                    KazakhTts.logger.d(TAG, "write: chunk=${samples.size} frames, written=$bytesWritten, total=$total")
                } else {
                    KazakhTts.logger.d(TAG, "write: chunk=${samples.size} frames, bytesWritten=0")
                }
                true
            }
        }
    }

    /**
     * Waits until the [AudioTrack] has played all written frames, then stops and releases it.
     *
     * Polls [AudioTrack.playbackHeadPosition] every [DRAIN_POLL_INTERVAL_MS] ms.
     * Stale-poll detection only begins **after** the head advances for the first time,
     * so that devices with high audio output latency are not force-stopped before
     * the first frame reaches the speaker. An overall wall-time cap (audio duration +
     * [DRAIN_LATENCY_BUDGET_MS]) guards against infinite waits.
     *
     * Must be called from a coroutine. Suspends on [Dispatchers.IO].
     */
    override suspend fun drainAndRelease() = withContext(Dispatchers.IO) {
        val trackToDrain = synchronized(trackLock) { audioTrack } ?: return@withContext

        val targetFrame = totalWrittenFrames.get() and UINT32_MASK
        val audioDurationMs = if (sampleRate > 0) targetFrame * 1000L / sampleRate else 0L
        val maxWaitMs = audioDurationMs + DRAIN_LATENCY_BUDGET_MS
        KazakhTts.logger.d(TAG, "drainAndRelease: target=$targetFrame frames (~${audioDurationMs}ms audio, ${maxWaitMs}ms budget)")
        if (targetFrame == 0L) {
            KazakhTts.logger.w(TAG, "drainAndRelease: no frames were written — TTS produced no audio")
        }

        var stalePollCount = 0
        var lastHeadFrame = 0L  // new AudioTrack always starts at 0
        var headHasMoved = false
        val startMs = System.currentTimeMillis()

        while (!isStopped.get()) {
            val currentHeadFrame = trackToDrain.playbackHeadPosition.toLong() and UINT32_MASK
            if (currentHeadFrame >= targetFrame) break

            val elapsed = System.currentTimeMillis() - startMs
            if (elapsed > maxWaitMs) {
                KazakhTts.logger.w(TAG, "drainAndRelease: overall timeout after ${elapsed}ms (head=$currentHeadFrame, target=$targetFrame)")
                break
            }

            if (currentHeadFrame != lastHeadFrame) {
                headHasMoved = true
                stalePollCount = 0
                lastHeadFrame = currentHeadFrame
            } else if (headHasMoved) {
                if (++stalePollCount > MAX_STALE_POLL_COUNT) {
                    KazakhTts.logger.w(TAG, "drainAndRelease: playback head stalled at $currentHeadFrame after ${elapsed}ms, forcing stop")
                    break
                }
            }
            delay(DRAIN_POLL_INTERVAL_MS)
        }

        val finalHead = trackToDrain.playbackHeadPosition.toLong() and UINT32_MASK
        KazakhTts.logger.d(TAG, "drainAndRelease: done — head=$finalHead, target=$targetFrame, elapsed=${System.currentTimeMillis() - startMs}ms")

        synchronized(trackLock) {
            if (audioTrack !== trackToDrain) return@synchronized // replaced by a concurrent init()
            runCatching { trackToDrain.stop() }
                .onFailure { KazakhTts.logger.w(TAG, "drainAndRelease: stop() failed", it) }
            trackToDrain.release()
            audioTrack = null
        }
    }

    /**
     * Pauses and flushes the [AudioTrack] immediately, discarding any buffered audio.
     *
     * Safe to call from any thread. Subsequent [write] calls will return `false`.
     * Does not release the [AudioTrack] — call [release] for full cleanup.
     */
    override fun stop() {
        isStopped.set(true)
        val currentTrack = synchronized(trackLock) { audioTrack } ?: return
        runCatching {
            currentTrack.pause()
            currentTrack.flush()
        }.onFailure { KazakhTts.logger.w(TAG, "stop: pause/flush failed", it) }
    }

    /**
     * Stops playback and releases all resources held by the [AudioTrack].
     *
     * Safe to call multiple times or from any thread.
     * After this call the instance can be reused via [init].
     */
    override fun release() {
        stop()
        synchronized(trackLock) {
            val currentTrack = audioTrack ?: return
            runCatching { currentTrack.stop() }
                .onFailure { KazakhTts.logger.w(TAG, "release: stop() failed", it) }
            currentTrack.release()
            audioTrack = null
        }
    }
}