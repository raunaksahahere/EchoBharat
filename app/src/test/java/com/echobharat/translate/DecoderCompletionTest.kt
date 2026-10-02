package com.echobharat.translate

import org.junit.Assert.*
import org.junit.Test

class DecoderCompletionTest {
    private fun decode(vararg tokens: Int?, budget: Int = 4): List<Int> {
        val iterator = tokens.iterator()
        return IndicTrans2Translator.decodeTokens(budget) { iterator.next() }
    }

    @Test fun `EOS returns complete tokens without the terminator`() {
        assertEquals(listOf(9, 8), decode(9, 8, SpmBpeTokenizer.EOS))
    }

    @Test fun `EOS on final budget step is still complete`() {
        assertEquals(listOf(9, 8, 7), decode(9, 8, 7, SpmBpeTokenizer.EOS))
    }

    @Test fun `budget exhaustion refuses a plausible translated prefix`() {
        assertThrows(IllegalStateException::class.java) { decode(9, 8, 7, 6) }
    }

    @Test fun `invalid logits after a valid prefix fail rather than imply EOS`() {
        assertThrows(IllegalStateException::class.java) { decode(9, null) }
    }

    @Test fun `early EOS never calls model again`() {
        assertEquals(emptyList<Int>(), decode(SpmBpeTokenizer.EOS))
    }

    @Test fun `nonpositive generation budget is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { decode(budget = 0) }
    }
}
