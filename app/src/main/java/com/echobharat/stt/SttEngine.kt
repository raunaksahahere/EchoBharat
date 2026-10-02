package com.echobharat.stt

import java.io.Closeable

/**
 * Single inference interface for speech-to-text, so the model behind it can be swapped
 * or requantised without touching callers (Rules §8).
 */
interface SttEngine : Closeable {

    /** BCP-47-ish language code this instance was loaded for. */
    val lang: String

    /**
     * Transcribes one complete utterance.
     *
     * @param samples mono 16 kHz audio in [-1, 1]
     * @return recognised text, or null when inference failed.
     */
    fun transcribe(samples: FloatArray): String?
}

/** Why STT is unavailable, so the UI can say something specific instead of failing mutely. */
sealed interface SttUnavailable {
    data class ModelMissing(val lang: String, val files: List<String>) : SttUnavailable
    data class LoadFailed(val lang: String, val reason: String) : SttUnavailable
    data object PermissionDenied : SttUnavailable
}
