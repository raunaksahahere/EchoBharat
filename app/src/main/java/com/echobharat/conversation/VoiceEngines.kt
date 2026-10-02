package com.echobharat.conversation

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.echobharat.stt.SttManager
import com.echobharat.translate.TranslationManager
import com.echobharat.tts.TtsManager

/**
 * The three on-device models, owned once for the whole app.
 *
 * They used to belong to the conversation screen, which meant every chat opened with a
 * cold model load and nothing could be spoken or translated with the screen closed —
 * including alerts, which are supposed to get through with the phone in a pocket.
 *
 * Memory is still bounded (Rules §9): each manager keeps one language resident, and
 * everything is released when the app leaves the foreground or the system is short of
 * memory. Models reload lazily on next use.
 */
class VoiceEngines private constructor(context: Context) {

    companion object {
        private const val TAG = "VoiceEngines"

        @Volatile
        private var instance: VoiceEngines? = null

        fun getInstance(context: Context): VoiceEngines =
            instance ?: synchronized(this) {
                instance ?: VoiceEngines(context.applicationContext).also { instance = it }
            }
    }

    val stt = SttManager(context)
    val tts = TtsManager(context)
    val translation = TranslationManager(context)

    fun release(reason: String) {
        Log.i(TAG, "Releasing models: $reason")
        stt.release()
        tts.release()
        translation.release()
    }

    /** Hooks [release] to the system's memory signals. */
    fun registerTrimCallbacks(context: Context) {
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    release("trim level $level")
                }
            }

            override fun onConfigurationChanged(newConfig: android.content.res.Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = release("low memory")
        })
    }
}
