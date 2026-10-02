package com.echobharat.translate

import java.io.Closeable

/**
 * Single inference interface for machine translation, so the model behind it can be
 * swapped or requantised without touching callers (Rules §8) — the same shape as
 * [com.echobharat.tts.TtsEngine] and [com.echobharat.stt.SttEngine].
 *
 * An engine is one model *family*: one direction of IndicTrans2 that serves every
 * language on its far side (see [TranslationRoutes]).
 */
interface TranslationEngine : Closeable {

    /** [TranslationRoutes.INDIC_EN] or [TranslationRoutes.EN_INDIC]. */
    val family: String

    /** True when the KV-cache decoder is loaded and decoding is linear, not quadratic. */
    val cached: Boolean

    /**
     * Translates raw user text from [source] into [target] (app language codes).
     *
     * Returns null rather than the original text when inference failed: callers must be
     * able to tell a translation apart from an untranslated passthrough, because
     * presenting untranslated text as translated is the dangerous failure in a distress
     * message.
     */
    fun translate(text: String, source: String, target: String): String?
}

sealed interface TranslationUnavailable {
    data class ModelMissing(val direction: String, val files: List<String>) : TranslationUnavailable
    data class LoadFailed(val direction: String, val reason: String) : TranslationUnavailable
    /** No model covers this pair, e.g. a language the app does not know. */
    data class UnsupportedPair(val sourceLang: String, val targetLang: String) : TranslationUnavailable
}
