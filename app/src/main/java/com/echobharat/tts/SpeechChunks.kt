package com.echobharat.tts

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Conservative per-inference budgets, independent of the size of the original message. */
internal object SpeechLimits {
    const val MAX_TEXT_UNITS = 160
    const val MAX_TOKENS = 160
    const val MEL_BINS = 80
    const val MAX_MEL_FRAMES = 2_048
    const val MAX_AUDIO_SAMPLES = MAX_MEL_FRAMES * 256 // <24 seconds at 22.05 kHz

    fun validMelShape(shape: LongArray): Boolean {
        val dimensions = if (shape.size == 3 && shape[0] == 1L) shape.drop(1) else shape.toList()
        return dimensions.size == 2 && dimensions[0] == MEL_BINS.toLong() &&
            dimensions[1] in 1L..MAX_MEL_FRAMES.toLong()
    }

    fun validAudioShape(shape: LongArray): Boolean = shape.isNotEmpty() && shape.size <= 3 &&
        shape.dropLast(1).all { it == 1L } && shape.last() in 1L..MAX_AUDIO_SAMPLES.toLong()
}

internal object SpeechChunks {
    /**
     * Lazy, lossless partition: punctuation first, whitespace next, then a bounded hard
     * split for unbroken text. No substring crosses a UTF-16 surrogate pair. Whitespace
     * belongs to a chunk; concatenating chunks reconstructs the original exactly.
     */
    fun split(text: String, maxUnits: Int = SpeechLimits.MAX_TEXT_UNITS): Sequence<String> = sequence {
        require(maxUnits >= 2)
        var start = 0
        while (start < text.length) {
            var limit = minOf(text.length, start + maxUnits)
            if (limit < text.length && text[limit - 1].isHighSurrogate() && text[limit].isLowSurrogate()) limit--
            var sentenceEnd = -1
            var whitespaceEnd = -1
            for (i in start until limit) {
                if (text[i] in ".!?।॥\n") {
                    sentenceEnd = i + 1
                    break
                }
                if (text[i].isWhitespace()) whitespaceEnd = i + 1
            }
            val end = when {
                sentenceEnd > start -> sentenceEnd
                limit == text.length -> limit
                whitespaceEnd > start -> whitespaceEnd
                else -> limit
            }
            yield(text.substring(start, end))
            start = end
        }
    }
}

/** One PCM chunk live at a time; no prefetch, concatenated PCM, or hidden truncation. */
internal suspend fun playSpeechChunks(
    text: String,
    shouldStop: () -> Boolean = { false },
    synthesize: suspend (String) -> FloatArray?,
    play: suspend (FloatArray) -> Boolean
): Boolean {
    for (chunk in SpeechChunks.split(text)) {
        currentCoroutineContext().ensureActive()
        if (shouldStop()) return false
        if (chunk.isBlank()) continue
        val pcm = synthesize(chunk) ?: return false
        currentCoroutineContext().ensureActive()
        if (shouldStop() || pcm.isEmpty() || pcm.size > SpeechLimits.MAX_AUDIO_SAMPLES || pcm.any { !it.isFinite() }) return false
        if (!play(pcm)) return false
    }
    return !shouldStop()
}
