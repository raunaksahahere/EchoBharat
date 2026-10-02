package com.echobharat.tts

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SpeechChunksTest {
    @Test fun `sentence boundaries and all whitespace are preserved`() {
        val text = "Hello world!  नमस्ते।\nHow are you?\nLast line without punctuation"
        val chunks = SpeechChunks.split(text, 24).toList()
        assertEquals(text, chunks.joinToString(""))
        assertEquals("Hello world!", chunks.first())
        assertTrue(chunks.all { it.length <= 24 })
    }

    @Test fun `huge unbroken input is bounded without losing supplementary characters`() {
        val text = "😀अ".repeat(10_000)
        val chunks = SpeechChunks.split(text, 17).toList()
        assertEquals(text, chunks.joinToString(""))
        assertTrue(chunks.all { it.length <= 17 })
        assertTrue(chunks.none { it.last().isHighSurrogate() || it.first().isLowSurrogate() })
    }

    @Test fun `empty and exact boundary texts are not truncated`() {
        assertTrue(SpeechChunks.split("").none())
        for (length in listOf(1, 159, 160, 161, 10000)) {
            val text = "x".repeat(length)
            assertEquals(text, SpeechChunks.split(text).joinToString(""))
            assertTrue(SpeechChunks.split(text).all { it.length <= SpeechLimits.MAX_TEXT_UNITS })
        }
    }

    @Test fun `mel and pcm limits reject oversized native outputs`() {
        assertTrue(SpeechLimits.validMelShape(longArrayOf(1, 80, SpeechLimits.MAX_MEL_FRAMES.toLong())))
        assertFalse(SpeechLimits.validMelShape(longArrayOf(1, 80, SpeechLimits.MAX_MEL_FRAMES + 1L)))
        assertFalse(SpeechLimits.validMelShape(longArrayOf(2, 80, 10)))
        assertFalse(SpeechLimits.validMelShape(longArrayOf(1, Long.MAX_VALUE, Long.MAX_VALUE)))
        assertTrue(SpeechLimits.validAudioShape(longArrayOf(1, 1, SpeechLimits.MAX_AUDIO_SAMPLES.toLong())))
        assertFalse(SpeechLimits.validAudioShape(longArrayOf(1, SpeechLimits.MAX_AUDIO_SAMPLES + 1L)))
        assertFalse(SpeechLimits.validAudioShape(longArrayOf(2, 1, 100)))
    }

    @Test fun `synthesis and playback are serial and preserve chunk order`() = runBlocking {
        val events = mutableListOf<String>()
        val result = playSpeechChunks("One. Two! Three?", synthesize = {
            events += "s:$it"
            FloatArray(it.length) { 0.1f }
        }, play = {
            events += "p:${it.size}"
            true
        })
        assertTrue(result)
        assertEquals(listOf("s:One.", "p:4", "s: Two!", "p:5", "s: Three?", "p:7"), events)
    }

    @Test fun `cancellation or failed chunk never starts later synthesis`() = runBlocking {
        var synthesized = 0
        assertFalse(playSpeechChunks("One. Two.", synthesize = {
            synthesized++
            floatArrayOf(0.1f)
        }, play = { false }))
        assertEquals(1, synthesized)
        synthesized = 0
        try {
            playSpeechChunks("One. Two.", synthesize = {
                synthesized++
                throw CancellationException("cancelled")
            }, play = { true })
            fail("Cancellation must escape")
        } catch (_: CancellationException) { }
        assertEquals(1, synthesized)
    }

    @Test fun `stop between chunks aborts normal speech but not alerts`() = runBlocking {
        suspend fun run(alert: Boolean): Int {
            var generation = 0
            var count = 0
            playSpeechChunks("One. Two.", shouldStop = { !alert && generation != 0 }, synthesize = {
                count++
                floatArrayOf(0.1f)
            }, play = { generation++; true })
            return count
        }
        assertEquals(1, run(false))
        assertEquals(2, run(true))
    }
}
