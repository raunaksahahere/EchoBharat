package com.echobharat.translate

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt in with -e models true after installing verified translation packs. */
class MultilingualModelsTest {
    @Test fun allTenLanguagesTranslateOnDevice() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("models") == "true")
        val manager = TranslationManager(InstrumentationRegistry.getInstrumentation().targetContext)
        val examples = linkedMapOf(
            "en" to "I need water and medicine.",
            "hi" to "मुझे पानी और दवा चाहिए।",
            "bn" to "আমার পানি ও ওষুধ দরকার।",
            "gu" to "મને પાણી અને દવા જોઈએ છે.",
            "kn" to "ನನಗೆ ನೀರು ಮತ್ತು ಔಷಧಿ ಬೇಕು.",
            "ml" to "എനിക്ക് വെള്ളവും മരുന്നും വേണം.",
            "mr" to "मला पाणी आणि औषध हवे आहे.",
            "ta" to "எனக்கு தண்ணீர் மற்றும் மருந்து தேவை.",
            "te" to "నాకు నీరు మరియు మందు కావాలి.",
            "or" to "ମୋତେ ପାଣି ଏବଂ ଔଷଧ ଦରକାର।"
        )
        try {
            for ((source, text) in examples) for (target in examples.keys) {
                val result = manager.translate(text, source, target)
                if (source == target) assertTrue(result is TranslationManager.Outcome.NotNeeded)
                else {
                    assertTrue("$source->$target: $result", result is TranslationManager.Outcome.Translated)
                    result as TranslationManager.Outcome.Translated
                    assertTrue(result.text.isNotBlank())
                    assertEquals(if (source != "en" && target != "en") "en" else null, result.via)
                    Log.i("MultilingualModelsTest", "$source->$target completed in ${result.millis}ms")
                }
            }
        } finally { manager.release() }
    }
}
