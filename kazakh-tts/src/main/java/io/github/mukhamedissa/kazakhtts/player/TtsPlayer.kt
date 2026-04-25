package io.github.mukhamedissa.kazakhtts.player

interface TtsPlayer {

    /** Prepare the player for a new utterance. Must be called before write(). */
    fun init()

    /** Write a chunk of PCM samples. Returns false if stopped or an error occurred. */
    fun write(samples: FloatArray): Boolean

    /** Wait for all written samples to finish playing, then release resources. */
    suspend fun drainAndRelease()

    /** Interrupt playback immediately, discard buffered audio. */
    fun stop()

    /** Full cleanup — safe to call from any state. */
    fun release()
}