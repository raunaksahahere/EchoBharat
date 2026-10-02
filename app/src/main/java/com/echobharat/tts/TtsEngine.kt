package com.echobharat.tts

import java.io.Closeable

/**
 * Single inference interface for text-to-speech, so the model behind it can be swapped
 * or requantised without touching callers (Rules §8).
 */
interface TtsEngine : Closeable {

    val lang: String

    /** Sample rate of the audio [synthesize] returns. */
    val sampleRate: Int

    /**
     * Synthesises [text] to mono PCM in [-1, 1], or null when inference failed.
     */
    fun synthesize(text: String): FloatArray?
}

sealed interface TtsUnavailable {
    data class ModelMissing(val lang: String, val files: List<String>) : TtsUnavailable
    data class LoadFailed(val lang: String, val reason: String) : TtsUnavailable
}
