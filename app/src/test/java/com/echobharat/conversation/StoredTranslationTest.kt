package com.echobharat.conversation

import org.junit.Assert.*
import org.junit.Test

class StoredTranslationTest {
    @Test fun `model missing is retryable after pack installation`() {
        assertFalse(StoredTranslation("hi", StoredTranslation.Status.UNAVAILABLE).reusableFor("hi"))
    }

    @Test fun `successful translation is cached only for its target`() {
        val translated = StoredTranslation("hi", StoredTranslation.Status.TRANSLATED, "नमस्ते")
        assertTrue(translated.reusableFor("hi"))
        assertFalse(translated.reusableFor("ta"))
    }

    @Test fun `same language and unknown source do not invoke a model again`() {
        assertTrue(StoredTranslation("hi", StoredTranslation.Status.NOT_NEEDED).reusableFor("hi"))
        assertTrue(StoredTranslation("hi", StoredTranslation.Status.UNKNOWN_SOURCE).reusableFor("hi"))
    }
}
