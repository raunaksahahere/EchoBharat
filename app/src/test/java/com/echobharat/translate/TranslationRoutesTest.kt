package com.echobharat.translate

import org.junit.Assert.*
import org.junit.Test

class TranslationRoutesTest {
    private val languages = listOf("en", "hi", "bn", "gu", "kn", "ml", "mr", "or", "ta", "te")

    @Test fun `all ten languages have a route in every direction`() {
        assertEquals(10, TranslationRoutes.FLORES.size)
        for (source in languages) for (target in languages) {
            val route = TranslationRoutes.route(source, target)
            assertNotNull("missing $source->$target", route)
            if (source == target) assertTrue(route!!.isEmpty())
            else if (source != "en" && target != "en") {
                assertEquals(listOf(TranslationRoutes.INDIC_EN, TranslationRoutes.EN_INDIC), route!!.map { it.family })
                assertEquals("en", route[0].target)
            } else assertEquals(1, route!!.size)
        }
    }

    @Test fun `Odia is text translation even without an acoustic voice pack`() {
        assertTrue(TranslationRoutes.FLORES.containsKey("or"))
        assertEquals(listOf(TranslationRoutes.INDIC_EN), TranslationRoutes.familiesFor("en"))
        assertEquals(listOf(TranslationRoutes.EN_INDIC, TranslationRoutes.INDIC_EN), TranslationRoutes.familiesFor("or"))
    }
}
