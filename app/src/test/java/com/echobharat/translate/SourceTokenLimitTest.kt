package com.echobharat.translate

import org.junit.Assert.*
import org.junit.Test

class SourceTokenLimitTest {
    @Test fun `input at limit keeps every body token`() {
        val input = IntArray(253) { it + 4 }
        assertArrayEquals(input, IndicTrans2Translator.sourceBody(input, 256))
    }

    @Test fun `overlong input fails explicitly instead of translating a prefix`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndicTrans2Translator.sourceBody(IntArray(254) { it + 4 }, 256)
        }
    }

    @Test fun `invalid metadata cannot reserve a negative body size`() {
        assertThrows(IllegalArgumentException::class.java) {
            IndicTrans2Translator.sourceBody(intArrayOf(), 2)
        }
    }
}
