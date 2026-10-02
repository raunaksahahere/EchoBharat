package com.echobharat.translate

import org.junit.Assert.*
import org.junit.Test

/** Android ICU is not Java's regex implementation: keep this on actual ART. */
class IndicTransAndroidTest {
    @Test fun preprocessingInitializesOnAndroidAndPreservesPlaceholders() {
        val prepared = IndicTransText.preprocess("I need water and medicine. Call 9876543210.", "en")
        assertTrue(prepared.text.contains("water"))
        val hindi = IndicTransText.preprocess("मुझे पानी और दवा चाहिए।", "hi")
        assertTrue(hindi.text.isNotBlank())
        assertEquals("I need water.", IndicTransText.postprocess("I need water .", "en", emptyMap()))
    }
}
