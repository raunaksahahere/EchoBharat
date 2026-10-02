package com.echobharat.schema

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguagesTest {

    @Test
    fun `scripts that belong to one language name it`() {
        assertEquals("ta", Languages.guessFromScript("மருந்து வேண்டும்"))
        assertEquals("bn", Languages.guessFromScript("আমার জল দরকার।"))
        assertEquals("te", Languages.guessFromScript("నాకు నీళ్ళు కావాలి"))
        assertEquals("kn", Languages.guessFromScript("ನನಗೆ ನೀರು ಬೇಕು"))
        assertEquals("ml", Languages.guessFromScript("എനിക്ക് വെള്ളം വേണം"))
        assertEquals("gu", Languages.guessFromScript("મને પાણી જોઈએ છે"))
        assertEquals("or", Languages.guessFromScript("ମୋତେ ପାଣି ଦରକାର"))
    }

    @Test
    fun `shared scripts are not guessed`() {
        // Devanagari is Hindi and Marathi; Latin is English and romanised Hindi.
        assertEquals(Languages.UNDETERMINED, Languages.guessFromScript("मुझे पानी चाहिए"))
        assertEquals(Languages.UNDETERMINED, Languages.guessFromScript("I need water"))
        assertEquals(Languages.UNDETERMINED, Languages.guessFromScript("mujhe paani chahiye"))
    }

    @Test
    fun `assamese letters veto bengali`() {
        assertEquals(Languages.UNDETERMINED, Languages.guessFromScript("মোক পানী লাগে ৰাতি"))
    }

    @Test
    fun `mixed or empty text is undetermined`() {
        assertEquals(Languages.UNDETERMINED, Languages.guessFromScript(""))
        assertEquals(Languages.UNDETERMINED, Languages.guessFromScript("123 !!"))
        assertEquals(Languages.UNDETERMINED, Languages.guessFromScript("water தண்ணீர் needed urgently"))
    }

    @Test
    fun `digits and punctuation do not dilute a single script`() {
        assertEquals("ta", Languages.guessFromScript("2 பேர் காயம்!"))
    }
}
